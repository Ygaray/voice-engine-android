package io.github.ygaray.sttengine.voicenotes.internal.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.voicenotes.NoteStatus
import kotlinx.coroutines.flow.first
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
 * The Room in-memory suite proving [NoteDao]/[NoteDatabase]/[NoteMapper]'s ordering, empty,
 * adjacency, and round-trip behavior (NOTE-03's edge-probes) — mirrors `RecorderTest`'s
 * Robolectric convention.
 *
 * ## What each test rules out
 *
 * | Test | The failure it makes impossible |
 * |---|---|
 * | empty DB emits emptyList(), never null | `observeAll()` on an empty table returns null instead of an empty list (edge-probe NOTE-03 empty) |
 * | distinct ids with identical other fields stay two rows | Two notes recorded at the same instant, same duration/format, get silently merged into one row (edge-probe NOTE-03 adjacency) |
 * | observeAll() orders by createdAt ascending, stably | Row order is SQLite's unspecified default rather than a deterministic, repeatable createdAt order (edge-probe NOTE-03 ordering) |
 * | REPLACE conflict strategy updates in place | `upsert()` on a pre-existing id duplicates the row instead of updating it |
 * | NoteMapper round-trip preserves every field | A field is dropped/mistranslated crossing the NoteEntity <-> NoteRecord boundary |
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoteDaoTest {

    private lateinit var db: NoteDatabase
    private lateinit var dao: NoteDao

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.noteDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `observeAll on an empty database emits an empty list, never null`() = runTest {
        val result = dao.observeAll().first()
        assertTrue("expected an empty list, got $result", result.isEmpty())
    }

    @Test
    fun `two notes with identical createdAt durationMs and format but distinct ids stay two rows`() =
        runTest {
            val shared = fixture(id = "shared-a", createdAt = "2026-08-24T00:00:00Z", durationMs = 5_000L)
            val other = fixture(id = "shared-b", createdAt = "2026-08-24T00:00:00Z", durationMs = 5_000L)

            dao.upsert(shared)
            dao.upsert(other)

            assertEquals(shared, dao.getById("shared-a"))
            assertEquals(other, dao.getById("shared-b"))
            assertEquals(2, dao.observeAll().first().size)
        }

    @Test
    fun `observeAll returns rows ordered by createdAt ascending, stable across repeated collection`() =
        runTest {
            val third = fixture(id = "third", createdAt = "2026-08-24T00:02:00Z")
            val first = fixture(id = "first", createdAt = "2026-08-24T00:00:00Z")
            val second = fixture(id = "second", createdAt = "2026-08-24T00:01:00Z")

            // Insert deliberately out of chronological order to prove the ORDER BY, not insertion order.
            dao.upsert(third)
            dao.upsert(first)
            dao.upsert(second)

            val expectedIds = listOf("first", "second", "third")
            assertEquals(expectedIds, dao.observeAll().first().map { it.id })
            // Repeated collection is stable — no mutation happened between collections.
            assertEquals(expectedIds, dao.observeAll().first().map { it.id })
        }

    @Test
    fun `upsert with REPLACE conflict strategy updates a pre-existing id in place`() = runTest {
        val original = fixture(id = "note-1", status = NoteStatus.Queued.wireName)
        dao.upsert(original)
        assertEquals(1, dao.observeAll().first().size)

        val updated = original.copy(status = NoteStatus.Done.wireName, transcript = "hello world")
        dao.upsert(updated)

        val rows = dao.observeAll().first()
        assertEquals("REPLACE must update in place, not duplicate", 1, rows.size)
        assertEquals(updated, dao.getById("note-1"))
    }

    @Test
    fun `NoteMapper round-trip preserves every field byte-for-byte`() = runTest {
        val record = fixture(
            id = "note-rt",
            status = NoteStatus.Failed.wireName,
            jobId = "job-42",
            transcript = null,
            errorCode = "transcription-failed",
            errorMessage = "server rejected the upload",
        ).toRecord()

        val roundTripped = record.toEntity().toRecord()

        assertEquals(record.id, roundTripped.id)
        assertEquals(record.uri, roundTripped.uri)
        assertEquals(record.format, roundTripped.format)
        assertEquals(record.createdAt, roundTripped.createdAt)
        assertEquals(record.durationMs, roundTripped.durationMs)
        assertEquals(record.status, roundTripped.status)
        assertEquals(record.jobId, roundTripped.jobId)
        assertEquals(record.transcript, roundTripped.transcript)
        assertEquals(record.errorCode?.value, roundTripped.errorCode?.value)
        assertEquals(record.errorMessage, roundTripped.errorMessage)
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun fixture(
        id: String,
        createdAt: String = "2026-08-24T00:00:00Z",
        durationMs: Long = 1_000L,
        status: String = NoteStatus.Recorded.wireName,
        jobId: String? = null,
        transcript: String? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
    ): NoteEntity = NoteEntity(
        id = id,
        uri = "/data/user/0/pkg/files/$id.m4a",
        format = "audio/mp4",
        createdAt = createdAt,
        durationMs = durationMs,
        status = status,
        jobId = jobId,
        transcript = transcript,
        errorCode = errorCode,
        errorMessage = errorMessage,
    )
}
