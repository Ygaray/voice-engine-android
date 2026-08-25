package io.github.ygaray.sttengine.voicenotes.internal

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.github.ygaray.sttengine.voicenotes.NoteErrorCode
import io.github.ygaray.sttengine.voicenotes.NoteStatus
import io.github.ygaray.sttengine.voicenotes.internal.db.NoteDatabase
import io.github.ygaray.sttengine.voicenotes.internal.db.NoteEntity
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves [DrainWorker]'s idempotent upload/poll loop against every Pattern-3/Pitfall-1/2/5
 * scenario from 10-RESEARCH.md (Task 2 acceptance criteria).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DrainWorkerTest {

    private lateinit var db: NoteDatabase
    private lateinit var context: Context
    private val serverUrl = "https://example.ts.net:8446"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        VoiceNotesRuntime.testDatabase = db
    }

    @After
    fun tearDown() {
        VoiceNotesRuntime.testDatabase = null
        VoiceNotesRuntime.httpClient = OkHttpBatchClient()
        VoiceNotesRuntime.lastSyncedAt.value = null
        db.close()
    }

    @Test
    fun `no jobId - postJob called exactly once, Accepted persists jobId and Transcribing before polling`() =
        runTest {
            db.noteDao().upsert(fixtureEntity(id = "note-1", jobId = null, status = NoteStatus.Queued.wireName))
            val fakeClient = FakeBatchHttpClient(
                postJobResult = PostJobOutcome.Accepted("job-new"),
                getJobResult = JobOutcome.Pending,
            )
            VoiceNotesRuntime.httpClient = fakeClient

            val result = runWorker(noteId = "note-1")

            assertEquals(1, fakeClient.postJobCallCount)
            assertEquals(1, fakeClient.getJobCallCount)
            assertTrue(result is ListenableWorker.Result.Retry)
            val row = db.noteDao().getById("note-1")
            assertEquals("job-new", row?.jobId)
            assertEquals(NoteStatus.Transcribing.wireName, row?.status)
        }

    @Test
    fun `existing jobId - postJob called ZERO times, goes straight to getJob (idempotent-replay)`() =
        runTest {
            db.noteDao().upsert(
                fixtureEntity(id = "note-resume", jobId = "job-99", status = NoteStatus.Transcribing.wireName),
            )
            val fakeClient = FakeBatchHttpClient(getJobResult = JobOutcome.Pending)
            VoiceNotesRuntime.httpClient = fakeClient

            val result = runWorker(noteId = "note-resume")

            assertEquals(0, fakeClient.postJobCallCount)
            assertEquals(1, fakeClient.getJobCallCount)
            assertTrue(result is ListenableWorker.Result.Retry)
        }

    @Test
    fun `getJob NotFound - jobId cleared, status queued, Result retry (never failure)`() = runTest {
        db.noteDao().upsert(
            fixtureEntity(id = "note-404", jobId = "job-lost", status = NoteStatus.Transcribing.wireName),
        )
        val fakeClient = FakeBatchHttpClient(getJobResult = JobOutcome.NotFound)
        VoiceNotesRuntime.httpClient = fakeClient

        val result = runWorker(noteId = "note-404")

        assertTrue(result is ListenableWorker.Result.Retry)
        val row = db.noteDao().getById("note-404")
        assertNull(row?.jobId)
        assertEquals(NoteStatus.Queued.wireName, row?.status)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `getJob Done - persist before notify, noteTranscribed collector observes an already-done Room row`() =
        runTest(UnconfinedTestDispatcher()) {
            db.noteDao().upsert(
                fixtureEntity(id = "note-done", jobId = "job-1", status = NoteStatus.Transcribing.wireName),
            )
            val fakeClient = FakeBatchHttpClient(getJobResult = JobOutcome.Done("hello world"))
            VoiceNotesRuntime.httpClient = fakeClient

            var statusAtEmission: String? = null
            var transcriptAtEmission: String? = null
            val collectorJob = launch {
                val received = VoiceNotesRuntime.noteTranscribed.first()
                val rowAtEmission = db.noteDao().getById(received.id)
                statusAtEmission = rowAtEmission?.status
                transcriptAtEmission = rowAtEmission?.transcript
            }

            val result = runWorker(noteId = "note-done")
            collectorJob.join()

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(NoteStatus.Done.wireName, statusAtEmission)
            assertEquals("hello world", transcriptAtEmission)
            assertEquals(NoteStatus.Done.wireName, db.noteDao().getById("note-done")?.status)
            assertEquals("hello world", db.noteDao().getById("note-done")?.transcript)
            assertTrue(VoiceNotesRuntime.lastSyncedAt.value != null)
        }

    @Test
    fun `getJob Failed - typed transcription failure persists and never retries`() = runTest {
        db.noteDao().upsert(
            fixtureEntity(id = "note-tfail", jobId = "job-2", status = NoteStatus.Transcribing.wireName),
        )
        val fakeClient = FakeBatchHttpClient(getJobResult = JobOutcome.Failed("model exploded"))
        VoiceNotesRuntime.httpClient = fakeClient

        val result = runWorker(noteId = "note-tfail")

        assertTrue(result is ListenableWorker.Result.Failure)
        val row = db.noteDao().getById("note-tfail")
        assertEquals(NoteStatus.Failed.wireName, row?.status)
        assertEquals(NoteErrorCode.TRANSCRIPTION_FAILED.value, row?.errorCode)
        assertEquals("model exploded", row?.errorMessage)
    }

    @Test
    fun `postJob Rejected upload-too-large - typed permanent failure never retries`() = runTest {
        db.noteDao().upsert(fixtureEntity(id = "note-reject", jobId = null, status = NoteStatus.Queued.wireName))
        val fakeClient = FakeBatchHttpClient(
            postJobResult = PostJobOutcome.Rejected(NoteErrorCode.UPLOAD_TOO_LARGE, "too big"),
        )
        VoiceNotesRuntime.httpClient = fakeClient

        val result = runWorker(noteId = "note-reject")

        assertTrue(result is ListenableWorker.Result.Failure)
        val row = db.noteDao().getById("note-reject")
        assertEquals(NoteStatus.Failed.wireName, row?.status)
        assertEquals("upload-too-large", row?.errorCode)
        assertEquals("too big", row?.errorMessage)
        assertEquals(0, fakeClient.getJobCallCount)
    }

    @Test
    fun `postJob Transient - retries without marking failed, status and jobId untouched`() = runTest {
        db.noteDao().upsert(fixtureEntity(id = "note-transient", jobId = null, status = NoteStatus.Queued.wireName))
        val fakeClient = FakeBatchHttpClient(postJobResult = PostJobOutcome.Transient)
        VoiceNotesRuntime.httpClient = fakeClient

        val result = runWorker(noteId = "note-transient")

        assertTrue(result is ListenableWorker.Result.Retry)
        val row = db.noteDao().getById("note-transient")
        assertEquals(NoteStatus.Queued.wireName, row?.status)
        assertNull(row?.jobId)
    }

    @Test
    fun `getJob Transient - retries without marking failed, status and jobId untouched`() = runTest {
        db.noteDao().upsert(
            fixtureEntity(id = "note-transient-poll", jobId = "job-3", status = NoteStatus.Transcribing.wireName),
        )
        val fakeClient = FakeBatchHttpClient(getJobResult = JobOutcome.Transient)
        VoiceNotesRuntime.httpClient = fakeClient

        val result = runWorker(noteId = "note-transient-poll")

        assertTrue(result is ListenableWorker.Result.Retry)
        val row = db.noteDao().getById("note-transient-poll")
        assertEquals(NoteStatus.Transcribing.wireName, row?.status)
        assertEquals("job-3", row?.jobId)
    }

    // --- helpers ----------------------------------------------------------------------------------

    private suspend fun runWorker(noteId: String): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<DrainWorker>(context)
            .setInputData(workDataOf("noteId" to noteId, "serverUrl" to serverUrl))
            .build()
        return worker.doWork()
    }

    private fun fixtureEntity(
        id: String,
        jobId: String?,
        status: String,
    ): NoteEntity = NoteEntity(
        id = id,
        uri = "/data/user/0/pkg/files/$id.m4a",
        format = "audio/mp4",
        createdAt = "2026-08-24T00:00:00Z",
        durationMs = 1_000L,
        status = status,
        jobId = jobId,
        transcript = null,
        errorCode = null,
        errorMessage = null,
    )

    private class FakeBatchHttpClient(
        private val postJobResult: PostJobOutcome = PostJobOutcome.Transient,
        private val getJobResult: JobOutcome = JobOutcome.Pending,
    ) : BatchHttpClient {
        var postJobCallCount = 0
        var getJobCallCount = 0

        override suspend fun postJob(file: File, serverUrl: String, format: String): PostJobOutcome {
            postJobCallCount++
            return postJobResult
        }

        override suspend fun getJob(jobId: String, serverUrl: String): JobOutcome {
            getJobCallCount++
            return getJobResult
        }
    }
}
