package io.github.ygaray.sttengine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Why a session fell back from the server to on-device recognition (LIVE-04) — the value of
 * [SttEngine.fallbackReason].
 *
 * **Why a `value class` over `String` and NOT an `enum`.** The set is open: more reasons will be
 * added as the server path learns new ways to fail (a busy server that is waited out longer, for
 * example). With an `enum`, every reason added in a future version would break any consumer's
 * exhaustive `when` at compile time. With a value class, a host matches the constants it knows and
 * treats any other value as "fell back for a reason this build does not name". This mirrors
 * [SttErrorCode] for the same reason. The `@JvmInline` erasure means a reason costs no allocation.
 *
 * **Android-only metadata.** The project's web client is server-only: it has no backend selection
 * and no on-device fallback, so it has nothing to report here. This value has no web counterpart,
 * exactly like [Backend], and it is not part of the cross-language error or event contract.
 *
 * The values are fixed lowercase words. They are never built from server text, a URL or an
 * exception message.
 */
@JvmInline
public value class FallbackReason(public val value: String) {

    public companion object {

        /** The server was still busy (serving its one session) when the try window ended. */
        public val BUSY: FallbackReason = FallbackReason("busy")

        /**
         * The try window ended without a busy answer: the server never answered, kept failing in a
         * retryable way, or the attempt still in flight when the window ended had not been answered.
         */
        public val TIMEOUT: FallbackReason = FallbackReason("timeout")

        /**
         * The server could not be reached at all, so the selector gave up at once: no DNS answer, a
         * refused or unroutable connection, cleartext refused, a certificate that does not verify, or
         * a non-5xx answer to the WebSocket upgrade.
         */
        public val UNREACHABLE: FallbackReason = FallbackReason("unreachable")

        /**
         * The server answered and refused the session (any error other than busy, such as an
         * unsupported language), or the session could not start for a local reason after the
         * server accepted it (for example, the microphone would not open).
         */
        public val REJECTED: FallbackReason = FallbackReason("rejected")

        /**
         * A session that was running on the server failed mid-session and was handed off to
         * on-device recognition.
         */
        public val DROPPED: FallbackReason = FallbackReason("dropped")
    }
}

/**
 * The one shared, never-written `null` flow that [SttEngine.fallbackReason]'s default getter hands
 * out, so every read of an implementer that does not override it returns the same instance.
 */
internal object NoFallbackReason {
    val flow: StateFlow<FallbackReason?> = MutableStateFlow<FallbackReason?>(null).asStateFlow()
}
