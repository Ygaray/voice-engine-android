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
import io.github.ygaray.sttengine.SttErrorCode
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TRY-02's selector policy in virtual time (D-06, D-14): a fast-fail falls back (Auto) or surfaces
 * one error (Server) at the moment of the failure; a retryable failure is retried with backoff
 * 250 → 500 → 1 000 ms under ONE deadline equal to the window; a black-holed host waits the whole
 * window; `stop()` during a backoff or an in-flight retry ends everything; and every attempt leaves
 * one privacy-safe `server-attempt` trace line.
 *
 * Harness copied from [BackendSelectorTest]: [FakeWebSocketFactory] for the server path,
 * [FakeRecognizerHandle] for the native fallback, `UnconfinedTestDispatcher(testScheduler)` as
 * main, `finitePcmSourceFactory(frames = 0)` so a `ready` never spins a pump (Pitfall 1). Each
 * attempt is a fresh socket, targeted as `fakeWs.sockets[i]`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class BackendSelectorTryWindowTest {

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

        /** The `server-attempt` lines only — the native fallback writes its own kinds to the same
         *  log once it starts. */
        fun attemptLines(): List<JSONObject> =
            (log.file?.readLines() ?: emptyList())
                .filter { it.isNotBlank() }
                .map { JSONObject(it) }
                .filter { it.getString("kind") == SessionLog.KIND_SERVER_ATTEMPT }

        fun rawLog(): String = log.file?.readText() ?: ""
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    /** Builds the selector and launches `start()`; returns with attempt 1's socket in flight. */
    private fun TestScope.startSelector(
        prefer: BackendPreference = BackendPreference.Auto,
        windowMs: Long = WINDOW_MS,
        log: SessionLog = SessionLog(enabled = true, directory = folder.root, sessionStartEpochMs = 1L),
    ): Harness {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val fakeWs = FakeWebSocketFactory()
        val fakeHandle = FakeRecognizerHandle()
        val selector = BackendSelector(
            appContext = context(),
            config = SttConfig(serverUrl = SERVER_URL, prefer = prefer),
            handleFactory = RecognizerHandleFactory {
                PreflightHandle(fakeHandle, LanguageAvailability.INSTALLED)
            },
            permissions = PermissionChecker { true },
            log = log,
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
        val errors = mutableListOf<SttError>()
        backgroundScope.launch(dispatcher) { selector.errors.collect { errors += it } }
        val started = launch(dispatcher) { selector.start() }
        assertEquals("attempt 1 must be in flight", 1, fakeWs.createCount)
        return Harness(dispatcher, fakeWs, fakeHandle, selector, errors, log, started)
    }

    /** Fails every socket created since the last call with [failure()] — one per attempt. */
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

    private fun tlsUntrusted(): SSLHandshakeException =
        SSLHandshakeException("PKIX path building failed").apply { initCause(CertificateException("untrusted")) }

    private fun response(code: Int): Response =
        Response.Builder()
            .request(Request.Builder().url(SERVER_URL).build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Service Unavailable")
            .build()

    /** Asserts the native fallback began at the moment of a fast-fail, then completes start(). */
    private fun TestScope.assertFastFailToNative(h: Harness) {
        runCurrent()
        assertTrue("fast-fail must fall back well inside the window", testScheduler.currentTime < 100)
        assertEquals("one socket only — a fast-fail is never retried", 1, h.fakeWs.createCount)
        assertEquals(1, h.fakeHandle.startCount)
        h.fakeHandle.reachListening()
        assertTrue(h.started.isCompleted)
        assertEquals(Backend.Native, h.selector.backend.value)
        assertEquals(EngineState.Listening, h.selector.state.value)
        assertTrue("Auto swallows the server failure", h.errors.isEmpty())
    }

    /** Asserts native starts at exactly [windowMs] — never earlier. */
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

    // --- fast-fail (D-06) ---------------------------------------------------------------------------

    @Test
    fun `a refused connect fast-fails to native at once`() = runTest {
        val h = startSelector()
        h.fakeWs.fireFailure(ConnectException("Failed to connect to x"), socket = h.socket(0))
        assertFastFailToNative(h)
    }

    @Test
    fun `an unknown host fast-fails to native at once`() = runTest {
        val h = startSelector()
        h.fakeWs.fireFailure(UnknownHostException("x"), socket = h.socket(0))
        assertFastFailToNative(h)
    }

    @Test
    fun `a busy close 1013 fast-fails to native at once`() = runTest {
        val h = startSelector()
        h.fakeWs.fireOpen(h.socket(0))
        h.fakeWs.fireClosing(1013, "busy", socket = h.socket(0))
        assertFastFailToNative(h)
    }

    @Test
    fun `a TLS certificate failure fast-fails to native at once`() = runTest {
        val h = startSelector()
        h.fakeWs.fireFailure(tlsUntrusted(), socket = h.socket(0))
        assertFastFailToNative(h)
    }

    // --- retry inside the window (D-06) -------------------------------------------------------------

    @Test
    fun `an EOF is retried after 250 ms and a ready on the retry commits to the server`() = runTest {
        val h = startSelector()
        h.fakeWs.fireFailure(eof(), socket = h.socket(0))

        advanceTimeBy(249)
        runCurrent()
        assertEquals("no retry before the first backoff elapses", 1, h.fakeWs.createCount)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("the retry opens a fresh socket at 250 ms", 2, h.fakeWs.createCount)

        h.fakeWs.fireOpen(h.socket(1))
        h.fakeWs.fireMessage("""{"type":"ready"}""", socket = h.socket(1))
        runCurrent()

        assertTrue(h.started.isCompleted)
        assertEquals(Backend.Server, h.selector.backend.value)
        assertEquals(EngineState.Listening, h.selector.state.value)
        assertEquals(2, h.fakeWs.createCount)
        assertEquals("native never starts once the retry is accepted", 0, h.fakeHandle.startCount)
        assertTrue("the failed attempt's socket was abandoned", h.socket(0).cancelCount >= 1)
        assertTrue(h.errors.isEmpty())
    }

    @Test
    fun `a 503 upgrade answer is retried and a ready on the retry commits to the server`() = runTest {
        val h = startSelector()
        h.fakeWs.fireFailure(
            ProtocolException("Expected HTTP 101 response but was '503 Service Unavailable'"),
            response = response(503),
            socket = h.socket(0),
        )
        advanceTimeBy(250)
        runCurrent()
        assertEquals(2, h.fakeWs.createCount)

        h.fakeWs.fireOpen(h.socket(1))
        h.fakeWs.fireMessage("""{"type":"ready"}""", socket = h.socket(1))
        runCurrent()

        assertEquals(Backend.Server, h.selector.backend.value)
        assertEquals(0, h.fakeHandle.startCount)
    }

    @Test
    fun `retryable failures until expiry fall back to native after the window`() = runTest {
        val window = 2_500L
        val h = startSelector(windowMs = window)
        failAttemptsUntil(FailEveryAttempt(h, ::eof), until = window - 1)
        assertNativeExactlyAtWindow(h, window)
        // 0, 250, 750 (+500), 1 750 (+1 000): four attempts inside 2 500 ms.
        assertTrue("expected >= 3 attempts, got ${h.fakeWs.createCount}", h.fakeWs.createCount >= 3)
    }

    // --- the black hole and the timeout decision (D-14) ---------------------------------------------

    @Test
    fun `a black-holed host waits the full window before falling back`() = runTest {
        val h = startSelector(windowMs = WINDOW_MS)
        assertNativeExactlyAtWindow(h, WINDOW_MS)
        assertEquals("a host that never answers gets one attempt", 1, h.fakeWs.createCount)
        assertTrue(h.socket(0).cancelCount >= 1)
        val lines = h.attemptLines()
        assertEquals(listOf("WindowExpired"), lines.map { it.getString("class") })
    }

    @Test
    fun `a socket timeout on every attempt never shortens the window`() = runTest {
        val h = startSelector(windowMs = WINDOW_MS)
        failAttemptsUntil(FailEveryAttempt(h) { SocketTimeoutException("connect timed out") }, WINDOW_MS - 1)
        assertNativeExactlyAtWindow(h, WINDOW_MS)
        assertTrue("timeouts are retried, got ${h.fakeWs.createCount}", h.fakeWs.createCount >= 2)
        val classes = h.attemptLines().map { it.getString("class") }
        assertEquals("WindowExpired", classes.last())
        assertTrue(classes.dropLast(1).all { it == "Retryable" })
        assertTrue(h.attemptLines().dropLast(1).all { it.getString("exception") == "SocketTimeoutException" })
    }

    // --- prefer = Server (transparency prohibition) -------------------------------------------------

    @Test
    fun `prefer=Server with a fast-fail surfaces exactly one error and never starts native`() = runTest {
        val h = startSelector(prefer = BackendPreference.Server)
        h.fakeWs.fireFailure(ConnectException("Failed to connect to x"), socket = h.socket(0))
        runCurrent()

        assertTrue(h.started.isCompleted)
        assertEquals(1, h.errors.size)
        assertEquals(SttErrorCode.SERVER_UNREACHABLE, h.errors.single().code)
        assertEquals(EngineState.Error, h.selector.state.value)
        assertEquals(1, h.fakeWs.createCount)
        assertEquals(0, h.fakeHandle.startCount)
    }

    @Test
    fun `prefer=Server with a TLS failure surfaces exactly one error and never starts native`() = runTest {
        val h = startSelector(prefer = BackendPreference.Server)
        h.fakeWs.fireFailure(tlsUntrusted(), socket = h.socket(0))
        runCurrent()

        assertTrue("TLS failure must not be retried", testScheduler.currentTime < 100)
        assertEquals(1, h.errors.size)
        assertEquals(1, h.fakeWs.createCount)
        assertEquals(0, h.fakeHandle.startCount)
    }

    @Test
    fun `prefer=Server with retryable failures until expiry surfaces exactly one error`() = runTest {
        val window = 2_500L
        val h = startSelector(prefer = BackendPreference.Server, windowMs = window)
        failAttemptsUntil(FailEveryAttempt(h, ::eof), until = window + 1_000L)
        advanceUntilIdle()

        assertTrue(h.started.isCompleted)
        assertEquals(1, h.errors.size)
        assertEquals(SttErrorCode.SERVER_UNREACHABLE, h.errors.single().code)
        assertEquals(EngineState.Error, h.selector.state.value)
        assertEquals(0, h.fakeHandle.startCount)
        assertTrue(h.fakeWs.createCount >= 3)
    }

    // --- stop() during the try window (concurrency edge) --------------------------------------------

    @Test
    fun `stop during a backoff creates no further attempt and never starts native`() = runTest {
        val h = startSelector()
        h.fakeWs.fireFailure(eof(), socket = h.socket(0))
        advanceTimeBy(100)
        runCurrent()

        val stopped = launch(h.dispatcher) { h.selector.stop() }
        stopped.join()
        h.started.join()
        advanceUntilIdle()

        assertEquals("no attempt may start after stop()", 1, h.fakeWs.createCount)
        assertEquals(0, h.fakeHandle.startCount)
        assertTrue(h.errors.isEmpty())
    }

    @Test
    fun `stop during an in-flight retry cancels that socket and never starts native`() = runTest {
        val h = startSelector()
        h.fakeWs.fireFailure(eof(), socket = h.socket(0))
        advanceTimeBy(250)
        runCurrent()
        assertEquals(2, h.fakeWs.createCount)
        assertEquals(0, h.socket(1).cancelCount)

        val stopped = launch(h.dispatcher) { h.selector.stop() }
        stopped.join()

        assertTrue("the in-flight retry's socket must be cancelled", h.socket(1).cancelCount >= 1)
        h.started.join()
        advanceUntilIdle()
        assertEquals(2, h.fakeWs.createCount)
        assertEquals(0, h.fakeHandle.startCount)
    }

    // --- start() re-entry (21-W46 review): mirrors SttEngineImpl.start()'s `if (running) return` ----
    //
    // Each re-entrant start() is launched and asserted complete at once, never joined: without the
    // guard it would suspend in a second attempt loop, and joining it lets a second native engine
    // share the one FakeRecognizerHandle with the first and restart against it under virtual time.
    // The session log is disabled for the same reason — a failing run must stop at the assertion,
    // not write without bound to the TemporaryFolder.

    private fun TestScope.startQuietSelector(prefer: BackendPreference = BackendPreference.Auto): Harness =
        startSelector(prefer = prefer, log = SessionLog())

    @Test
    fun `a second start during the try window is a no-op and stop still ends everything`() = runTest {
        val h = startQuietSelector()
        h.fakeWs.fireFailure(eof(), socket = h.socket(0))
        advanceTimeBy(100)
        runCurrent()

        val second = launch(h.dispatcher) { h.selector.start() }
        assertTrue("a re-entrant start() returns at once", second.isCompleted)
        assertEquals("a re-entrant start() must not open a second socket", 1, h.fakeWs.createCount)

        advanceTimeBy(150)
        runCurrent()
        assertEquals("only the one loop retries at 250 ms", 2, h.fakeWs.createCount)

        launch(h.dispatcher) { h.selector.stop() }.join()
        assertTrue("stop() cancels the in-flight retry", h.socket(1).cancelCount >= 1)
        h.started.join()
        advanceUntilIdle()
        assertEquals("no loop may retry after stop()", 2, h.fakeWs.createCount)
        assertEquals("no loop may start native after stop()", 0, h.fakeHandle.startCount)
        assertTrue(h.errors.isEmpty())
    }

    @Test
    fun `a second start on a committed server session is a no-op`() = runTest {
        val h = startQuietSelector()
        h.fakeWs.fireOpen(h.socket(0))
        h.fakeWs.fireMessage("""{"type":"ready"}""", socket = h.socket(0))
        runCurrent()
        assertTrue(h.started.isCompleted)

        val second = launch(h.dispatcher) { h.selector.start() }
        assertTrue("a re-entrant start() returns at once", second.isCompleted)
        assertEquals("no second socket while the server session is live", 1, h.fakeWs.createCount)
        assertEquals(0, h.socket(0).cancelCount)
        assertEquals(Backend.Server, h.selector.backend.value)
        assertEquals(EngineState.Listening, h.selector.state.value)
        assertEquals(0, h.fakeHandle.startCount)

        launch(h.dispatcher) { h.selector.stop() }.join()
    }

    @Test
    fun `a second start on a live native fallback is a no-op`() = runTest {
        val h = startQuietSelector()
        h.fakeWs.fireFailure(ConnectException("Failed to connect to x"), socket = h.socket(0))
        assertFastFailToNative(h)

        val second = launch(h.dispatcher) { h.selector.start() }
        assertTrue("a re-entrant start() returns at once", second.isCompleted)
        assertEquals("no new server attempt while native is live", 1, h.fakeWs.createCount)
        assertEquals("never a second native recognizer", 1, h.fakeHandle.startCount)
        assertEquals(EngineState.Listening, h.selector.state.value)

        launch(h.dispatcher) { h.selector.stop() }.join()
    }

    @Test
    fun `start after a prefer=Server terminal error begins a fresh session`() = runTest {
        val h = startQuietSelector(prefer = BackendPreference.Server)
        h.fakeWs.fireFailure(ConnectException("Failed to connect to x"), socket = h.socket(0))
        runCurrent()
        assertEquals(EngineState.Error, h.selector.state.value)

        val again = launch(h.dispatcher) { h.selector.start() }
        assertEquals("a terminal error ends the session, so start() runs again", 2, h.fakeWs.createCount)
        h.fakeWs.fireOpen(h.socket(1))
        h.fakeWs.fireMessage("""{"type":"ready"}""", socket = h.socket(1))
        runCurrent()
        assertTrue(again.isCompleted)
        assertEquals(Backend.Server, h.selector.backend.value)
        assertEquals(EngineState.Listening, h.selector.state.value)
    }

    // --- the server-attempt trace line (D-09 capture input, T-21-15) ---------------------------------

    @Test
    fun `each attempt writes one server-attempt line and none carries a message or the url`() = runTest {
        val h = startSelector()
        h.fakeWs.fireFailure(eof(), socket = h.socket(0))
        advanceTimeBy(250)
        runCurrent()
        h.fakeWs.fireOpen(h.socket(1))
        h.fakeWs.fireMessage("""{"type":"ready"}""", socket = h.socket(1))
        runCurrent()

        val lines = h.attemptLines()
        assertEquals(2, lines.size)
        lines.forEach {
            assertEquals(
                setOf("t", "kind", "attempt", "class", "exception", "sawOpen", "closeCode", "responseCode"),
                it.keys().asSequence().toSet(),
            )
        }
        val (first, second) = lines
        assertEquals(1, first.getInt("attempt"))
        assertEquals("Retryable", first.getString("class"))
        assertEquals("IOException", first.getString("exception"))
        assertFalse(first.getBoolean("sawOpen"))
        assertTrue(first.isNull("closeCode"))
        assertTrue(first.isNull("responseCode"))
        assertEquals(2, second.getInt("attempt"))
        assertEquals("Connected", second.getString("class"))
        assertTrue(second.isNull("exception"))

        val raw = h.rawLog()
        assertFalse("the server URL must never be logged", raw.contains("example.ts.net"))
        assertFalse("an exception message must never be logged", raw.contains("unexpected end of stream"))
    }

    @Test
    fun `a busy fast-fail line records the close code and the open`() = runTest {
        val h = startSelector()
        h.fakeWs.fireOpen(h.socket(0))
        h.fakeWs.fireClosing(1013, "busy", socket = h.socket(0))
        runCurrent()

        val line = h.attemptLines().single()
        assertEquals("FastFail", line.getString("class"))
        assertEquals(1013, line.getInt("closeCode"))
        assertTrue(line.getBoolean("sawOpen"))
        assertTrue(line.isNull("exception"))
        // Let the native fallback's start() complete.
        h.fakeHandle.reachListening()
        h.started.join()
    }

    @Test
    fun `a 503 retry line records the response code`() = runTest {
        val h = startSelector()
        h.fakeWs.fireFailure(
            ProtocolException("Expected HTTP 101 response but was '503 Service Unavailable'"),
            response = response(503),
            socket = h.socket(0),
        )
        runCurrent()

        val line = h.attemptLines().single()
        assertEquals("Retryable", line.getString("class"))
        assertEquals("ProtocolException", line.getString("exception"))
        assertEquals(503, line.getInt("responseCode"))
        // The loop is now in its backoff — end the session so start() completes.
        launch(h.dispatcher) { h.selector.stop() }.join()
        h.started.join()
    }

    @Test
    fun `a disabled log writes nothing during a retrying session`() = runTest {
        val h = startSelector(log = SessionLog(directory = folder.root, sessionStartEpochMs = 1L))
        h.fakeWs.fireFailure(eof(), socket = h.socket(0))
        advanceTimeBy(250)
        runCurrent()
        h.fakeWs.fireFailure(ConnectException("x"), socket = h.socket(1))
        h.fakeHandle.reachListening()

        assertEquals(2, h.fakeWs.createCount)
        assertEquals(emptyList<String>(), folder.root.list()!!.toList())
    }

    private companion object {
        const val SERVER_URL = "wss://example.ts.net:8444/"
        const val WINDOW_MS = 5_000L
        const val STEP_MS = 50L
    }
}
