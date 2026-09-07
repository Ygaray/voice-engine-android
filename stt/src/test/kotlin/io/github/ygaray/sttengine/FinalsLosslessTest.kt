package io.github.ygaray.sttengine

import io.github.ygaray.sttengine.corrections.CorrectionPair
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **The cheapest proof of the most expensive bug in this library.**
 *
 * A dictation app's user backgrounds the app mid-thought. A lifecycle-aware Compose host detaches
 * its collectors on `STOPPED` — that is not a bug, it is the documented, recommended thing to do.
 * If `finals` were a replay-free `SharedFlow`, every segment committed during that window would be
 * dropped on the floor: words the user actually spoke, gone, with no error, no log line and no
 * symptom until they read their notes back. That is data loss, not a UI glitch, and it is why
 * [SttEngine.finals] is backed by `Channel(UNLIMITED)`.
 *
 * This test is written so that **a replay-free shared flow could not make it pass**: it detaches,
 * commits three finals into the detached window, reattaches, and demands all three back in order.
 * If someone later "simplifies" the backing primitive, this is the file that goes red.
 *
 * The price of the guarantee is single-consumer semantics, which the contract documents. This suite
 * therefore uses exactly one collector at a time — a second would steal from the first, which is the
 * behaviour, not a flaw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class FinalsLosslessTest {

    @Test
    fun `finals committed while no collector is attached are all delivered on reattach, in order`() =
        runTest {
            val h = EngineHarness(testScheduler)
            val seen = mutableListOf<FinalSegment>()

            // 1. attached
            val first = collect(h, seen)
            h.reachListening()
            commitFinal(h, "the morning light")
            assertEquals(listOf(FinalSegment("the morning light", 0)), seen.toList())

            // 2. DETACHED — the host went to the background mid-dictation
            first.cancelAndJoin()
            commitFinal(h, "warmed the kitchen table")
            commitFinal(h, "and the whole house woke up")
            commitFinal(h, "one slow room at a time")
            assertEquals(
                "nothing may be delivered while detached — but nothing may be LOST either",
                1,
                seen.size,
            )

            // 3. reattached
            collect(h, seen)
            runCurrent()

            assertEquals(
                listOf(
                    FinalSegment("the morning light", 0),
                    FinalSegment("warmed the kitchen table", 1),
                    FinalSegment("and the whole house woke up", 2),
                    FinalSegment("one slow room at a time", 3),
                ),
                seen.toList(),
            )
        }

    @Test
    fun `a final committed before any collector ever attaches is still delivered`() = runTest {
        val h = EngineHarness(testScheduler)
        h.reachListening()
        commitFinal(h, "spoken before anyone was listening")

        val seen = mutableListOf<FinalSegment>()
        collect(h, seen)
        runCurrent()

        assertEquals(listOf(FinalSegment("spoken before anyone was listening", 0)), seen.toList())
    }

    @Test
    fun `a terminal error's typed error survives a detached window too`() = runTest {
        val h = EngineHarness(testScheduler)
        h.reachListening()

        // Nothing is collecting `errors` at all when the failure happens.
        h.fake.fireError(ERROR_LANGUAGE_NOT_SUPPORTED)
        advanceUntilIdle()

        val seen = mutableListOf<SttError>()
        backgroundScope.launch(h.dispatcher) { h.engine.errors.collect { seen += it } }
        runCurrent()

        assertEquals(
            "the one error a session ever emits is the only explanation the host gets",
            listOf(SttErrorCode.UNSUPPORTED_LANGUAGE),
            seen.map { it.code },
        )
    }

    @Test
    fun `a configured correction ruleset corrects the committed final's text (Phase 13, CORR-01, D-01 origin point 1)`() =
        runTest {
            val h =
                EngineHarness(
                    testScheduler,
                    config = SttConfig(corrections = listOf(CorrectionPair("teh", "the"))),
                )
            val seen = mutableListOf<FinalSegment>()
            collect(h, seen)
            h.reachListening()

            h.fake.fireResults("teh cat sat")
            runCurrent()

            assertEquals(listOf(FinalSegment("the cat sat", 0)), seen.toList())
        }

    @Test
    fun `partial text stays byte-identical to the raw recognizer output even with a non-empty corrections ruleset configured (CORR-03)`() =
        runTest {
            val h =
                EngineHarness(
                    testScheduler,
                    config = SttConfig(corrections = listOf(CorrectionPair("teh", "the"))),
                )
            h.reachListening(withSpeech = false)

            h.fake.firePartial("teh cat")
            runCurrent()

            assertEquals("teh cat", h.engine.partial.value)
        }

    // --- helpers ------------------------------------------------------------------------------------

    private fun kotlinx.coroutines.test.TestScope.collect(
        h: EngineHarness,
        into: MutableList<FinalSegment>,
    ): Job = backgroundScope.launch(h.dispatcher) { h.engine.finals.collect { into += it } }

    /**
     * One complete utterance: partial, then the empty `onResults` bundle this phase's tester really
     * delivers, which commits the last non-blank partial and restarts the session.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.commitFinal(h: EngineHarness, text: String) {
        h.fake.firePartial(text)
        runCurrent()
        h.fake.fireEmptyResultsBundle()
        advanceUntilIdle()
    }

    private companion object {
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    }
}
