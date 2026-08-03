package io.github.ygaray.sttengine.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dedup / segment layer (D-02, D-03 mechanism 2, AND-02).
 *
 * Two things are asserted here that no transcript inspection would ever reveal:
 *  1. a final **replaces** the live partial and never appends to it, and
 *  2. a forced teardown commits the last non-blank partial exactly once — neither dropped nor
 *     double-emitted when a late final arrives afterwards.
 *
 * Plus the measured device reality (05-RUNG-VERDICT.md §"`onResults` delivers an EMPTY Bundle"):
 * on the tester, `onResults` carries a **zero-key Bundle** both on natural silence-end and on an
 * explicit `stopListening()`. Committing the last non-blank partial is therefore the PRIMARY path
 * to committed text on rung (a), not a fallback — without it dictation yields nothing at all.
 */
class SegmentAccumulatorTest {

    @Test
    fun `a fresh accumulator reports segmentId 0 and an empty live partial`() {
        val acc = SegmentAccumulator()
        assertEquals(0, acc.segmentId)
        assertEquals("", acc.livePartial)
    }

    @Test
    fun `three successive partials leave only the last as the live partial`() {
        val acc = SegmentAccumulator()
        acc.onPartial("the")
        acc.onPartial("the quick")
        acc.onPartial("the quick brown")

        assertEquals(
            "each partial REPLACES the previous one; concatenation is the classic duplication bug",
            "the quick brown",
            acc.livePartial,
        )
    }

    @Test
    fun `a blank partial is ignored rather than clearing a good partial`() {
        val acc = SegmentAccumulator()
        acc.onPartial("the quick brown")
        acc.onPartial("")
        acc.onPartial("   ")

        assertEquals("the quick brown", acc.livePartial)
    }

    @Test
    fun `a final's text equals its input and does not carry the preceding partial concatenated in front`() {
        val acc = SegmentAccumulator()
        acc.onPartial("the")
        acc.onPartial("the quick")
        acc.onPartial("the quick brown")

        val final = acc.onFinal("The quick brown fox.")

        assertNotNull(final)
        assertEquals("The quick brown fox.", final!!.text)
        assertTrue(
            "the final must REPLACE the partial, never append to it — got '${final.text}'",
            !final.text.startsWith("the quick brown"),
        )
        assertEquals("committing clears the live partial", "", acc.livePartial)
    }

    @Test
    fun `the first final has segmentId 0 and the final after one boundary has segmentId 1`() {
        val acc = SegmentAccumulator()

        val first = acc.onFinal("first segment")
        assertEquals(0, first!!.segmentId)

        acc.onSegmentBoundary()

        val second = acc.onFinal("second segment")
        assertEquals(1, second!!.segmentId)
    }

    @Test
    fun `a partial arriving after a final but before a boundary does not advance the counter`() {
        val acc = SegmentAccumulator()
        acc.onFinal("first segment")
        assertEquals(0, acc.segmentId)

        acc.onPartial("more speech")
        acc.onPartial("more speech arriving")

        assertEquals("segmentId advances on a boundary only, never on a partial", 0, acc.segmentId)
    }

    @Test
    fun `commitLivePartialAsFinal emits one final carrying the live partial's text`() {
        val acc = SegmentAccumulator()
        acc.onPartial("the quick brown fox jumps")

        val final = acc.commitLivePartialAsFinal()

        assertNotNull("a forced teardown must not eat the live partial", final)
        assertEquals("the quick brown fox jumps", final!!.text)
        assertEquals(0, final.segmentId)
        assertEquals("", acc.livePartial)
    }

    @Test
    fun `commitLivePartialAsFinal followed by a late onFinal for the same segment emits exactly one final`() {
        val acc = SegmentAccumulator()
        acc.onPartial("the quick brown fox jumps")

        val emitted = buildList {
            acc.commitLivePartialAsFinal()?.let(::add)
            // The dying session's `onResults` finally lands. Same segment, already committed.
            acc.onFinal("The quick brown fox jumps.")?.let(::add)
            acc.onFinal("The quick brown fox jumps.")?.let(::add)
        }

        assertEquals("exactly one final for a committed segment, no matter how late the real one is", 1, emitted.size)
        assertEquals("the quick brown fox jumps", emitted.single().text)
    }

    @Test
    fun `commitLivePartialAsFinal returns null when the live partial is blank`() {
        val acc = SegmentAccumulator()
        assertNull("nothing was said — there is nothing to commit", acc.commitLivePartialAsFinal())

        acc.onPartial("   ")
        assertNull(acc.commitLivePartialAsFinal())
    }

    @Test
    fun `no final ever carries empty or whitespace-only text`() {
        val acc = SegmentAccumulator()
        assertNull(acc.onFinal(""))
        assertNull(acc.onFinal("   \t \n "))
        assertNull(acc.commitLivePartialAsFinal())

        acc.onPartial("real speech")
        val final = acc.onFinal("  ")
        assertNotNull(final)
        assertEquals("real speech", final!!.text)
    }

    // --- The measured device reality: onResults delivers a ZERO-KEY Bundle on the tester --------

    @Test
    fun `a blank onFinal commits the last non-blank partial - the measured primary path on this device`() {
        val acc = SegmentAccumulator()
        acc.onPartial("Kitchen window")
        acc.onPartial("Kitchen window and fell across the table")

        // 05-PROBE-REPORT.json part2.finalResultDelivery: `onResults` bundle keys == [] on BOTH
        // natural silence-end and explicit stopListening(). The engine hands us "" here.
        val final = acc.onFinal("")

        assertNotNull("rung (a) is the ladder's floor — if this drops the text, dictation yields NOTHING", final)
        assertEquals("Kitchen window and fell across the table", final!!.text)
        assertEquals(FinalTextSource.LAST_NON_BLANK_PARTIAL, acc.lastCommitSource)
    }

    @Test
    fun `a non-blank platform final is PREFERRED over the live partial when the device delivers one`() {
        val acc = SegmentAccumulator()
        acc.onPartial("kitchen window and fell across the")

        val final = acc.onFinal("Kitchen window and fell across the table.")

        assertEquals("Kitchen window and fell across the table.", final!!.text)
        assertEquals(FinalTextSource.PLATFORM_FINAL, acc.lastCommitSource)
    }

    @Test
    fun `the commit-source policy is explicit and independently addressable`() {
        assertEquals(
            FinalTextSource.PLATFORM_FINAL,
            FinalTextPolicy.sourceFor(platformFinalText = "a final", livePartial = "a par"),
        )
        assertEquals(
            FinalTextSource.LAST_NON_BLANK_PARTIAL,
            FinalTextPolicy.sourceFor(platformFinalText = "", livePartial = "a partial"),
        )
        assertEquals(
            FinalTextSource.NONE,
            FinalTextPolicy.sourceFor(platformFinalText = "  ", livePartial = ""),
        )
        assertEquals("a final", FinalTextPolicy.resolve("a final", "a par"))
        assertEquals("a partial", FinalTextPolicy.resolve("", "a partial"))
        assertNull(FinalTextPolicy.resolve("", "  "))
    }

    // --- The prohibition: never satisfy "no duplicated text" by discarding words ----------------

    @Test
    fun `a partial arriving into an already-committed segment survives to the next boundary`() {
        val acc = SegmentAccumulator()
        acc.onPartial("first utterance")
        val first = acc.commitLivePartialAsFinal()
        assertEquals("first utterance", first!!.text)

        // New speech lands before the engine has announced the boundary.
        acc.onPartial("second utterance")
        assertNull("the committed segment must not emit twice", acc.commitLivePartialAsFinal())
        assertEquals("...but the words must NOT be discarded", "second utterance", acc.livePartial)

        acc.onSegmentBoundary()
        val second = acc.commitLivePartialAsFinal()
        assertEquals("second utterance", second!!.text)
        assertEquals(1, second.segmentId)
    }

    @Test
    fun `onSegmentBoundary reopens the segment for commit and advances the id`() {
        val acc = SegmentAccumulator()
        acc.onFinal("segment zero")
        assertNull(acc.onFinal("late duplicate for segment zero"))

        acc.onSegmentBoundary()

        val next = acc.onFinal("segment one")
        assertEquals("segment one", next!!.text)
        assertEquals(1, next.segmentId)
    }

    @Test
    fun `a full restart cycle produces neither duplicated nor dropped text`() {
        // The AND-02 scenario in miniature: partials, a forced teardown that commits, a late final
        // from the dying session, then the next session's segment.
        val acc = SegmentAccumulator()
        val emitted = mutableListOf<String>()

        acc.onPartial("she poured")
        acc.onPartial("she poured a second cup of coffee")
        acc.commitLivePartialAsFinal()?.let { emitted += it.text }
        acc.onFinal("")?.let { emitted += it.text }          // teardown's blank onResults
        acc.onSegmentBoundary()

        acc.onPartial("rain was expected")
        acc.onPartial("rain was expected before noon")
        acc.onFinal("")?.let { emitted += it.text }          // next session's blank onResults
        acc.onSegmentBoundary()

        assertEquals(
            listOf("she poured a second cup of coffee", "rain was expected before noon"),
            emitted,
        )
        assertEquals(2, acc.segmentId)
    }
}
