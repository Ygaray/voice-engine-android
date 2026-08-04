package io.github.ygaray.sttengine

import io.github.ygaray.sttengine.analyzer.AssertionId
import io.github.ygaray.sttengine.analyzer.SessionLogAnalyzer
import io.github.ygaray.sttengine.internal.SessionLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The finals' segment-id sequence, which AND-02 reads as a **dropped-text detector**.
 *
 * ## The run that produced this suite
 *
 * A 138-second file-fed AND-02 run on the tester (SM-S908U, API 35), rung (c)
 * `RawAudioSegmented`, produced 14 finals whose text was verbatim-correct but whose segment ids ran
 * `0, 1, 2, 3, 5, 6, … 14` — **id 4 never appeared**, and no partial carried it either. Two
 * independent runs broke at the same stimulus boundary, so it was deterministic rather than flaky.
 * `SessionLogAnalyzer`'s assertion 3 is what caught it, and to a host reading the log that gap is
 * indistinguishable from a segment of speech the engine silently swallowed.
 *
 * ## The invariant these tests defend
 *
 * **A segment id is consumed by a COMMIT, never by a boundary.** The accumulator's counter used to
 * advance on every `onSegmentBoundary()`, and the engine announces a boundary on paths that commit
 * nothing — a segmented session that ends after its last segment result, a session that recognised
 * nothing at all, a `stop()` with an empty buffer. Each of those burned an id and put a hole in the
 * sequence.
 *
 * Every scenario below is a lifecycle shape the device really produces, driven through
 * [io.github.ygaray.sttengine.internal.FakeRecognizerHandle] because `ShadowSpeechRecognizer` has
 * neither `triggerOnSegmentResults` nor `triggerOnEndOfSegmentedSession`. Robolectric is the runner
 * (the fake's bundles must retain their text); SDK 35 because Robolectric refuses 36 below Java 21.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SegmentIdSequenceTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    // --- the measured shape: rung (c), a segmented session ending after its last segment ----------

    @Test
    fun `a segmented session ending after its last segment does not burn a segment id`() = runTest {
        val h = EngineHarness(testScheduler, supportsSegmented = true, supportsRawAudio = true)
        h.collectInto(backgroundScope)
        h.reachListening()

        // Session 1. Each segment result commits AND opens the next segment.
        h.fake.fireSegmentResults("the morning light")
        h.fake.fireSegmentResults("came through the kitchen window")
        runCurrent()

        // …and then the speaker pauses and the segmented session ends with NOTHING left to commit:
        // the last segment result already took the text. This is the boundary the device broke at.
        h.fake.fireEndOfSegmentedSession()
        advanceUntilIdle()

        // Session 2, on the same rung.
        h.fake.reachListening()
        h.fake.fireSegmentResults("and fell across the table")
        runCurrent()
        h.fake.fireEndOfSegmentedSession()
        advanceUntilIdle()

        // Session 3.
        h.fake.reachListening()
        h.fake.fireSegmentResults("she poured a second cup of coffee")
        runCurrent()

        assertEquals(
            "an id must be consumed by a commit, never by a session end that committed nothing — " +
                "a hole here reads as dropped speech",
            listOf(0, 1, 2, 3),
            h.finals.map { it.segmentId },
        )
        assertEquals(
            "and the text itself must be untouched by the fix",
            listOf(
                "the morning light",
                "came through the kitchen window",
                "and fell across the table",
                "she poured a second cup of coffee",
            ),
            h.finals.map { it.text },
        )
    }

    @Test
    fun `partials carry the same gapless ids the finals do`() = runTest {
        val log = enabledLog()
        val h = EngineHarness(
            testScheduler,
            log = log,
            supportsSegmented = true,
            supportsRawAudio = true,
        )
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.firePartial("the morning")
        h.fake.fireSegmentResults("the morning light")
        runCurrent()
        h.fake.fireEndOfSegmentedSession()
        advanceUntilIdle()

        h.fake.reachListening()
        h.fake.firePartial("came through")
        h.fake.fireSegmentResults("came through the kitchen window")
        runCurrent()

        // On the device the partials skipped id 4 alongside the finals, which is what proved the
        // counter — not the emission — was what had moved.
        assertEquals(
            "a partial must be attributed to the segment that will actually commit it",
            listOf(0, 1),
            partialSegmentIds(log),
        )
        assertEquals(listOf(0, 1), h.finals.map { it.segmentId })
    }

    // --- the same defect on the floor rung ---------------------------------------------------------

    @Test
    fun `a session that recognised nothing does not burn a segment id`() = runTest {
        val h = EngineHarness(testScheduler)
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.firePartial("the morning light")
        runCurrent()
        h.fake.fireResults("the morning light")
        advanceUntilIdle()

        // A silent session: the user paused between sentences. ERROR_NO_MATCH ends it, the engine
        // restarts invisibly, and there was never anything to commit.
        h.fake.reachListening()
        h.fake.fireError(ERROR_NO_MATCH)
        advanceUntilIdle()

        h.fake.reachListening()
        h.fake.firePartial("came through the kitchen window")
        runCurrent()
        h.fake.fireResults("came through the kitchen window")
        advanceUntilIdle()

        assertEquals(
            "a pause between sentences is not a segment of dropped speech",
            listOf(0, 1),
            h.finals.map { it.segmentId },
        )
    }

    @Test
    fun `stopping with nothing in flight leaves the next session's first final at id 0`() = runTest {
        val h = EngineHarness(testScheduler)
        h.collectInto(backgroundScope)
        h.reachListening()

        // Nothing was said. stop() flushes an empty buffer and announces a boundary anyway.
        h.engine.stop()
        advanceUntilIdle()

        h.reachListening()
        h.fake.firePartial("the morning light")
        runCurrent()
        h.fake.fireResults("the morning light")
        advanceUntilIdle()

        assertEquals(
            "the very first committed final of a session must be segment 0",
            listOf(0),
            h.finals.map { it.segmentId },
        )
    }

    // --- end to end, through the instrument that found it ------------------------------------------

    @Test
    fun `the analyzer scores a segmented run's session log with no segment-id violation`() =
        runTest {
            val log = enabledLog()
            val h = EngineHarness(
                testScheduler,
                log = log,
                supportsSegmented = true,
                supportsRawAudio = true,
            )
            h.collectInto(backgroundScope)
            h.reachListening()

            // Four segmented sessions, each ending the way the tester's do: a run of segment
            // results, then `onEndOfSegmentedSession` with an empty buffer.
            listOf(
                listOf("the morning light", "came through the kitchen window"),
                listOf("and fell across the table"),
                listOf("she poured a second cup of coffee", "the rain was expected before noon"),
                listOf("the last of the coffee went cold"),
            ).forEachIndexed { index, utterances ->
                if (index > 0) h.fake.reachListening()
                utterances.forEach { h.fake.fireSegmentResults(it) }
                runCurrent()
                h.fake.fireEndOfSegmentedSession()
                advanceUntilIdle()
            }
            h.engine.stop()
            advanceUntilIdle()

            val report = SessionLogAnalyzer.analyzeFile(requireNotNull(log.file))
            val sequence = report.resultOf(AssertionId.SegmentIdSequence)
            assertTrue(
                "assertion 3 is the AND-02 dropped-text detector: ${sequence.detail}",
                sequence.passed,
            )
            assertTrue(
                "no final may be blank either: ${report.detailOf(AssertionId.NoBlankFinal)}",
                report.resultOf(AssertionId.NoBlankFinal).passed,
            )
            assertEquals(6, report.stats.finalCount)
        }

    // --- helpers -----------------------------------------------------------------------------------

    private fun enabledLog(): SessionLog =
        SessionLog(enabled = true, directory = folder.root, sessionStartEpochMs = FIXED_EPOCH_MS)

    private fun partialSegmentIds(log: SessionLog): List<Int> =
        requireNotNull(log.file).readLines()
            .filter { it.isNotBlank() }
            .map { JSONObject(it) }
            .filter { it.getString("kind") == "partial" }
            .map { it.getInt("segmentId") }

    private companion object {
        const val FIXED_EPOCH_MS = 1_770_000_000_000L

        /** `ERROR_NO_MATCH` — the ordinary end of a silent utterance. */
        const val ERROR_NO_MATCH = 7
    }
}
