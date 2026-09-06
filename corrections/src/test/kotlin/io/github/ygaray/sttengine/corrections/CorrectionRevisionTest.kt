package io.github.ygaray.sttengine.corrections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Equality-contract test for [CorrectionRevision] (Phase 12, CORPUS-01). This is a new-for-this-
 * phase pattern — no `FinalSegmentTest.kt`/`NoteRecordTest.kt` precedent exists anywhere in this
 * repo (confirmed in RESEARCH.md's explicit search) — established here per the plan's `<behavior>`
 * block: two instances built from identical field values are `.equals()` and share `.hashCode()`;
 * two instances differing in exactly one field are NOT `.equals()`.
 */
public class CorrectionRevisionTest {

    private fun sample(
        recognized: String = "helo world",
        corrected: String = "hello world",
        audioUri: String? = "opaque://host-issued-locator",
        createdAt: String = "2026-09-06T00:00:00.000Z",
    ): CorrectionRevision = CorrectionRevision(recognized, corrected, createdAt, audioUri)

    @Test
    public fun `identical field values are equal and share hashCode`() {
        val a = sample()
        val b = sample()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    public fun `differing recognized is not equal`() {
        assertNotEquals(sample(recognized = "helo world"), sample(recognized = "different"))
    }

    @Test
    public fun `differing corrected is not equal`() {
        assertNotEquals(sample(corrected = "hello world"), sample(corrected = "different"))
    }

    @Test
    public fun `differing audioUri is not equal`() {
        assertNotEquals(sample(audioUri = "opaque://a"), sample(audioUri = "opaque://b"))
    }

    @Test
    public fun `differing createdAt is not equal`() {
        assertNotEquals(
            sample(createdAt = "2026-09-06T00:00:00.000Z"),
            sample(createdAt = "2026-09-06T00:00:01.000Z"),
        )
    }

    @Test
    public fun `null audioUri is handled correctly for equality and hashCode`() {
        val a = sample(audioUri = null)
        val b = sample(audioUri = null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, sample(audioUri = "opaque://host-issued-locator"))
    }
}
