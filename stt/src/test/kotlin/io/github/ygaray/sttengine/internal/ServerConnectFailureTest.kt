package io.github.ygaray.sttengine.internal

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The D-06 connection-phase classifier as a plain table (TRY-02). Pure function, plain JUnit — no
 * Robolectric, no socket. `ServerConnectRealSocketTest` proves which of these shapes a real OkHttp
 * 5.2.1 socket actually raises; this file pins the decision for every shape, including the TLS ones
 * a real-socket test cannot build without a new test package.
 */
class ServerConnectFailureTest {

    private fun classify(
        throwable: Throwable? = null,
        responseCode: Int? = null,
        closeCode: Int? = null,
        serverErrorCode: String? = null,
        sawOpen: Boolean = false,
    ): ConnectFailureClass = classifyConnectFailure(
        ConnectObservation(
            throwable = throwable,
            responseCode = responseCode,
            closeCode = closeCode,
            serverErrorCode = serverErrorCode,
            sawOpen = sawOpen,
        ),
    )

    // --- before the host answers: fast-fail ---------------------------------------------------------

    @Test
    fun `an unknown host fast-fails`() {
        assertEquals(ConnectFailureClass.FastFail, classify(UnknownHostException("x"), sawOpen = false))
    }

    @Test
    fun `a refused TCP connect fast-fails`() {
        assertEquals(ConnectFailureClass.FastFail, classify(ConnectException("Failed to connect to x")))
    }

    @Test
    fun `no route to host fast-fails`() {
        assertEquals(ConnectFailureClass.FastFail, classify(NoRouteToHostException("x")))
    }

    @Test
    fun `a cleartext refusal before any connection fast-fails`() {
        assertEquals(
            ConnectFailureClass.FastFail,
            classify(UnknownServiceException("CLEARTEXT communication not permitted")),
        )
    }

    @Test
    fun `a connect-phase reset wrapped in ConnectException fast-fails - the top-level type decides`() {
        val wrapped = ConnectException("Failed to connect to x").apply {
            initCause(SocketException("Connection reset"))
        }
        assertEquals(ConnectFailureClass.FastFail, classify(wrapped))
    }

    // --- the same connect-phase failures handed over wrapped: still fast-fail (21-W46 review) -------

    @Test
    fun `every connect-phase failure wrapped in an IOException fast-fails`() {
        listOf(
            UnknownHostException("x"),
            ConnectException("Failed to connect to x"),
            NoRouteToHostException("x"),
            UnknownServiceException("CLEARTEXT communication not permitted"),
        ).forEach { cause ->
            assertEquals(
                "IOException wrapping ${cause.javaClass.simpleName}",
                ConnectFailureClass.FastFail,
                classify(IOException("wrapped", cause)),
            )
        }
    }

    @Test
    fun `a connect-phase failure several wrappers deep fast-fails`() {
        val inner = SocketException("inner").apply { initCause(UnknownHostException("x")) }
        val t = IOException("outer", IOException("middle", inner))
        assertEquals(ConnectFailureClass.FastFail, classify(t))
    }

    @Test
    fun `a connect-phase reset under a ConnectException under a wrapper fast-fails`() {
        val connect = ConnectException("Failed to connect to x").apply {
            initCause(SocketException("Connection reset"))
        }
        assertEquals(ConnectFailureClass.FastFail, classify(IOException("wrapped", connect)))
    }

    @Test
    fun `a reset under a plain wrapper with no connect-phase cause stays retryable`() {
        val t = IOException("wrapped", SocketException("Connection reset"))
        assertEquals(ConnectFailureClass.Retryable, classify(t))
        assertEquals(
            "a top-level established-connection reset stays retryable",
            ConnectFailureClass.Retryable,
            classify(SocketException("Connection reset")),
        )
    }

    @Test
    fun `a connect-phase cause past the depth bound is not reached`() {
        var t: Throwable = UnknownHostException("x")
        repeat(8) { t = IOException("wrapper $it", t) }
        assertEquals(ConnectFailureClass.Retryable, classify(t))
    }

    // --- deterministic rejections after the host answered: fast-fail --------------------------------

    @Test
    fun `server busy close 1013 fast-fails`() {
        assertEquals(ConnectFailureClass.FastFail, classify(closeCode = 1013, sawOpen = true))
    }

    @Test
    fun `every server error frame before ready fast-fails`() {
        listOf("unsupported-language", "invalid-config", "protocol-violation", "internal-error").forEach {
            assertEquals(
                "error frame $it",
                ConnectFailureClass.FastFail,
                classify(serverErrorCode = it, sawOpen = true),
            )
        }
    }

    @Test
    fun `a non-5xx upgrade answer fast-fails`() {
        assertEquals(
            ConnectFailureClass.FastFail,
            classify(ProtocolException("Expected HTTP 101 response but was '404 Not Found'"), responseCode = 404),
        )
    }

    // --- permanent TLS verification failures: fast-fail ---------------------------------------------

    @Test
    fun `a JDK untrusted certificate fast-fails`() {
        val t = SSLHandshakeException("PKIX path building failed").apply {
            initCause(CertificateException("untrusted"))
        }
        assertEquals(ConnectFailureClass.FastFail, classify(t))
    }

    @Test
    fun `a Conscrypt untrusted anchor fast-fails`() {
        val t = SSLHandshakeException("x").apply {
            initCause(
                CertificateException(
                    CertPathValidatorException("Trust anchor for certification path not found."),
                ),
            )
        }
        assertEquals(ConnectFailureClass.FastFail, classify(t))
    }

    @Test
    fun `a handshake whose direct cause is a CertPathValidatorException fast-fails`() {
        val t = SSLHandshakeException("x").apply { initCause(CertPathValidatorException("x")) }
        assertEquals(ConnectFailureClass.FastFail, classify(t))
    }

    @Test
    fun `an expired certificate fast-fails`() {
        val t = SSLException("x", CertificateExpiredException())
        assertEquals(ConnectFailureClass.FastFail, classify(t))
    }

    @Test
    fun `a hostname verification failure fast-fails`() {
        assertEquals(
            ConnectFailureClass.FastFail,
            classify(SSLPeerUnverifiedException("Hostname x not verified")),
        )
    }

    // --- post-answer transient failures before ready: retry -----------------------------------------

    @Test
    fun `a 503 upgrade answer is retryable`() {
        assertEquals(
            ConnectFailureClass.Retryable,
            classify(
                ProtocolException("Expected HTTP 101 response but was '503 Service Unavailable'"),
                responseCode = 503,
            ),
        )
    }

    @Test
    fun `502 and 504 upgrade answers are retryable`() {
        listOf(502, 504).forEach {
            assertEquals(
                "response $it",
                ConnectFailureClass.Retryable,
                classify(ProtocolException("Expected HTTP 101 response but was '$it'"), responseCode = it),
            )
        }
    }

    @Test
    fun `EOF before the response headers is retryable - the tailscale-serve cold start`() {
        val t = IOException("unexpected end of stream on x", EOFException())
        assertEquals(ConnectFailureClass.Retryable, classify(t, sawOpen = false))
    }

    @Test
    fun `a peer-terminated SSL handshake is retryable`() {
        assertEquals(ConnectFailureClass.Retryable, classify(SSLException("handshake")))
    }

    @Test
    fun `a handshake terminated by the peer with an EOF cause is retryable`() {
        val t = SSLHandshakeException("Remote host terminated the handshake").apply {
            initCause(EOFException())
        }
        assertEquals(ConnectFailureClass.Retryable, classify(t))
    }

    @Test
    fun `an SSL connection reset is retryable`() {
        assertEquals(ConnectFailureClass.Retryable, classify(SSLException("Connection reset")))
    }

    @Test
    fun `a reset on an established connection is retryable`() {
        assertEquals(
            ConnectFailureClass.Retryable,
            classify(SocketException("Connection reset"), sawOpen = false),
        )
    }

    @Test
    fun `a connect timeout is retryable - D-14 a stall is the black-holed shape`() {
        assertEquals(
            ConnectFailureClass.Retryable,
            classify(SocketTimeoutException("connect timed out"), sawOpen = false),
        )
    }

    @Test
    fun `a read timeout after open is retryable`() {
        assertEquals(
            ConnectFailureClass.Retryable,
            classify(SocketTimeoutException("timeout"), sawOpen = true),
        )
    }

    @Test
    fun `a close other than 1013 before ready is retryable`() {
        listOf(1006, 1011, 1000).forEach {
            assertEquals("close $it", ConnectFailureClass.Retryable, classify(closeCode = it, sawOpen = true))
        }
    }

    @Test
    fun `a cyclic cause chain terminates and stays retryable`() {
        val a = IOException("a")
        val b = IOException("b")
        a.initCause(b)
        b.initCause(a)
        assertEquals(ConnectFailureClass.Retryable, classify(a))
    }

    // --- local failures with nothing to wait for: fast-fail -----------------------------------------

    @Test
    fun `an observation with no throwable, close or error frame fast-fails`() {
        assertEquals(ConnectFailureClass.FastFail, classify(sawOpen = true))
        assertEquals(ConnectFailureClass.FastFail, classify())
    }

    @Test
    fun `a non-IO throwable fast-fails`() {
        assertEquals(ConnectFailureClass.FastFail, classify(IllegalStateException("x")))
    }
}
