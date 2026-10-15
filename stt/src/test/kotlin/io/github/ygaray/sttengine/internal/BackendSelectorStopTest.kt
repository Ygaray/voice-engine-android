package io.github.ygaray.sttengine.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.FinalSegment
import io.github.ygaray.sttengine.PreflightHandle
import io.github.ygaray.sttengine.SttConfig
import io.github.ygaray.sttengine.SttError
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
    ) {
        val socket: FakeWebSocket get() = checkNotNull(fakeWs.lastSocket)
        fun log(): List<String> = synchronized(socket.sendLog) { socket.sendLog.toList() }
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    /** A selector committed to the server path (`ready` reached), with collectors on its flows. */
    private fun TestScope.committedServerSession(): Harness {
        val main = StandardTestDispatcher(testScheduler)
        val fakeWs = FakeWebSocketFactory()
        val fakeHandle = FakeRecognizerHandle()
        val selector = BackendSelector(
            appContext = context(),
            config = SttConfig(serverUrl = "wss://example.ts.net:8444/"),
            handleFactory = RecognizerHandleFactory {
                PreflightHandle(fakeHandle, LanguageAvailability.INSTALLED)
            },
            permissions = PermissionChecker { true },
            webSocketFactory = fakeWs,
            mainDispatcher = main,
            serverBackendFactory = { url ->
                ServerBackend(
                    webSocketFactory = fakeWs,
                    serverUrl = url,
                    language = "en",
                    mainDispatcher = main,
                    pcmSourceFactory = finitePcmSourceFactory(frames = 0),
                )
            },
        )
        val finals = mutableListOf<FinalSegment>()
        val errors = mutableListOf<SttError>()
        val states = mutableListOf<EngineState>()
        backgroundScope.launch(main) { selector.finals.collect { finals += it } }
        backgroundScope.launch(main) { selector.errors.collect { errors += it } }
        backgroundScope.launch(main) { selector.state.collect { states += it } }

        val started = launch(main) { selector.start() }
        runCurrent()
        fakeWs.fireOpen()
        runCurrent()
        fakeWs.fireMessage("""{"type":"ready"}""")
        runCurrent()
        assertTrue("start() must have returned once ready committed the server", started.isCompleted)
        assertEquals(Backend.Server, selector.backend.value)
        assertEquals(EngineState.Listening, selector.state.value)

        return Harness(main, fakeWs, fakeHandle, selector, finals, errors, states)
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
}
