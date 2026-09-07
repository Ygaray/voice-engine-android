package io.github.ygaray.sttengine.corrections

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Portable correction core — `Corrections.apply` (Phase 13, CORR-01/CORR-03). Covers this plan's
 * `<behavior>` list plus the two-different-pairs adjacency case (no boundary corruption between
 * independently-matched tokens) — the Kotlin twin of `web/tests/corrections.test.ts`.
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
}
