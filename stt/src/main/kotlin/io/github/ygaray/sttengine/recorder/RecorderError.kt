package io.github.ygaray.sttengine.recorder

/**
 * The Recorder's own typed error taxonomy — a FRESH, separate set from
 * [io.github.ygaray.sttengine.SttErrorCode]. Never add a Recorder-only value to
 * [io.github.ygaray.sttengine.SttErrorCode.SHARED] / `.ANDROID_ONLY`, and never touch
 * `ErrorCodeParityTest` or `scripts/check-error-code-parity.sh` for this type — those gates exist
 * exclusively for the frozen 11-code recognition contract, which has nothing to do with recording.
 *
 * `@JvmInline value class` over `String`, mirroring [io.github.ygaray.sttengine.SttErrorCode]'s
 * open-set idiom: `android.media.MediaRecorder` can raise failure modes this Recorder did not
 * anticipate, so the set stays open rather than a closed `enum` that would source-break every
 * consumer's exhaustive `when` the day a new code is added.
 */
@JvmInline
public value class RecorderErrorCode(public val value: String) {

    public companion object {

        /** `MediaRecorder.start()` raised a `RuntimeException` — the mic is unavailable or busy. */
        public val MIC_DENIED: RecorderErrorCode = RecorderErrorCode("mic-denied")

        /** `prepare()`/`start()` raised an `IOException` — disk-full or a permission failure. */
        public val NO_STORAGE: RecorderErrorCode = RecorderErrorCode("no-storage")

        /** `start()` was called while a session was already [RecorderState.Recording]/[RecorderState.Paused]. */
        public val ALREADY_RECORDING: RecorderErrorCode = RecorderErrorCode("already-recording")

        /**
         * A call arrived out of order for the current [RecorderState] — `pause()` while not
         * `Recording`, `resume()` while not `Paused`, or `delete(uri)` on the live recording's own
         * `uri`.
         */
        public val INVALID_STATE: RecorderErrorCode = RecorderErrorCode("invalid-state")
    }
}

/**
 * A Recorder failure event: `{ code, message }`.
 *
 * **Deliberately NOT a `data class`** (`android/CLAUDE.md` invariant #2): a `data class` regenerates
 * `copy()` and a synthetic constructor keyed to its exact parameter list, so adding a field later
 * would break binary compatibility for every already-published consumer. A plain class with
 * hand-written `equals`/`hashCode`/`toString` gives a host everything a data class would, and stays
 * additively extensible — mirroring [io.github.ygaray.sttengine.FinalSegment]'s pattern exactly.
 */
public class RecorderError(
    public val code: RecorderErrorCode,
    public val message: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecorderError) return false
        return code == other.code && message == other.message
    }

    override fun hashCode(): Int = 31 * code.hashCode() + message.hashCode()

    override fun toString(): String = "RecorderError(code=$code, message=$message)"
}
