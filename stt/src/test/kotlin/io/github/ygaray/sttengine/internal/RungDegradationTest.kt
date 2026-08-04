package io.github.ygaray.sttengine.internal

import android.speech.RecognizerIntent
import io.github.ygaray.sttengine.EngineHarness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ladder, live: rungs (b) and (c) are enabled from the probe verdict
 * (`05-RUNG-DECISION.md`), so the degradation trigger is no longer decorative.
 *
 * ## Why this suite drives the fake and not Robolectric's shadow
 *
 * `ShadowSpeechRecognizer` 4.16.1 has **no** `triggerOnSegmentResults` and **no**
 * `triggerOnEndOfSegmentedSession` (verified by `javap` in plan 04) — which are precisely the two
 * callbacks these rungs run on. Every scenario below is therefore driven through
 * [FakeRecognizerHandle]. Robolectric is still the *runner*, because the fake's bundles must retain
 * their text; SDK 35 because Robolectric refuses 36 below Java 21 and this module is Java 17.
 *
 * ## What is actually being proven
 *
 * A silently-ignored extra is the failure mode that costs the most and shows the least: the session
 * behaves like the rung below it, so the transcript looks fine while every restart spends a
 * 164–259 ms deaf window for nothing. The trigger therefore tests **evidence that the rung did not
 * do its job** — a session that ended on a final having never delivered `onEndOfSegmentedSession`,
 * a callback that exists *only* in segmented mode — rather than the mere occurrence of an error.
 *
 * And it is one way. A device that ignored the extra will ignore it again; climbing back to re-probe
 * would spend another deaf window to learn the same answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class RungDegradationTest {

    // --- the capability is a per-start() parameter ------------------------------------------------

    @Test
    fun `the segmented capability reaches the ladder and puts rung b above the floor`() = runTest {
        val h = EngineHarness(testScheduler, supportsSegmented = true)
        h.collectInto(backgroundScope)
        h.reachListening()

        assertEquals(Rung.SilenceSegmented, h.engine.activeRung)
        assertTrue(
            "rung (b)'s session must carry the segmented-session extra",
            h.fake.startedIntents[0].hasExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION),
        )
    }

    @Test
    fun `without the capability the engine starts on the floor and carries no segmented extra`() =
        runTest {
            val h = EngineHarness(testScheduler, supportsSegmented = false)
            h.collectInto(backgroundScope)
            h.reachListening()

            assertEquals(Rung.RestartingSingleSession, h.engine.activeRung)
            assertFalse(
                h.fake.startedIntents[0].hasExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION),
            )
        }

    // --- scenario 1: the rung does its job and is kept ---------------------------------------------

    @Test
    fun `a session delivering segment results and the segmented end callback stays on rung b`() =
        runTest {
            val h = EngineHarness(testScheduler, supportsSegmented = true)
            h.collectInto(backgroundScope)
            h.reachListening()

            // The measured shape of a working segmented session on the tester: several segment
            // results inside ONE session, then the segmented-end callback. `onResults` never fires.
            h.fake.fireSegmentResults("the morning light")
            h.fake.fireSegmentResults("came through the kitchen window")
            h.fake.fireSegmentResults("and fell across the table")
            runCurrent()

            assertEquals(
                "a segment result commits without restarting — a segmented session continues",
                1,
                h.fake.startCount,
            )
            assertEquals(3, h.finals.size)

            h.fake.fireEndOfSegmentedSession()
            advanceUntilIdle()

            assertEquals(
                "the rung did exactly what it exists to do; keep it",
                Rung.SilenceSegmented,
                h.engine.activeRung,
            )
            assertTrue(
                "the next session must still be a segmented one",
                h.fake.startedIntents[1].hasExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION),
            )
        }

    // --- scenario 2: the extra was silently ignored, so the rung is dropped -------------------------

    @Test
    fun `a session ending on a final with no segmented end callback degrades to the floor`() =
        runTest {
            val h = EngineHarness(testScheduler, supportsSegmented = true)
            h.collectInto(backgroundScope)
            h.reachListening()
            assertEquals(Rung.SilenceSegmented, h.engine.activeRung)

            h.fake.firePartial("the morning light")
            runCurrent()
            // This is the whole signature: a plain `onResults` ended the session, and
            // `onEndOfSegmentedSession` — which is delivered ONLY in segmented mode — never came.
            // One such session is proof enough that the extra was ignored.
            h.fake.fireResults("the morning light")
            advanceUntilIdle()

            assertEquals(Rung.RestartingSingleSession, h.engine.activeRung)
            assertFalse(
                "the session after the evidence must not carry the ignored extra",
                h.fake.startedIntents[1].hasExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION),
            )
            assertEquals(
                "degrading must not cost the words the user already spoke",
                listOf("the morning light"),
                h.finals.map { it.text },
            )
        }

    @Test
    fun `a blank platform final is still degradation evidence - the tester's real onResults`() =
        runTest {
            val h = EngineHarness(testScheduler, supportsSegmented = true)
            h.collectInto(backgroundScope)
            h.reachListening()

            h.fake.firePartial("the morning light")
            runCurrent()
            // On the tester `onResults` carries a ZERO-KEY bundle. The rung is judged on HOW the
            // session ended, never on whether that ending carried text — otherwise the one device
            // this phase runs on could never degrade at all.
            h.fake.fireEmptyResultsBundle()
            advanceUntilIdle()

            assertEquals(Rung.RestartingSingleSession, h.engine.activeRung)
            assertEquals(listOf("the morning light"), h.finals.map { it.text })
        }

    // --- scenario 3: degradation is one way ---------------------------------------------------------

    @Test
    fun `once degraded no later session in the same cycle returns to the higher rung`() = runTest {
        val h = EngineHarness(testScheduler, supportsSegmented = true)
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.fireResults("the morning light")
        advanceUntilIdle()
        assertEquals(Rung.RestartingSingleSession, h.engine.activeRung)

        // Now hand the engine the very evidence that WOULD have justified the higher rung, three
        // sessions running. A rung that could be re-probed would take the bait here and spend a deaf
        // window per attempt to relearn what one session already proved.
        repeat(3) {
            h.fake.reachListening()
            h.fake.fireSegmentResults("a segment arrived anyway")
            h.fake.fireEndOfSegmentedSession()
            advanceUntilIdle()

            assertEquals(
                "the ladder must never climb back mid-cycle",
                Rung.RestartingSingleSession,
                h.engine.activeRung,
            )
        }

        assertTrue(
            "every session after the evidence ran on the floor",
            h.fake.startedIntents.drop(1)
                .none { it.hasExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION) },
        )
    }

    @Test
    fun `a withdrawn rung is not offered again by a later start on the same engine`() = runTest {
        val h = EngineHarness(testScheduler, supportsSegmented = true)
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.fireResults("the morning light")
        advanceUntilIdle()
        assertEquals(Rung.RestartingSingleSession, h.engine.activeRung)

        h.engine.stop()
        advanceUntilIdle()
        h.reachListening()

        // The capability is re-resolved at every start() — that is what makes it a parameter rather
        // than a constant. Re-resolving must read the evidence this engine gathered, not the seed it
        // was constructed with, or the withdrawal would last exactly one start-stop cycle.
        assertEquals(Rung.RestartingSingleSession, h.engine.activeRung)
        assertTrue(
            h.fake.startedIntents.last().hasExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION).not(),
        )
    }

    // --- the floor is never degraded away from ------------------------------------------------------

    @Test
    fun `the floor survives the shape that degrades every rung above it`() = runTest {
        val h = EngineHarness(testScheduler, supportsSegmented = false)
        h.collectInto(backgroundScope)
        h.reachListening()

        repeat(3) {
            h.fake.fireResults("the morning light")
            advanceUntilIdle()
            h.fake.reachListening()
        }

        assertEquals(
            "there is nothing below the classic restart rung",
            Rung.RestartingSingleSession,
            h.engine.activeRung,
        )
    }
}
