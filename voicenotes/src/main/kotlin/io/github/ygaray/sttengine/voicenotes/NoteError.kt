package io.github.ygaray.sttengine.voicenotes

/**
 * The voice-note taxonomy's typed error codes — a FRESH, separate set from
 * [io.github.ygaray.sttengine.SttErrorCode] and from
 * [io.github.ygaray.sttengine.recorder.RecorderErrorCode]. Never add a note-only value to
 * `SttErrorCode.SHARED`/`.ANDROID_ONLY`, and never touch `ErrorCodeParityTest` or
 * `scripts/check-error-code-parity.sh` for this type — those gates exist exclusively for the
 * frozen 11-code recognition contract, which has nothing to do with note persistence or draining.
 *
 * Reuses the SAME 4 string constants as
 * [io.github.ygaray.sttengine.recorder.RecorderErrorCode] verbatim, per the note-contract Runtime
 * Decision (D-01, refreshed 2026-08-24): "Reuse Phase 8's Recorder error vocabulary... for
 * record-time failures — do not invent a parallel taxonomy." Adds 3 new codes for upload/drain
 * failures the Recorder has no concept of.
 *
 * `@JvmInline value class` over `String`, mirroring
 * [io.github.ygaray.sttengine.recorder.RecorderErrorCode]'s open-set idiom: the batch endpoint
 * (plan 10-04's drain worker) can raise failure modes this module did not anticipate, so the set
 * stays open rather than a closed `enum` that would source-break every consumer's exhaustive
 * `when` the day a new code is added.
 */
@JvmInline
public value class NoteErrorCode(public val value: String) {

    public companion object {

        /** `MediaRecorder.start()` raised a `RuntimeException` — the mic is unavailable or busy. */
        public val MIC_DENIED: NoteErrorCode = NoteErrorCode("mic-denied")

        /** `prepare()`/`start()` raised an `IOException` — disk-full or a permission failure. */
        public val NO_STORAGE: NoteErrorCode = NoteErrorCode("no-storage")

        /** `record()` was called while a session was already in progress. */
        public val ALREADY_RECORDING: NoteErrorCode = NoteErrorCode("already-recording")

        /** A call arrived out of order for the note's current lifecycle stage. */
        public val INVALID_STATE: NoteErrorCode = NoteErrorCode("invalid-state")

        /** `POST /jobs` rejected the upload as exceeding the server's enforced size cap. */
        public val UPLOAD_TOO_LARGE: NoteErrorCode = NoteErrorCode("upload-too-large")

        /** `POST /jobs` rejected the upload for a reason other than size (e.g. malformed multipart). */
        public val UPLOAD_REJECTED: NoteErrorCode = NoteErrorCode("upload-rejected")

        /** `GET /jobs/{id}` reached a terminal `status=failed` — a typed, permanent transcription failure. */
        public val TRANSCRIPTION_FAILED: NoteErrorCode = NoteErrorCode("transcription-failed")
    }
}

/**
 * A voice-note failure event: `{ code, message }`.
 *
 * **Deliberately NOT a `data class`** (`android/CLAUDE.md` invariant #2): a `data class`
 * regenerates `copy()` and a synthetic constructor keyed to its exact parameter list, so adding a
 * field later would break binary compatibility for every already-published consumer. A plain
 * class with hand-written `equals`/`hashCode`/`toString` gives a host everything a data class
 * would, and stays additively extensible — mirroring
 * [io.github.ygaray.sttengine.recorder.RecorderError]'s pattern exactly.
 */
public class NoteError(
    public val code: NoteErrorCode,
    public val message: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NoteError) return false
        return code == other.code && message == other.message
    }

    override fun hashCode(): Int = 31 * code.hashCode() + message.hashCode()

    override fun toString(): String = "NoteError(code=$code, message=$message)"
}
