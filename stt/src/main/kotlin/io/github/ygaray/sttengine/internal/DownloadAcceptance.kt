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
 * The platform's "cannot listen to download events" error, `ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS`.
 *
 * A raw literal, not the platform field: that field is API 34, above this module's `minSdk 33`, so
 * referencing it would draw `NewApi` — and this taxonomy is about integers received across a Binder
 * boundary, which are stable. Same rule as `ErrorMapper`'s disposition table.
 */
private const val ERROR_DOWNLOAD_EVENTS_UNAVAILABLE: Int = 15

/**
 * Turns the download listener's callbacks into one Boolean: did the platform ACCEPT the request?
 *
 * [register] is handed a sink and must pass the request to the platform with a listener that
 * forwards to it. **The first callback decides:**
 * - `onSuccess`, `onScheduled`, or the first `onProgress` → `true` (done, queued, or underway; the
 *   progress percentage is discarded — no progress stream is exposed);
 * - `onError(15)` → `true`: the progress FEED was lost, not the download, which may well arrive
 *   anyway (the same reading `ErrorMapper` gives error 15);
 * - any other `onError` → `false`;
 * - no callback within [timeoutMs] → `false`, i.e. "not confirmed", not "refused".
 *
 * Later callbacks are ignored, including any that arrive after the bound already answered.
 *
 * A [register] that throws synchronously is a request the platform never took, so it resolves
 * `false` at once — except a [CancellationException], which is the caller's own cancellation and is
 * rethrown, never folded.
 */
internal suspend fun awaitDownloadAcceptance(
    timeoutMs: Long,
    register: (DownloadOutcomeSink) -> Unit,
): Boolean = withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine<Boolean> { continuation ->
        // Callbacks can arrive on binder threads concurrently; the flag makes "first one wins" exact
        // instead of racing two resumes past the isActive check.
        val settled = AtomicBoolean(false)
        fun settle(accepted: Boolean) {
            if (continuation.isActive && settled.compareAndSet(false, true)) continuation.resume(accepted)
        }

        val sink = object : DownloadOutcomeSink {
            override fun onProgress() = settle(true)
            override fun onSuccess() = settle(true)
            override fun onScheduled() = settle(true)
            override fun onError(error: Int) = settle(error == ERROR_DOWNLOAD_EVENTS_UNAVAILABLE)
        }

        try {
            register(sink)
        } catch (cancellation: CancellationException) {
            // A RuntimeException subclass — check it first, or the fold below would swallow it.
            throw cancellation
        } catch (failure: RuntimeException) {
            settle(false)
        }
    }
} ?: false
