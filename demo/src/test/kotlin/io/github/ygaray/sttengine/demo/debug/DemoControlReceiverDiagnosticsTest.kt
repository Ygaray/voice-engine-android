package io.github.ygaray.sttengine.demo.debug

import io.github.ygaray.sttengine.FinalSegment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral coverage for the pure diagnostics helpers added in Phase 19 (D-02):
 * [lastSegmentLanguageOf], [segmentLanguagesOf], and [languageDiagnostics] — plus Phase 21's
 * [lastFinalDiagnostics] (LAST-01).
 *
 * Plain JUnit only — no Robolectric, no Context, no live [io.github.ygaray.sttengine.demo.DictationViewModel].
 * Every function under test takes a plain `List<FinalSegment>` / a bare `JSONObject`, so none of
 * that is needed. `:demo`'s `testImplementation(libs.json)` (see `build.gradle.kts`) gives this
 * module a REAL `org.json.JSONObject`/`JSONArray` implementation on the host JVM — without it,
 * these classes are Android-platform stubs that throw "not mocked" at runtime (19-REVIEWS.md
 * cycle 3, Codex HIGH).
 */
class DemoControlReceiverDiagnosticsTest {

    @Test
    fun `lastSegmentLanguageOf returns null for an empty list`() {
        assertNull(lastSegmentLanguageOf(emptyList()))
    }

    @Test
    fun `lastSegmentLanguageOf returns the latest segment's language, not the first`() {
        val committed = listOf(
            FinalSegment("hola", 0, "es"),
            FinalSegment("hello", 1, "en"),
        )
        assertEquals("en", lastSegmentLanguageOf(committed))
    }

    @Test
    fun `lastSegmentLanguageOf returns null when the latest segment has no confident label`() {
        val committed = listOf(
            FinalSegment("hello", 0, "en"),
            FinalSegment("mumble", 1, null),
        )
        assertNull(lastSegmentLanguageOf(committed))
    }

    @Test
    fun `segmentLanguagesOf returns an empty JSONArray for an empty list`() {
        val array = segmentLanguagesOf(emptyList())
        assertEquals(0, array.length())
    }

    @Test
    fun `segmentLanguagesOf preserves order and NULL handling across a mixed sequence`() {
        val committed = listOf(
            FinalSegment("hola", 0, "es"),
            FinalSegment("mumble", 1, null),
            FinalSegment("hello", 2, "en"),
        )
        val array = segmentLanguagesOf(committed)

        assertEquals(3, array.length())

        val first = array.getJSONObject(0)
        assertEquals(0, first.getInt("segmentId"))
        assertEquals("es", first.getString("language"))

        val second = array.getJSONObject(1)
        assertEquals(1, second.getInt("segmentId"))
        assertTrue(second.isNull("language"))

        val third = array.getJSONObject(2)
        assertEquals(2, third.getInt("segmentId"))
        assertEquals("en", third.getString("language"))
    }

    @Test
    fun `languageDiagnostics writes both keys onto the actual produced record with no text key`() {
        val committed = listOf(
            FinalSegment("hola", 0, "es"),
            FinalSegment("mumble", 1, null),
            FinalSegment("hello", 2, "en"),
        )
        val into = JSONObject()

        languageDiagnostics(into, committed)

        // Scalar convenience field — always equal to the array's last entry.
        assertEquals("en", into.getString("lastSegmentLanguage"))

        // The full ordered record.
        val array = into.getJSONArray("segmentLanguages")
        assertEquals(3, array.length())
        assertEquals("es", array.getJSONObject(0).getString("language"))
        assertTrue(array.getJSONObject(1).isNull("language"))
        assertEquals("en", array.getJSONObject(2).getString("language"))

        // Privacy invariant: never a transcript/text key anywhere in the produced record.
        assertTrue(!into.has("text"))
        assertTrue(!into.has("segmentTexts"))
        for (i in 0 until array.length()) {
            assertTrue(!array.getJSONObject(i).has("text"))
        }
    }

    // --- LAST-01: lastFinalDiagnostics ------------------------------------------------------------

    @Test
    fun `lastFinalDiagnostics writes both keys as JSON null before any final`() {
        val into = JSONObject()

        lastFinalDiagnostics(into, null)

        assertTrue(into.has("lastFinalSegmentId"))
        assertTrue(into.has("lastFinalLanguage"))
        assertTrue(into.isNull("lastFinalSegmentId"))
        assertTrue(into.isNull("lastFinalLanguage"))
    }

    @Test
    fun `lastFinalDiagnostics writes the id and language and never the transcript text`() {
        val into = JSONObject()

        lastFinalDiagnostics(into, FinalSegment("secret words", 7, "es"))

        assertEquals(7, into.getInt("lastFinalSegmentId"))
        assertEquals("es", into.getString("lastFinalLanguage"))
        // Privacy invariant (D-08/SRV-05): no value anywhere in the record is the dictated text.
        val keys = into.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            assertNotEquals("key $key leaked the transcript text", "secret words", into.get(key))
        }
        assertTrue(!into.toString().contains("secret words"))
    }

    @Test
    fun `lastFinalDiagnostics writes JSON null language for an unlabeled final`() {
        val into = JSONObject()

        lastFinalDiagnostics(into, FinalSegment("mumble", 3, null))

        assertEquals(3, into.getInt("lastFinalSegmentId"))
        assertTrue(into.isNull("lastFinalLanguage"))
    }
}
