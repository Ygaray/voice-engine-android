package io.github.ygaray.sttengine.internal

import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.FinalSegment
import io.github.ygaray.sttengine.SttError
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `ServerBackend.stop()`'s flush contract (Phase 21, SRV-05, D-04), one test per branch:
 * clean flush (ordering, close-1000 reply, flushed final), await timeout, starved drain hitting the
 * SAME end-to-end bound, mid-flush failure, abnormal close, refused token, and a repeat call.
 *
 * Harness: [StandardTestDispatcher] as `mainDispatcher` so virtual time drives the
 * [ServerBackend.STOP_FLUSH_TIMEOUT_MS] bound and every hop is scheduled explicitly with
 * `runCurrent()` (Pitfall 8 — no unconfined hop can run the consumer on the pump thread). Before
 * stopping, each test waits (real time) for the finite source to be exhausted, so the pump has read
 * and handed over every frame. The starved-drain case swaps in a real executor it can park.
 * Robolectric (sdk 35) only because the config frame is built with `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class ServerBackendStopFlushTest {

    private class Harness(
        val main: CoroutineDispatcher,
        val fakeWs: FakeWebSocketFactory,
        val backend: ServerBackend,
        val finals: MutableList<FinalSegment>,
        val errors: MutableList<SttError>,
        val states: MutableList<EngineState>,
    ) {
        val socket: FakeWebSocket get() = checkNotNull(fakeWs.lastSocket)
        fun log(): List<String> = synchronized(socket.sendLog) { socket.sendLog.toList() }
    }

    private fun awaitExhausted(sources: FinitePcmSourceFactory) {
        val deadline = System.currentTimeMillis() + 2_000L
        while (sources.opened.isEmpty() || !sources.last.exhausted) {
            check(System.currentTimeMillis() < deadline) { "the pump never exhausted the source" }
            Thread.sleep(5)
        }
    }

    /** A backend connected to `ready` whose pump has delivered all [frames] frames to the socket. */
    private fun TestScope.readyHarness(frames: Int = 3): Harness {
        val main = StandardTestDispatcher(testScheduler)
        val fakeWs = FakeWebSocketFactory()
        val sources = finitePcmSourceFactory(frames = frames)
        val backend = ServerBackend(
            webSocketFactory = fakeWs,
            serverUrl = "wss://example.ts.net:8444/",
            language = "en",
            mainDispatcher = main,
            pcmSourceFactory = sources,
        )
        val finals = mutableListOf<FinalSegment>()
        val errors = mutableListOf<SttError>()
        val states = mutableListOf<EngineState>()
        backgroundScope.launch(main) { backend.finals.collect { finals += it } }
        backgroundScope.launch(main) { backend.errors.collect { errors += it } }
        backgroundScope.launch(main) { backend.state.collect { states += it } }

        backgroundScope.launch(main) { backend.connect() }
        runCurrent()
        fakeWs.fireOpen()
        runCurrent()
        fakeWs.fireMessage("""{"type":"ready"}""")
        runCurrent()
        assertEquals(EngineState.Listening, backend.state.value)

        awaitExhausted(sources)
        runCurrent() // the main consumer drains every pumped frame through the gated send path
        val harness = Harness(main, fakeWs, backend, finals, errors, states)
        assertEquals(frames, harness.socket.sentBinary.size)
        return harness
    }

    private fun TestScope.openPartial(h: Harness) {
        h.fakeWs.fireMessage("""{"type":"partial","text":"open words","segmentId":9}""")
        runCurrent()
        assertEquals("open words", h.backend.partial.value)
    }

    private fun assertRequestedStopNeverErrored(h: Harness) {
        assertTrue("a requested stop must never emit on errors: ${h.errors}", h.errors.isEmpty())
        assertFalse("a requested stop must never reach Error: ${h.states}", EngineState.Error in h.states)
        assertEquals(EngineState.Idle, h.backend.state.value)
    }

    @Test
    fun `stop drains every frame, then sends the token, and never closes before the server does`() =
        runTest {
            val h = readyHarness(frames = 3)
            val config = h.socket.sent.first()

            val stopJob = launch(h.main) { h.backend.stop() }
            runCurrent()

            assertEquals(
                listOf("text:$config", "binary:1024", "binary:1024", "binary:1024", "text:stop"),
                h.log(),
            )
            assertFalse("stop() must still be awaiting the server's flush", stopJob.isCompleted)

            h.fakeWs.fireClosing(1000)
            runCurrent()
            assertTrue(stopJob.isCompleted)
        }

    @Test
    fun `the flushed final is emitted and the client replies close 1000 to the server's close`() =
        runTest {
            val h = readyHarness()

            val stopJob = launch(h.main) { h.backend.stop() }
            runCurrent()
            h.fakeWs.fireMessage("""{"type":"final","text":"tail","segmentId":7}""")
            h.fakeWs.fireClosing(1000)
            runCurrent()

            assertTrue(stopJob.isCompleted)
            assertEquals(listOf(FinalSegment("tail", 7)), h.finals)
            assertEquals("close:1000", h.log().last())
            assertFalse("a clean flush never cancels the socket", "cancel" in h.log())
            assertRequestedStopNeverErrored(h)
        }

    @Test
    fun `an unanswered stop commits the live partial with its own segmentId at the bound`() =
        runTest {
            val h = readyHarness()
            openPartial(h)
            val start = testScheduler.currentTime

            val stopJob = launch(h.main) { h.backend.stop() }
            runCurrent()
            advanceTimeBy(ServerBackend.STOP_FLUSH_TIMEOUT_MS - 1)
            runCurrent()
            assertFalse("stop() must wait out the whole bound", stopJob.isCompleted)
            assertTrue("nothing committed before the bound", h.finals.isEmpty())

            advanceTimeBy(1)
            runCurrent()

            assertTrue(stopJob.isCompleted)
            assertEquals(ServerBackend.STOP_FLUSH_TIMEOUT_MS, testScheduler.currentTime - start)
            assertEquals(listOf(FinalSegment("open words", 9)), h.finals)
            assertEquals("", h.backend.partial.value)
            assertTrue("the unflushed socket must be cancelled: ${h.log()}", "cancel" in h.log())
            assertRequestedStopNeverErrored(h)
        }

    @Test
    fun `the stop-flush local commit carries no language and no source`() = runTest {
        val h = readyHarness()
        openPartial(h)

        val stopJob = launch(h.main) { h.backend.stop() }
        runCurrent()
        advanceTimeBy(ServerBackend.STOP_FLUSH_TIMEOUT_MS)
        runCurrent()

        assertTrue(stopJob.isCompleted)
        val committed = h.finals.single()
        assertEquals("open words", committed.text)
        assertNull("a local commit has no detection evidence", committed.language)
        assertNull(committed.languageSource)
    }

    @Test
    fun `an unanswered stop with no open partial commits nothing`() = runTest {
        val h = readyHarness()

        val stopJob = launch(h.main) { h.backend.stop() }
        runCurrent()
        advanceTimeBy(ServerBackend.STOP_FLUSH_TIMEOUT_MS)
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertTrue(h.finals.isEmpty())
        assertRequestedStopNeverErrored(h)
    }

    @Test
    fun `a socket failure after the token ends the flush promptly with a local commit and no error`() =
        runTest {
            val h = readyHarness()
            openPartial(h)
            val start = testScheduler.currentTime

            val stopJob = launch(h.main) { h.backend.stop() }
            runCurrent()
            assertEquals("text:stop", h.log().last())

            h.fakeWs.fireFailure(IOException("reset"))
            runCurrent()

            assertTrue("stop() must not wait out the bound after a failure", stopJob.isCompleted)
            assertEquals(start, testScheduler.currentTime)
            assertEquals(listOf(FinalSegment("open words", 9)), h.finals)
            assertRequestedStopNeverErrored(h)
        }

    @Test
    fun `an abnormal server close mid-flush commits locally and never errors`() = runTest {
        val h = readyHarness()
        openPartial(h)

        val stopJob = launch(h.main) { h.backend.stop() }
        runCurrent()
        h.fakeWs.fireClosing(1011, "internal error")
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertEquals(listOf(FinalSegment("open words", 9)), h.finals)
        assertFalse("no close reply to an abnormal close: ${h.log()}", h.log().any { it.startsWith("close:") })
        assertRequestedStopNeverErrored(h)
    }

    @Test
    fun `a refused stop token commits locally at once without waiting for the bound`() = runTest {
        val h = readyHarness()
        openPartial(h)
        h.socket.sendSucceeds = false
        val start = testScheduler.currentTime

        val stopJob = launch(h.main) { h.backend.stop() }
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertEquals(start, testScheduler.currentTime)
        assertFalse("text:stop" in h.log())
        assertEquals(listOf(FinalSegment("open words", 9)), h.finals)
        assertRequestedStopNeverErrored(h)
    }

    @Test
    fun `a second stop after the first returned is a no-op`() = runTest {
        val h = readyHarness()

        val first = launch(h.main) { h.backend.stop() }
        runCurrent()
        h.fakeWs.fireClosing(1000)
        runCurrent()
        assertTrue(first.isCompleted)

        val second = launch(h.main) { h.backend.stop() }
        runCurrent()

        assertTrue(second.isCompleted)
        assertEquals(1, h.log().count { it == "text:stop" })
        assertRequestedStopNeverErrored(h)
    }

    /**
     * The bound is END TO END, not just the final await: with the backend's main executor parked,
     * the consumer can never finish draining — yet `stop()` returns exactly at
     * [ServerBackend.STOP_FLUSH_TIMEOUT_MS] of virtual time, the deadline having fired at the drain
     * stage (no `stop` token was ever sent), with the partial committed locally and no frame sent
     * after the bound.
     */
    @Test
    fun `a starved drain still ends at exactly the end-to-end bound`() = runTest {
        val executor = Executors.newSingleThreadExecutor()
        val mainExec = executor.asCoroutineDispatcher()
        val latch = CountDownLatch(1)
        try {
            val fakeWs = FakeWebSocketFactory()
            val sources = finitePcmSourceFactory(frames = 3)
            val backend = ServerBackend(
                webSocketFactory = fakeWs,
                serverUrl = "wss://example.ts.net:8444/",
                language = "en",
                mainDispatcher = mainExec,
                pcmSourceFactory = sources,
            )
            val finals: MutableList<FinalSegment> = Collections.synchronizedList(mutableListOf())
            val errors: MutableList<SttError> = Collections.synchronizedList(mutableListOf())
            backgroundScope.launch(Dispatchers.Unconfined) { backend.finals.collect { finals += it } }
            backgroundScope.launch(Dispatchers.Unconfined) { backend.errors.collect { errors += it } }

            val connected = async(mainExec) { backend.connect() }
            awaitReal("the socket to be created") { withContext(mainExec) { fakeWs.lastSocket != null } }
            fakeWs.fireOpen()
            fakeWs.fireMessage("""{"type":"ready"}""")
            assertTrue(connected.await())
            fakeWs.fireMessage("""{"type":"partial","text":"open words","segmentId":9}""")
            val socket = checkNotNull(fakeWs.lastSocket)
            awaitReal("every frame sent and the partial applied") {
                withContext(mainExec) {
                    socket.sentBinary.size == 3 && backend.partial.value == "open words"
                }
            }

            // Park the backend's main thread: the consumer's channel-close resumption cannot run.
            executor.execute { latch.await() }
            val start = testScheduler.currentTime

            backend.stop()

            assertEquals(
                "stop() must return exactly at the end-to-end bound",
                ServerBackend.STOP_FLUSH_TIMEOUT_MS,
                testScheduler.currentTime - start,
            )
            val log = synchronized(socket.sendLog) { socket.sendLog.toList() }
            assertFalse("the deadline fired at the drain stage — no token: $log", "text:stop" in log)
            assertTrue("the unflushed socket must be cancelled: $log", "cancel" in log)
            assertEquals(listOf(FinalSegment("open words", 9)), finals.toList())
            assertTrue("a requested stop must never error: $errors", errors.isEmpty())
            assertEquals(EngineState.Idle, backend.state.value)

            val sentAtBound = socket.sentBinary.size
            latch.countDown()
            // Let the un-parked executor run whatever was queued behind the latch.
            val sentAfter = withContext(mainExec) { socket.sentBinary.size }
            assertEquals("no frame may be sent after the bound", sentAtBound, sentAfter)
        } finally {
            latch.countDown()
            mainExec.close()
        }
    }

    /** Polls [condition] in REAL time (the starved case waits on a real executor, not the clock). */
    private suspend fun awaitReal(what: String, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000L
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(5)
        }
    }
}
