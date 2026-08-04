package io.github.ygaray.sttengine.internal

import android.speech.RecognizerIntent
import io.github.ygaray.sttengine.EngineHarness
import io.github.ygaray.sttengine.EngineState
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

    // --- rung (c): the app-owned microphone ----------------------------------------------------------

    @Test
    fun `rung c leads the ladder and its intent carries the audio source extras`() = runTest {
        val h = rawAudioHarness()
        h.collectInto(backgroundScope)
        h.reachListening()

        assertEquals(Rung.RawAudioSegmented, h.engine.activeRung)
        assertEquals("exactly one pipe per session", 1, h.audioPipes.size)
        assertTrue("the session's own pipe must be live", h.audioPipes[0].isOpen)

        val intent = h.fake.startedIntents[0]
        assertTrue(intent.hasExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE))
        assertEquals(
            "the recorder and the intent must not drift apart on sample rate",
            RecognizerIntents.AUDIO_SOURCE_SAMPLE_RATE_HZ,
            intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, -1),
        )
    }

    @Test
    fun `an unprovoked code 5 while our pipe is open degrades the rung instead of failing`() =
        runTest {
            val h = rawAudioHarness()
            h.collectInto(backgroundScope)
            h.reachListening()
            assertTrue(h.audioPipes[0].isOpen)

            // The upstream data-loss signature. In the error table an unprovoked ERROR_CLIENT is
            // FATAL, and on any other rung that is right — but here it is the documented way the
            // app-owned audio source misbehaves, and killing the engine would throw away a working
            // dictation session to punish an optional rung.
            h.fake.fireError(ERROR_CLIENT)
            advanceUntilIdle()

            assertEquals(Rung.SilenceSegmented, h.engine.activeRung)
            assertTrue("the host must not see a terminal failure", h.errors.isEmpty())
            assertTrue(
                "and must not see an error state either",
                h.states.none { it == EngineState.Error },
            )
            assertFalse(
                "the session after the evidence must not ask for raw audio",
                h.fake.startedIntents[1].hasExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE),
            )
        }

    @Test
    fun `a rung c session that produced no segment results degrades`() = runTest {
        val h = rawAudioHarness()
        h.collectInto(backgroundScope)
        h.reachListening()

        // The pipe fed and the session ended, but nothing ever came back. A rung that never segments
        // is a no-op wearing a costume — it carries rung (c)'s whole cost for rung (b)'s behaviour.
        h.fake.fireEndOfSegmentedSession()
        advanceUntilIdle()

        assertEquals(Rung.SilenceSegmented, h.engine.activeRung)
    }

    @Test
    fun `a code 5 arriving after the host stopped is not evidence against rung c`() = runTest {
        val h = rawAudioHarness()
        h.collectInto(backgroundScope)
        h.reachListening()
        val dyingListener = h.listenerFor(0)

        h.fake.fireSegmentResults("the morning light")
        runCurrent()
        h.engine.stop()
        advanceUntilIdle()

        // Exactly the shape the probe measured: code 5 arriving at OUR OWN teardown, after segment
        // finals had already landed, on the rung that scored precision 1.000 on the device.
        // Degrading here would abandon the best rung available because of something we did.
        h.fake.fireError(ERROR_CLIENT, target = dyingListener)
        advanceUntilIdle()

        h.reachListening()
        assertEquals(
            "our own teardown is not a failure of the rung",
            Rung.RawAudioSegmented,
            h.engine.activeRung,
        )
        assertTrue(h.errors.isEmpty())
    }

    @Test
    fun `a pipe that cannot be opened degrades inside the same start rather than failing`() =
        runTest {
            val h = rawAudioHarness(pipeOpenFails = true)
            h.collectInto(backgroundScope)
            h.reachListening()

            // A microphone we cannot open is evidence the rung does not work here, and the whole
            // point of a ladder is that there is always something below. The host sees a normal
            // session start — no error, no extra deaf window, one startListening.
            assertEquals(Rung.SilenceSegmented, h.engine.activeRung)
            assertEquals(1, h.fake.startCount)
            assertTrue(h.errors.isEmpty())
            assertFalse(h.fake.startedIntents[0].hasExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE))
            assertTrue(h.fake.startedIntents[0].hasExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION))
        }

    @Test
    fun `every restart closes the previous pipe and opens exactly one new one`() = runTest {
        val h = rawAudioHarness()
        h.collectInto(backgroundScope)
        h.reachListening()

        // Keep the rung: segment results plus the segmented-end callback is the shape that proves it
        // works, so the ladder does not degrade and the next session is rung (c) again.
        h.fake.fireSegmentResults("the morning light")
        h.fake.fireEndOfSegmentedSession()
        advanceUntilIdle()

        assertEquals(Rung.RawAudioSegmented, h.engine.activeRung)
        assertEquals("one pipe per session, no more", 2, h.audioPipes.size)
        assertFalse(
            "a pipe left open across a restart is a leaked descriptor per session",
            h.audioPipes[0].isOpen,
        )
        assertTrue(h.audioPipes[1].isOpen)
    }

    @Test
    fun `stop releases the microphone`() = runTest {
        val h = rawAudioHarness()
        h.collectInto(backgroundScope)
        h.reachListening()

        h.engine.stop()
        advanceUntilIdle()

        assertFalse(
            "an idle engine must not still be holding the microphone",
            h.audioPipes[0].isOpen,
        )
    }

    @Test
    fun `a terminal failure releases the microphone`() = runTest {
        val h = rawAudioHarness()
        h.collectInto(backgroundScope)
        h.reachListening()

        // ERROR_INSUFFICIENT_PERMISSIONS: fatal on every rung, and nothing the ladder can absorb.
        h.fake.fireError(ERROR_INSUFFICIENT_PERMISSIONS)
        advanceUntilIdle()

        assertEquals(1, h.errors.size)
        assertFalse(
            "the terminal path is exactly where a leak would survive unnoticed",
            h.audioPipes[0].isOpen,
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

    // --- fixtures -------------------------------------------------------------------------------

    /**
     * A harness with the full ladder `[RawAudioSegmented, SilenceSegmented, RestartingSingleSession]`
     * — the shape `createSttEngine` builds on this phase's tester.
     */
    @Suppress("TestFunctionName")
    private fun kotlinx.coroutines.test.TestScope.rawAudioHarness(
        pipeOpenFails: Boolean = false,
    ) = EngineHarness(
        testScheduler,
        supportsSegmented = true,
        supportsRawAudio = true,
        audioPipeOpenFails = pipeOpenFails,
    )

    private companion object {
        /** `ERROR_CLIENT`, as a raw Binder-delivered integer. */
        const val ERROR_CLIENT = 5

        /** `ERROR_INSUFFICIENT_PERMISSIONS`, fatal on every rung. */
        const val ERROR_INSUFFICIENT_PERMISSIONS = 9
    }
}
