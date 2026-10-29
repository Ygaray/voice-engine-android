package io.github.ygaray.sttengine.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.FallbackReason
import io.github.ygaray.sttengine.LanguageAvailability
import io.github.ygaray.sttengine.PreflightHandle
import io.github.ygaray.sttengine.SttConfig
import io.github.ygaray.sttengine.SttError
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LIVE-01 (Phase 28, D-13) at the selector: a committed server session whose pump ends unasked
 * hands off to native SILENTLY (no host error, `state` never Error) and reads
 * [FallbackReason.DROPPED]; a requested `stop()` never starts native; and the fail-versus-pump-end
 * race hands off exactly once whichever event lands first.
 *
 * The thread hop is the whole point (the pump thread posts its end onto main), so this runs on a
 * REAL single-thread executor as `mainDispatcher` — never an unconfined test dispatcher, which
 * would run the hop inline on the pump thread and pass for the wrong reason. Test-driven
 * recognizer callbacks hop onto that executor with `withContext`. Robolectric (sdk 35) for the
 * native fallback's real `Context`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackendSelectorPumpDeathTest {

    private lateinit var executor: ExecutorService
    private lateinit var main: CoroutineDispatcher

    @Before
    fun setUp() {
        executor = Executors.newSingleThreadExecutor { r -> Thread(r, "selector-main").apply { isDaemon = true } }
        main = executor.asCoroutineDispatcher()
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private class Harness(
        val selector: BackendSelector,
        val fakeWs: FakeWebSocketFactory,
        val fakeHandle: FakeRecognizerHandle,
        val errors: MutableList<SttError>,
        val states: MutableList<EngineState>,
        private val recorders: CoroutineScope,
    ) {
        fun close() = recorders.cancel()
    }

    private fun harness(pcm: PcmSourceFactory): Harness {
        val fakeWs = FakeWebSocketFactory()
        val fakeHandle = FakeRecognizerHandle()
        val selector = BackendSelector(
            appContext = context(),
            config = SttConfig(serverUrl = SERVER_URL),
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
        val errors: MutableList<SttError> = Collections.synchronizedList(mutableListOf())
        val states: MutableList<EngineState> = Collections.synchronizedList(mutableListOf())
        val recorders = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        recorders.launch { selector.errors.collect { errors += it } }
        recorders.launch { selector.state.collect { states += it } }
        return Harness(selector, fakeWs, fakeHandle, errors, states, recorders)
    }

    private suspend fun awaitOnMain(what: String, timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!withContext(main) { condition() }) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            delay(20)
        }
    }

    /** Starts the selector and commits it to the server (open + ready). */
    private suspend fun CoroutineScope.commitServer(h: Harness) {
        val started = async(Dispatchers.Default) { h.selector.start() }
        awaitOnMain("the first socket") { h.fakeWs.lastSocket != null }
        h.fakeWs.fireOpen()
        h.fakeWs.fireMessage("""{"type":"ready"}""")
        started.await()
        // start() returns once the server is committed; its forwarded Listening still has to hop
        // through the state collector on the executor.
        awaitOnMain("the selector to forward the server's Listening") {
            h.selector.state.value == EngineState.Listening
        }
        assertEquals(Backend.Server, h.selector.backend.value)
    }

    /** `stop()` bounded by a future: it is non-cancellable by design, so a coroutine timeout cannot. */
    private fun stopWithin(selector: BackendSelector, timeoutMs: Long = 5_000L) {
        val done = CompletableFuture<Unit>()
        CoroutineScope(Dispatchers.Unconfined).launch {
            try {
                selector.stop()
                done.complete(Unit)
            } catch (t: Throwable) {
                done.completeExceptionally(t)
            }
        }
        runCatching { done.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `a committed server session whose pump ends unasked hands off to native once, silently, reading DROPPED`() {
        val h = harness(finitePcmSourceFactory(frames = 3, endsStream = true))
        try {
            runBlocking {
                withTimeout(15_000) {
                    commitServer(h)

                    awaitOnMain("the dead pump to start native") { h.fakeHandle.startCount == 1 }
                    withContext(main) { h.fakeHandle.reachListening() }
                    awaitOnMain("the handoff to reach native Listening") {
                        h.selector.backend.value == Backend.Native
                    }
                    delay(300) // a second handoff would show up here

                    assertEquals("exactly one native start", 1, h.fakeHandle.startCount)
                    assertEquals(FallbackReason.DROPPED, h.selector.fallbackReason.value)
                    assertTrue("the handoff is silent: ${h.errors}", h.errors.isEmpty())
                    assertFalse("state never reads Error: ${h.states}", h.states.contains(EngineState.Error))
                    assertEquals(EngineState.Listening, h.selector.state.value)
                }
            }
        } finally {
            stopWithin(h.selector)
            h.close()
        }
    }

    @Test
    fun `a requested stop on a live mic never starts native and reads no fallback reason`() {
        val pcm = finitePcmSourceFactory(frames = 2) // parks: a live, silent mic
        val h = harness(pcm)
        try {
            runBlocking {
                withTimeout(15_000) {
                    commitServer(h)
                    val socket = checkNotNull(h.fakeWs.lastSocket)
                    socket.onTextSent = { text ->
                        if (text == ServerBackend.STOP_TOKEN) h.fakeWs.fireClosing(1000, "", socket)
                    }
                    awaitOnMain("the pump to be parked") { pcm.opened.isNotEmpty() && pcm.last.exhausted }

                    h.selector.stop()
                    delay(300)

                    assertEquals("a requested stop never starts native", 0, h.fakeHandle.startCount)
                    assertNull(h.selector.fallbackReason.value)
                    assertTrue(h.errors.isEmpty())
                    assertEquals(EngineState.Idle, h.selector.state.value)
                    assertEquals(Backend.Server, h.selector.backend.value)
                }
            }
        } finally {
            stopWithin(h.selector)
            h.close()
        }
    }

    /** See `ServerBackendPumpTest`'s race: the executor is held by a blocker task, so the queue
     *  order of the socket failure and the pump-end callback is the run order. */
    private fun selectorRace(pumpEndFirst: Boolean) {
        val gate = CountDownLatch(1)
        val (source, factory) = gatedEndPcmSourceFactory(gate)
        val h = harness(factory)
        try {
            runBlocking {
                withTimeout(15_000) {
                    commitServer(h)
                    val deadline = System.currentTimeMillis() + 5_000
                    while (source.readerThread == null) {
                        check(System.currentTimeMillis() < deadline) { "the pump never started reading" }
                        delay(10)
                    }
                    val pump = checkNotNull(source.readerThread)

                    val blocker = CountDownLatch(1)
                    executor.execute { blocker.await() }
                    try {
                        if (pumpEndFirst) {
                            gate.countDown()
                            pump.join(5_000)
                            assertFalse(pump.isAlive)
                            h.fakeWs.fireFailure(IOException("connection reset"))
                        } else {
                            h.fakeWs.fireFailure(IOException("connection reset"))
                            gate.countDown()
                            pump.join(5_000)
                            assertFalse(pump.isAlive)
                        }
                    } finally {
                        blocker.countDown()
                    }

                    awaitOnMain("the handoff to start native") { h.fakeHandle.startCount >= 1 }
                    delay(300) // a second handoff would show up here
                    assertEquals("exactly one handoff, never two", 1, h.fakeHandle.startCount)
                    assertTrue("the handoff is silent: ${h.errors}", h.errors.isEmpty())
                    assertFalse("state never reads Error: ${h.states}", h.states.contains(EngineState.Error))
                    assertEquals(1, source.releaseCount)
                }
            }
        } finally {
            gate.countDown()
            stopWithin(h.selector)
            h.close()
        }
    }

    @Test
    fun `fail first, then the pump end - the selector hands off exactly once`() = selectorRace(pumpEndFirst = false)

    @Test
    fun `pump end first, then fail - the selector hands off exactly once`() = selectorRace(pumpEndFirst = true)

    private companion object {
        const val SERVER_URL = "wss://example.ts.net:8444/"
        const val WINDOW_MS = 5_000L
    }
}
