package io.github.ygaray.sttengine.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.BackendPreference
import io.github.ygaray.sttengine.PreflightHandle
import io.github.ygaray.sttengine.SttConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `BackendSelector.start()`'s re-entry guard under REAL concurrency (21-W46 cycle 2, finding B).
 *
 * The public contract never confines callers to the main thread (D-09), so two host coroutines on
 * two threads can call `start()` at the same instant. The guard is a check-and-install; done in the
 * caller's own thread both callers could pass the check before either installs its job, and each
 * would run its own try-server loop. The fix runs the check-and-install on the selector's
 * single-threaded `mainDispatcher`.
 *
 * Harness: a real single-thread executor stands in for the main thread, and two real caller threads
 * are released together by a spin barrier. Each launches `start()` on `Dispatchers.Unconfined`, so
 * whatever `start()` does before its first suspension runs on the CALLER's thread — exactly the
 * off-main caller the contract allows. `prefer = Server` keeps the native recognizer out of it, and
 * each round's attempt simply waits on a socket that never answers until `stop()` abandons it.
 *
 * Fails fast: a round is asserted the moment the main thread has drained, the loop ends at the first
 * double start, every wait is bounded, the session log is disabled, and the whole test is capped at
 * [ROUNDS] rounds or [BUDGET_MS], whichever comes first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackendSelectorStartRaceTest {

    private lateinit var mainExecutor: ExecutorService
    private lateinit var main: CoroutineDispatcher
    private lateinit var callers: ExecutorService

    @Before
    fun setUp() {
        mainExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "selector-main").apply { isDaemon = true } }
        main = mainExecutor.asCoroutineDispatcher()
        callers = Executors.newFixedThreadPool(2) { r -> Thread(r, "selector-caller").apply { isDaemon = true } }
    }

    @After
    fun tearDown() {
        callers.shutdownNow()
        mainExecutor.shutdownNow()
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    /** Runs every task queued on the main executor so far, [times] times over (each pass may queue more). */
    private fun drainMain(times: Int = 1) {
        repeat(times) { mainExecutor.submit {}.get(DRAIN_MS, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `two concurrent off-main start calls run exactly one try-server loop`() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUDGET_MS)
        var rounds = 0
        while (rounds < ROUNDS && System.nanoTime() < deadline) {
            rounds += 1
            val loops = AtomicInteger()
            val fakeWs = FakeWebSocketFactory()
            val selector = BackendSelector(
                appContext = context(),
                config = SttConfig(serverUrl = SERVER_URL, prefer = BackendPreference.Server),
                handleFactory = RecognizerHandleFactory {
                    PreflightHandle(FakeRecognizerHandle(), LanguageAvailability.INSTALLED)
                },
                permissions = PermissionChecker { true },
                webSocketFactory = fakeWs,
                mainDispatcher = main,
                tryServerTimeoutMs = WINDOW_MS,
                serverBackendFactory = { url ->
                    loops.incrementAndGet()
                    ServerBackend(
                        webSocketFactory = fakeWs,
                        serverUrl = url,
                        language = "en",
                        mainDispatcher = main,
                        pcmSourceFactory = finitePcmSourceFactory(frames = 0),
                    )
                },
            )
            val callerScope = CoroutineScope(SupervisorJob())
            val arrived = AtomicInteger()
            val go = AtomicBoolean(false)
            try {
                val calls = List(2) {
                    callers.submit {
                        arrived.incrementAndGet()
                        // Spin, so both callers are released as close together as possible (bounded).
                        val spinUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_MS)
                        while (!go.get() && System.nanoTime() < spinUntil) { /* spin */ }
                        callerScope.launch(Dispatchers.Unconfined) { selector.start() }
                    }
                }
                val arriveBy = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_MS)
                while (arrived.get() < 2 && System.nanoTime() < arriveBy) { /* both callers on the spin */ }
                go.set(true)
                calls.forEach { it.get(DRAIN_MS, TimeUnit.MILLISECONDS) }

                // Let every queued check, install and runStart() reach its first suspension.
                drainMain(times = 4)
                assertTrue("round $rounds: no start() reached the try-server loop", loops.get() >= 1)
                assertEquals(
                    "round $rounds: two concurrent start() calls must run exactly one try-server loop",
                    1,
                    loops.get(),
                )
            } finally {
                runBlocking { withTimeout(DRAIN_MS) { selector.stop() } }
                callerScope.cancel()
            }
        }
        assertTrue("the race must actually be exercised", rounds >= MIN_ROUNDS)
    }

    private companion object {
        const val SERVER_URL = "wss://example.ts.net:8444/"
        /** Long enough that no window expires inside a round; `stop()` ends every attempt. */
        const val WINDOW_MS = 10_000L
        const val ROUNDS = 400
        const val MIN_ROUNDS = 20
        const val BUDGET_MS = 20_000L
        const val DRAIN_MS = 5_000L
    }
}
