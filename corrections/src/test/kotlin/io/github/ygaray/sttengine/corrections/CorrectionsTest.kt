package io.github.ygaray.sttengine.corrections

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Portable correction core — `Corrections.apply`/`Corrections.deriveCorrections` (Phase 13,
 * CORR-01/CORR-02/CORR-03). Covers this plan's `<behavior>` list plus the two-different-pairs
 * adjacency case (no boundary corruption between independently-matched tokens), and 13-03's
 * `deriveCorrections` word-level diff-mining cases plus the deriveCorrections -> apply()
 * integration proof — the Kotlin twin of `web/tests/corrections.test.ts`.
 */
class CorrectionsTest {

    @Test
    fun `apply on empty text with an empty ruleset returns empty text`() {
        assertEquals("", Corrections.apply("", emptyList()))
    }

    @Test
    fun `apply with an empty ruleset returns the text unchanged (fast path)`() {
        assertEquals("hello world", Corrections.apply("hello world", emptyList()))
    }

    @Test
    fun `apply replaces a whole-token match`() {
        val ruleset = listOf(CorrectionPair("cassa", "casa"))
        assertEquals("mi casa", Corrections.apply("mi cassa", ruleset))
    }

    @Test
    fun `apply replaces adjacent identical-pattern tokens independently, with no merge or collision`() {
        val ruleset = listOf(CorrectionPair("a", "b"))
        assertEquals("b b b", Corrections.apply("a a a", ruleset))
    }

    @Test
    fun `two DIFFERENT ruleset entries matching two different tokens in the same string each replace independently`() {
        val ruleset = listOf(CorrectionPair("foo", "quux"), CorrectionPair("bar", "baz"))
        assertEquals("quux baz", Corrections.apply("foo bar", ruleset))
    }

    @Test
    fun `deriveCorrections mines a single-word substitution pair`() {
        val pairs = Corrections.deriveCorrections("mi cassa", "mi casa")
        assertEquals(listOf(CorrectionPair("cassa", "casa")), pairs)
    }

    @Test
    fun `deriveCorrections mines a multi-word pair from an asymmetric word-count diff`() {
        val pairs = Corrections.deriveCorrections("el dock tor llego", "el doctor llego")
        assertEquals(listOf(CorrectionPair("dock tor", "doctor")), pairs)
    }

    @Test
    fun `deriveCorrections returns empty list for a pure insertion`() {
        val pairs = Corrections.deriveCorrections("hola", "hola mundo")
        assertEquals(emptyList<CorrectionPair>(), pairs)
    }

    @Test
    fun `deriveCorrections returns empty list for a pure deletion`() {
        val pairs = Corrections.deriveCorrections("mundo hola", "hola")
        assertEquals(emptyList<CorrectionPair>(), pairs)
    }

    @Test
    fun `deriveCorrections returns empty list for identical text (no-op guard)`() {
        val pairs = Corrections.deriveCorrections("mismo", "mismo")
        assertEquals(emptyList<CorrectionPair>(), pairs)
    }

    @Test
    fun `a pair mined from one sentence corrects the SAME wrong text in a DIFFERENT sentence`() {
        val mined = Corrections.deriveCorrections("mi cassa", "mi casa")
        assertEquals("mi casa es grande", Corrections.apply("mi cassa es grande", mined))
    }

    @Test
    fun `a multi-word pair mined from an asymmetric-word-count diff corrects that phrase elsewhere`() {
        val mined = Corrections.deriveCorrections("el dock tor llego", "el doctor llego")
        assertEquals("el doctor esta aqui", Corrections.apply("el dock tor esta aqui", mined))
    }
}
