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

    /** Builds a `CorrectionRevision` fixture with the given recognized/corrected pair, repeated
     *  `n` times — the Kotlin twin of `web/tests/corrections.test.ts`'s `revisions()` helper. */
    private fun revisions(recognized: String, corrected: String, n: Int): List<CorrectionRevision> =
        (0 until n).map {
            CorrectionRevision(recognized = recognized, corrected = corrected, createdAt = "2026-01-01T00:00:00Z")
        }

    @Test
    fun `a pair observed one fewer time than the threshold is excluded (below threshold)`() {
        val revs = revisions("mi cassa", "mi casa", Corrections.DEFAULT_PROMOTION_THRESHOLD - 1)
        val result = Corrections.buildRuleset(revs, thresholdN = Corrections.DEFAULT_PROMOTION_THRESHOLD)
        assertEquals(emptyList<CorrectionPair>(), result)
    }

    @Test
    fun `a pair observed exactly the threshold number of times is included (meets threshold exactly)`() {
        val revs = revisions("mi cassa", "mi casa", Corrections.DEFAULT_PROMOTION_THRESHOLD)
        val result = Corrections.buildRuleset(revs, thresholdN = Corrections.DEFAULT_PROMOTION_THRESHOLD)
        assertEquals(listOf(CorrectionPair("cassa", "casa")), result)
    }

    @Test
    fun `a common EN function word (a) never promotes regardless of observed count (anti-list overrides frequency-gating)`() {
        val revs = revisions("a", "uh", Corrections.DEFAULT_PROMOTION_THRESHOLD)
        val result = Corrections.buildRuleset(revs, thresholdN = Corrections.DEFAULT_PROMOTION_THRESHOLD)
        assertEquals(emptyList<CorrectionPair>(), result)
    }

    @Test
    fun `5 distinct qualifying pairs truncate to EXACTLY maxRulesetSize entries (cap enforced inside the function)`() {
        val revs =
            revisions("wordone", "wordonefix", 1) +
                revisions("wordtwo", "wordtwofix", 1) +
                revisions("wordthree", "wordthreefix", 1) +
                revisions("wordfour", "wordfourfix", 1) +
                revisions("wordfive", "wordfivefix", 1)
        val result = Corrections.buildRuleset(revs, thresholdN = 1, maxRulesetSize = 3)
        assertEquals(3, result.size)
    }

    @Test
    fun `calling buildRuleset twice with the identical revisions list returns deep-equal output both times (purity, no in-module counter)`() {
        val revs = revisions("mi cassa", "mi casa", Corrections.DEFAULT_PROMOTION_THRESHOLD)
        val first = Corrections.buildRuleset(revs, thresholdN = Corrections.DEFAULT_PROMOTION_THRESHOLD)
        val second = Corrections.buildRuleset(revs, thresholdN = Corrections.DEFAULT_PROMOTION_THRESHOLD)
        assertEquals(first, second)
    }
}
