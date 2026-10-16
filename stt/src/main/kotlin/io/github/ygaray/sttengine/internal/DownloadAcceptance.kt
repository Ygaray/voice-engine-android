package io.github.ygaray.sttengine.internal

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The platform's `ModelDownloadListener` (API 34), restated as a plain interface.
 *
 * This file deliberately imports nothing from `android.*`. The listener type is above this module's
 * `minSdk 33` and cannot be built on the plain-JVM `android.jar`, so the adapter forwards each
 * callback here and every acceptance rule below is provable on the host with no Robolectric. The
 * progress percentage is not carried — no progress stream is exposed, so only the fact that progress
 * arrived matters.
 */
internal interface DownloadOutcomeSink {
    fun onProgress()
    fun onSuccess()
    fun onScheduled()
    fun onError(error: Int)
}

/**
 * What the first `ModelDownloadListener` callback (or its absence) says about a listener-based
 * download request.
 */
internal enum class DownloadOutcome {

    /** `onSuccess`, `onScheduled` or a first `onProgress`: done, queued, or underway. */
    ACCEPTED,

    /** Any `onError` other than 15, or a registration that threw: the platform did not take it. */
    REFUSED,

    /**
     * `onError(15)`, `ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS`: the recognition service does not
     * implement the listener-based download hook, so **nothing was downloaded**.
     *
     * In AOSP, `RecognitionService.dispatchTriggerModelDownload` calls ONLY the 3-arg
     * `onTriggerModelDownload(intent, attributionSource, listener)` when a listener is supplied, and
     * that hook's default implementation is just `listener.onError(15)` — it never forwards to the
     * 2-arg hook that actually starts a download [VERIFIED: `RecognitionService.java:226-235,
     * 483-488`, android-36.1 sources]. So during a download request, 15 means "this service ignored
     * your listener-based request", NOT "the progress feed was lost". `ErrorMapper`'s reading of 15
     * belongs to recognition sessions and does not carry over. The caller must retry WITHOUT a
     * listener.
     */
    LISTENER_UNSUPPORTED,

    /** No callback within the bound: "not confirmed", not "refused". */
    NOT_CONFIRMED,
}

/**
 * The platform's "cannot listen to download events" error, `ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS`.
 *
 * A raw literal, not the platform field: that field is API 34, above this module's `minSdk 33`, so
 * referencing it would draw `NewApi` — and this taxonomy is about integers received across a Binder
 * boundary, which are stable. Same rule as `ErrorMapper`'s disposition table.
 */
private const val ERROR_DOWNLOAD_EVENTS_UNAVAILABLE: Int = 15

/**
 * Turns the download listener's callbacks into one [DownloadOutcome].
 *
 * [register] is handed a sink and must pass the request to the platform with a listener that
 * forwards to it. **The first callback decides:**
 * - `onSuccess`, `onScheduled`, or the first `onProgress` → [DownloadOutcome.ACCEPTED] (done,
 *   queued, or underway; the progress percentage is discarded — no progress stream is exposed);
 * - `onError(15)` → [DownloadOutcome.LISTENER_UNSUPPORTED]: the service has no listener-based
 *   download hook and started nothing (see that entry for the AOSP evidence);
 * - any other `onError` → [DownloadOutcome.REFUSED];
 * - no callback within [timeoutMs] → [DownloadOutcome.NOT_CONFIRMED], i.e. "not confirmed", not
 *   "refused".
 *
 * Later callbacks are ignored, including any that arrive after the bound already answered.
 *
 * A [register] that throws synchronously is a request the platform never took, so it resolves
 * [DownloadOutcome.REFUSED] at once — except a [CancellationException], which is the caller's own
 * cancellation and is rethrown, never folded.
 */
internal suspend fun awaitDownloadOutcome(
    timeoutMs: Long,
    register: (DownloadOutcomeSink) -> Unit,
): DownloadOutcome = withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine<DownloadOutcome> { continuation ->
        // Callbacks can arrive on binder threads concurrently; the flag makes "first one wins" exact
        // instead of racing two resumes past the isActive check.
        val settled = AtomicBoolean(false)
        fun settle(outcome: DownloadOutcome) {
            if (continuation.isActive && settled.compareAndSet(false, true)) continuation.resume(outcome)
        }

        val sink = object : DownloadOutcomeSink {
            override fun onProgress() = settle(DownloadOutcome.ACCEPTED)
            override fun onSuccess() = settle(DownloadOutcome.ACCEPTED)
            override fun onScheduled() = settle(DownloadOutcome.ACCEPTED)
            override fun onError(error: Int) = settle(
                if (error == ERROR_DOWNLOAD_EVENTS_UNAVAILABLE) {
                    DownloadOutcome.LISTENER_UNSUPPORTED
                } else {
                    DownloadOutcome.REFUSED
                },
            )
        }

        try {
            register(sink)
        } catch (cancellation: CancellationException) {
            // A RuntimeException subclass — check it first, or the fold below would swallow it.
            throw cancellation
        } catch (failure: RuntimeException) {
            settle(DownloadOutcome.REFUSED)
        }
    }
} ?: DownloadOutcome.NOT_CONFIRMED
