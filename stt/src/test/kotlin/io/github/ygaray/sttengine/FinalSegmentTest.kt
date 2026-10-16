package io.github.ygaray.sttengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shape and value semantics of [FinalSegment] with its provenance field. The equality change is
 * intentional: a segment that carries a source no longer equals one built without it.
 */
public class FinalSegmentTest {

    @Test
    public fun `existing constructor shapes default the source to null`() {
        assertNull(FinalSegment("x", 0).languageSource)
        assertNull(FinalSegment("x", 0, "es").languageSource)
        assertEquals(
            LanguageSource.DETECTED,
            FinalSegment("x", 0, "es", LanguageSource.DETECTED).languageSource,
        )
        assertEquals(
            LanguageSource.FALLBACK,
            FinalSegment("x", 0, "en", LanguageSource.FALLBACK).languageSource,
        )
    }

    @Test
    public fun `same fields are equal with equal hashCode`() {
        val a = FinalSegment("x", 0, "es", LanguageSource.DETECTED)
        val b = FinalSegment("x", 0, "es", LanguageSource.DETECTED)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    public fun `different source is not equal`() {
        val detected = FinalSegment("x", 0, "es", LanguageSource.DETECTED)
        val fallback = FinalSegment("x", 0, "es", LanguageSource.FALLBACK)
        assertNotEquals(detected, fallback)
    }

    @Test
    public fun `null source versus DETECTED is not equal`() {
        val withSource = FinalSegment("x", 0, "es", LanguageSource.DETECTED)
        val legacy = FinalSegment("x", 0, "es", null)
        assertNotEquals(withSource, legacy)
        assertNotEquals(legacy, withSource)
        assertEquals(legacy, FinalSegment("x", 0, "es"))
    }

    @Test
    public fun `toString carries the source`() {
        val text = FinalSegment("x", 0, "es", LanguageSource.DETECTED).toString()
        assertTrue(text, text.contains("languageSource=DETECTED"))
        assertTrue(FinalSegment("x", 0).toString().contains("languageSource=null"))
    }

    @Test
    public fun `hash is computed from the enum name so it is stable across runs`() {
        val seg = FinalSegment("x", 0, "es", LanguageSource.DETECTED)
        val expected = 31 * (31 * (31 * "x".hashCode() + 0) + "es".hashCode()) + "DETECTED".hashCode()
        assertEquals(expected, seg.hashCode())
        val noSource = FinalSegment("x", 0, "es")
        assertEquals(31 * (31 * (31 * "x".hashCode() + 0) + "es".hashCode()) + 0, noSource.hashCode())
    }

    @Test
    public fun `HashSet distinguishes a source from its absence and collapses identical segments`() {
        val detected = FinalSegment("x", 0, "es", LanguageSource.DETECTED)
        val legacy = FinalSegment("x", 0, "es", null)
        assertEquals(2, hashSetOf(detected, legacy).size)
        assertEquals(
            1,
            hashSetOf(detected, FinalSegment("x", 0, "es", LanguageSource.DETECTED)).size,
        )
    }

    @Test
    public fun `HashMap finds an entry through an equal key and not through the null-source key`() {
        val map = HashMap<FinalSegment, String>()
        map[FinalSegment("x", 0, "es", LanguageSource.DETECTED)] = "hit"
        assertEquals("hit", map[FinalSegment("x", 0, "es", LanguageSource.DETECTED)])
        assertNull(map[FinalSegment("x", 0, "es", null)])
    }

    @Test
    public fun `distinct keeps both a sourced and an unsourced segment`() {
        val list = listOf(
            FinalSegment("x", 0, "es", LanguageSource.DETECTED),
            FinalSegment("x", 0, "es", null),
        )
        assertEquals(2, list.distinct().size)
    }

    @Test
    public fun `FinalSegment stays a plain class with no copy or componentN`() {
        val names = FinalSegment::class.java.methods.map { it.name }
        assertFalse("copy must not exist", names.contains("copy"))
        assertFalse("component1 must not exist", names.contains("component1"))
    }
}
