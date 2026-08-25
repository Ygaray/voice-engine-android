package io.github.ygaray.sttengine.voicenotes

/**
 * A voice note's observable lifecycle — a closed set by design (mirrors
 * [io.github.ygaray.sttengine.EngineState], not [io.github.ygaray.sttengine.recorder.RecorderErrorCode]):
 * a host genuinely wants the compiler to point at a new lifecycle stage, unlike
 * [NoteErrorCode], which stays open because the drain worker can surface a failure this
 * codebase did not anticipate.
 *
 * `recorded -> queued -> transcribing -> done | failed`, per the note-contract Runtime Decision
 * (D-01, refreshed 2026-08-24). [wireName] mirrors [io.github.ygaray.sttengine.EngineState.wireName]'s
 * rationale: the lowercase string is what a log line, a Room column, or a diagnostic surface should
 * render, not `toString()`'s `Recorded`/`Queued`/etc.
 */
public enum class NoteStatus(public val wireName: String) {
    /** [io.github.ygaray.sttengine.recorder.Recorder.stop] returned, not yet queued for drain. */
    Recorded("recorded"),

    /** Persisted to the durable store and enqueued for drain, no job id obtained yet. */
    Queued("queued"),

    /** A job id was obtained from `POST /jobs` and is being polled via `GET /jobs/{id}`. */
    Transcribing("transcribing"),

    /** Terminal success: [NoteRecord.transcript] is non-null. */
    Done("done"),

    /** Terminal-for-now failure: [NoteRecord.errorCode]/[NoteRecord.errorMessage] are set. Retryable. */
    Failed("failed"),
}
