package io.github.ygaray.sttengine

import io.github.ygaray.sttengine.internal.LanguageAvailability
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * `start()` must ALWAYS complete (D-10).
 *
 * The failure this guards against is not an exception, it is a **hang**. `start()` is specified to
 * complete when the engine reaches `listening`, so the naive implementation awaits exactly that —
 * and on a device where the permission was never granted, or where there is no on-device recognizer
 * at all, `listening` never arrives and the caller's coroutine waits forever. The host sees a spinner
 * that never stops and no error anywhere.
 *
 * So every terminal path has to complete the same settle. And it must **complete**, not
 * complete-exceptionally: permission denial is specified as a typed EVENT, not an exception
 * (D-10/D-11), so a host that wrapped `start()` in a `try`/`catch` would catch nothing and a host
 * that did not would still not crash.
 *
 * Every test here runs on virtual time. None waits on a wall clock, so "it completed" is a real
 * assertion rather than a slow one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class StartCompletionTest {

    @Test
    fun `start completes on the listening path`() = runTest {
        val h = EngineHarness(testScheduler)
        val started = launch(h.dispatcher) { h.engine.start() }
        runCurrent()

        assertFalse(
            "start() must NOT complete before the recognizer is actually ready",
            started.isCompleted,
        )

        h.fake.reachListening()
        advanceUntilIdle()

        assertTrue(started.isCompleted)
        assertEquals(EngineState.Listening, h.engine.state.value)
    }

    @Test
    fun `start completes on the denied-permission path without throwing`() = runTest {
        val h = EngineHarness(testScheduler, permissionGranted = false)
        var threw: Throwable? = null

        val started = launch(h.dispatcher) {
            try {
                h.engine.start()
            } catch (t: Throwable) {
                threw = t
            }
        }
        advanceUntilIdle()

        assertTrue("a mic-denied device must not hang the caller forever", started.isCompleted)
        assertEquals("permission denial is a typed event, never an exception", null, threw)
        assertEquals(EngineState.Error, h.engine.state.value)
    }

    @Test
    fun `start completes on the throwing-factory path without throwing`() = runTest {
        val h = EngineHarness(testScheduler, factoryThrows = true)
        var threw: Throwable? = null

        val started = launch(h.dispatcher) {
            try {
                h.engine.start()
            } catch (t: Throwable) {
                threw = t
            }
        }
        advanceUntilIdle()

        assertTrue("a recognizer-less device must not hang the caller forever", started.isCompleted)
        assertEquals(
            "UnsupportedOperationException must be caught and typed, not propagated",
            null,
            threw,
        )
        assertEquals(EngineState.Error, h.engine.state.value)
    }

    @Test
    fun `start completes on a language preflight failure`() = runTest {
        val h = EngineHarness(
            testScheduler,
            languageAvailability = LanguageAvailability.SUPPORTED_NOT_INSTALLED,
            config = SttConfig(language = "de-DE"),
        )
        val started = launch(h.dispatcher) { h.engine.start() }
        advanceUntilIdle()

        assertTrue(started.isCompleted)
        assertEquals(EngineState.Error, h.engine.state.value)
        assertEquals(
            "a language preflight failure must not open the microphone",
            0,
            h.fake.startCount,
        )
    }

    /**
     * The sharpest form of the D-10 claim, and the only one that exercises the settle in `fail()`.
     *
     * The two preflight tests above complete because `start()` returns before it ever awaits — a
     * real property, but a different one. This is the case where `start()` is genuinely parked on
     * its settle: the recognizer was constructed, the intent went out, and then the session died
     * without ever signalling ready. A busy recognition service does exactly this. If the terminal
     * path did not complete the settle, the caller would wait forever and no other test in this file
     * would notice.
     */
    @Test
    fun `start completes when a terminal error arrives before listening was ever reached`() =
        runTest {
            val h = EngineHarness(testScheduler)
            val started = launch(h.dispatcher) { h.engine.start() }
            runCurrent()
            assertFalse("precondition: start() is parked on its settle", started.isCompleted)

            h.fake.fireError(ERROR_RECOGNIZER_BUSY)
            advanceUntilIdle()

            assertTrue(
                "a session that dies before ready must still release the caller",
                started.isCompleted,
            )
            assertEquals(EngineState.Error, h.engine.state.value)
        }

    @Test
    fun `start completes on an error that arrives after listening was reached`() = runTest {
        val h = EngineHarness(testScheduler)
        val started = launch(h.dispatcher) { h.engine.start() }
        runCurrent()
        h.fake.reachListening()
        advanceUntilIdle()
        assertTrue(started.isCompleted)

        // And the terminal path afterwards is still safe to run against a settled start.
        h.fake.fireError(ERROR_RECOGNIZER_BUSY)
        advanceUntilIdle()
        assertEquals(EngineState.Error, h.engine.state.value)
    }

    @Test
    fun `stop completes even when start never reached listening`() = runTest {
        val h = EngineHarness(testScheduler)
        val started = launch(h.dispatcher) { h.engine.start() }
        runCurrent()
        assertFalse(started.isCompleted)

        h.engine.stop()
        advanceUntilIdle()

        assertTrue("stop() must release a start() that is still waiting", started.isCompleted)
        assertEquals(EngineState.Idle, h.engine.state.value)
    }

    private companion object {
        const val ERROR_RECOGNIZER_BUSY = 8
    }
}
