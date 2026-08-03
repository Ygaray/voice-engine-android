package io.github.ygaray.sttengine.internal

/**
 * The monotonic session epoch — D-03 mechanism 1, and the mitigation for threat **T-05-14**
 * (a late callback from a cancelled session mutating the live one).
 *
 * ## The binding rule — read this before writing a listener
 *
 * A `RecognitionListener` must be constructed **bound to** the epoch that created it, capturing
 * that value, and must check [accepts] with its own bound epoch as the **first statement of every
 * single callback**:
 *
 * ```kotlin
 * private fun listenerFor(epoch: Long) = object : RecognitionListener {
 *     private fun accept() = fence.accepts(epoch)          // main-thread read
 *     override fun onPartialResults(b: Bundle?) { if (!accept()) return; … }
 *     override fun onResults(b: Bundle?)        { if (!accept()) return; … }
 *     override fun onError(code: Int)           { if (!accept()) return; … }
 *     // …every callback fenced identically. A MISSING FENCE IN ANY ONE CALLBACK IS THE BUG.
 * }
 * ```
 *
 * Binding at construction is what makes this safe. A listener that instead read a shared mutable
 * "current session id" field could not tell its own session apart from the one that replaced it,
 * so it would happily attribute a dying session's `onResults` to the live one — which is precisely
 * the duplicated-text failure AND-02 forbids. A bound listener physically cannot mis-attribute
 * itself.
 *
 * ## Why a `Long` rather than a UUID
 *
 * The reference implementation (openclaw `TalkModeManager.acceptRecognitionCallback`) compares a
 * `String` UUID. A monotonic `Long` is strictly better here: the library already carries a
 * monotonic segment counter ([SegmentAccumulator.segmentId]), staleness becomes a single primitive
 * comparison rather than a string compare, and epochs printed into a session log are **orderable**,
 * which is what lets plan 08's analyzer reconstruct the restart sequence from the log alone.
 *
 * ## Why there is no synchronization here
 *
 * Every `RecognitionListener` callback is delivered on the **main looper** in FIFO order —
 * `SpeechRecognizerImpl.InternalRecognitionListener` holds a `Handler(Looper.getMainLooper())` and
 * dispatches every incoming Binder call through it. This state is therefore main-thread-confined by
 * construction and a plain `var` is correct.
 *
 * Do **not** "harden" this with a volatile marker, an atomic long, a lock block or a coroutine
 * mutex — an acceptance gate greps this file to prove none of them appear. Any of them would
 * advertise a concurrency model the library does not have, and would invite a future contributor to
 * move recognizer work off the main thread — which the platform forbids outright
 * (`checkIsCalledFromMainThread()` throws a `RuntimeException` from `startListening`, `stopListening`,
 * `cancel`, `setRecognitionListener` and the `create*` factories).
 */
internal class SessionEpoch {

    /** The epoch of the session that is currently live. Starts at 0, like [SegmentAccumulator.segmentId]. */
    var current: Long = 0L
        private set

    /**
     * Retire the current session and mint the next epoch, returning it.
     *
     * Call this **after** [SegmentAccumulator.commitLivePartialAsFinal] and before tearing the
     * recognizer down: commit first, because teardown is what causes the final that never arrives,
     * then fence, so a late callback from the dying session cannot re-emit the same words.
     * Reversing those two steps reintroduces both AND-02 failure modes at once.
     */
    fun advance(): Long {
        current += 1L
        return current
    }

    /**
     * True only for the epoch that is live right now.
     *
     * This is deliberately an **equality** test, not `epoch >= current`: an epoch from the future
     * is as wrong as one from the past, and an off-by-one that produced one would be a bug worth
     * failing loudly on rather than tolerating.
     */
    fun accepts(epoch: Long): Boolean = epoch == current
}
