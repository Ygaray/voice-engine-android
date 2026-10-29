package io.github.ygaray.sttengine.internal

import io.github.ygaray.sttengine.FallbackReason
import java.io.EOFException
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
 * deterministic rejection → fast-fail; on a post-answer transient failure before `ready`, or a busy
 * server → retry.** A host that never answered will not answer a quarter-second later, and a
 * rejection the server or the client made on purpose (a refused config, a non-5xx upgrade answer, a
 * certificate that does not verify) will not change inside the window either — retrying those only
 * adds deaf time before the native fallback. A host that answered and then dropped the attempt is,
 * typically, a service that is still starting.
 *
 * **Busy is retried inside the window (Phase 28 D-05, LIVE-04).** A busy server frees its single
 * session slot within seconds — most often it is still releasing the previous session after that
 * session's flushing `stop()` — so busy is no longer a deterministic rejection: both of its wire
 * signals (the `busy` error frame and close `1013`) are Retryable under the selector's existing
 * backoff and single window deadline, with no budget of their own. This reverses Phase 21 D-06 for
 * busy only; every other pre-`ready` error frame still fast-fails. The real server sends the frame
 * AND the close, but a [ServerBackend] records only the first terminal signal (the frame's `fail()`
 * fences the close out), so in practice an observation carries one or the other; rules 1 and 2 also
 * cover an observation that holds both.
 *
 * Rules, first match wins:
 *
 * 1. a `busy` server `error` frame before `ready` → [ConnectFailureClass.Retryable]
 * 2. close `1013` (busy) → Retryable
 * 3. any other server `error` frame before `ready` (including `internal-error`) →
 *    [ConnectFailureClass.FastFail]
 * 4. [UnknownHostException] (DNS / no network) → FastFail
 * 5. [ConnectException] or [NoRouteToHostException] → FastFail
 * 6. [UnknownServiceException] (cleartext not permitted — refused before any connection) → FastFail
 *
 *    Rules 4-6 match the TOP-LEVEL throwable only; rule 12 is their cause-chain counterpart.
 * 7. a TLS certificate or hostname verification failure → FastFail
 * 8. a 5xx upgrade answer → Retryable
 * 9. any other upgrade answer → FastFail
 * 10. [SocketTimeoutException] → Retryable (the dedicated D-14 rule below)
 * 11. an end of stream — an [EOFException], an [IOException] whose direct cause is one, or OkHttp's
 *     `"unexpected end of stream"` [IOException] → Retryable (the cold-start rule below)
 * 12. any other throwable with a rule 4-6 type somewhere in its cause chain → FastFail (see "Why the
 *     cause chain" below)
 * 13. any other [IOException] → Retryable
 * 14. no throwable, but a close before `ready` → Retryable
 * 15. anything else → FastFail
 *
 * **Why the tailscale-serve cold start must stay Retryable.** Through `tailscale serve`, the proxy is
 * up before `stt-server` is: tailscaled completes TCP and TLS itself, then its loopback dial to the
 * service is refused, so the client sees an EOF before any response header (OkHttp:
 * `IOException("unexpected end of stream on …")` with an `EOFException` cause), or a handshake the
 * peer terminated. Both are post-TCP, both clear within seconds, and both are retried (rules 11
 * and 13). Without
 * retry, the session is lost to a one-way native fallback while the server finishes loading. The
 * exact class the live path produces is confirmed by the opt-in `server-attempt` trace line on a
 * planned service restart.
 *
 * **Why a peer-terminated handshake retries but a certificate failure does not (rule 7).** A
 * verification failure is post-TCP too, but it is a deterministic client-side rejection of the same
 * kind as a config reject: a certificate cannot change inside the window. It is detected as an
 * [SSLPeerUnverifiedException] (OkHttp's hostname check) or as any throwable whose cause chain holds a
 * [CertificateException] (the JDK's untrusted, expired or mismatched certificate, including its
 * `ValidatorException`) or a [CertPathValidatorException] (Conscrypt's untrusted trust anchor). A
 * handshake the peer terminated has no certificate cause and falls through to rule 11 or 13. Rule 7
 * runs before them because every `SSLException` is an [IOException].
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
 * fast-fails (rule 5), while a bare reset on an established connection retries (rule 13). `sawOpen`
 * is still recorded for the trace line.
 *
 * **Why the cause chain (rule 12), and why it comes after rules 10 and 11.** A transport layer may
 * hand the failure over wrapped — an [IOException] whose cause is the [UnknownHostException] or
 * [ConnectException] — and that wrapper is still a host that never answered. Matching only the
 * top-level type would class it rule 13 and spend the whole window retrying a host that cannot
 * answer before falling back. So rule 12 walks the same bounded, cycle-checked chain rule 7 does. A
 * reset is retried only when no connect-phase exception sits anywhere in its chain — a reset alone
 * (top-level or under a plain wrapper) is rule 13, a reset under a [ConnectException] is rule 5 or 12.
 *
 * The OUTER failure still decides when it names a transient shape on its own: a top-level
 * [SocketTimeoutException] (rule 10) or end of stream (rule 11) says what happened to THIS attempt —
 * a stall, or a proxy that answered and dropped the request while its upstream was not up yet — and
 * a connect-phase exception nested under it is history (a proxy's own failed upstream dial, say),
 * not a host that never answered. Classing those FastFail would turn the tailscale-serve cold start
 * into a one-way native fallback, the exact failure the retry exists to prevent. So the nested match
 * is applied only after the explicit retry shapes, and only to otherwise-plain wrappers.
 *
 * **Why no `EventListener`.** OkHttp forces `EventListener.NONE` on WebSocket calls, so no
 * handshake-phase callback can be observed; classification reads only the exceptions and codes
 * OkHttp already surfaces, and no TLS behavior is changed.
 */
internal fun classifyConnectFailure(observation: ConnectObservation): ConnectFailureClass {
    val t = observation.throwable
    val responseCode = observation.responseCode
    return when {
        observation.serverErrorCode == BUSY_ERROR_CODE -> ConnectFailureClass.Retryable
        observation.closeCode == BUSY_CLOSE_CODE -> ConnectFailureClass.Retryable
        observation.serverErrorCode != null -> ConnectFailureClass.FastFail
        t != null && isConnectPhaseType(t) -> ConnectFailureClass.FastFail
        t != null && isTlsVerificationFailure(t) -> ConnectFailureClass.FastFail
        responseCode != null && responseCode in 500..599 -> ConnectFailureClass.Retryable
        responseCode != null -> ConnectFailureClass.FastFail
        t is SocketTimeoutException -> ConnectFailureClass.Retryable
        t != null && isEndOfStream(t) -> ConnectFailureClass.Retryable
        t != null && causeChainContains(t, ::isConnectPhaseType) -> ConnectFailureClass.FastFail
        t is IOException -> ConnectFailureClass.Retryable
        t == null && observation.closeCode != null -> ConnectFailureClass.Retryable
        else -> ConnectFailureClass.FastFail
    }
}

// RED skeleton (28-04): the real bodies land with the GREEN commit.
internal fun isBusyRejection(observation: ConnectObservation): Boolean = false

internal fun fallbackReasonForFastFail(observation: ConnectObservation): FallbackReason =
    FallbackReason.REJECTED

/**
 * `true` for a connect-phase failure type — the host never answered — see [classifyConnectFailure]
 * rules 4-6 (this throwable) and rule 12 (any link of its cause chain).
 */
private fun isConnectPhaseType(t: Throwable): Boolean =
    t is UnknownHostException || t is ConnectException || t is NoRouteToHostException ||
        t is UnknownServiceException

/**
 * `true` for an end of stream on this attempt — see [classifyConnectFailure] rule 11: an
 * [EOFException], an [IOException] whose DIRECT cause is one (OkHttp's shape before the response
 * headers, and a handshake the peer terminated), or OkHttp's `"unexpected end of stream"` message.
 * The message is only matched, never logged.
 */
private fun isEndOfStream(t: Throwable): Boolean =
    t is EOFException ||
        (t is IOException && t.cause is EOFException) ||
        (t is IOException && t.message?.startsWith(OKHTTP_END_OF_STREAM) == true)

/**
 * `true` for a certificate or hostname verification failure — see [classifyConnectFailure] rule 7.
 */
private fun isTlsVerificationFailure(t: Throwable): Boolean =
    t is SSLPeerUnverifiedException ||
        causeChainContains(t) { it is CertificateException || it is CertPathValidatorException }

/**
 * `true` when [t] or one of its causes satisfies [match]. Walks at most [MAX_CAUSE_DEPTH] links and
 * stops on a self or repeated cause, so a cyclic chain cannot hang the selector.
 */
private inline fun causeChainContains(t: Throwable, match: (Throwable) -> Boolean): Boolean {
    val seen = ArrayList<Throwable>(MAX_CAUSE_DEPTH)
    var current: Throwable? = t
    while (current != null && seen.size < MAX_CAUSE_DEPTH && seen.none { it === current }) {
        if (match(current)) return true
        seen += current
        current = current.cause
    }
    return false
}

private const val BUSY_CLOSE_CODE = 1013

/** The server's `busy` error-frame code (the shared `SttErrorCode.BUSY` string). */
private const val BUSY_ERROR_CODE = "busy"
private const val MAX_CAUSE_DEPTH = 8

/** The prefix of OkHttp's `Http1ExchangeCodec` EOF-before-headers message ("…on <address>"). */
private const val OKHTTP_END_OF_STREAM = "unexpected end of stream"
