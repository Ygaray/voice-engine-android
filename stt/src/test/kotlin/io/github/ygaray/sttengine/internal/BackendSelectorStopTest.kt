package io.github.ygaray.sttengine.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.FinalSegment
import io.github.ygaray.sttengine.PreflightHandle
import io.github.ygaray.sttengine.SttConfig
import io.github.ygaray.sttengine.SttError
import io.github.ygaray.sttengine.SttEvent
import java.io.IOException
import java.net.ConnectException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
 * `BackendSelector.stop()` on a committed server session (Phase 21, SRV-05, D-04).
 *
 * The selector detaches [ServerBackend] (`activeServer = null`) BEFORE it awaits the up-to-~2.5 s
 * flush, so anything that goes wrong on the socket during that wait lands on
 * `handleServerDrop`'s identity guard as a no-op: a stop can never start the native recognizer.
 * The forwarding collectors keep running after the detach, so the server's flushed tail still
 * reaches [BackendSelector.finals] and the selector's `state` still follows the backend to `Idle`.
 *
 * Harness: [StandardTestDispatcher] as `mainDispatcher` with explicit `runCurrent()` between fires
 * (every OkHttp callback hops onto main, so each fire must be drained before the next assertion).
 * The committed session uses `finitePcmSourceFactory(frames = 0)` — the pump ends at once instead
 * of spinning on the Robolectric-shadowed `AudioRecord` (Pitfall 1). Robolectric (sdk 35) for the
 * native fallback's real `Context`, exactly like [BackendSelectorTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class BackendSelectorStopTest {

    private class Harness(
        val main: CoroutineDispatcher,
        val fakeWs: FakeWebSocketFactory,
        val fakeHandle: FakeRecognizerHandle,
        val selector: BackendSelector,
        val finals: MutableList<FinalSegment>,
        val errors: MutableList<SttError>,
        val states: MutableList<EngineState>,
        val pcm: FinitePcmSourceFactory,
    ) {
        val socket: FakeWebSocket get() = checkNotNull(fakeWs.lastSocket)
        fun log(): List<String> = synchronized(socket.sendLog) { socket.sendLog.toList() }
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    /** A selector committed to the server path (`ready` reached), with collectors on its flows. */
    private fun TestScope.committedServerSession(): Harness {
        val h = session()
        val started = launch(h.main) { h.selector.start() }
        runCurrent()
        h.fakeWs.fireOpen()
        runCurrent()
        h.fakeWs.fireMessage("""{"type":"ready"}""")
        runCurrent()
        assertTrue("start() must have returned once ready committed the server", started.isCompleted)
        assertEquals(Backend.Server, h.selector.backend.value)
        assertEquals(EngineState.Listening, h.selector.state.value)
        return h
    }

    /** A selector whose `start()` is suspended in the try-server window: attempt 1 never answers. */
    private fun TestScope.tryWindowSession(): Harness {
        val h = session()
        launch(h.main) { h.selector.start() }
        runCurrent()
        assertEquals("attempt 1 must be in flight", 1, h.fakeWs.createCount)
        assertEquals(EngineState.Connecting, h.selector.state.value)
        assertTrue("start() must still be in the try window", h.selector.startJob?.isActive == true)
        return h
    }

    /** A selector that fell back to a live native session (attempt 1 refused, `prefer = Auto`). */
    private fun TestScope.nativeSession(): Harness {
        val h = session()
        launch(h.main) { h.selector.start() }
        runCurrent()
        h.fakeWs.fireFailure(ConnectException("refused"))
        runCurrent()
        assertEquals("the refused attempt falls back to native", 1, h.fakeHandle.startCount)
        h.fakeHandle.reachListening()
        runCurrent()
        assertEquals(Backend.Native, h.selector.backend.value)
        assertEquals(EngineState.Listening, h.selector.state.value)
        return h
    }

    /** The selector and its collectors; nothing started yet. */
    private fun TestScope.session(): Harness {
        val main = StandardTestDispatcher(testScheduler)
        val fakeWs = FakeWebSocketFactory()
        val fakeHandle = FakeRecognizerHandle()
        val pcm = finitePcmSourceFactory(frames = 0)
        val selector = BackendSelector(
            appContext = context(),
            config = SttConfig(serverUrl = "wss://example.ts.net:8444/"),
            handleFactory = RecognizerHandleFactory {
                PreflightHandle(fakeHandle, LanguageAvailability.INSTALLED)
            },
            permissions = PermissionChecker { true },
            webSocketFactory = fakeWs,
            mainDispatcher = main,
            tryServerTimeoutMs = WINDOW_MS,
            serverBackendFactory = { url ->
                ServerBackend(
                    webSocketFactory = fakeWs,
                    serverUrl = url,
                    language = "en",
                    mainDispatcher = main,
                    pcmSourceFactory = pcm,
                )
            },
        )
        val finals = mutableListOf<FinalSegment>()
        val errors = mutableListOf<SttError>()
        val states = mutableListOf<EngineState>()
        backgroundScope.launch(main) { selector.finals.collect { finals += it } }
        backgroundScope.launch(main) { selector.errors.collect { errors += it } }
        backgroundScope.launch(main) { selector.state.collect { states += it } }
        return Harness(main, fakeWs, fakeHandle, selector, finals, errors, states, pcm)
    }

    /** Launches `selector.stop()` and runs until the `stop` token is on the wire (the flush wait). */
    private fun TestScope.stopUntilTokenSent(h: Harness): Job {
        val stopJob = launch(h.main) { h.selector.stop() }
        runCurrent()
        assertTrue("the stop token must be on the wire: ${h.log()}", "text:stop" in h.log())
        assertFalse("stop() must still be awaiting the server's flush", stopJob.isCompleted)
        return stopJob
    }

    private fun assertStoppedWithoutNative(h: Harness, stopJob: Job) {
        assertTrue("stop() must have returned", stopJob.isCompleted)
        assertEquals("a stop must never start the native recognizer", 0, h.fakeHandle.startCount)
        assertTrue("a requested stop must never emit on errors: ${h.errors}", h.errors.isEmpty())
        assertFalse("a requested stop must never reach Error: ${h.states}", EngineState.Error in h.states)
        assertEquals(EngineState.Idle, h.selector.state.value)
        assertEquals(Backend.Server, h.selector.backend.value)
    }

    @Test
    fun `a socket failure during the stop flush never starts native`() = runTest {
        val h = committedServerSession()
        val stopJob = stopUntilTokenSent(h)

        h.fakeWs.fireFailure(IOException("reset"))
        runCurrent()

        assertStoppedWithoutNative(h, stopJob)
    }

    @Test
    fun `an abnormal close during the stop flush never starts native`() = runTest {
        val h = committedServerSession()
        val stopJob = stopUntilTokenSent(h)

        h.fakeWs.fireClosing(1011, "internal")
        runCurrent()

        assertStoppedWithoutNative(h, stopJob)
    }

    @Test
    fun `a server error frame during the stop flush never starts native`() = runTest {
        val h = committedServerSession()
        val stopJob = stopUntilTokenSent(h)

        h.fakeWs.fireMessage("""{"type":"error","code":"internal-error","message":"boom"}""")
        runCurrent()

        assertStoppedWithoutNative(h, stopJob)
    }

    @Test
    fun `the final flushed during a selector stop reaches selector finals`() = runTest {
        val h = committedServerSession()
        val stopJob = stopUntilTokenSent(h)

        h.fakeWs.fireMessage("""{"type":"final","text":"tail","segmentId":5}""")
        h.fakeWs.fireClosing(1000)
        runCurrent()

        assertStoppedWithoutNative(h, stopJob)
        assertEquals(listOf(FinalSegment("tail", 5)), h.finals)
        assertEquals("close:1000", h.log().last())
    }

    // --- start() against a stop() that is still flushing (21-W46 cycle 2, finding A) ----------------
    //
    // The re-entrant start() is launched and asserted complete at once, never joined: without the
    // guard it suspends in a fresh try window on a second socket, and the assertion must end the test
    // there rather than wait on it.

    @Test
    fun `a start during the stop flush is ignored and lastFinal is the flushed tail when stop returns`() = runTest {
        val h = committedServerSession()
        val stopJob = stopUntilTokenSent(h)

        val second = launch(h.main) { h.selector.start() }
        runCurrent()
        assertTrue("a start() during the flush returns at once", second.isCompleted)
        assertEquals("a start() during the flush must not open a second socket", 1, h.fakeWs.createCount)

        h.fakeWs.fireMessage("""{"type":"final","text":"tail","segmentId":5}""", socket = h.fakeWs.sockets[0])
        h.fakeWs.fireClosing(1000, socket = h.fakeWs.sockets[0])
        runCurrent()

        assertStoppedWithoutNative(h, stopJob)
        assertEquals("LAST-01: the flushed tail is readable as stop() returns", FinalSegment("tail", 5), h.selector.lastFinal)
        assertEquals(listOf(FinalSegment("tail", 5)), h.finals)
    }

    @Test
    fun `a start after stop returns begins a fresh session and retires the old session's forwarders`() = runTest {
        val h = committedServerSession()
        val oldForwarders = h.selector.sessionForwarders
        val stopJob = stopUntilTokenSent(h)
        h.fakeWs.fireClosing(1000)
        runCurrent()
        assertStoppedWithoutNative(h, stopJob)
        assertFalse("the forwarders outlive the detach, so the flush still reaches the host", oldForwarders.isCancelled)

        val again = launch(h.main) { h.selector.start() }
        runCurrent()
        assertEquals("a start() after stop() returned opens a fresh attempt", 2, h.fakeWs.createCount)
        assertEquals(EngineState.Connecting, h.selector.state.value)
        assertTrue("the previous session's forwarders are cancelled", oldForwarders.isCancelled)
        assertTrue("…and none is left running", oldForwarders.children.none())

        h.fakeWs.fireOpen()
        h.fakeWs.fireMessage("""{"type":"ready"}""")
        runCurrent()
        assertTrue(again.isCompleted)
        assertEquals(Backend.Server, h.selector.backend.value)
        assertEquals(EngineState.Listening, h.selector.state.value)
    }

    @Test
    fun `an open partial is committed onto selector finals when the flush fails`() = runTest {
        val h = committedServerSession()
        h.fakeWs.fireMessage("""{"type":"partial","text":"open words","segmentId":9}""")
        runCurrent()
        assertEquals("open words", h.selector.partial.value)
        val stopJob = stopUntilTokenSent(h)

        h.fakeWs.fireFailure(IOException("reset"))
        runCurrent()

        assertStoppedWithoutNative(h, stopJob)
        assertEquals(listOf(FinalSegment("open words", 9)), h.finals)
    }

    // --- a cancelled stop() caller can never skip the teardown (21-W46 cycle 3, finding 1) --------
    //
    // stop() hops onto main to tear down. A cancellable hop would never run its block for a caller
    // that is already cancelled, or is cancelled while the hop is still queued: the start job, the
    // socket or native recognizer and the mic would all stay live. Every case below must end torn
    // down. Virtual time throughout, so a regression fails at its first assertion.

    /** Where every cancelled-caller case must end: nothing starting, nothing live, mic released. */
    private fun assertTornDown(h: Harness, caller: Job) {
        assertTrue("the caller's stop() must have finished", caller.isCompleted)
        assertTrue(
            "the start job must not still be running: ${h.selector.startJob}",
            h.selector.startJob?.isActive != true,
        )
        h.fakeWs.sockets.forEachIndexed { i, socket ->
            assertTrue("socket $i must be closed or cancelled", socket.closeCount + socket.cancelCount >= 1)
        }
        assertTrue("every server-path mic must be released", h.pcm.opened.all { it.released })
        assertEquals(EngineState.Idle, h.selector.state.value)
        assertTrue("a requested stop must never emit on errors: ${h.errors}", h.errors.isEmpty())
    }

    /** The try-window cases: the start job was cancelled, and nothing starts later. */
    private fun TestScope.assertTryWindowEnded(h: Harness, caller: Job) {
        assertTornDown(h, caller)
        assertTrue("stop() must cancel the start job", h.selector.startJob?.isCancelled == true)
        advanceTimeBy(WINDOW_MS + 1)
        runCurrent()
        assertEquals("no attempt may follow the stop", 1, h.fakeWs.createCount)
        assertEquals("native must never start after the stop", 0, h.fakeHandle.startCount)
        assertEquals(EngineState.Idle, h.selector.state.value)
    }

    /** Launches stop() from a coroutine that cancels itself first, then drains main. */
    private fun TestScope.stopFromCancelledCaller(h: Harness): Job {
        val caller = launch(h.main) {
            cancel()
            h.selector.stop()
        }
        runCurrent()
        return caller
    }

    /** Launches stop() off main, cancels the caller while the hop onto main is still queued, then
     *  drains main. */
    private fun TestScope.stopCancelledWhileQueued(h: Harness): Job {
        val caller = launch(UnconfinedTestDispatcher(testScheduler)) { h.selector.stop() }
        assertFalse("precondition: stop()'s hop onto main is still queued", caller.isCompleted)
        caller.cancel()
        runCurrent()
        return caller
    }

    /** The server flush, answered: the tail final, then the close 1000. */
    private fun TestScope.answerFlush(h: Harness) {
        h.fakeWs.fireMessage("""{"type":"final","text":"tail","segmentId":5}""")
        h.fakeWs.fireClosing(1000)
        runCurrent()
    }

    @Test
    fun `stop from an already-cancelled caller still flushes and ends a committed server session`() = runTest {
        val h = committedServerSession()

        val caller = stopFromCancelledCaller(h)
        assertTrue("the teardown must run: the stop token is on the wire: ${h.log()}", "text:stop" in h.log())
        answerFlush(h)

        assertTornDown(h, caller)
        assertEquals(listOf(FinalSegment("tail", 5)), h.finals)
        assertEquals(FinalSegment("tail", 5), h.selector.lastFinal)
        assertEquals(0, h.fakeHandle.startCount)
    }

    @Test
    fun `stop from an already-cancelled caller still ends the try window`() = runTest {
        val h = tryWindowSession()

        val caller = stopFromCancelledCaller(h)

        assertTryWindowEnded(h, caller)
    }

    @Test
    fun `stop from an already-cancelled caller still stops a native session`() = runTest {
        val h = nativeSession()

        val caller = stopFromCancelledCaller(h)

        assertTornDown(h, caller)
        assertTrue("the native recognizer must be released", h.fakeHandle.destroyCount >= 1)
    }

    @Test
    fun `a caller cancelled while the stop hop is queued still ends a committed server session`() = runTest {
        val h = committedServerSession()

        val caller = stopCancelledWhileQueued(h)
        assertTrue("the teardown must run: the stop token is on the wire: ${h.log()}", "text:stop" in h.log())
        answerFlush(h)

        assertTornDown(h, caller)
        assertEquals(listOf(FinalSegment("tail", 5)), h.finals)
    }

    @Test
    fun `a caller cancelled while the stop hop is queued still ends the try window`() = runTest {
        val h = tryWindowSession()

        val caller = stopCancelledWhileQueued(h)

        assertTryWindowEnded(h, caller)
    }

    @Test
    fun `a caller cancelled during the flush still receives the flushed tail and a full teardown`() = runTest {
        val h = committedServerSession()
        h.fakeWs.fireMessage("""{"type":"partial","text":"open words","segmentId":5}""")
        runCurrent()
        val caller = stopUntilTokenSent(h)

        caller.cancel()
        runCurrent()
        assertFalse("a cancelled caller must not cut the flush short", caller.isCompleted)
        answerFlush(h)

        assertTornDown(h, caller)
        assertEquals("the server's flushed tail, not a local commit", listOf(FinalSegment("tail", 5)), h.finals)
        assertEquals(FinalSegment("tail", 5), h.selector.lastFinal)
        assertEquals("close:1000", h.log().last())
    }

    @Test
    fun `a caller cancelled during an unanswered flush still returns at the D-04 bound`() = runTest {
        val h = committedServerSession()
        h.fakeWs.fireMessage("""{"type":"partial","text":"open words","segmentId":9}""")
        runCurrent()
        val caller = stopUntilTokenSent(h)
        val flushStartedAt = testScheduler.currentTime

        caller.cancel()
        runCurrent()
        advanceTimeBy(ServerBackend.STOP_FLUSH_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse("the flush keeps its full bound", caller.isCompleted)
        advanceTimeBy(1)
        runCurrent()

        assertEquals(ServerBackend.STOP_FLUSH_TIMEOUT_MS, testScheduler.currentTime - flushStartedAt)
        assertTornDown(h, caller)
        assertEquals(listOf(FinalSegment("open words", 9)), h.finals)
    }

    // --- a session's finals collectors end with it (21-W46 cycle 3, finding 2) --------------------
    //
    // `finals` is lossless, so its forwarding collectors are never cancelled. Before this fix they
    // were never ended either: each stayed suspended on its backend's channel for the selector's
    // whole life, and only dispatch order kept a final it still held from reaching the host after
    // the next session had begun. Now a settled stop (or the next start, after a session that ended
    // on an error) closes each backend's `finals` and joins its collector.

    /** stop(), then — inside the same coroutine, before the test advances anything — whether every
     *  collector in [forwarders] had already completed normally, and how many were still live. */
    private class StopReadout {
        var forwardersDoneAtReturn: Boolean? = null
        var liveAtReturn: Int? = null
    }

    private fun TestScope.stopAndRead(h: Harness, forwarders: List<Job>, then: suspend () -> Unit = {}): Pair<Job, StopReadout> {
        val readout = StopReadout()
        val caller = launch(h.main) {
            h.selector.stop()
            readout.forwardersDoneAtReturn = forwarders.all { it.isCompleted && !it.isCancelled }
            readout.liveAtReturn = h.selector.finalsForwarderJobs.size
            then()
        }
        runCurrent()
        return caller to readout
    }

    private fun assertRetiredAtReturn(readout: StopReadout) {
        assertEquals(
            "every finals collector must have drained and completed (not been cancelled) by the time stop() returns",
            true,
            readout.forwardersDoneAtReturn,
        )
        assertEquals("no finals collector may outlive the stopped session", 0, readout.liveAtReturn)
    }

    @Test
    fun `the server finals collector has delivered the flushed tail and completed when stop returns`() = runTest {
        val h = committedServerSession()
        val forwarders = h.selector.finalsForwarderJobs
        assertEquals(1, forwarders.size)

        val (caller, readout) = stopAndRead(h, forwarders)
        answerFlush(h)

        assertTrue(caller.isCompleted)
        assertRetiredAtReturn(readout)
        assertEquals(listOf(FinalSegment("tail", 5)), h.finals)
        assertEquals(FinalSegment("tail", 5), h.selector.lastFinal)
    }

    @Test
    fun `a final committed at the flush bound reaches the stopping session, never the next one`() = runTest {
        val h = committedServerSession()
        val events = mutableListOf<SttEvent>()
        backgroundScope.launch(h.main) { h.selector.events.collect { events += it } }
        h.fakeWs.fireMessage("""{"type":"partial","text":"open words","segmentId":9}""")
        runCurrent()
        val forwarders = h.selector.finalsForwarderJobs

        // The local commit at the bound is sent from inside stop() itself, so its forwarding hop is
        // still pending as the flush returns; the next start() is issued the instant stop() returns.
        val (caller, readout) = stopAndRead(h, forwarders) { h.selector.start() }
        advanceTimeBy(ServerBackend.STOP_FLUSH_TIMEOUT_MS)
        runCurrent()
        assertRetiredAtReturn(readout)
        assertEquals("the next session's attempt is in flight", 2, h.fakeWs.createCount)

        h.fakeWs.fireOpen()
        h.fakeWs.fireMessage("""{"type":"ready"}""")
        h.fakeWs.fireMessage("""{"type":"final","text":"next","segmentId":1}""")
        runCurrent()
        assertTrue(caller.isCompleted)

        assertEquals(listOf(FinalSegment("open words", 9), FinalSegment("next", 1)), h.finals)
        val oldFinal = events.indexOfFirst { it is SttEvent.Final && it.segment.text == "open words" }
        val nextConnecting = events.indexOfLast { it is SttEvent.State && it.state == EngineState.Connecting }
        assertTrue("the old session's final must precede the next session's first event: $events", oldFinal in 0 until nextConnecting)
        assertEquals(
            "exactly one final event after the next session began",
            listOf("next"),
            events.drop(nextConnecting).filterIsInstance<SttEvent.Final>().map { it.segment.text },
        )
        assertEquals("only the next session's collector is live", 1, h.selector.finalsForwarderJobs.size)
    }

    @Test
    fun `the native finals collector has delivered the committed partial and completed when stop returns`() = runTest {
        val h = nativeSession()
        h.fakeHandle.firePartial("native words")
        runCurrent()
        val forwarders = h.selector.finalsForwarderJobs
        assertEquals(1, forwarders.size)

        val (caller, readout) = stopAndRead(h, forwarders)

        assertTrue(caller.isCompleted)
        assertRetiredAtReturn(readout)
        assertEquals(listOf("native words"), h.finals.map { it.text })
    }

    @Test
    fun `a session that ended on a terminal error has its finals collector retired before the next session begins`() =
        runTest {
            val h = nativeSession()
            val forwarders = h.selector.finalsForwarderJobs
            assertEquals(1, forwarders.size)
            h.fakeHandle.fireError(ERROR_LANGUAGE_NOT_SUPPORTED)
            runCurrent()
            assertEquals(EngineState.Error, h.selector.state.value)
            assertTrue("an error alone does not retire the collector", forwarders.none { it.isCompleted })

            launch(h.main) { h.selector.start() }
            runCurrent()

            assertEquals("the next session began", 2, h.fakeWs.createCount)
            assertTrue(
                "the old collector must have drained and completed, not been cancelled: $forwarders",
                forwarders.all { it.isCompleted && !it.isCancelled },
            )
            assertEquals("only the next session's collectors may be live", 0, h.selector.finalsForwarderJobs.size)

            launch(h.main) { h.selector.stop() }
            runCurrent()
        }

    private companion object {
        const val WINDOW_MS = 5_000L
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    }
}
