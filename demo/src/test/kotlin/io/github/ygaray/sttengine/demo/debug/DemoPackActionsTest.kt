package io.github.ygaray.sttengine.demo.debug

import io.github.ygaray.sttengine.LanguageAvailability
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral coverage for the Phase 22 (D-06) language-pack debug actions: the pure helpers behind
 * [DemoControlReceiver.ACTION_CHECK_PACKS] / [DemoControlReceiver.ACTION_DOWNLOAD_PACK], and the
 * Kotlin-to-manifest action-name drift check.
 *
 * Plain JUnit, exactly like [DemoServerPathTest]: every helper under test is a top-level function,
 * so no Context, no Robolectric and no live view model are needed.
 */
class DemoPackActionsTest {

    // --- packsLogLine -------------------------------------------------------------------------------

    @Test
    fun `packs log line lists each language in map order`() {
        val result = linkedMapOf(
            "en" to LanguageAvailability.INSTALLED,
            "es" to LanguageAvailability.DOWNLOADABLE,
        )
        assertEquals("""PACKS {"en":"INSTALLED","es":"DOWNLOADABLE"}""", packsLogLine(result))
    }

    // --- packsDiagnostics ---------------------------------------------------------------------------

    @Test
    fun `packs diagnostics writes exactly kind, packs and elapsedMs`() {
        val json = JSONObject()
        packsDiagnostics(
            into = json,
            result = linkedMapOf(
                "en" to LanguageAvailability.INSTALLED,
                "es" to LanguageAvailability.PENDING,
            ),
            elapsedMs = 321L,
        )

        assertEquals(setOf("kind", "packs", "elapsedMs"), json.keys().asSequence().toSet())
        assertEquals("demo-packs", json.getString("kind"))
        val packs = json.getJSONObject("packs")
        assertEquals(setOf("en", "es"), packs.keys().asSequence().toSet())
        assertEquals("INSTALLED", packs.getString("en"))
        assertEquals("PENDING", packs.getString("es"))
        assertEquals(321L, json.getLong("elapsedMs"))
        assertFalse(json.has("text"))
        assertFalse(json.has("partial"))
    }

    // --- packLangLabel ------------------------------------------------------------------------------

    @Test
    fun `a bare key passes through the label unchanged`() {
        assertEquals("es", packLangLabel("es"))
        assertEquals("en", packLangLabel("en"))
    }

    @Test
    fun `a region-tagged or case-variant tag is shape-valid and passes through`() {
        // The library itself refuses both; the label only bounds what reaches logcat and the sidecar.
        assertEquals("en-US", packLangLabel("en-US"))
        assertEquals("EN", packLangLabel("EN"))
    }

    @Test
    fun `a value that is not tag-shaped becomes unrecognized`() {
        assertEquals(UNRECOGNIZED_LABEL, packLangLabel("a b"))
    }

    @Test
    fun `a 17-char tag-shaped value is over the bound and becomes unrecognized`() {
        val seventeen = "abcdefgh-ijklmnop"
        assertEquals(17, seventeen.length)
        assertEquals(UNRECOGNIZED_LABEL, packLangLabel(seventeen))
    }

    @Test
    fun `a 16-char tag-shaped value is at the bound and passes through`() {
        val sixteen = "abcdefgh-ijklmno"
        assertEquals(16, sixteen.length)
        assertEquals(sixteen, packLangLabel(sixteen))
    }

    @Test
    fun `a 5000-char value becomes unrecognized`() {
        assertEquals(UNRECOGNIZED_LABEL, packLangLabel("e".repeat(5_000)))
    }

    // --- downloadLogLine ----------------------------------------------------------------------------

    @Test
    fun `download log line names the label, acceptance and elapsed ms`() {
        assertEquals(
            "DOWNLOAD es accepted=true elapsedMs=412",
            downloadLogLine(packLangLabel("es"), true, 412L),
        )
    }

    // --- downloadDiagnostics ------------------------------------------------------------------------

    @Test
    fun `download diagnostics writes exactly kind, lang, accepted and elapsedMs`() {
        val json = JSONObject()
        downloadDiagnostics(json, packLangLabel("es"), accepted = false, elapsedMs = 5_001L)

        assertEquals(setOf("kind", "lang", "accepted", "elapsedMs"), json.keys().asSequence().toSet())
        assertEquals("demo-pack-download", json.getString("kind"))
        assertEquals("es", json.getString("lang"))
        assertFalse(json.getBoolean("accepted"))
        assertEquals(5_001L, json.getLong("elapsedMs"))
    }

    @Test
    fun `an oversized language value never reaches the sidecar`() {
        val raw = "e".repeat(5_000)
        val json = JSONObject()
        downloadDiagnostics(json, packLangLabel(raw), accepted = false, elapsedMs = 3L)

        assertEquals(UNRECOGNIZED_LABEL, json.getString("lang"))
        val serialized = json.toString()
        assertTrue("serialized sidecar line is ${serialized.length} chars", serialized.length < 200)
        assertFalse(serialized.contains(raw))
    }

    // --- manifest drift -----------------------------------------------------------------------------

    @Test
    fun `the debug manifest declares every receiver action constant`() {
        // Gradle runs unit tests from the module directory, so this is :demo's debug manifest.
        val manifest = File("src/debug/AndroidManifest.xml")
        assertTrue("debug manifest not found at ${manifest.absolutePath}", manifest.isFile)
        val text = manifest.readText()

        val actions = listOf(
            DemoControlReceiver.ACTION_START,
            DemoControlReceiver.ACTION_STOP,
            DemoControlReceiver.ACTION_DUMP_DIAGNOSTICS,
            DemoControlReceiver.ACTION_CHECK_PACKS,
            DemoControlReceiver.ACTION_DOWNLOAD_PACK,
        )
        val missing = actions.filterNot { text.contains("""<action android:name="$it" />""") }
        assertTrue("debug manifest lacks <action> entries for $missing", missing.isEmpty())
    }

    @Test
    fun `the pack action names are the cross-plan literals`() {
        assertEquals(
            "io.github.ygaray.sttengine.demo.debug.CHECK_PACKS",
            DemoControlReceiver.ACTION_CHECK_PACKS,
        )
        assertEquals(
            "io.github.ygaray.sttengine.demo.debug.DOWNLOAD_PACK",
            DemoControlReceiver.ACTION_DOWNLOAD_PACK,
        )
    }

    @Test
    fun `the pack action budget stays below the broadcast receiver limit`() {
        assertTrue(DemoControlReceiver.PACK_ACTION_BUDGET_MS < 10_000L)
        // The library's worst case is about 2 s query + 5 s download acceptance.
        assertTrue(DemoControlReceiver.PACK_ACTION_BUDGET_MS >= 7_000L)
    }

    private companion object {
        const val UNRECOGNIZED_LABEL = "(unrecognized)"
    }
}
