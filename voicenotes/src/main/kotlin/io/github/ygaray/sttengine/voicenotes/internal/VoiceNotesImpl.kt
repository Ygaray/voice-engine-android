package io.github.ygaray.sttengine.voicenotes.internal

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import io.github.ygaray.sttengine.recorder.Recorder
import io.github.ygaray.sttengine.voicenotes.NoteRecord
import io.github.ygaray.sttengine.voicenotes.NoteStatus
import io.github.ygaray.sttengine.voicenotes.VoiceNotes
import io.github.ygaray.sttengine.voicenotes.internal.db.toEntity
import io.github.ygaray.sttengine.voicenotes.internal.db.toRecord
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Composes [Recorder] (Phase 8, capture) + [VoiceNotesRuntime]'s Room store + WorkManager into
 * the public [VoiceNotes] contract — the ONLY place this composition happens (mirrors
 * `SttEngineImpl`'s composition-root shape).
 */
internal class VoiceNotesImpl(
    private val context: Context,
    private val recorder: Recorder,
    private val serverUrl: String,
    private val workManager: WorkManager = WorkManager.getInstance(context),
) : VoiceNotes {

    // Not exposed for cancellation anywhere in the public VoiceNotes contract: SharingStarted.Eagerly
    // never stops sharing on its own, so every createVoiceNotes() call permanently pins this
    // SupervisorJob + its Room Flow collector for the remaining life of the process. Callers are
    // expected to call createVoiceNotes() exactly once per process and retain the result for its
    // lifetime — there is currently no lifecycle-teardown method (WR-03, 10-REVIEW.md).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val dao get() = VoiceNotesRuntime.database(context).noteDao()

    override val notes: kotlinx.coroutines.flow.StateFlow<List<NoteRecord>> = dao.observeAll()
        .map { entities -> entities.map { it.toRecord() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val noteTranscribed = VoiceNotesRuntime.noteTranscribed.asSharedFlow()

    // Derived from `notes` rather than a second independent `dao.observeAll()` — avoids
    // duplicating the same underlying Room query with its own separate collector.
    override val pendingCount: kotlinx.coroutines.flow.StateFlow<Int> = notes
        .map { records -> records.count { it.status != NoteStatus.Done } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    override val lastSyncedAt = VoiceNotesRuntime.lastSyncedAt.asStateFlow()

    override suspend fun record() {
        recorder.start()
    }

    override suspend fun stopAndEnqueue(): NoteRecord {
        val recordedFile = recorder.stop()
        val record = NoteRecord(
            id = UUID.randomUUID().toString(),
            uri = recordedFile.uri,
            format = recordedFile.format,
            createdAt = recordedFile.createdAt,
            durationMs = recordedFile.durationMs,
            status = NoteStatus.Queued,
        )
        // Durable-before-return: the Room write completes BEFORE this function returns to the
        // caller (resolves edge-probe NOTE-02 concurrency — mirrors web note.ts's Task-1 discipline).
        dao.upsert(record.toEntity())
        workManager.enqueueUniqueWork(
            record.id,
            ExistingWorkPolicy.KEEP,
            oneTimeDrainRequestFor(record.id),
        )
        return record
    }

    override suspend fun drainNow() {
        val entities = dao.observeAll().first()
        entities
            .filter { it.status != NoteStatus.Done.wireName }
            .forEach { entity ->
                // KEEP: an already-enqueued-or-running attempt is left untouched — never
                // cancelled or duplicated (edge-probe NOTE-06 boundary). Zero eligible notes
                // means zero enqueueUniqueWork calls (the "empty queue is a no-op" half of NOTE-06).
                workManager.enqueueUniqueWork(
                    entity.id,
                    ExistingWorkPolicy.KEEP,
                    oneTimeDrainRequestFor(entity.id),
                )
            }
    }

    override suspend fun delete(id: String) {
        val entity = dao.getById(id) ?: return
        // Stop any in-flight/queued drain for this note FIRST — otherwise a DrainWorker that
        // already read this row before delete() ran can resurrect it via its own terminal
        // `dao.upsert(...)` (INSERT OR REPLACE) after the file and row below are gone.
        workManager.cancelUniqueWork(id)
        // The Recorder's own idempotent file deletion — never reconstruct a path from `id` (V12).
        recorder.delete(entity.uri)
        dao.deleteById(id)
    }

    private fun oneTimeDrainRequestFor(noteId: String) =
        OneTimeWorkRequestBuilder<DrainWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .setInputData(workDataOf("noteId" to noteId, "serverUrl" to serverUrl))
            .build()
}
