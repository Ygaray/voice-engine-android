package io.github.ygaray.sttengine.demo

import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.FallbackReason
import io.github.ygaray.sttengine.FinalSegment
import io.github.ygaray.sttengine.LanguageSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-seam coverage for the per-row metadata line (Phase 31, DEMO-01): every decision the line
 * makes (which words, which are loud) lives in [rowLabels], so plain JUnit proves it with no
 * Compose UI test library (none may be added). Pixels, line fit and dark mode are Gate-2 backstops.
 *
 * Plain JUnit, no Context. No host, URL or device alias appears here (public mirror).
 */
class RowMetaTest {

    private fun seg(id: Int, source: LanguageSource? = null, language: String? = null) =
        FinalSegment("a", id, language, source)

    @Test
    fun `native auto confident row`() {
        val labels = rowLabels(
            FinalSegment("a", 0, "en", LanguageSource.DETECTED),
            RowMeta(Backend.Native, null),
        )
        assertEquals("DETECTED", labels.source)
        assertFalse(labels.sourceLoud)
        assertEquals("Native", labels.backend)
        assertEquals(EMPTY_VALUE, labels.reason)
        assertFalse(labels.reasonLoud)
    }

    @Test
    fun `native short or explicit row`() {
        val labels = rowLabels(FinalSegment("a", 1), RowMeta(Backend.Native, null))
        assertEquals(EMPTY_VALUE, labels.source)
        assertEquals("Native", labels.backend)
        assertEquals(EMPTY_VALUE, labels.reason)
        assertFalse(labels.sourceLoud)
        assertFalse(labels.reasonLoud)
    }

    @Test
    fun `server auto fallback row is loud on source`() {
        val labels = rowLabels(seg(0, LanguageSource.FALLBACK, "en"), RowMeta(Backend.Server, null))
        assertEquals("FALLBACK", labels.source)
        assertTrue(labels.sourceLoud)
        assertEquals("Server", labels.backend)
        assertEquals(EMPTY_VALUE, labels.reason)
        assertFalse(labels.reasonLoud)
    }

    @Test
    fun `server auto detected row is not loud`() {
        val labels = rowLabels(seg(0, LanguageSource.DETECTED, "es"), RowMeta(Backend.Server, null))
        assertEquals("DETECTED", labels.source)
        assertFalse(labels.sourceLoud)
        assertEquals("Server", labels.backend)
    }

    @Test
    fun `server explicit es row`() {
        val labels = rowLabels(FinalSegment("a", 2), RowMeta(Backend.Server, null))
        assertEquals(EMPTY_VALUE, labels.source)
        assertEquals("Server", labels.backend)
        assertEquals(EMPTY_VALUE, labels.reason)
        assertFalse(labels.sourceLoud)
        assertFalse(labels.reasonLoud)
    }

    @Test
    fun `every known fallback reason is shown and loud`() {
        val known = listOf(
            FallbackReason.BUSY to "busy",
            FallbackReason.TIMEOUT to "timeout",
            FallbackReason.UNREACHABLE to "unreachable",
            FallbackReason.REJECTED to "rejected",
            FallbackReason.DROPPED to "dropped",
        )
        for ((reason, word) in known) {
            val labels = rowLabels(seg(0), RowMeta(Backend.Native, reason))
            assertEquals(word, labels.reason)
            assertTrue("reason $word must be loud", labels.reasonLoud)
            assertEquals("Native", labels.backend)
        }
    }

    @Test
    fun `unknown reason is shown raw and loud`() {
        val raw = rowLabels(seg(0), RowMeta(Backend.Native, FallbackReason("new-thing")))
        assertEquals("new-thing", raw.reason)
        assertTrue(raw.reasonLoud)

        // A blank value shows the em dash but the reason itself is non-null, so it stays loud.
        val blank = rowLabels(seg(0), RowMeta(Backend.Native, FallbackReason("")))
        assertEquals(EMPTY_VALUE, blank.reason)
        assertTrue(blank.reasonLoud)
    }

    @Test
    fun `handoff pair keeps each row's own snapshot`() {
        val metas = listOf(RowMeta(Backend.Server, null), RowMeta(Backend.Native, FallbackReason.DROPPED))
        val rows = metas.mapIndexed { i, m -> rowLabels(seg(i), m) }
        assertEquals("Server", rows[0].backend)
        assertEquals(EMPTY_VALUE, rows[0].reason)
        assertFalse(rows[0].reasonLoud)
        assertEquals("Native", rows[1].backend)
        assertEquals("dropped", rows[1].reason)
        assertTrue(rows[1].reasonLoud)
    }

    @Test
    fun `missing row meta renders em dashes`() {
        val labels = rowLabels(seg(0, LanguageSource.DETECTED, "en"), null)
        assertEquals(EMPTY_VALUE, labels.backend)
        assertEquals(EMPTY_VALUE, labels.reason)
        assertFalse(labels.reasonLoud)
        // The screen reads rowMeta with getOrNull(index): a short list must reach here as null.
        val shorter = emptyList<RowMeta>()
        val viaGetOrNull = rowLabels(seg(0), shorter.getOrNull(0))
        assertEquals(EMPTY_VALUE, viaGetOrNull.backend)
        assertEquals(EMPTY_VALUE, viaGetOrNull.reason)
    }

    @Test
    fun `null source renders the em dash and is not loud`() {
        val labels = rowLabels(seg(0, null, "en"), RowMeta(Backend.Native, null))
        assertEquals(EMPTY_VALUE, labels.source)
        assertFalse(labels.sourceLoud)
    }

    @Test
    fun `empty value is the em dash`() {
        assertEquals("—", EMPTY_VALUE)
        assertEquals('—', EMPTY_VALUE.single())
    }

    /**
     * RF-1 (31-FOUNDATION-NOTE.md section 5, R1-05-M1): the ViewModel holds `committed` and `rowMeta`
     * as two separate StateFlows, so the screen can recompose between the two writes. Appending the
     * row meta FIRST means a rendered final index always has its metadata (at worst the meta list is
     * one longer than committed for one emission); appending it second lets a final render with a
     * missing entry, a transient em dash row. A plain-JUnit ViewModel is not constructible (it needs
     * an Application), so this pins the write order in the source of onSegmentCommitted.
     */
    @Test
    fun `row meta is appended before the committed segment`() {
        val src = viewModelSource()
        val start = src.indexOf("fun onSegmentCommitted(")
        assertTrue("onSegmentCommitted not found", start >= 0)
        val body = src.substring(start, src.indexOf("\n    }\n", start))
        val metaAt = body.indexOf("_rowMeta.value = _rowMeta.value +")
        val committedAt = body.indexOf("_committed.value = _committed.value +")
        assertTrue("rowMeta append missing", metaAt >= 0)
        assertTrue("committed append missing", committedAt >= 0)
        assertTrue("rowMeta must be appended before committed (RF-1)", metaAt < committedAt)
    }

    private fun viewModelSource(): String {
        val rel = "src/main/kotlin/io/github/ygaray/sttengine/demo/DictationViewModel.kt"
        val candidates = listOf(File(rel), File("android/demo/$rel"), File("demo/$rel"))
        val f = candidates.firstOrNull { it.isFile }
            ?: error("DictationViewModel.kt not found from ${File(".").absolutePath}")
        return f.readText()
    }
}
