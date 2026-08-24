package io.github.ygaray.sttengine.recorder

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The public Recorder contract a host consumes — mirroring
 * [io.github.ygaray.sttengine.SttEngine]'s Flow/StateFlow shape (same rationale, a distinct
 * capability): mic in, compressed `.m4a` file out, usable standalone by a record-only consumer with
 * no dependency on recognition (REC-05).
 *
 * The full interface is declared here in one pass (Task 1) even though [pause]/[resume] and the
 * amplitude signal on [level] only gain real behavior in Tasks 2-3 — this keeps `apiDump` a
 * single, deliberate freeze of the whole surface rather than three incremental widenings.
 */
public interface Recorder {

    /** The Recorder's current state, replayed to every new collector. */
    public val state: StateFlow<RecorderState>

    /**
     * Normalized `[0,1]` amplitude ticks, roughly every ~150ms, while [state] is [RecorderState.Recording].
     *
     * Conflated — a stale meter frame is worthless, same rationale as
     * [io.github.ygaray.sttengine.SttEngine.level]. Silent (no ticks), not zero-valued, before
     * [start] and while [RecorderState.Idle]/[RecorderState.Paused] — the amplitude channel is
     * quiet in the absence of an active session, it never emits a `0f` placeholder.
     *
     * **Platform asymmetry, documented not hidden (REC-06):** this is `MediaRecorder.getMaxAmplitude()`
     * PEAK, not RMS — there is no RMS variant exposed by the platform API. The web Recorder's
     * equivalent signal is RMS. A host that renders "the same meter feel" on both platforms without
     * accounting for this will see different behavior for identical audio.
     */
    public val level: Flow<Float>

    /** Typed Recorder failures, at most one path to [RecorderState.Error] per session. */
    public val errors: Flow<RecorderError>

    /**
     * Begin a recording session: open a new output file and start capturing.
     *
     * A second `start()` while already [RecorderState.Recording]/[RecorderState.Paused] pushes
     * [RecorderErrorCode.ALREADY_RECORDING] on [errors] and does not open a second session.
     */
    public suspend fun start()

    /**
     * Suspend the active session. Writes to the same output file resume on [resume]. `pause()`
     * while not [RecorderState.Recording] pushes [RecorderErrorCode.INVALID_STATE].
     */
    public suspend fun pause()

    /**
     * Resume a suspended session. `resume()` while not [RecorderState.Paused] pushes
     * [RecorderErrorCode.INVALID_STATE].
     */
    public suspend fun resume()

    /**
     * End the session and return the finished [RecordedFile]. `durationMs` excludes any time spent
     * [RecorderState.Paused]. Returns the Recorder to [RecorderState.Idle].
     */
    public suspend fun stop(): RecordedFile

    /**
     * Delete the file at [uri]. A no-op (not an error) if the file does not exist — idempotent by
     * design (REC-03). Rejected with [RecorderErrorCode.INVALID_STATE] if [uri] is the currently
     * open (not-yet-stopped) recording.
     */
    public suspend fun delete(uri: String)
}
