package io.github.ygaray.sttengine.internal

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * What [BackendSelector] does with one failed server try (Phase 21, TRY-02, D-06): give up on the
 * server for this session at once ([FastFail]), or try a fresh connection after a short backoff while
 * the window still has time ([Retryable]).
 */
internal enum class ConnectFailureClass { FastFail, Retryable }

/**
 * Everything one [ServerBackend] connection attempt saw before it failed, as recorded by its
 * listener before `connect()` resolves `false`. The input to [classifyConnectFailure], and the
 * source of the opt-in `server-attempt` trace line's fields.
 *
 * @param throwable what OkHttp's `onFailure` delivered, or `null` when the attempt ended without one
 *   (a close frame, an error frame, a local failure such as an unavailable microphone).
 * @param responseCode the HTTP status of a rejected upgrade (`onFailure`'s non-null `Response`).
 * @param closeCode a close code received before `ready`.
 * @param serverErrorCode the `code` of a server `error` frame received before `ready`.
 * @param sawOpen whether `onOpen` fired (the HTTP 101 completed). Recorded for the trace line only —
 *   see [classifyConnectFailure] for why it is not a classification input.
 */
internal class ConnectObservation(
    val throwable: Throwable? = null,
    val responseCode: Int? = null,
    val closeCode: Int? = null,
    val serverErrorCode: String? = null,
    val sawOpen: Boolean = false,
)

/**
 * Classifies a failed server try by connection phase (D-06): **before the host answers, or on a
 * deterministic rejection → fast-fail; on a post-answer transient failure before `ready` → retry.**
 * A host that never answered will not answer a quarter-second later, and a rejection the server or
 * the client made on purpose (busy, a refused config, a non-5xx upgrade answer, a certificate that
 * does not verify) will not change inside the window either — retrying those only adds deaf time
 * before the native fallback. A host that answered and then dropped the attempt is, typically, a
 * service that is still starting.
 *
 * Rules, first match wins:
 *
 * 1. a server `error` frame before `ready` (including `internal-error`) → [ConnectFailureClass.FastFail]
 * 2. close `1013` (busy) → FastFail
 * 3. [UnknownHostException] (DNS / no network) → FastFail
 * 4. [ConnectException] or [NoRouteToHostException] → FastFail
 * 5. [UnknownServiceException] (cleartext not permitted — refused before any connection) → FastFail
 * 6. a TLS certificate or hostname verification failure → FastFail
 * 7. a 5xx upgrade answer → [ConnectFailureClass.Retryable]
 * 8. any other upgrade answer → FastFail
 * 9. [SocketTimeoutException] → Retryable (the dedicated D-14 rule below)
 * 10. any other [IOException] → Retryable
 * 11. no throwable, but a close before `ready` → Retryable
 * 12. anything else → FastFail
 *
 * **Why the tailscale-serve cold start must stay Retryable.** Through `tailscale serve`, the proxy is
 * up before `stt-server` is: tailscaled completes TCP and TLS itself, then its loopback dial to the
 * service is refused, so the client sees an EOF before any response header (OkHttp:
 * `IOException("unexpected end of stream on …")` with an `EOFException` cause), or a handshake the
 * peer terminated. Both are post-TCP, both clear within seconds, and both land in rule 10. Without
 * retry, the session is lost to a one-way native fallback while the server finishes loading. The
 * exact class the live path produces is confirmed by the opt-in `server-attempt` trace line on a
 * planned service restart.
 *
 * **Why a peer-terminated handshake retries but a certificate failure does not (rule 6).** A
 * verification failure is post-TCP too, but it is a deterministic client-side rejection of the same
 * kind as busy or a config reject: a certificate cannot change inside the window. It is detected as an
 * [SSLPeerUnverifiedException] (OkHttp's hostname check) or as any throwable whose cause chain holds a
 * [CertificateException] (the JDK's untrusted, expired or mismatched certificate, including its
 * `ValidatorException`) or a [CertPathValidatorException] (Conscrypt's untrusted trust anchor). A
 * handshake the peer terminated has no certificate cause and falls through to rule 10. Rule 6 runs
 * before rule 10 because every `SSLException` is an [IOException].
 *
 * **The explicit [SocketTimeoutException] decision (D-14).** A connect or read timeout is the
 * stalled, black-holed shape, not a refusal, so it is Retryable: the selector keeps trying until its
 * one window expires and never falls back early because a socket timeout fired. The window, not a
 * short `connectTimeout`, is the only bound. With the window clamped to at most 10 000 ms and
 * OkHttp's 10 s default connect and read timeouts, a socket timeout can at most tie the window.
 *
 * **Why [ConnectObservation.sawOpen] is not an input.** The cold-start EOF arrives before the 101,
 * with `sawOpen == false`, exactly like a pre-answer failure, so `sawOpen` cannot separate the two
 * phases. The exception type does: OkHttp's `ConnectPlan` wraps every connect-phase failure (refused,
 * reset during connect, unreachable) in a [ConnectException], and on Android libcore raises
 * `ConnectException` for an unreachable network too. So a `ConnectException` whose cause is a reset
 * fast-fails (rule 4), while a bare reset on an established connection retries (rule 10). `sawOpen`
 * is still recorded for the trace line.
 *
 * **Why no `EventListener`.** OkHttp forces `EventListener.NONE` on WebSocket calls, so no
 * handshake-phase callback can be observed; classification reads only the exceptions and codes
 * OkHttp already surfaces, and no TLS behavior is changed.
 */
internal fun classifyConnectFailure(observation: ConnectObservation): ConnectFailureClass {
    val t = observation.throwable
    val responseCode = observation.responseCode
    return when {
        observation.serverErrorCode != null -> ConnectFailureClass.FastFail
        observation.closeCode == BUSY_CLOSE_CODE -> ConnectFailureClass.FastFail
        t is UnknownHostException -> ConnectFailureClass.FastFail
        t is ConnectException || t is NoRouteToHostException -> ConnectFailureClass.FastFail
        t is UnknownServiceException -> ConnectFailureClass.FastFail
        t != null && isTlsVerificationFailure(t) -> ConnectFailureClass.FastFail
        responseCode != null && responseCode in 500..599 -> ConnectFailureClass.Retryable
        responseCode != null -> ConnectFailureClass.FastFail
        t is SocketTimeoutException -> ConnectFailureClass.Retryable
        t is IOException -> ConnectFailureClass.Retryable
        t == null && observation.closeCode != null -> ConnectFailureClass.Retryable
        else -> ConnectFailureClass.FastFail
    }
}

/**
 * `true` for a certificate or hostname verification failure — see [classifyConnectFailure] rule 6.
 * Walks at most [MAX_CAUSE_DEPTH] links and stops on a self or repeated cause, so a cyclic chain
 * cannot hang the selector.
 */
private fun isTlsVerificationFailure(t: Throwable): Boolean {
    if (t is SSLPeerUnverifiedException) return true
    val seen = ArrayList<Throwable>(MAX_CAUSE_DEPTH)
    var current: Throwable? = t
    while (current != null && seen.size < MAX_CAUSE_DEPTH && seen.none { it === current }) {
        if (current is CertificateException || current is CertPathValidatorException) return true
        seen += current
        current = current.cause
    }
    return false
}

private const val BUSY_CLOSE_CODE = 1013
private const val MAX_CAUSE_DEPTH = 8
