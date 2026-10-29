package io.github.ygaray.sttengine.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.BackendPreference
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.FallbackReason
import io.github.ygaray.sttengine.LanguageAvailability
import io.github.ygaray.sttengine.PreflightHandle
import io.github.ygaray.sttengine.SttConfig
import io.github.ygaray.sttengine.SttError
import io.github.ygaray.sttengine.SttErrorCode
import io.github.ygaray.sttengine.createSttEngine
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LIVE-04 (D-01..D-03): why a session fell back from the server to on-device recognition, as read
 * from [BackendSelector.fallbackReason].
 *
 * - Window-end reasons: the window ran out during a backoff → the last RESOLVED attempt decides
 *   (busy → BUSY, anything else → TIMEOUT); the window ran out with an attempt still in flight for
 *   less than [BackendSelector]'s 1 s inherit bound → it inherits the last resolved answer (Phase 28
 *   review WR-02: a server busy all window reads BUSY wherever the deadline lands); in flight for
 *   longer → THAT attempt decides, never an earlier busy (review 28-04).
 * - Fast-fail reasons: a host that cannot be reached → UNREACHABLE; a server that refuses the
 *   session, or the residual local failure after `ready` → REJECTED.
 * - A post-commit server failure that hands off to native → DROPPED, published together with the
 *   `backend` flip (so the handoff final itself reads `(Server, null)`).
 * - `null` on the server, on a config-chosen native engine, under `prefer = Server`, and after
 *   every `start()`.
 *
 * Harness copied from [BackendSelectorTryWindowTest]: [FakeWebSocketFactory] for the server path,
 * [FakeRecognizerHandle] for native, `UnconfinedTestDispatcher(testScheduler)` as main, virtual
 * time throughout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class BackendSelectorFallbackReasonTest {

    private class Harness(
        val dispatcher: CoroutineDispatcher,
        val fakeWs: FakeWebSocketFactory,
        val fakeHandle: FakeRecognizerHandle,
        val selector: BackendSelector,
        val errors: MutableList<SttError>,
        val started: Job,
    ) {
        fun socket(i: Int): FakeWebSocket = fakeWs.sockets[i]
        val reason: FallbackReason? get() = selector.fallbackReason.value
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    /** Builds the selector and launches `start()`. */
    private fun TestScope.startSelector(
        prefer: BackendPreference = BackendPreference.Auto,
        serverUrl: String? = SERVER_URL,
        windowMs: Long = WINDOW_MS,
        pcmSourceFactory: PcmSourceFactory = finitePcmSourceFactory(frames = 0),
    ): Harness {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val fakeWs = FakeWebSocketFactory()
        val fakeHandle = FakeRecognizerHandle()
        val selector = BackendSelector(
            appContext = context(),
            config = SttConfig(serverUrl = serverUrl, prefer = prefer),
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
                    pcmSourceFactory = pcmSourceFactory,
                )
            },
        )
        val errors = mutableListOf<SttError>()
        backgroundScope.launch(dispatcher) { selector.errors.collect { errors += it } }
        val started = launch(dispatcher) { selector.start() }
        return Harness(dispatcher, fakeWs, fakeHandle, selector, errors, started)
    }

    // --- scripted attempts ------------------------------------------------------------------------

    private fun busyFrame(h: Harness): (FakeWebSocket) -> Unit = { socket ->
        h.fakeWs.fireOpen(socket)
        h.fakeWs.fireMessage(BUSY_FRAME, socket = socket)
    }

    private fun eofFailure(h: Harness): (FakeWebSocket) -> Unit = { socket ->
        h.fakeWs.fireFailure(IOException("unexpected end of stream on $SERVER_URL", EOFException()), socket = socket)
    }

    /** An attempt that is never answered at all (a black hole). */
    private val blackHole: (FakeWebSocket) -> Unit = { }

    /**
     * Answers attempt `i` with `script[i]`, and every attempt past the end of the script with
     * [rest] — each exactly once, as it appears.
     */
    private class Scripted(
        private val h: Harness,
        private val script: List<(FakeWebSocket) -> Unit>,
        private val rest: (FakeWebSocket) -> Unit,
    ) {
        private var fired = 0
        fun fireNew() {
            while (fired < h.fakeWs.createCount) {
                (script.getOrNull(fired) ?: rest)(h.socket(fired))
                fired++
            }
        }
    }

    /**
     * Answers every attempt with a busy frame [latencyMs] after it appeared, like a real busy server
     * a few round trips away (WR-02). Polled on the 50 ms slices of [answerUntil], so every attempt
     * time and answer time in the rows that use it is a multiple of 50 ms.
     */
    private class SlowBusy(
        private val h: Harness,
        private val now: () -> Long,
        private val latencyMs: Long,
    ) {
        private val seenAt = mutableListOf<Long>()
        private var answered = 0
        fun fireNew() {
            while (seenAt.size < h.fakeWs.createCount) seenAt += now()
            while (answered < seenAt.size && now() >= seenAt[answered] + latencyMs) {
                val socket = h.socket(answered)
                h.fakeWs.fireOpen(socket)
                h.fakeWs.fireMessage(BUSY_FRAME, socket = socket)
                answered++
            }
        }
    }

    /** Steps virtual time to [until] in 50 ms slices, answering each new attempt as it appears. */
    private fun TestScope.answerUntil(answers: Scripted, until: Long) = answerUntil(answers::fireNew, until)

    private fun TestScope.answerUntil(fireNew: () -> Unit, until: Long) {
        fireNew()
        while (testScheduler.currentTime < until) {
            advanceTimeBy(minOf(STEP_MS, until - testScheduler.currentTime))
            runCurrent()
            fireNew()
        }
    }

    /** Runs the window out, lets native reach Listening, and returns the reason it reads. */
    private fun TestScope.windowEndReason(h: Harness, answers: Scripted, window: Long): FallbackReason? =
        windowEndReason(h, answers::fireNew, window)

    private fun TestScope.windowEndReason(h: Harness, answers: () -> Unit, window: Long): FallbackReason? {
        answerUntil(answers, until = window - 1)
        assertEquals("native must not start before the window", 0, h.fakeHandle.startCount)
        assertNull("no reason while the window is still open", h.reason)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("native starts when the window expires", 1, h.fakeHandle.startCount)
        h.fakeHandle.reachListening()
        runCurrent()
        assertEquals(Backend.Native, h.selector.backend.value)
        assertTrue("Auto's fallback stays silent", h.errors.isEmpty())
        return h.reason
    }

    private fun response(code: Int): Response =
        Response.Builder()
            .request(Request.Builder().url(SERVER_URL).build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Not Found")
            .build()

    /** Asserts an Auto fast-fail fell back to native at once and returns the reason it reads. */
    private fun TestScope.fastFailReason(h: Harness): FallbackReason? {
        runCurrent()
        assertTrue("a fast-fail falls back well inside the window", testScheduler.currentTime < 100)
        assertEquals("one socket only — a fast-fail is never retried", 1, h.fakeWs.createCount)
        assertEquals(1, h.fakeHandle.startCount)
        h.fakeHandle.reachListening()
        runCurrent()
        assertEquals(Backend.Native, h.selector.backend.value)
        assertTrue("Auto swallows the server failure", h.errors.isEmpty())
        return h.reason
    }

    // --- Task 1 tracer: busy for the whole window reads BUSY ---------------------------------------

    @Test
    fun `busy for the whole window falls back with BUSY and the reason is null until then`() = runTest {
        val window = 2_500L
        val h = startSelector(windowMs = window)
        val answers = Scripted(h, emptyList(), rest = busyFrame(h))

        answerUntil(answers, until = 1_000L)
        assertTrue("busy is being retried", h.fakeWs.createCount >= 2)
        assertNull("still inside the window: no reason yet", h.reason)
        assertEquals(EngineState.Connecting, h.selector.state.value)

        assertEquals(FallbackReason.BUSY, windowEndReason(h, answers, window))
    }

    // --- window-end reasons ------------------------------------------------------------------------

    @Test
    fun `an EOF on every attempt until the window ends reads TIMEOUT`() = runTest {
        val window = 2_500L
        val h = startSelector(windowMs = window)
        assertEquals(FallbackReason.TIMEOUT, windowEndReason(h, Scripted(h, emptyList(), eofFailure(h)), window))
    }

    @Test
    fun `a black-holed host reads TIMEOUT`() = runTest {
        val window = 2_500L
        val h = startSelector(windowMs = window)
        assertEquals(FallbackReason.TIMEOUT, windowEndReason(h, Scripted(h, emptyList(), blackHole), window))
        assertEquals("a host that never answers gets one attempt", 1, h.fakeWs.createCount)
    }

    @Test
    fun `EOFs then a busy with the window ending in the backoff reads BUSY`() = runTest {
        // Attempts at 0, 250, 750 (EOF) and 1 750 (busy); the 1 000 ms backoff outlives the window.
        val window = 2_500L
        val h = startSelector(windowMs = window)
        val answers = Scripted(h, listOf(eofFailure(h), eofFailure(h), eofFailure(h)), rest = busyFrame(h))
        assertEquals(FallbackReason.BUSY, windowEndReason(h, answers, window))
        assertEquals(4, h.fakeWs.createCount)
    }

    @Test
    fun `busy then an EOF with the window ending in the backoff reads TIMEOUT`() = runTest {
        val window = 2_500L
        val h = startSelector(windowMs = window)
        val answers = Scripted(h, listOf(busyFrame(h), busyFrame(h), busyFrame(h)), rest = eofFailure(h))
        assertEquals(FallbackReason.TIMEOUT, windowEndReason(h, answers, window))
        assertEquals(4, h.fakeWs.createCount)
    }

    @Test
    fun `busy then a newer attempt that is never answered reads TIMEOUT`() = runTest {
        // Attempts 1 and 2 are busy; attempt 3 (at 750 ms) black-holes until the window ends. The
        // unanswered in-flight attempt decides, not the earlier busy (review 28-04).
        val window = 2_500L
        val h = startSelector(windowMs = window)
        val answers = Scripted(h, listOf(busyFrame(h), busyFrame(h)), rest = blackHole)
        assertEquals(FallbackReason.TIMEOUT, windowEndReason(h, answers, window))
        assertEquals(3, h.fakeWs.createCount)
    }

    @Test
    fun `a server busy all window reads BUSY even when the window ends inside an attempt`() = runTest {
        // WR-02. Every attempt is answered busy 100 ms after it starts: attempts at 0, 350, 950 and
        // 2 050 (busy at 100, 450, 1 050; backoffs 250, 500, 1 000). The 2 100 ms window ends 50 ms
        // into attempt 4, before its busy arrives. The young in-flight attempt inherits the last
        // answer (busy); the review 28-04 rule alone read TIMEOUT here.
        val window = 2_100L
        val h = startSelector(windowMs = window)
        val answers = SlowBusy(h, { testScheduler.currentTime }, latencyMs = 100L)
        assertEquals(FallbackReason.BUSY, windowEndReason(h, answers::fireNew, window))
        assertEquals("the window ended during attempt 4", 4, h.fakeWs.createCount)
    }

    @Test
    fun `busy then an attempt unanswered for under the inherit bound reads BUSY`() = runTest {
        // Attempt 1 busy at 0; attempt 2 at 250 black-holes. The window ends at 1 200 ms, when
        // attempt 2 has been unanswered for 950 ms (< 1 000 ms): it inherits busy.
        val window = 1_200L
        val h = startSelector(windowMs = window)
        val answers = Scripted(h, listOf(busyFrame(h)), rest = blackHole)
        assertEquals(FallbackReason.BUSY, windowEndReason(h, answers, window))
        assertEquals(2, h.fakeWs.createCount)
    }

    @Test
    fun `busy then an attempt unanswered for over the inherit bound reads TIMEOUT`() = runTest {
        // Same script; the window ends at 1 300 ms, when attempt 2 has been unanswered for 1 050 ms
        // (> 1 000 ms): it counts as black-holed and decides from its own (empty) observation.
        val window = 1_300L
        val h = startSelector(windowMs = window)
        val answers = Scripted(h, listOf(busyFrame(h)), rest = blackHole)
        assertEquals(FallbackReason.TIMEOUT, windowEndReason(h, answers, window))
        assertEquals(2, h.fakeWs.createCount)
    }

    // --- fast-fail reasons -------------------------------------------------------------------------

    @Test
    fun `an unknown host reads UNREACHABLE`() = runTest {
        val h = startSelector()
        h.fakeWs.fireFailure(UnknownHostException("x"), socket = h.socket(0))
        assertEquals(FallbackReason.UNREACHABLE, fastFailReason(h))
    }

    @Test
    fun `a refused connect reads UNREACHABLE`() = runTest {
        val h = startSelector()
        h.fakeWs.fireFailure(ConnectException("Failed to connect to x"), socket = h.socket(0))
        assertEquals(FallbackReason.UNREACHABLE, fastFailReason(h))
    }

    @Test
    fun `a TLS certificate failure reads UNREACHABLE`() = runTest {
        val h = startSelector()
        val tls = SSLHandshakeException("PKIX path building failed").apply { initCause(CertificateException("untrusted")) }
        h.fakeWs.fireFailure(tls, socket = h.socket(0))
        assertEquals(FallbackReason.UNREACHABLE, fastFailReason(h))
    }

    @Test
    fun `a 404 upgrade answer reads UNREACHABLE`() = runTest {
        val h = startSelector()
        h.fakeWs.fireFailure(
            ProtocolException("Expected HTTP 101 response but was '404 Not Found'"),
            response = response(404),
            socket = h.socket(0),
        )
        assertEquals(FallbackReason.UNREACHABLE, fastFailReason(h))
    }

    @Test
    fun `an unsupported-language error frame reads REJECTED`() = runTest {
        val h = startSelector()
        h.fakeWs.fireOpen(h.socket(0))
        h.fakeWs.fireMessage(
            """{"type":"error","code":"unsupported-language","message":"no"}""",
            socket = h.socket(0),
        )
        assertEquals(FallbackReason.REJECTED, fastFailReason(h))
    }

    @Test
    fun `an invalid-config error frame reads REJECTED`() = runTest {
        val h = startSelector()
        h.fakeWs.fireOpen(h.socket(0))
        h.fakeWs.fireMessage("""{"type":"error","code":"invalid-config","message":"no"}""", socket = h.socket(0))
        assertEquals(FallbackReason.REJECTED, fastFailReason(h))
    }

    @Test
    fun `a microphone that will not open after ready reads REJECTED`() = runTest {
        // The residual fast-fail: no throwable, no upgrade answer, no error frame (research A7).
        val h = startSelector(pcmSourceFactory = PcmSourceFactory { throw IllegalStateException("mic in use") })
        h.fakeWs.fireOpen(h.socket(0))
        h.fakeWs.fireMessage("""{"type":"ready"}""", socket = h.socket(0))
        assertEquals(FallbackReason.REJECTED, fastFailReason(h))
    }

    // --- null cases --------------------------------------------------------------------------------

    @Test
    fun `prefer=Server with busy for the whole window surfaces one busy error and no reason`() = runTest {
        val window = 2_500L
        val h = startSelector(prefer = BackendPreference.Server, windowMs = window)
        answerUntil(Scripted(h, emptyList(), busyFrame(h)), until = window + 500L)
        advanceUntilIdle()

        assertEquals(1, h.errors.size)
        assertEquals(SttErrorCode.BUSY, h.errors.single().code)
        assertEquals(EngineState.Error, h.selector.state.value)
        assertEquals(0, h.fakeHandle.startCount)
        assertNull("prefer=Server raises an error instead of a fallback", h.reason)
    }

    @Test
    fun `a session that commits to the server reads null`() = runTest {
        val h = startSelector()
        h.fakeWs.fireOpen(h.socket(0))
        h.fakeWs.fireMessage("""{"type":"ready"}""", socket = h.socket(0))
        runCurrent()
        assertTrue(h.started.isCompleted)
        assertEquals(Backend.Server, h.selector.backend.value)
        assertNull(h.reason)
        launch(h.dispatcher) { h.selector.stop() }
        h.fakeWs.fireClosing(1000, socket = h.socket(0))
        runCurrent()
    }

    @Test
    fun `a config-chosen OnDevice engine reads null`() = runTest {
        val h = startSelector(prefer = BackendPreference.OnDevice)
        runCurrent()
        assertEquals("OnDevice never touches the server", 0, h.fakeWs.createCount)
        h.fakeHandle.reachListening()
        runCurrent()
        assertEquals(Backend.Native, h.selector.backend.value)
        assertEquals(EngineState.Listening, h.selector.state.value)
        assertNull(h.reason)
    }

    @Test
    fun `a config with no serverUrl reads null`() = runTest {
        val h = startSelector(serverUrl = null)
        runCurrent()
        assertEquals(0, h.fakeWs.createCount)
        h.fakeHandle.reachListening()
        runCurrent()
        assertEquals(Backend.Native, h.selector.backend.value)
        assertNull(h.reason)
    }

    @Test
    fun `the factory's plain native engine reads null`() {
        val engine = createSttEngine(context(), SttConfig())
        assertNull(engine.fallbackReason.value)
    }

    // --- reset on every start ----------------------------------------------------------------------

    @Test
    fun `a second start resets a BUSY reason to null`() = runTest {
        val window = 2_500L
        val h = startSelector(windowMs = window)
        assertEquals(FallbackReason.BUSY, windowEndReason(h, Scripted(h, emptyList(), busyFrame(h)), window))
        val firstSessionSockets = h.fakeWs.createCount

        launch(h.dispatcher) { h.selector.stop() }.join()
        assertEquals("the reason is a snapshot of the last session until the next start", FallbackReason.BUSY, h.reason)

        val again = launch(h.dispatcher) { h.selector.start() }
        runCurrent()
        assertEquals(firstSessionSockets + 1, h.fakeWs.createCount)
        assertNull("start() resets the reason", h.reason)
        h.fakeWs.fireOpen(h.socket(firstSessionSockets))
        h.fakeWs.fireMessage("""{"type":"ready"}""", socket = h.socket(firstSessionSockets))
        runCurrent()
        assertTrue(again.isCompleted)
        assertEquals(Backend.Server, h.selector.backend.value)
        assertNull(h.reason)
        launch(h.dispatcher) { h.selector.stop() }
        h.fakeWs.fireClosing(1000, socket = h.socket(firstSessionSockets))
        runCurrent()
    }

    // --- dropped: a post-commit server failure -----------------------------------------------------

    /** Commits a server session and records `(backend, reason)` at every `finals` arrival and every
     *  `backend` emission. */
    private class Committed(
        val h: Harness,
        val atFinal: MutableList<Pair<Backend, FallbackReason?>>,
        val atBackend: MutableList<Pair<Backend, FallbackReason?>>,
    )

    private fun TestScope.committedServerSession(): Committed {
        val h = startSelector()
        val atFinal = mutableListOf<Pair<Backend, FallbackReason?>>()
        val atBackend = mutableListOf<Pair<Backend, FallbackReason?>>()
        backgroundScope.launch(h.dispatcher) {
            h.selector.finals.collect { atFinal += h.selector.backend.value to h.selector.fallbackReason.value }
        }
        backgroundScope.launch(h.dispatcher) {
            h.selector.backend.collect { atBackend += it to h.selector.fallbackReason.value }
        }
        h.fakeWs.fireOpen(h.socket(0))
        h.fakeWs.fireMessage("""{"type":"ready"}""", socket = h.socket(0))
        runCurrent()
        assertTrue(h.started.isCompleted)
        assertEquals(Backend.Server, h.selector.backend.value)
        assertNull(h.reason)
        return Committed(h, atFinal, atBackend)
    }

    /** After a mid-session failure: still `(Server, null)` until native is Listening, then
     *  `(Native, DROPPED)`, and the reason was already there when `backend` read Native. */
    private fun TestScope.assertDroppedHandoff(c: Committed) {
        val h = c.h
        runCurrent()
        assertEquals("the drop must start native", 1, h.fakeHandle.startCount)
        assertEquals("backend stays Server until native is Listening", Backend.Server, h.selector.backend.value)
        assertNull("no reason before the backend flip", h.reason)

        h.fakeHandle.reachListening()
        runCurrent()
        assertEquals(Backend.Native, h.selector.backend.value)
        assertEquals(FallbackReason.DROPPED, h.reason)
        assertTrue("the handoff is silent on errors", h.errors.isEmpty())

        val afterServer = c.atBackend.dropWhile { it.first != Backend.Server }
        assertEquals(
            "a backend collector that sees Native already reads the reason",
            listOf(Backend.Native to FallbackReason.DROPPED),
            afterServer.filter { it.first == Backend.Native },
        )
    }

    @Test
    fun `a ping timeout after ready hands off to native and reads DROPPED`() = runTest {
        val c = committedServerSession()
        c.h.fakeWs.fireFailure(
            SocketTimeoutException("sent ping but didn't receive pong within 10000ms"),
            socket = c.h.socket(0),
        )
        assertDroppedHandoff(c)
    }

    @Test
    fun `an internal-error frame after ready hands off to native and reads DROPPED`() = runTest {
        val c = committedServerSession()
        c.h.fakeWs.fireMessage("""{"type":"error","code":"internal-error","message":"boom"}""", socket = c.h.socket(0))
        assertDroppedHandoff(c)
    }

    @Test
    fun `the handoff final reads Server and null, the pair after native Listening reads Native and DROPPED`() =
        runTest {
            val c = committedServerSession()
            c.h.fakeWs.fireMessage("""{"type":"partial","text":"open words","segmentId":3}""", socket = c.h.socket(0))
            runCurrent()
            c.h.fakeWs.fireFailure(
                SocketTimeoutException("sent ping but didn't receive pong within 10000ms"),
                socket = c.h.socket(0),
            )
            runCurrent()
            assertEquals(
                "the handoff final is committed before native exists",
                listOf(Backend.Server to null),
                c.atFinal.toList(),
            )
            assertDroppedHandoff(c)
            assertEquals(Backend.Native to FallbackReason.DROPPED, c.h.selector.backend.value to c.h.reason)
        }

    // --- ordering: the reason is written before the backend flip -----------------------------------

    @Test
    fun `a start-time fallback after a server session publishes the reason before backend flips`() = runTest {
        val c = committedServerSession()
        val h = c.h
        launch(h.dispatcher) { h.selector.stop() }
        h.fakeWs.fireClosing(1000, socket = h.socket(0))
        runCurrent()
        assertEquals("backend still reads the last session's Server", Backend.Server, h.selector.backend.value)

        // Session 2: busy for the whole window, so backend flips Server -> Native at the window.
        val t0 = testScheduler.currentTime
        val again = launch(h.dispatcher) { h.selector.start() }
        runCurrent()
        assertEquals("session 2 opened its first attempt", 2, h.fakeWs.createCount)
        assertNull("start() reset the reason", h.reason)
        // Socket 0 is session 1's (already closed): leave it alone, answer every session-2 attempt busy.
        val sessionTwo = Scripted(h, listOf(blackHole), rest = busyFrame(h))
        answerUntil(sessionTwo, until = t0 + WINDOW_MS + 100)
        assertEquals(1, h.fakeHandle.startCount)
        h.fakeHandle.reachListening()
        runCurrent()
        assertTrue(again.isCompleted)

        assertEquals(Backend.Native, h.selector.backend.value)
        assertEquals(FallbackReason.BUSY, h.reason)
        val afterServer = c.atBackend.dropWhile { it.first != Backend.Server }
        assertEquals(
            "a backend collector that sees Native already reads the reason",
            listOf(Backend.Native to FallbackReason.BUSY),
            afterServer.filter { it.first == Backend.Native },
        )
    }

    private companion object {
        const val SERVER_URL = "wss://example.ts.net:8444/"
        const val WINDOW_MS = 5_000L
        const val STEP_MS = 50L
        const val BUSY_FRAME = """{"type":"error","code":"busy","message":"server busy"}"""
    }
}
