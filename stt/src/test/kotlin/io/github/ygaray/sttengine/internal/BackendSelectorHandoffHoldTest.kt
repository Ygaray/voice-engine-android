package io.github.ygaray.sttengine.internal

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.LanguageAvailability
import io.github.ygaray.sttengine.PreflightHandle
import io.github.ygaray.sttengine.SttConfig
import io.github.ygaray.sttengine.SttError
import io.github.ygaray.sttengine.SttErrorCode
import io.github.ygaray.sttengine.SttEvent
import java.io.IOException
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * XR-01's proof of SEL-04/D-03's "invisible handoff": a mid-session server→native handoff must not
 * surface native's own pre-Listening states on the shared `state` contract. Before XR-01 a host
 * collecting [BackendSelector.events] saw `Listening → Connecting → Listening` across the handoff;
 * the probe that confirmed it also refuted the "transient Idle" theory (the native collector's first
 * read is `Connecting`, never `Idle`), so the fix holds Connecting/Reconnecting rather than
 * filtering Idle.
 *
 * Each test pins one clause of the teamlead ruling (option C, v0.8.1):
 * - the handoff itself is invisible — Tests A (virtual time) and A' (real `Dispatchers.Main.immediate`);
 * - **(a)** a native `Error` during the hold passes through at once, with no wait — Test B;
 * - **(b)** a native that is still not Listening when [BackendSelector.tryServerTimeoutMs] runs out
 *   is surfaced at exactly that bound as its CURRENT state, with no new error — Test C;
 * - the hold ends for good once native reaches Listening, so a later `Reconnecting` forwards — Test D;
 * - **(c)** the Auto-fallback path holds nothing and expires nothing — Test E (two variants);
 * - stop() during the hold ends at Idle and the hold never writes into the next session — Test F;
 * - **(d)** no public API change — proven by `:stt:apiCheck`, not here.
 *
 * Assertions read the ORDERED [SttEvent.State] list from `events`, never `state` snapshots, which
 * conflate. Every hold test uses `runCurrent()` (never `advanceUntilIdle()`) between the drop and
 * `reachListening()`: `advanceUntilIdle()` jumps virtual time and would run the hold timer to expiry,
 * so the test would pass for the wrong reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class BackendSelectorHandoffHoldTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private class Harness(
        val fakeWs: FakeWebSocketFactory,
        val fakeHandle: FakeRecognizerHandle,
        val selector: BackendSelector,
        val states: MutableList<EngineState>,
        val errors: MutableList<SttError>,
    )

    private fun buildSelector(
        fakeWs: FakeWebSocketFactory,
        fakeHandle: FakeRecognizerHandle,
        dispatcher: CoroutineDispatcher,
        windowMs: Long = WINDOW_MS,
    ): BackendSelector = BackendSelector(
        appContext = context(),
        config = SttConfig(serverUrl = SERVER_URL),
        handleFactory = RecognizerHandleFactory {
            PreflightHandle(fakeHandle, LanguageAvailability.INSTALLED)
        },
        permissions = PermissionChecker { true },
        webSocketFactory = fakeWs,
        mainDispatcher = dispatcher,
        tryServerTimeoutMs = windowMs,
        serverBackendFactory = { url ->
            ServerBackend(
                webSocketFactory = fakeWs,
                serverUrl = url,
                language = "en",
                mainDispatcher = dispatcher,
                pcmSourceFactory = finitePcmSourceFactory(frames = 0),
            )
        },
    )

    /** Builds the selector and starts collecting State events and errors BEFORE any `start()`. */
    private fun TestScope.harness(): Harness {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val fakeWs = FakeWebSocketFactory()
        val fakeHandle = FakeRecognizerHandle()
        val selector = buildSelector(fakeWs, fakeHandle, dispatcher)
        val states = mutableListOf<EngineState>()
        val errors = mutableListOf<SttError>()
        backgroundScope.launch(dispatcher) {
            selector.events.collect { if (it is SttEvent.State) states += it.state }
        }
        backgroundScope.launch(dispatcher) { selector.errors.collect { errors += it } }
        return Harness(fakeWs, fakeHandle, selector, states, errors)
    }

    /** Commits a server session ([Backend.Server], Listening), then drops it. Returns t0, the
     *  virtual time right after the drop. */
    private fun TestScope.commitServerThenDrop(h: Harness): Long {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val started = launch(dispatcher) { h.selector.start() }
        h.fakeWs.fireOpen()
        h.fakeWs.fireMessage("""{"type":"ready"}""")
        runCurrent()
        assertTrue("start() must return once the server is committed", started.isCompleted)
        assertEquals(Backend.Server, h.selector.backend.value)
        assertEquals(EngineState.Listening, h.selector.state.value)
        assertEquals(listOf(EngineState.Connecting, EngineState.Listening), h.states.toList())

        h.fakeWs.fireFailure(IOException("connection reset"))
        runCurrent()
        assertEquals("the drop must start native", 1, h.fakeHandle.startCount)
        return testScheduler.currentTime
    }

    // --- Test A: the handoff is invisible ----------------------------------------------------------

    @Test
    fun `A - a handoff where native reaches Listening shows the host Connecting then Listening only`() =
        runTest {
            val h = harness()
            commitServerThenDrop(h)

            assertEquals(
                "native's own Connecting must be held during the handoff",
                listOf(EngineState.Connecting, EngineState.Listening),
                h.states.toList(),
            )
            assertEquals(EngineState.Listening, h.selector.state.value)
            assertEquals("backend stays Server until native reaches Listening", Backend.Server, h.selector.backend.value)

            h.fakeHandle.reachListening()
            runCurrent()

            assertEquals(listOf(EngineState.Connecting, EngineState.Listening), h.states.toList())
            assertEquals(EngineState.Listening, h.selector.state.value)
            assertEquals(Backend.Native, h.selector.backend.value)
            assertTrue(h.errors.isEmpty())
        }

    // --- Test A': the same on the real Dispatchers.Main.immediate ----------------------------------

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Fires a socket callback from a background thread, the way OkHttp does, then drains main. */
    private fun offMain(fire: () -> Unit) {
        thread { fire() }.join()
        idle()
    }

    @Test
    fun `A' - the handoff is invisible on the real Dispatchers Main immediate too`() {
        val fakeWs = FakeWebSocketFactory()
        val fakeHandle = FakeRecognizerHandle()
        val selector = buildSelector(fakeWs, fakeHandle, Dispatchers.Main.immediate, windowMs = 5_000L)
        val states = mutableListOf<EngineState>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            scope.launch { selector.events.collect { if (it is SttEvent.State) states += it.state } }
            scope.launch { selector.start() }
            idle()
            offMain { fakeWs.fireOpen() }
            offMain { fakeWs.fireMessage("""{"type":"ready"}""") }
            assertEquals(Backend.Server, selector.backend.value)

            offMain { fakeWs.fireFailure(IOException("connection reset")) }
            assertEquals(1, fakeHandle.startCount)
            assertEquals(listOf(EngineState.Connecting, EngineState.Listening), states.toList())

            fakeHandle.reachListening()
            idle()

            assertEquals(listOf(EngineState.Connecting, EngineState.Listening), states.toList())
            assertEquals(Backend.Native, selector.backend.value)
            assertEquals(EngineState.Listening, selector.state.value)
        } finally {
            scope.launch { selector.stop() }
            idle()
            scope.cancel()
        }
    }

    // --- Test B: ruling (a), a native Error passes through at once ---------------------------------

    @Test
    fun `B - a native Error during the hold is forwarded immediately and reaches errors exactly once`() =
        runTest {
            val h = harness()
            val t0 = commitServerThenDrop(h)

            h.fakeHandle.fireError(ERROR_INSUFFICIENT_PERMISSIONS)
            runCurrent()

            assertEquals(
                listOf(EngineState.Connecting, EngineState.Listening, EngineState.Error),
                h.states.toList(),
            )
            assertEquals("the Error must not wait for the hold window", t0, testScheduler.currentTime)
            assertEquals(1, h.errors.size)
            assertEquals(SttErrorCode.MIC_DENIED, h.errors.single().code)

            advanceTimeBy(WINDOW_MS * 2)
            runCurrent()
            assertEquals(
                "nothing may land after the Error",
                listOf(EngineState.Connecting, EngineState.Listening, EngineState.Error),
                h.states.toList(),
            )
            assertEquals(1, h.errors.size)
        }

    // --- Test C: ruling (b), a stuck native surfaces exactly at tryServerTimeoutMs -----------------

    @Test
    fun `C - a native still Connecting is surfaced exactly at tryServerTimeoutMs and not before`() =
        runTest {
            val h = harness()
            val t0 = commitServerThenDrop(h)

            advanceTimeBy(WINDOW_MS - 1)
            runCurrent()
            assertEquals(t0 + WINDOW_MS - 1, testScheduler.currentTime)
            assertEquals(EngineState.Listening, h.selector.state.value)
            assertEquals(listOf(EngineState.Connecting, EngineState.Listening), h.states.toList())

            advanceTimeBy(1)
            runCurrent()
            assertEquals(t0 + WINDOW_MS, testScheduler.currentTime)
            assertEquals(EngineState.Connecting, h.selector.state.value)
            assertEquals(
                listOf(EngineState.Connecting, EngineState.Listening, EngineState.Connecting),
                h.states.toList(),
            )
            assertTrue("expiry introduces no error", h.errors.isEmpty())

            h.fakeHandle.reachListening()
            runCurrent()
            assertEquals(
                listOf(
                    EngineState.Connecting,
                    EngineState.Listening,
                    EngineState.Connecting,
                    EngineState.Listening,
                ),
                h.states.toList(),
            )
            assertEquals(Backend.Native, h.selector.backend.value)
        }

    // --- Test D: once the hold ends, later native states forward -----------------------------------

    @Test
    fun `D - after native reaches Listening a later Reconnecting reaches the host`() = runTest {
        val h = harness()
        commitServerThenDrop(h)
        h.fakeHandle.reachListening()
        runCurrent()

        h.fakeHandle.fireError(ERROR_SERVER_DISCONNECTED)
        runCurrent()

        assertEquals(
            listOf(EngineState.Connecting, EngineState.Listening, EngineState.Reconnecting),
            h.states.toList(),
        )
        assertEquals(EngineState.Reconnecting, h.selector.state.value)
    }

    // --- Test E: ruling (c), the Auto-fallback path is unchanged -----------------------------------

    @Test
    fun `E - the Auto fallback after a fast-fail still emits Connecting then Listening`() = runTest {
        val h = harness()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val started = launch(dispatcher) { h.selector.start() }
        h.fakeWs.fireOpen()
        h.fakeWs.fireClosing(1013, "busy")
        runCurrent()
        assertEquals(1, h.fakeHandle.startCount)

        h.fakeHandle.reachListening()
        runCurrent()
        started.join()

        assertEquals(listOf(EngineState.Connecting, EngineState.Listening), h.states.toList())
        assertEquals(Backend.Native, h.selector.backend.value)
    }

    @Test
    fun `E2 - a stuck native after the Auto fallback has nothing held and nothing expiring`() = runTest {
        val h = harness()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val started = launch(dispatcher) { h.selector.start() }
        h.fakeWs.fireOpen()
        h.fakeWs.fireClosing(1013, "busy")
        runCurrent()
        assertEquals(1, h.fakeHandle.startCount)
        assertEquals(listOf(EngineState.Connecting), h.states.toList())

        advanceTimeBy(WINDOW_MS * 2)
        runCurrent()
        assertEquals("no hold, no expiry on the fallback path", listOf(EngineState.Connecting), h.states.toList())

        h.fakeHandle.reachListening()
        runCurrent()
        started.join()
        assertEquals(listOf(EngineState.Connecting, EngineState.Listening), h.states.toList())
        assertEquals(Backend.Native, h.selector.backend.value)
    }

    // --- Test F: stop() during the hold -------------------------------------------------------------

    @Test
    fun `F - stop during the hold ends at Idle and the hold never writes into the next session`() =
        runTest {
            val h = harness()
            val t0 = commitServerThenDrop(h)
            advanceTimeBy(WINDOW_MS / 2)
            runCurrent()

            h.selector.stop()
            runCurrent()

            assertEquals(EngineState.Idle, h.selector.state.value)
            assertEquals(
                "after the server's Listening the host sees Idle only",
                listOf(EngineState.Idle),
                h.states.drop(2),
            )

            // A second session on the same engine, black-holed (its socket never opens), started
            // inside what would have been the first session's hold window.
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val firstSessionCount = h.states.size
            val started2 = launch(dispatcher) { h.selector.start() }
            runCurrent()
            assertEquals(2, h.fakeWs.createCount)
            assertEquals(EngineState.Connecting, h.selector.state.value)
            assertEquals(listOf(EngineState.Connecting), h.states.drop(firstSessionCount))

            // Past the first session's old hold deadline, still inside the second session's window.
            advanceTimeBy(t0 + WINDOW_MS + 1 - testScheduler.currentTime)
            runCurrent()
            assertTrue(testScheduler.currentTime < t0 + WINDOW_MS / 2 + WINDOW_MS)
            assertEquals(EngineState.Connecting, h.selector.state.value)
            assertEquals(
                "the first session's hold must not write into the second session",
                listOf(EngineState.Connecting),
                h.states.drop(firstSessionCount),
            )

            h.selector.stop()
            started2.join()
        }

    private companion object {
        const val SERVER_URL = "wss://example.ts.net:8444/"
        const val WINDOW_MS = 2_000L

        /** `SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS` — fatal (mic-denied). */
        const val ERROR_INSUFFICIENT_PERMISSIONS = 9

        /** `SpeechRecognizer.ERROR_SERVER_DISCONNECTED` — rebind and restart via Reconnecting. */
        const val ERROR_SERVER_DISCONNECTED = 11
    }
}
