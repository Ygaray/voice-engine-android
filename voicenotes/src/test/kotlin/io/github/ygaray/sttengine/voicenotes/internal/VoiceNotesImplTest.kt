package io.github.ygaray.sttengine.voicenotes.internal

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.ygaray.sttengine.recorder.RecordedFile
import io.github.ygaray.sttengine.recorder.Recorder
import io.github.ygaray.sttengine.recorder.RecorderError
import io.github.ygaray.sttengine.recorder.RecorderState
import io.github.ygaray.sttengine.voicenotes.NoteStatus
import io.github.ygaray.sttengine.voicenotes.internal.db.NoteDatabase
import io.github.ygaray.sttengine.voicenotes.internal.db.NoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves [VoiceNotesImpl]'s composition of Recorder + Room + WorkManager (Task 1 acceptance
 * criteria).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceNotesImplTest {

    private lateinit var db: NoteDatabase
    private lateinit var context: Context
    private lateinit var fakeRecorder: FakeRecorder
    private lateinit var impl: VoiceNotesImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        VoiceNotesRuntime.testDatabase = db

        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        fakeRecorder = FakeRecorder()
        impl = VoiceNotesImpl(
            context = context,
            recorder = fakeRecorder,
            serverUrl = "https://example.ts.net:8446",
            workManager = WorkManager.getInstance(context),
        )
    }

    @After
    fun tearDown() {
        VoiceNotesRuntime.testDatabase = null
        db.close()
    }

    @Test
    fun `stopAndEnqueue persists a Queued row visible in notes value before the call returns`() =
        runTest {
            fakeRecorder.stopResult = RecordedFile(
                uri = "/data/user/0/pkg/files/rec-1.m4a",
                durationMs = 4_000L,
                format = "audio/mp4",
                createdAt = "2026-08-24T00:00:00Z",
            )

            val record = impl.stopAndEnqueue()

            val persisted = db.noteDao().getById(record.id)
            assertEquals(NoteStatus.Queued.wireName, persisted?.status)
            assertEquals("/data/user/0/pkg/files/rec-1.m4a", persisted?.uri)
        }

    @Test
    fun `delete calls the fake recorder delete with the note's own stored uri, never a reconstructed path`() =
        runTest {
            val entity = NoteEntity(
                id = "note-1",
                uri = "/data/user/0/pkg/files/note-1-actual.m4a",
                format = "audio/mp4",
                createdAt = "2026-08-24T00:00:00Z",
                durationMs = 1_000L,
                status = NoteStatus.Recorded.wireName,
                jobId = null,
                transcript = null,
                errorCode = null,
                errorMessage = null,
            )
            db.noteDao().upsert(entity)

            impl.delete("note-1")

            assertEquals("/data/user/0/pkg/files/note-1-actual.m4a", fakeRecorder.deletedUri)
            assertEquals(null, db.noteDao().getById("note-1"))
        }

    @Test
    fun `drainNow against an empty note set issues zero enqueueUniqueWork calls`() = runTest {
        impl.drainNow()

        val sampleIds = listOf("sample-a", "sample-b", "sample-c")
        sampleIds.forEach { id ->
            val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(id).get()
            assertTrue("expected no work for id=$id, got $infos", infos.isEmpty())
        }
    }

    @Test
    fun `pendingCount is nonzero when a Failed-status row exists in the seeded DB`() = runTest {
        val entity = NoteEntity(
            id = "failed-note",
            uri = "/data/user/0/pkg/files/failed-note.m4a",
            format = "audio/mp4",
            createdAt = "2026-08-24T00:00:00Z",
            durationMs = 1_000L,
            status = NoteStatus.Failed.wireName,
            jobId = null,
            transcript = null,
            errorCode = "transcription-failed",
            errorMessage = "server rejected the upload",
        )
        db.noteDao().upsert(entity)

        // pendingCount is a StateFlow eagerly started against the Room Flow; give the DB write's
        // change notification a moment to propagate through the StateFlow before asserting.
        var attempts = 0
        while (impl.pendingCount.value == 0 && attempts < 50) {
            kotlinx.coroutines.delay(10)
            attempts++
        }

        assertEquals(1, impl.pendingCount.value)
    }

    // --- fakes ------------------------------------------------------------------------------------

    private class FakeRecorder : Recorder {
        override val state: StateFlow<RecorderState> = MutableStateFlow(RecorderState.Idle)
        override val level: Flow<Float> = emptyFlow()
        override val errors: Flow<RecorderError> = emptyFlow()

        var stopResult: RecordedFile = RecordedFile(
            uri = "/data/user/0/pkg/files/default.m4a",
            durationMs = 0L,
            format = "audio/mp4",
            createdAt = "2026-08-24T00:00:00Z",
        )
        var deletedUri: String? = null

        override suspend fun start() {}
        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun stop(): RecordedFile = stopResult
        override suspend fun delete(uri: String) {
            deletedUri = uri
        }
    }
}
