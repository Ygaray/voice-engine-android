package io.github.ygaray.sttengine.voicenotes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The public voice-note contract a host consumes (NOTE-01..06) — a thin composition over
 * [io.github.ygaray.sttengine.recorder.Recorder] (capture, Phase 8) and the Phase 7 batch
 * endpoint (transcription), persisting every note transition to a Room-backed durable store as
 * the source of truth (D-03).
 *
 * **NOTE-05 asymmetry, stated explicitly, not hidden:** this Android implementation drains via
 * `WorkManager`, in the BACKGROUND, surviving app close — the OPPOSITE of the web `note.ts`
 * backend, which drains only while its page is open (an in-page timer loop, no background
 * capability on the web platform). This asymmetry is deliberate: Android has true
 * background-execution primitives WorkManager exposes; the web platform does not. A host
 * targeting both platforms must not assume identical drain timing.
 *
 * **Delivery semantics differ per property, deliberately** — mirrors
 * [io.github.ygaray.sttengine.SttEngine]'s own doc-comment table convention:
 *
 * | Property | Shape | Why |
 * |---|---|---|
 * | [notes] | `StateFlow` | current-value replay; a recomposing Compose host reads the whole list immediately |
 * | [noteTranscribed] | `Flow`, LOSSLESS | a transcript committed while no consumer was collecting must never be silently dropped (NOTE-04) |
 * | [pendingCount] | `StateFlow` | current-value replay; a badge/indicator reads it immediately |
 * | [lastSyncedAt] | `StateFlow` | current-value replay; `null` until the first successful drain |
 *
 * **NOTE-04's "never event-only" guarantee is a persistence-ordering rule, not just an event
 * shape:** every implementation MUST persist a note's new state to the durable store BEFORE
 * emitting [noteTranscribed] for it (persist-before-notify) — a consumer that calls into [notes]
 * from inside its own [noteTranscribed] collector must see the already-persisted transcript, not
 * a stale `transcribing` row.
 */
public interface VoiceNotes {

    /**
     * Every known note, replayed to every new collector.
     *
     * A `StateFlow` because a recomposing Compose host wants the current list immediately, the
     * same rationale as [io.github.ygaray.sttengine.SttEngine.state].
     */
    public val notes: StateFlow<List<NoteRecord>>

    /**
     * Delivered when a consumer is alive AND persisted onto the corresponding [NoteRecord]
     * (NOTE-04) — never event-only. A `Flow`, not a `StateFlow`: a transcript that has already
     * been delivered must never be silently replaced/dropped for a late collector, mirroring
     * [io.github.ygaray.sttengine.SttEngine.finals]'s lossless rationale.
     */
    public val noteTranscribed: Flow<NoteRecord>

    /**
     * The count of notes whose [NoteRecord.status] is [NoteStatus.Queued],
     * [NoteStatus.Transcribing], or the retryable [NoteStatus.Failed] — i.e. not yet [NoteStatus.Done].
     *
     * A `StateFlow` for the same current-value-replay reason as [notes] — a pending-count badge
     * wants the current value immediately, not the next transition.
     */
    public val pendingCount: StateFlow<Int>

    /**
     * The ISO-8601 UTC timestamp of the most recent successful drain (a note reaching
     * [NoteStatus.Done]), or `null` if none has ever succeeded.
     */
    public val lastSyncedAt: StateFlow<String?>

    /**
     * Begin recording a new note — forwards to
     * [io.github.ygaray.sttengine.recorder.Recorder.start]. Record-time failures
     * ([NoteErrorCode.MIC_DENIED]/[NoteErrorCode.NO_STORAGE]/[NoteErrorCode.ALREADY_RECORDING])
     * surface through the Recorder's own error path, per D-01's "reuse Phase 8's Recorder error
     * vocabulary" decision.
     */
    public suspend fun record()

    /**
     * Stop the in-progress recording, persist a new [NoteRecord] with [NoteStatus.Queued]
     * (durable BEFORE returning — a recorded note must never be lost), and schedule its drain.
     * Returns the freshly persisted [NoteRecord].
     */
    public suspend fun stopAndEnqueue(): NoteRecord

    /**
     * Force a drain attempt now, bypassing any backoff wait currently in effect — without
     * cancelling an attempt that is already in flight (Pitfall 5: never blind-`REPLACE` a
     * `RUNNING` drain).
     */
    public suspend fun drainNow()

    /**
     * Remove a note and its underlying recorded file. Delegates file removal to
     * [io.github.ygaray.sttengine.recorder.Recorder.delete] using the note's own stored [NoteRecord.uri]
     * unchanged — never reconstructing a path from [NoteRecord.id] or any other field (T-10-07).
     */
    public suspend fun delete(id: String)
}
