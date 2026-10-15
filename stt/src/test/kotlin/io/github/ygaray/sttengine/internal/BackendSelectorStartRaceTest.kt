package io.github.ygaray.sttengine.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.BackendPreference
import io.github.ygaray.sttengine.LanguageAvailability
import io.github.ygaray.sttengine.PreflightHandle
import io.github.ygaray.sttengine.SttConfig
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * double start, and the session log is disabled. The whole test runs under ONE deadline
 * ([BUDGET_MS]): every wait — the spin barrier, the callers, each main-thread drain and each
 * round's `stop()` — is bounded by the time left on it, and a round only begins with at least
 * [ROUND_RESERVE_MS] left. It ends after [ROUNDS] rounds or at that reserve, whichever comes first.
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

    /** The one deadline the whole test runs under; every wait is bounded by what is left of it. */
    private class Deadline(budgetMs: Long) {
        private val endNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        val endNanoTime: Long get() = endNanos
        fun remainingMs(): Long = maxOf(0L, TimeUnit.NANOSECONDS.toMillis(endNanos - System.nanoTime()))
        fun expired(): Boolean = System.nanoTime() >= endNanos
    }

    /** Runs every task queued on the main executor so far, [times] times over (each pass may queue
     *  more), within what is left of [deadline]. */
    private fun drainMain(deadline: Deadline, times: Int = 1) {
        repeat(times) { mainExecutor.submit {}.get(deadline.remainingMs(), TimeUnit.MILLISECONDS) }
    }

    /**
     * `stop()` within what is left of [deadline]; `false` if it did not return in time. `stop()` is
     * non-cancellable by design (a cancelled caller must still tear down), so a coroutine timeout
     * cannot bound it — the wait is on a future instead.
     */
    private fun stopWithin(selector: BackendSelector, deadline: Deadline): Boolean {
        val done = CompletableFuture<Unit>()
        CoroutineScope(Dispatchers.Unconfined).launch {
            try {
                selector.stop()
                done.complete(Unit)
            } catch (t: Throwable) {
                done.completeExceptionally(t)
            }
        }
        return try {
            done.get(deadline.remainingMs(), TimeUnit.MILLISECONDS)
            true
        } catch (timeout: TimeoutException) {
            false
        }
    }

    @Test
    fun `two concurrent off-main start calls run exactly one try-server loop`() {
        val deadline = Deadline(BUDGET_MS)
        var rounds = 0
        var stopTimedOut = false
        // A round only begins with ROUND_RESERVE_MS still left, so its waits have a real bound and
        // the whole test — rounds, waits and stops — ends inside BUDGET_MS.
        while (rounds < ROUNDS && !stopTimedOut && deadline.remainingMs() > ROUND_RESERVE_MS) {
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
                        // Spin, so both callers are released as close together as possible
                        // (bounded by the test's deadline).
                        while (!go.get() && System.nanoTime() < deadline.endNanoTime) { /* spin */ }
                        callerScope.launch(Dispatchers.Unconfined) { selector.start() }
                    }
                }
                while (arrived.get() < 2 && !deadline.expired()) { /* both callers on the spin */ }
                go.set(true)
                calls.forEach { it.get(deadline.remainingMs(), TimeUnit.MILLISECONDS) }

                // Let every queued check, install and runStart() reach its first suspension.
                drainMain(deadline, times = 4)
                assertTrue("round $rounds: no start() reached the try-server loop", loops.get() >= 1)
                assertEquals(
                    "round $rounds: two concurrent start() calls must run exactly one try-server loop",
                    1,
                    loops.get(),
                )
            } finally {
                stopTimedOut = !stopWithin(selector, deadline)
                callerScope.cancel()
            }
        }
        assertFalse("round $rounds: stop() must return inside the test's deadline", stopTimedOut)
        assertTrue("the race must actually be exercised", rounds >= MIN_ROUNDS)
    }

    private companion object {
        const val SERVER_URL = "wss://example.ts.net:8444/"
        /** Long enough that no window expires inside a round; `stop()` ends every attempt. */
        const val WINDOW_MS = 10_000L
        const val ROUNDS = 400
        const val MIN_ROUNDS = 20
        /** The whole test's deadline: no wait, round or stop can run past it. */
        const val BUDGET_MS = 25_000L
        /** No new round begins with less than this left (a healthy round takes milliseconds). */
        const val ROUND_RESERVE_MS = 8_000L
    }
}
