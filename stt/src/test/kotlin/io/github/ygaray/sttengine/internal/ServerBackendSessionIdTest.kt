package io.github.ygaray.sttengine.internal

import io.github.ygaray.sttengine.EngineState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CORR-07 at the backend (D-04 refined by D-17(c), D-09): `ServerBackend.endedSessionId` is the
 * corpus session id and it exists only after a `stop()` that observed the server's clean close 1000
 * after the flush, on a retained session whose first accepted `ready` carried an id. Every other path
 * reads null. The server attempts the corpus commit strictly before that close, so the observed
 * close is the earliest moment the record can exist.
 *
 * Idle ordering (review round 1): the id is published BEFORE the backend reports
 * [EngineState.Idle]. That is what makes "wait for Idle, then read the id" race-free for a host, the
 * demo dump and the live procedure. The Idle rows therefore snapshot the id AT the moment Idle is
 * observed, from a collector on [UnconfinedTestDispatcher] so it runs inline inside the backend's
 * own `setState` call. A collector on the same standard dispatcher as the backend would run only
 * after `stop()` had finished and would read the id even if the assignment came after `setState`.
 *
 * Harness: [StandardTestDispatcher] as `mainDispatcher` (virtual time drives
 * [ServerBackend.STOP_FLUSH_TIMEOUT_MS]); every hop is scheduled explicitly with `runCurrent()`.
 * Robolectric (sdk 35) only because the config frame is built with `org.json`. The id is the fixed
 * literal [ID], never a random value, so a failure reproduces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class ServerBackendSessionIdTest {

    /**
     * Records the id each time Idle is observed, but only after [arm] (called just before `stop()`
     * is launched). Everything observed earlier is discarded, so a replayed or pre-stop Idle can
     * never satisfy a row that asserts "the first Idle after the stop began".
     */
    private class IdleSnapshots(private val read: () -> String?) {
        @Volatile
        var armed = false
            private set
        val seen = mutableListOf<String?>()

        fun arm() {
            armed = true
        }

        fun onState(state: EngineState) {
            if (armed && state == EngineState.Idle) seen += read()
        }
    }

    private class Harness(
        val main: CoroutineDispatcher,
        val fakeWs: FakeWebSocketFactory,
        val backend: ServerBackend,
        val pcm: FinitePcmSourceFactory,
        val idle: IdleSnapshots,
    ) {
        val socket: FakeWebSocket get() = checkNotNull(fakeWs.lastSocket)
        fun log(): List<String> = synchronized(socket.sendLog) { socket.sendLog.toList() }
    }

    private fun readyFrame(sessionId: String?): String =
        if (sessionId == null) """{"type":"ready"}""" else """{"type":"ready","sessionId":"$sessionId"}"""

    private fun awaitExhausted(sources: FinitePcmSourceFactory) {
        val deadline = System.currentTimeMillis() + 2_000L
        while (sources.opened.isEmpty() || !sources.last.exhausted) {
            check(System.currentTimeMillis() < deadline) { "the pump never exhausted the source" }
            Thread.sleep(5)
        }
    }

    /**
     * A backend connected through `open`, with the Idle-snapshot collector subscribed from the start.
     * [ready] is the `ready` frame delivered (null = none yet, so the backend stays connecting);
     * [retain] is the `retainForCorpus` constructor flag. When [ready] is delivered the harness
     * waits until the (empty) finite source is exhausted and the pump has drained.
     */
    private fun TestScope.harness(ready: String? = readyFrame(ID), retain: Boolean = true): Harness {
        val main = StandardTestDispatcher(testScheduler)
        val fakeWs = FakeWebSocketFactory()
        val pcm = finitePcmSourceFactory(frames = 0)
        val backend = ServerBackend(
            webSocketFactory = fakeWs,
            serverUrl = "wss://example.ts.net:8444/",
            language = "en",
            mainDispatcher = main,
            pcmSourceFactory = pcm,
            retainForCorpus = retain,
        )
        val idle = IdleSnapshots { backend.endedSessionId }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            backend.state.collect { idle.onState(it) }
        }
        backgroundScope.launch(main) { backend.connect() }
        runCurrent()
        fakeWs.fireOpen()
        runCurrent()
        if (ready != null) {
            fakeWs.fireMessage(ready)
            runCurrent()
            assertEquals(EngineState.Listening, backend.state.value)
            awaitExhausted(pcm)
            runCurrent()
        }
        return Harness(main, fakeWs, backend, pcm, idle)
    }

    /** Launches `stop()` (arming the Idle snapshots first) and runs until the stop token is sent. */
    private fun TestScope.stopUntilTokenSent(h: Harness): Job {
        h.idle.arm()
        val stopJob = launch(h.main) { h.backend.stop() }
        runCurrent()
        assertTrue("the stop token must be on the wire: ${h.log()}", "text:stop" in h.log())
        assertFalse("stop() must still be awaiting the server's flush", stopJob.isCompleted)
        return stopJob
    }

    // --- Task 1: the id is published before Idle -----------------------------------------------

    @Test
    fun `the id is published before Idle after a clean flushed stop`() = runTest {
        val h = harness()
        assertNull("a live session has no id yet", h.backend.endedSessionId)

        val stopJob = stopUntilTokenSent(h)
        assertNull("an in-flight stop has no id yet", h.backend.endedSessionId)
        assertTrue("no Idle may be observed while the flush is awaited", h.idle.seen.isEmpty())

        h.fakeWs.fireClosing(1000)
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertEquals(EngineState.Idle, h.backend.state.value)
        assertEquals(
            "the id must already be there at the first moment Idle is observed",
            listOf<String?>(ID),
            h.idle.seen,
        )
        assertEquals(ID, h.backend.endedSessionId)
    }

    @Test
    fun `a flush timeout reports Idle with a null id`() = runTest {
        val h = harness()

        val stopJob = stopUntilTokenSent(h)
        advanceTimeBy(ServerBackend.STOP_FLUSH_TIMEOUT_MS)
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertEquals(EngineState.Idle, h.backend.state.value)
        assertEquals("Idle is reported once, with no id", listOf<String?>(null), h.idle.seen)
        assertNull(h.backend.endedSessionId)
    }

    @Test
    fun `the idle snapshot helper ignores every Idle seen before the stop began`() = runTest {
        // Subscribed before connect, driven through ready: the collector has seen Connecting and
        // Listening, and the helper has recorded nothing and is not armed.
        val h = harness()
        assertFalse(h.idle.armed)
        assertTrue("nothing is recorded until stop() is called", h.idle.seen.isEmpty())

        // An Idle delivered while unarmed is not counted, even when it carries a readable id.
        val unarmed = IdleSnapshots { ID }
        unarmed.onState(EngineState.Idle)
        assertTrue("an Idle seen before the stop began must not be recorded", unarmed.seen.isEmpty())
        unarmed.arm()
        unarmed.onState(EngineState.Listening)
        assertTrue("armed, a non-Idle state is still not recorded", unarmed.seen.isEmpty())
        unarmed.onState(EngineState.Idle)
        assertEquals(listOf<String?>(ID), unarmed.seen)

        // And through the real collector: only the Idle after arm() is the one that counts.
        val stopJob = stopUntilTokenSent(h)
        h.fakeWs.fireClosing(1000)
        runCurrent()
        assertTrue(stopJob.isCompleted)
        assertEquals(listOf<String?>(ID), h.idle.seen)
    }

    // --- Task 2: the full lifecycle matrix -------------------------------------------------------

    private val errorFrame = """{"type":"error","code":"internal-error","message":"boom"}"""

    @Test
    fun `a live session has no id, even with partials and finals flowing`() = runTest {
        val h = harness()
        h.fakeWs.fireMessage("""{"type":"partial","text":"hello","segmentId":1}""")
        h.fakeWs.fireMessage("""{"type":"final","text":"hello there","segmentId":1}""")
        runCurrent()

        assertEquals(EngineState.Listening, h.backend.state.value)
        assertNull("the id exists only after a flushed stop", h.backend.endedSessionId)
    }

    @Test
    fun `a clean stop with the server's close 1000 after the flush publishes the id`() = runTest {
        val h = harness()

        val stopJob = stopUntilTokenSent(h)
        h.fakeWs.fireClosing(1000)
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertEquals(ID, h.backend.endedSessionId)
    }

    @Test
    fun `a flush timeout publishes no id`() = runTest {
        val h = harness()

        val stopJob = stopUntilTokenSent(h)
        advanceTimeBy(ServerBackend.STOP_FLUSH_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse("stop() must wait out the whole bound", stopJob.isCompleted)
        assertNull(h.backend.endedSessionId)

        advanceTimeBy(1)
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertTrue("the unflushed socket must be cancelled: ${h.log()}", "cancel" in h.log())
        assertNull("a flush that never saw the server's close publishes nothing", h.backend.endedSessionId)
    }

    @Test
    fun `an abnormal close during the stop flush publishes no id`() = runTest {
        val h = harness()

        val stopJob = stopUntilTokenSent(h)
        h.fakeWs.fireClosing(1011, "internal error")
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertNull(h.backend.endedSessionId)
    }

    @Test
    fun `a socket failure during the stop flush publishes no id`() = runTest {
        val h = harness()

        val stopJob = stopUntilTokenSent(h)
        h.fakeWs.fireFailure(java.io.IOException("reset"))
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertNull(h.backend.endedSessionId)
    }

    @Test
    fun `an error frame mid-session then stop publishes no id`() = runTest {
        val h = harness()
        h.fakeWs.fireMessage(errorFrame)
        runCurrent()
        assertEquals(EngineState.Error, h.backend.state.value)

        h.idle.arm()
        val stopJob = launch(h.main) { h.backend.stop() }
        runCurrent()

        assertTrue("stop() on a failed instance returns at once", stopJob.isCompleted)
        assertNull("a failed session is never published", h.backend.endedSessionId)
        assertTrue("a failed instance never reports Idle", h.idle.seen.isEmpty())
    }

    @Test
    fun `an error frame during the stop flush publishes no id`() = runTest {
        val h = harness()

        val stopJob = stopUntilTokenSent(h)
        h.fakeWs.fireMessage(errorFrame)
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertNull(h.backend.endedSessionId)
        assertEquals(EngineState.Idle, h.backend.state.value)
    }

    @Test
    fun `a stop before any ready publishes no id even after a clean close`() = runTest {
        val h = harness(ready = null)

        val stopJob = stopUntilTokenSent(h)
        h.fakeWs.fireClosing(1000)
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertNull("no ready, so no id was ever carried", h.backend.endedSessionId)
        assertEquals(listOf<String?>(null), h.idle.seen)
    }

    @Test
    fun `a ready that arrives after the stop began is ignored and publishes no id`() = runTest {
        val h = harness(ready = null)

        val stopJob = stopUntilTokenSent(h)
        h.fakeWs.fireMessage(readyFrame(ID))
        runCurrent()
        assertTrue("a late ready must never open the microphone", h.pcm.opened.isEmpty())
        h.fakeWs.fireClosing(1000)
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertNull(h.backend.endedSessionId)
    }

    @Test
    fun `a retained session whose ready carries no sessionId publishes no id`() = runTest {
        val h = harness(ready = readyFrame(null))

        val stopJob = stopUntilTokenSent(h)
        h.fakeWs.fireClosing(1000)
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertNull("an older or unarmed server sends no id", h.backend.endedSessionId)
        assertEquals(listOf<String?>(null), h.idle.seen)
    }

    @Test
    fun `a retained session whose ready carries an empty sessionId publishes no id`() = runTest {
        val h = harness(ready = readyFrame(""))

        val stopJob = stopUntilTokenSent(h)
        h.fakeWs.fireClosing(1000)
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertNull("an empty string is no claim", h.backend.endedSessionId)
    }

    @Test
    fun `an unsolicited sessionId on a non-retained session is ignored`() = runTest {
        val h = harness(retain = false)

        val stopJob = stopUntilTokenSent(h)
        h.fakeWs.fireClosing(1000)
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertNull("a session that did not ask for retention never exposes an id", h.backend.endedSessionId)
        assertEquals(listOf<String?>(null), h.idle.seen)
    }

    @Test
    fun `a repeated ready with a different id keeps the first id`() = runTest {
        val h = harness()
        h.fakeWs.fireMessage(readyFrame(OTHER_ID))
        runCurrent()
        assertEquals("the repeat is ignored, the session stays healthy", EngineState.Listening, h.backend.state.value)

        val stopJob = stopUntilTokenSent(h)
        h.fakeWs.fireClosing(1000)
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertEquals(ID, h.backend.endedSessionId)
    }

    @Test
    fun `a second stop after the clean one keeps the published id`() = runTest {
        val h = harness()

        val first = stopUntilTokenSent(h)
        h.fakeWs.fireClosing(1000)
        runCurrent()
        assertTrue(first.isCompleted)

        val second = launch(h.main) { h.backend.stop() }
        runCurrent()

        assertTrue(second.isCompleted)
        assertEquals(ID, h.backend.endedSessionId)
    }

    private companion object {
        /** The fixed 32-lowercase-hex id used by every row. */
        const val ID = "0123456789abcdef0123456789abcdef"

        /** A different well-formed id, used only to prove the first one wins. */
        const val OTHER_ID = "fedcba9876543210fedcba9876543210"
    }
}
