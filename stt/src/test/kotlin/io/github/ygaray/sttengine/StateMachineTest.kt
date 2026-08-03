package io.github.ygaray.sttengine

import kotlinx.coroutines.launch
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
 * The engine's state machine (D-10, D-17): what a host sees, and — just as important — what it must
 * never see.
 *
 * The counting assertions here are modelled on `web/tests/errors.test.ts`'s first-error-wins suite.
 * "Emitted exactly once" is the claim that actually protects a host: a second `state(error)` would
 * re-run every error-handling side effect a host attached to it.
 *
 * Robolectric because several cases assert on recognized TEXT, which a plain-JVM `Bundle` silently
 * drops (plan 04's measurement), and pinned to SDK 35 for the Java-17 reason recorded in
 * `EngineHarness`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StateMachineTest {

    // --- the happy path -------------------------------------------------------------------------

    @Test
    fun `start reaches listening with a granted permission and a working recognizer`() = runTest {
        val h = EngineHarness(testScheduler)
        h.collectInto(backgroundScope)

        val started = launch(h.dispatcher) { h.engine.start() }
        runCurrent()
        h.fake.reachListening()
        runCurrent()

        assertTrue("start() must complete once the engine is listening", started.isCompleted)
        assertEquals(EngineState.Listening, h.engine.state.value)
        assertEquals(1, h.handleCreateCount)
    }

    // --- preflight failures are typed EVENTS, never exceptions ----------------------------------

    @Test
    fun `a denied permission emits one mic-denied error and moves to error without throwing`() =
        runTest {
            val h = EngineHarness(testScheduler, permissionGranted = false)
            h.collectInto(backgroundScope)

            h.engine.start()
            advanceUntilIdle()

            assertEquals(1, h.errors.size)
            assertEquals(SttErrorCode.MIC_DENIED, h.errors.single().code)
            assertEquals(EngineState.Error, h.engine.state.value)
            assertEquals("no session may be started after a preflight failure", 0, h.fake.startCount)
        }

    @Test
    fun `a throwing handle factory emits one recognizer-unavailable error`() = runTest {
        val h = EngineHarness(testScheduler, factoryThrows = true)
        h.collectInto(backgroundScope)

        h.engine.start()
        advanceUntilIdle()

        assertEquals(1, h.errors.size)
        assertEquals(SttErrorCode.RECOGNIZER_UNAVAILABLE, h.errors.single().code)
        assertEquals(EngineState.Error, h.engine.state.value)
    }

    // --- partials and finals --------------------------------------------------------------------

    @Test
    fun `a partial then a final yields the partial text, one final at segmentId 0, then a cleared partial`() =
        runTest {
            val h = EngineHarness(testScheduler)
            h.collectInto(backgroundScope)
            h.reachListening()

            h.fake.firePartial("the morning")
            runCurrent()
            assertEquals("the morning", h.engine.partial.value)
            assertTrue("a partial is not a final", h.finals.isEmpty())

            h.fake.fireResults("the morning light")
            runCurrent()

            assertEquals(listOf(FinalSegment("the morning light", 0)), h.finals.toList())
            assertEquals("", h.engine.partial.value)
        }

    // --- the ordinary restart is INVISIBLE (D-17, AND-02) ---------------------------------------

    @Test
    fun `a no-match error restarts the session without leaving listening and without an error`() =
        runTest {
            val h = EngineHarness(testScheduler)
            h.collectInto(backgroundScope)
            h.reachListening()

            h.fake.fireError(ERROR_NO_MATCH)
            advanceUntilIdle()

            assertTrue("an ordinary restart must emit no error", h.errors.isEmpty())
            assertEquals(EngineState.Listening, h.engine.state.value)
            assertEquals(2, h.fake.startCount)
        }

    @Test
    fun `a no-match driven restart never produces a reconnecting state`() = runTest {
        val h = EngineHarness(testScheduler)
        h.collectInto(backgroundScope)
        h.reachListening()

        repeat(3) {
            h.fake.fireError(ERROR_NO_MATCH)
            advanceUntilIdle()
            h.fake.reachListening()
            runCurrent()
        }

        assertFalse(
            "the restart gap AND-02 requires to be invisible must never surface as reconnecting",
            h.states.contains(EngineState.Reconnecting),
        )
        assertEquals(EngineState.Listening, h.engine.state.value)
    }

    // --- first error wins -----------------------------------------------------------------------

    @Test
    fun `a language-not-supported error emits exactly one unsupported-language error`() = runTest {
        val h = EngineHarness(testScheduler)
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.fireError(ERROR_LANGUAGE_NOT_SUPPORTED)
        advanceUntilIdle()

        assertEquals(1, h.errors.size)
        assertEquals(SttErrorCode.UNSUPPORTED_LANGUAGE, h.errors.single().code)
        assertEquals(EngineState.Error, h.engine.state.value)
    }

    @Test
    fun `a second failure condition after a terminal error is a no-op`() = runTest {
        val h = EngineHarness(testScheduler)
        h.collectInto(backgroundScope)
        h.reachListening()
        val listener = h.listenerFor(0)

        h.fake.fireError(ERROR_LANGUAGE_NOT_SUPPORTED, target = listener)
        advanceUntilIdle()
        h.fake.fireError(ERROR_RECOGNIZER_BUSY, target = listener)
        advanceUntilIdle()

        assertEquals("first error wins — no second error may reach the host", 1, h.errors.size)
        assertEquals(SttErrorCode.UNSUPPORTED_LANGUAGE, h.errors.single().code)
        assertEquals(
            "state(error) must be emitted exactly once",
            1,
            h.states.count { it == EngineState.Error },
        )
    }

    // --- stop() flushes ---------------------------------------------------------------------------

    @Test
    fun `stop flushes the open segment as a final before the state leaves listening`() = runTest {
        val h = EngineHarness(testScheduler)
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.firePartial("half a sentence")
        runCurrent()
        h.engine.stop()
        advanceUntilIdle()

        assertEquals(listOf(FinalSegment("half a sentence", 0)), h.finals.toList())
        assertEquals(EngineState.Idle, h.engine.state.value)
        // The flush has to land while the engine is still listening: a host rendering `finals` only
        // while listening would otherwise drop the last thing the user said.
        val finalIndex = h.events.indexOfFirst { it is SttEvent.Final }
        val idleIndex = h.events.indexOfFirst { it is SttEvent.State && it.state == EngineState.Idle }
        assertTrue("the flushed final must precede the idle transition", finalIndex in 0 until idleIndex)
    }

    // --- the surface is HOT (D-08) ----------------------------------------------------------------

    @Test
    fun `two simultaneous collectors construct exactly one recognizer`() = runTest {
        val h = EngineHarness(testScheduler)
        val seenPartials = mutableListOf<String>()
        val seenLevels = mutableListOf<Float>()
        backgroundScope.launch(h.dispatcher) { h.engine.partial.collect { seenPartials += it } }
        backgroundScope.launch(h.dispatcher) { h.engine.level.collect { seenLevels += it } }
        runCurrent()

        h.reachListening()
        h.fake.fireRmsChanged(4f)
        runCurrent()

        assertEquals(
            "a cold public flow would open one microphone session per collector",
            1,
            h.handleCreateCount,
        )
        assertEquals(1, h.fake.setListenerCount)
        assertEquals(1, seenLevels.size)
    }

    @Test
    fun `rms is normalized into 0 to 1 and non-finite values are discarded`() = runTest {
        val h = EngineHarness(testScheduler)
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.fireRmsChanged(-100f)
        h.fake.fireRmsChanged(100f)
        h.fake.fireRmsChanged(Float.NaN)
        h.fake.fireRmsChanged(Float.POSITIVE_INFINITY)
        runCurrent()

        assertEquals(listOf(0f, 1f), h.levels.toList())
    }

    private companion object {
        // Raw integers with the symbolic name here, for the same reason ErrorMapper uses them: these
        // are values received across a Binder boundary, not APIs this test calls.
        const val ERROR_NO_MATCH = 7
        const val ERROR_RECOGNIZER_BUSY = 8
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    }
}
