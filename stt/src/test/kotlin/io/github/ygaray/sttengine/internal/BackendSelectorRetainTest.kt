package io.github.ygaray.sttengine.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.LanguageAvailability
import io.github.ygaray.sttengine.PreflightHandle
import io.github.ygaray.sttengine.SttConfig
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CORR-06 through the selector's DEFAULT `serverBackendFactory` (no factory override): the host's
 * `SttConfig.retainForCorpus` must reach the first config frame on the wire, and a default config
 * must leave that frame string-equal to the frozen legacy builder's output. A knob that exists on
 * the config but is never threaded into the default factory would pass every `SttConfig` test and
 * still retain nothing, which is the same Pitfall `SttEngineFactoryTryWindowTest` guards for the
 * try window.
 *
 * The session is never committed (no `ready`), so no microphone source is ever opened: the test
 * reads the first frame after `fireOpen()` and then ends the try window with `stop()`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class BackendSelectorRetainTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    /** The first text frame the selector's default factory put on the socket after `onOpen`. */
    private fun TestScope.firstFrameFor(config: SttConfig): String {
        val main = StandardTestDispatcher(testScheduler)
        val fakeWs = FakeWebSocketFactory()
        val fakeHandle = FakeRecognizerHandle()
        val selector = BackendSelector(
            appContext = context(),
            config = config,
            handleFactory = RecognizerHandleFactory {
                PreflightHandle(fakeHandle, LanguageAvailability.INSTALLED)
            },
            permissions = PermissionChecker { true },
            webSocketFactory = fakeWs,
            mainDispatcher = main,
            tryServerTimeoutMs = 5_000L,
            // serverBackendFactory deliberately NOT overridden: the DEFAULT factory is under test.
        )
        launch(main) { selector.start() }
        runCurrent()
        assertEquals("attempt 1 must be in flight", 1, fakeWs.createCount)
        fakeWs.fireOpen()
        runCurrent()
        val socket = checkNotNull(fakeWs.lastSocket)
        val first = synchronized(socket.sendLog) { socket.sendLog.first() }
        assertTrue("expected a text frame, got $first", first.startsWith("text:"))

        // End the try window so the test leaves nothing suspended.
        launch(main) { selector.stop() }
        runCurrent()
        return first.removePrefix("text:")
    }

    @Test
    fun `retainForCorpus true reaches the first config frame through the default factory`() = runTest {
        val frame = firstFrameFor(SttConfig(serverUrl = "wss://example.ts.net:8444/", retainForCorpus = true))

        val json = JSONObject(frame)
        assertTrue("config frame must carry the opt-in: $frame", json.has("retainForCorpus"))
        assertEquals(true, json.getBoolean("retainForCorpus"))
        assertEquals("en", json.getString("language"))
    }

    @Test
    fun `the default config leaves the first frame string-equal to the frozen legacy builder`() = runTest {
        val frame = firstFrameFor(SttConfig(serverUrl = "wss://example.ts.net:8444/"))

        assertEquals(io.github.ygaray.sttengine.legacy.buildConfigFrame("en"), frame)
        assertTrue(!JSONObject(frame).has("retainForCorpus"))
    }

    @Test
    fun `lastSessionId is null before any session`() = runTest {
        val selector = BackendSelector(
            appContext = context(),
            config = SttConfig(serverUrl = "wss://example.ts.net:8444/", retainForCorpus = true),
            handleFactory = RecognizerHandleFactory {
                PreflightHandle(FakeRecognizerHandle(), LanguageAvailability.INSTALLED)
            },
            permissions = PermissionChecker { true },
            webSocketFactory = FakeWebSocketFactory(),
            mainDispatcher = StandardTestDispatcher(testScheduler),
        )
        assertNull(selector.lastSessionId)
    }

    // --- CORR-07: the lastSessionId lifecycle through a retained ServerBackend (plan 25-08) -------
    //
    // The rows above go through the selector's DEFAULT factory; the rows below inject a factory that
    // builds the ServerBackend with `retainForCorpus = true` over a finite (empty) PCM source, so the
    // session reaches `ready` without opening a microphone. The id is the fixed literal [ID].

    /**
     * Records `selector.lastSessionId` each time the selector's state reads Idle, but only after
     * [arm] (called just before `stop()` is launched). The selector's state starts at Idle, and a
     * StateFlow replays its current value on subscription, so an unarmed helper discards that
     * replayed Idle and every other one seen before the stop began.
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
        val fakeHandle: FakeRecognizerHandle,
        val selector: BackendSelector,
        val idle: IdleSnapshots,
    ) {
        val socket: FakeWebSocket get() = checkNotNull(fakeWs.lastSocket)
        fun log(): List<String> = synchronized(socket.sendLog) { socket.sendLog.toList() }
    }

    private fun readyFrame(sessionId: String?): String =
        if (sessionId == null) """{"type":"ready"}""" else """{"type":"ready","sessionId":"$sessionId"}"""

    /**
     * A selector whose server backends are built with [retain] and the (empty) finite PCM source,
     * nothing started yet. The Idle-snapshot collector is subscribed here, before `start()`, on an
     * [UnconfinedTestDispatcher] so it runs inline inside the selector's own `setState` call.
     */
    private fun TestScope.session(
        retain: Boolean = true,
        prefer: io.github.ygaray.sttengine.BackendPreference = io.github.ygaray.sttengine.BackendPreference.Auto,
    ): Harness {
        val main = StandardTestDispatcher(testScheduler)
        val fakeWs = FakeWebSocketFactory()
        val fakeHandle = FakeRecognizerHandle()
        val pcm = finitePcmSourceFactory(frames = 0)
        val selector = BackendSelector(
            appContext = context(),
            config = SttConfig(serverUrl = "wss://example.ts.net:8444/", retainForCorpus = retain, prefer = prefer),
            handleFactory = RecognizerHandleFactory {
                PreflightHandle(fakeHandle, LanguageAvailability.INSTALLED)
            },
            permissions = PermissionChecker { true },
            webSocketFactory = fakeWs,
            mainDispatcher = main,
            tryServerTimeoutMs = 5_000L,
            serverBackendFactory = { url ->
                ServerBackend(
                    webSocketFactory = fakeWs,
                    serverUrl = url,
                    language = "en",
                    mainDispatcher = main,
                    pcmSourceFactory = pcm,
                    retainForCorpus = retain,
                )
            },
        )
        val idle = IdleSnapshots { selector.lastSessionId }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            selector.state.collect { idle.onState(it) }
        }
        return Harness(main, fakeWs, fakeHandle, selector, idle)
    }

    /** A selector committed to the server path (its first accepted `ready` is [ready]). */
    private fun TestScope.committedServerSession(
        ready: String = readyFrame(ID),
        retain: Boolean = true,
    ): Harness {
        val h = session(retain = retain)
        val started = launch(h.main) { h.selector.start() }
        runCurrent()
        h.fakeWs.fireOpen()
        runCurrent()
        h.fakeWs.fireMessage(ready)
        runCurrent()
        assertTrue("start() must have returned once ready committed the server", started.isCompleted)
        assertEquals(Backend.Server, h.selector.backend.value)
        assertEquals(EngineState.Listening, h.selector.state.value)
        return h
    }

    /** Arms the Idle snapshots, launches `selector.stop()` and runs until the stop token is sent. */
    private fun TestScope.stopUntilTokenSent(h: Harness): Job {
        h.idle.arm()
        val stopJob = launch(h.main) { h.selector.stop() }
        runCurrent()
        assertTrue("the stop token must be on the wire: ${h.log()}", "text:stop" in h.log())
        assertFalse("stop() must still be awaiting the server's flush", stopJob.isCompleted)
        return stopJob
    }

    // NOTE: this proves the id is readable once the selector reports Idle. The selector's state
    // forwarder runs on a standard dispatcher, so it cannot discriminate the ORDER of "assign the id"
    // versus "set Idle" inside ServerBackend.stop(); that ordering is proven by ServerBackendSessionIdTest.
    @Test
    fun `the id is readable once the selector reports Idle after a flushed stop`() = runTest {
        val h = committedServerSession()
        assertNull("a live session has no id yet", h.selector.lastSessionId)

        val stopJob = stopUntilTokenSent(h)
        assertNull("an in-flight stop has no id yet", h.selector.lastSessionId)
        assertTrue("no Idle may be observed while the flush is awaited", h.idle.seen.isEmpty())

        h.fakeWs.fireClosing(1000)
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertEquals(EngineState.Idle, h.selector.state.value)
        assertEquals(
            "the id must be there at the first moment the selector reports Idle",
            listOf<String?>(ID),
            h.idle.seen,
        )
        assertEquals(ID, h.selector.lastSessionId)
    }

    @Test
    fun `a flush timeout makes the selector report Idle with a null id`() = runTest {
        val h = committedServerSession()

        val stopJob = stopUntilTokenSent(h)
        advanceTimeBy(ServerBackend.STOP_FLUSH_TIMEOUT_MS)
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertEquals(EngineState.Idle, h.selector.state.value)
        assertEquals("Idle is reported once, with no id", listOf<String?>(null), h.idle.seen)
        assertNull(h.selector.lastSessionId)
    }

    @Test
    fun `the selector idle snapshot helper ignores the replayed initial Idle and everything before stop`() = runTest {
        // Subscribed before start(): the StateFlow replayed its initial Idle to the collector, and
        // the helper must not have counted it.
        val h = session()
        runCurrent()
        assertEquals(EngineState.Idle, h.selector.state.value)
        assertFalse(h.idle.armed)
        assertTrue("the replayed initial Idle must not be recorded", h.idle.seen.isEmpty())

        val started = launch(h.main) { h.selector.start() }
        runCurrent()
        h.fakeWs.fireOpen()
        runCurrent()
        h.fakeWs.fireMessage(readyFrame(ID))
        runCurrent()
        assertTrue(started.isCompleted)
        assertTrue("nothing is recorded until stop() is called", h.idle.seen.isEmpty())

        val stopJob = stopUntilTokenSent(h)
        h.fakeWs.fireClosing(1000)
        runCurrent()
        assertTrue(stopJob.isCompleted)
        assertEquals("only the Idle after the stop began is recorded", listOf<String?>(ID), h.idle.seen)
    }

    // --- the lifecycle matrix: null on every path except a flushed stop of a retained session ------

    private val errorFrame = """{"type":"error","code":"internal-error","message":"boom"}"""

    @Test
    fun `a live server session reads a null lastSessionId`() = runTest {
        val h = committedServerSession()
        h.fakeWs.fireMessage("""{"type":"partial","text":"hello","segmentId":1}""")
        runCurrent()

        assertNull("the id exists only after a flushed stop", h.selector.lastSessionId)
    }

    @Test
    fun `lastSessionId reads the id once a clean stop returns`() = runTest {
        val h = committedServerSession()

        val stopJob = stopUntilTokenSent(h)
        h.fakeWs.fireClosing(1000)
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertEquals(ID, h.selector.lastSessionId)
    }

    @Test
    fun `an in-flight stop reads a null lastSessionId until the server's close arrives`() = runTest {
        val h = committedServerSession()

        val stopJob = stopUntilTokenSent(h)
        advanceTimeBy(ServerBackend.STOP_FLUSH_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse("the stop is still flushing", stopJob.isCompleted)
        assertNull("the detached backend has not finished its flush", h.selector.lastSessionId)

        h.fakeWs.fireClosing(1000)
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertEquals(ID, h.selector.lastSessionId)
    }

    @Test
    fun `a flush timeout leaves lastSessionId null after stop returns`() = runTest {
        val h = committedServerSession()

        val stopJob = stopUntilTokenSent(h)
        advanceTimeBy(ServerBackend.STOP_FLUSH_TIMEOUT_MS)
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertNull(h.selector.lastSessionId)
    }

    @Test
    fun `an abnormal close during the stop flush leaves lastSessionId null`() = runTest {
        val h = committedServerSession()

        val stopJob = stopUntilTokenSent(h)
        h.fakeWs.fireClosing(1011, "internal")
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertNull(h.selector.lastSessionId)
    }

    @Test
    fun `a ready without a sessionId leaves lastSessionId null after a clean stop`() = runTest {
        val h = committedServerSession(ready = readyFrame(null))

        val stopJob = stopUntilTokenSent(h)
        h.fakeWs.fireClosing(1000)
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertNull(h.selector.lastSessionId)
    }

    @Test
    fun `an unsolicited sessionId on a non-retained selector session is never exposed`() = runTest {
        val h = committedServerSession(retain = false)

        val stopJob = stopUntilTokenSent(h)
        h.fakeWs.fireClosing(1000)
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertNull(h.selector.lastSessionId)
    }

    @Test
    fun `a second start clears the previous session's id`() = runTest {
        val h = committedServerSession()
        val firstStop = stopUntilTokenSent(h)
        h.fakeWs.fireClosing(1000)
        runCurrent()
        assertTrue(firstStop.isCompleted)
        assertEquals(ID, h.selector.lastSessionId)

        val again = launch(h.main) { h.selector.start() }
        runCurrent()
        assertEquals("a start() after stop() returned opens a fresh attempt", 2, h.fakeWs.createCount)
        assertNull("the new session must not inherit the previous id", h.selector.lastSessionId)

        h.fakeWs.fireOpen()
        runCurrent()
        h.fakeWs.fireMessage(readyFrame(OTHER_ID))
        runCurrent()
        assertTrue(again.isCompleted)
        assertNull("the second session is live, so there is no id yet", h.selector.lastSessionId)

        val secondStop = launch(h.main) { h.selector.stop() }
        runCurrent()
        h.fakeWs.fireClosing(1000)
        runCurrent()
        assertTrue(secondStop.isCompleted)
        assertEquals(OTHER_ID, h.selector.lastSessionId)
    }

    @Test
    fun `a server failure mid-session that hands off to native reads a null lastSessionId`() = runTest {
        val h = committedServerSession()

        h.fakeWs.fireMessage(errorFrame)
        runCurrent()
        assertEquals("the failure must have handed the session off to native", 1, h.fakeHandle.startCount)
        assertNull("a handed-off session never ran a flushed stop", h.selector.lastSessionId)

        val stopJob = launch(h.main) { h.selector.stop() }
        runCurrent()
        assertTrue(stopJob.isCompleted)
        assertNull("stopping the native fallback must not surface an id either", h.selector.lastSessionId)
    }

    @Test
    fun `a stop during the try window reads a null lastSessionId`() = runTest {
        val h = session()
        val started = launch(h.main) { h.selector.start() }
        runCurrent()
        assertEquals("attempt 1 must be in flight", 1, h.fakeWs.createCount)
        assertEquals(EngineState.Connecting, h.selector.state.value)

        h.idle.arm()
        val stopJob = launch(h.main) { h.selector.stop() }
        runCurrent()

        assertTrue(stopJob.isCompleted)
        assertTrue(started.isCompleted)
        assertEquals(EngineState.Idle, h.selector.state.value)
        assertNull(h.selector.lastSessionId)
        assertEquals("Idle is reported with no id", listOf<String?>(null), h.idle.seen)
    }

    @Test
    fun `a session that fell back to native under Auto reads a null lastSessionId`() = runTest {
        val h = session()
        launch(h.main) { h.selector.start() }
        runCurrent()
        h.fakeWs.fireFailure(java.net.ConnectException("refused"))
        runCurrent()
        assertEquals("the refused attempt falls back to native", 1, h.fakeHandle.startCount)
        h.fakeHandle.reachListening()
        runCurrent()
        assertEquals(Backend.Native, h.selector.backend.value)
        assertNull(h.selector.lastSessionId)

        val stopJob = launch(h.main) { h.selector.stop() }
        runCurrent()
        assertTrue(stopJob.isCompleted)
        assertNull(h.selector.lastSessionId)
    }

    @Test
    fun `a failed try under prefer Server reads a null lastSessionId`() = runTest {
        val h = session(prefer = io.github.ygaray.sttengine.BackendPreference.Server)
        launch(h.main) { h.selector.start() }
        runCurrent()
        h.fakeWs.fireFailure(java.net.ConnectException("refused"))
        runCurrent()

        assertEquals("prefer = Server never falls back to native", 0, h.fakeHandle.startCount)
        assertEquals(EngineState.Error, h.selector.state.value)
        assertNull(h.selector.lastSessionId)
    }

    private companion object {
        /** The fixed 32-lowercase-hex id used by every row. */
        const val ID = "0123456789abcdef0123456789abcdef"

        /** A different well-formed id, used for the second session of the restart row. */
        const val OTHER_ID = "fedcba9876543210fedcba9876543210"
    }
}
