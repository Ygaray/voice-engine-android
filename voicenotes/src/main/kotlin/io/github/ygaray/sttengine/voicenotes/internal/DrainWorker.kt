package io.github.ygaray.sttengine.voicenotes.internal

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.ygaray.sttengine.voicenotes.NoteErrorCode
import io.github.ygaray.sttengine.voicenotes.NoteRecord
import io.github.ygaray.sttengine.voicenotes.NoteStatus
import io.github.ygaray.sttengine.voicenotes.internal.db.toEntity
import io.github.ygaray.sttengine.voicenotes.internal.db.toRecord
import java.io.File
import java.time.Instant

/**
 * The idempotent upload/poll loop (Pattern 3, Pitfalls 1/2/5 in 10-RESEARCH.md).
 *
 * - A note with no [NoteRecord.jobId] gets exactly one `postJob` call; on `Accepted`, the job id
 *   and `Transcribing` status are persisted BEFORE any polling begins (Pattern 3).
 * - A note with an existing `jobId` skips `postJob` entirely and goes straight to `getJob`
 *   (idempotent-replay — resolves Pitfall 2's double-POST-on-restart hazard).
 * - `getJob` returning [JobOutcome.NotFound] means the job reference was lost (TTL eviction or a
 *   server restart, per Pitfall 1) — NOT that transcription failed. The note is requeued
 *   (`jobId = null`, `status = Queued`) and the worker retries, so the NEXT attempt re-POSTs the
 *   still-present original file.
 * - `getJob` returning [JobOutcome.Done] persists `status = Done` + the transcript BEFORE emitting
 *   into [VoiceNotesRuntime.noteTranscribed] and updating [VoiceNotesRuntime.lastSyncedAt]
 *   (persist-before-notify, NOTE-04).
 */
internal class DrainWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val noteId = inputData.getString("noteId") ?: return Result.failure()
        val serverUrl = inputData.getString("serverUrl") ?: return Result.failure()

        val dao = VoiceNotesRuntime.database(applicationContext).noteDao()
        val entity = dao.getById(noteId) ?: return Result.failure()
        val note = entity.toRecord()
        val client = VoiceNotesRuntime.httpClient

        val jobId = note.jobId ?: run {
            when (val outcome = client.postJob(File(note.uri), serverUrl)) {
                is PostJobOutcome.Accepted -> {
                    val updated = NoteRecord(
                        id = note.id,
                        uri = note.uri,
                        format = note.format,
                        createdAt = note.createdAt,
                        durationMs = note.durationMs,
                        status = NoteStatus.Transcribing,
                        jobId = outcome.jobId,
                    )
                    dao.upsert(updated.toEntity())
                    outcome.jobId
                }
                is PostJobOutcome.Rejected -> {
                    val failed = NoteRecord(
                        id = note.id,
                        uri = note.uri,
                        format = note.format,
                        createdAt = note.createdAt,
                        durationMs = note.durationMs,
                        status = NoteStatus.Failed,
                        jobId = null,
                        errorCode = outcome.code,
                        errorMessage = outcome.message,
                    )
                    dao.upsert(failed.toEntity())
                    return Result.failure()
                }
                PostJobOutcome.Transient -> return Result.retry()
            }
        }

        return when (val outcome = client.getJob(jobId, serverUrl)) {
            is JobOutcome.Done -> {
                val updated = NoteRecord(
                    id = note.id,
                    uri = note.uri,
                    format = note.format,
                    createdAt = note.createdAt,
                    durationMs = note.durationMs,
                    status = NoteStatus.Done,
                    jobId = jobId,
                    transcript = outcome.transcript,
                )
                dao.upsert(updated.toEntity())
                VoiceNotesRuntime.lastSyncedAt.value = Instant.now().toString()
                // AFTER the persist — resolves edge-probe NOTE-04 (persist-before-notify).
                VoiceNotesRuntime.noteTranscribed.tryEmit(updated)
                Result.success()
            }
            is JobOutcome.Failed -> {
                val failed = NoteRecord(
                    id = note.id,
                    uri = note.uri,
                    format = note.format,
                    createdAt = note.createdAt,
                    durationMs = note.durationMs,
                    status = NoteStatus.Failed,
                    jobId = jobId,
                    errorCode = NoteErrorCode.TRANSCRIPTION_FAILED,
                    errorMessage = outcome.error,
                )
                dao.upsert(failed.toEntity())
                Result.failure()
            }
            JobOutcome.NotFound -> {
                val requeued = NoteRecord(
                    id = note.id,
                    uri = note.uri,
                    format = note.format,
                    createdAt = note.createdAt,
                    durationMs = note.durationMs,
                    status = NoteStatus.Queued,
                    jobId = null,
                )
                dao.upsert(requeued.toEntity())
                Result.retry()
            }
            JobOutcome.Pending, JobOutcome.Transient -> Result.retry()
        }
    }
}
