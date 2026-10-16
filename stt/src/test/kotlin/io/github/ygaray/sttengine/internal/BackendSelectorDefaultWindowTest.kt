package io.github.ygaray.sttengine.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.BackendPreference
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.LanguageAvailability
import io.github.ygaray.sttengine.PreflightHandle
import io.github.ygaray.sttengine.SttConfig
import io.github.ygaray.sttengine.SttError
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
 * D-12: the DEFAULT try-server window (10 000 ms) as production sees it, in virtual time.
 *
 * Every row builds the selector from an [SttConfig] and lets the selector derive its window from
 * `config.serverTryTimeoutMs`; no row hands the selector a window of its own. The explicit-window
 * rows live in [BackendSelectorTryWindowTest] and are untouched.
 *
 * The rows pin the OUTCOME, not a timer ordering. The coroutine window is intended to be the overall
 * bound, but the WebSocket client uses OkHttp's default 10 s timers, equal to the new default, so
 * near the boundary either timer may fire first. Whichever does, the selector must end in exactly one
 * fallback to the on-device recognizer, no error, and `WindowExpired` as the last `server-attempt`
 * trace line; a late failure from an already-abandoned attempt must change nothing.
 *
 * Harness copied from [BackendSelectorTryWindowTest] with the one change that the window comes from
 * the config.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class BackendSelectorDefaultWindowTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private class Harness(
        val dispatcher: CoroutineDispatcher,
        val fakeWs: FakeWebSocketFactory,
        val fakeHandle: FakeRecognizerHandle,
        val selector: BackendSelector,
        val errors: MutableList<SttError>,
        val log: SessionLog,
        val started: Job,
    ) {
        fun socket(i: Int): FakeWebSocket = fakeWs.sockets[i]

        /** The `server-attempt` lines only; the native fallback writes its own kinds to the same log. */
        fun attemptLines(): List<JSONObject> =
            (log.file?.readLines() ?: emptyList())
                .filter { it.isNotBlank() }
                .map { JSONObject(it) }
                .filter { it.getString("kind") == SessionLog.KIND_SERVER_ATTEMPT }
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    /**
     * Builds the selector from [config] and launches `start()`; returns with attempt 1's socket in
     * flight. The selector's window is whatever [config] carries: the production default unless the
     * config sets it.
     */
    private fun TestScope.startSelector(
        config: SttConfig = SttConfig(serverUrl = SERVER_URL, prefer = BackendPreference.Auto),
    ): Harness {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val fakeWs = FakeWebSocketFactory()
        val fakeHandle = FakeRecognizerHandle()
        val log = SessionLog(enabled = true, directory = folder.root, sessionStartEpochMs = 1L)
        val selector = BackendSelector(
            appContext = context(),
            config = config,
            handleFactory = RecognizerHandleFactory {
                PreflightHandle(fakeHandle, LanguageAvailability.INSTALLED)
            },
            permissions = PermissionChecker { true },
            log = log,
            webSocketFactory = fakeWs,
            mainDispatcher = dispatcher,
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
        val errors = mutableListOf<SttError>()
        backgroundScope.launch(dispatcher) { selector.errors.collect { errors += it } }
        val started = launch(dispatcher) { selector.start() }
        assertEquals("attempt 1 must be in flight", 1, fakeWs.createCount)
        return Harness(dispatcher, fakeWs, fakeHandle, selector, errors, log, started)
    }

    /** Fails every socket created since the last call with the retryable EOF, one per attempt. */
    private class FailEveryAttempt(private val h: Harness, private val failure: () -> Throwable) {
        private var fired = 0
        fun fireNew() {
            while (fired < h.fakeWs.createCount) {
                h.fakeWs.fireFailure(failure(), socket = h.socket(fired))
                fired++
            }
        }
    }

    /** Steps virtual time to [until] in 50 ms slices, failing each new attempt as it appears. */
    private fun TestScope.failAttemptsUntil(failer: FailEveryAttempt, until: Long) {
        failer.fireNew()
        while (testScheduler.currentTime < until) {
            advanceTimeBy(minOf(STEP_MS, until - testScheduler.currentTime))
            runCurrent()
            failer.fireNew()
        }
    }

    private fun eof(): IOException = IOException("unexpected end of stream on $SERVER_URL", EOFException())

    /** Asserts native has not started one millisecond before [windowMs], then starts exactly at it. */
    private fun TestScope.assertNativeExactlyAtWindow(h: Harness, windowMs: Long) {
        if (testScheduler.currentTime < windowMs - 1) {
            advanceTimeBy(windowMs - 1 - testScheduler.currentTime)
        }
        runCurrent()
        assertEquals("native must not start before the window", 0, h.fakeHandle.startCount)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(windowMs, testScheduler.currentTime)
        assertEquals("native must start when the window expires", 1, h.fakeHandle.startCount)
        h.fakeHandle.reachListening()
        assertEquals(Backend.Native, h.selector.backend.value)
        assertTrue(h.errors.isEmpty())
    }

    @Test
    fun `black hole at the default window falls back to native at exactly 10 seconds`() = runTest {
        val h = startSelector()
        assertNativeExactlyAtWindow(h, DEFAULT_WINDOW_MS)
        assertEquals("a host that never answers gets one attempt", 1, h.fakeWs.createCount)
        assertTrue(h.socket(0).cancelCount >= 1)
        assertEquals(listOf("WindowExpired"), h.attemptLines().map { it.getString("class") })
    }

    @Test
    fun `cold start accepted inside the default window when the server opens after 8 seconds`() = runTest {
        val h = startSelector()
        failAttemptsUntil(FailEveryAttempt(h, ::eof), until = COLD_START_MS)
        assertTrue("an 8 s cold start must still be inside the window", testScheduler.currentTime < DEFAULT_WINDOW_MS)
        assertEquals("no fallback while the server is still starting", 0, h.fakeHandle.startCount)

        // The next attempt that appears is the one the server answers.
        val seen = h.fakeWs.createCount
        while (h.fakeWs.createCount == seen && testScheduler.currentTime < DEFAULT_WINDOW_MS - 1) {
            advanceTimeBy(STEP_MS)
            runCurrent()
        }
        assertEquals("an attempt must appear before the window ends", seen + 1, h.fakeWs.createCount)
        val answered = h.socket(seen)
        h.fakeWs.fireOpen(answered)
        h.fakeWs.fireMessage("""{"type":"ready"}""", socket = answered)
        runCurrent()

        assertTrue(h.started.isCompleted)
        assertEquals(Backend.Server, h.selector.backend.value)
        assertEquals(EngineState.Listening, h.selector.state.value)
        assertEquals("native never starts once the server answers", 0, h.fakeHandle.startCount)
        assertTrue(h.errors.isEmpty())
        assertTrue(
            "accepted before the window ends, at ${testScheduler.currentTime} ms",
            testScheduler.currentTime < DEFAULT_WINDOW_MS,
        )
    }

    @Test
    fun `control with the previous 5 second window the same cold start falls back and never reaches the server`() =
        runTest {
            val h = startSelector(
                SttConfig(serverUrl = SERVER_URL, prefer = BackendPreference.Auto, serverTryTimeoutMs = 5_000L),
            )
            failAttemptsUntil(FailEveryAttempt(h, ::eof), until = 5_000L - 1)
            assertNativeExactlyAtWindow(h, 5_000L)
            assertTrue("the server was never answered", h.attemptLines().none { it.getString("class") == "Connected" })
            assertEquals("WindowExpired", h.attemptLines().last().getString("class"))
        }

    @Test
    fun `boundary failure first a socket timeout at 9999 and the window at 10000 give one fallback`() = runTest {
        val h = startSelector()
        advanceTimeBy(DEFAULT_WINDOW_MS - 1)
        runCurrent()
        assertEquals(0, h.fakeHandle.startCount)

        // The in-flight socket times out one millisecond before the window: retryable, backoff starts.
        h.fakeWs.fireFailure(SocketTimeoutException("connect timed out"), socket = h.socket(0))
        runCurrent()
        assertEquals("no fallback yet: the failure is retried, not terminal", 0, h.fakeHandle.startCount)

        assertNativeExactlyAtWindow(h, DEFAULT_WINDOW_MS)
        assertEquals("the backoff outlasts the window, so no second socket", 1, h.fakeWs.createCount)
        val classes = h.attemptLines().map { it.getString("class") }
        assertEquals(listOf("Retryable", "WindowExpired"), classes)
    }

    @Test
    fun `boundary window first a late socket timeout on the abandoned attempt changes nothing`() = runTest {
        val h = startSelector()
        assertNativeExactlyAtWindow(h, DEFAULT_WINDOW_MS)
        val linesBefore = h.attemptLines().size
        assertEquals(listOf("WindowExpired"), h.attemptLines().map { it.getString("class") })

        // The abandoned socket's own timer fires afterwards.
        h.fakeWs.fireFailure(SocketTimeoutException("connect timed out"), socket = h.socket(0))
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals("native started exactly once", 1, h.fakeHandle.startCount)
        assertEquals(Backend.Native, h.selector.backend.value)
        assertTrue("no error is surfaced", h.errors.isEmpty())
        assertEquals("no new attempt line", linesBefore, h.attemptLines().size)
        assertEquals("no new socket", 1, h.fakeWs.createCount)
    }

    private companion object {
        const val SERVER_URL = "wss://example.ts.net:8444/"
        const val DEFAULT_WINDOW_MS = 10_000L
        const val COLD_START_MS = 8_000L
        const val STEP_MS = 50L
    }
}
