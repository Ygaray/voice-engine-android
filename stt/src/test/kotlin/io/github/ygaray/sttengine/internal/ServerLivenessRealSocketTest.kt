package io.github.ygaray.sttengine.internal

import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.SttError
import io.github.ygaray.sttengine.SttErrorCode
import io.github.ygaray.sttengine.buildServerHttpClient
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LIVE-03 (D-11) over a REAL OkHttp socket: the client built by [buildServerHttpClient], with its
 * ping interval scaled down to [INTERVAL_MS], fails a peer that stops answering and leaves a healthy
 * peer alone. The production interval (10 s) is pinned separately by `ServerHttpClientTest`; this
 * proves that the interval is what actually ends the session, through the existing
 * `doOnFailure` -> `fail(SERVER_UNREACHABLE)` path with no new plumbing.
 *
 * Timing is asserted relative to the UPGRADE instant, because OkHttp starts its ping schedule when
 * the WebSocket is initialized, not when `ready` arrives: the first tick sends a ping, the next tick
 * finds it unanswered and fails the socket. A peer silent from the upgrade is therefore failed at
 * about two intervals after it. The ready, first-ping and Listening instants are printed next to it
 * and never asserted as a one-to-two-interval bound.
 *
 * Harness copied from [ServerBackendRealSocketTest]: real time, `runBlocking`, a single-thread
 * executor as `mainDispatcher`, Robolectric sdk 35 for `org.json`. Loopback and ephemeral ports
 * only; nothing here contacts the live server or any tailnet address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerLivenessRealSocketTest {

    private lateinit var executor: ExecutorService
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private val client: OkHttpClient =
        buildServerHttpClient().newBuilder().pingInterval(INTERVAL_MS, TimeUnit.MILLISECONDS).build()

    @Before
    fun setUp() {
        executor = Executors.newSingleThreadExecutor()
        mainDispatcher = executor.asCoroutineDispatcher()
    }

    @After
    fun tearDown() {
        mainDispatcher.close()
        executor.awaitTermination(2, TimeUnit.SECONDS)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    /** What the backend did, stamped with `System.nanoTime()` by collectors on its own dispatcher. */
    private class Observed {
        @Volatile var listeningAt: Long? = null
        @Volatile var errorAt: Long? = null
        val states: MutableList<EngineState> = Collections.synchronizedList(mutableListOf())
        val errors: MutableList<SttError> = Collections.synchronizedList(mutableListOf())
    }

    private fun CoroutineScope.observe(backend: ServerBackend): Observed {
        val seen = Observed()
        launch {
            backend.state.collect {
                val now = System.nanoTime()
                seen.states += it
                if (it == EngineState.Listening && seen.listeningAt == null) seen.listeningAt = now
            }
        }
        launch {
            backend.errors.collect {
                val now = System.nanoTime()
                seen.errors += it
                if (seen.errorAt == null) seen.errorAt = now
            }
        }
        return seen
    }

    private fun newBackend(url: String): ServerBackend = ServerBackend(
        webSocketFactory = OkHttpWebSocketFactory(client),
        serverUrl = url,
        language = "en",
        mainDispatcher = mainDispatcher,
        // Zero frames, parking (the default since plan 28-05): a live mic with nothing to say, so
        // the pump never ends the session and only the socket can.
        pcmSourceFactory = finitePcmSourceFactory(frames = 0),
    )

    private suspend fun awaitUntil(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            delay(5)
        }
    }

    private fun msSince(origin: Long, instant: Long?): String =
        if (instant == null) "not observed" else "%.1f ms".format((instant - origin) / 1e6)

    @Test
    fun `a peer that completes the upgrade and then answers no ping is failed as server-unreachable`() =
        runBlocking {
            val peer = RawSilentPeer(sendReady = true)
            val collectors = CoroutineScope(SupervisorJob() + mainDispatcher)
            try {
                withTimeout(15_000) {
                    val backend = newBackend(peer.url)
                    val seen = collectors.observe(backend)

                    val connected = withContext(mainDispatcher) { backend.connect() }
                    assertTrue("connect() must resolve true on the peer's ready", connected)

                    // Generous outer wait; the window itself is asserted below against the upgrade.
                    awaitUntil("the silent peer to be failed", timeoutMs = WINDOW_MAX_MS + 3_000L) {
                        seen.errorAt != null && backend.state.value == EngineState.Error
                    }

                    val upgradeAt = checkNotNull(peer.upgradeAt) { "the peer never completed the upgrade" }
                    val errorAt = checkNotNull(seen.errorAt)
                    println(
                        "LIVENESS interval=${INTERVAL_MS}ms relative to upgrade: " +
                            "upgrade=0.0 ms, ready=${msSince(upgradeAt, peer.readyAt)}, " +
                            "firstPing=${msSince(upgradeAt, peer.firstPingAt)}, " +
                            "listening=${msSince(upgradeAt, seen.listeningAt)}, " +
                            "failure=${msSince(upgradeAt, errorAt)}; " +
                            "failure relative to ready=${msSince(checkNotNull(peer.readyAt), errorAt)}, " +
                            "relative to listening=${msSince(checkNotNull(seen.listeningAt), errorAt)}",
                    )

                    assertEquals(listOf(SttErrorCode.SERVER_UNREACHABLE), seen.errors.map { it.code })
                    assertEquals(EngineState.Error, backend.state.value)
                    assertTrue("the session reached Listening first: ${seen.states}", seen.listeningAt != null)

                    val failureMs = (errorAt - upgradeAt) / 1_000_000
                    assertTrue(
                        "OkHttp cannot call a pong missing before its first ping: failed ${failureMs}ms " +
                            "after the upgrade, interval ${INTERVAL_MS}ms",
                        failureMs >= INTERVAL_MS,
                    )
                    assertTrue(
                        "the silent peer must be failed within two intervals plus ${SLACK_MS}ms of the " +
                            "upgrade: failed ${failureMs}ms after it",
                        failureMs <= WINDOW_MAX_MS,
                    )
                }
            } finally {
                collectors.cancel()
                peer.close()
            }
        }

    @Test
    fun `a peer that keeps answering pings is never failed`() = runBlocking {
        val server = MockWebServer()
        server.start(InetAddress.getLoopbackAddress(), 0)
        val collectors = CoroutineScope(SupervisorJob() + mainDispatcher)
        try {
            server.enqueue(
                MockResponse.Builder().webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (text.startsWith("{")) webSocket.send("""{"type":"ready"}""")
                        }
                    },
                ).build(),
            )
            val url = server.url("/").toString().replaceFirst("http", "ws")
            withTimeout(10_000) {
                val backend = newBackend(url)
                val seen = collectors.observe(backend)

                assertTrue(withContext(mainDispatcher) { backend.connect() })
                val listeningAt = System.nanoTime()

                // More than three intervals: a missed pong would have failed the socket by now.
                delay(HEALTHY_HOLD_MS)

                println(
                    "LIVENESS healthy peer: still ${backend.state.value} " +
                        "${(System.nanoTime() - listeningAt) / 1_000_000} ms after Listening",
                )
                assertEquals(EngineState.Listening, backend.state.value)
                assertTrue("a healthy peer must never be failed: ${seen.errors}", seen.errors.isEmpty())
                withContext(mainDispatcher) { backend.abandon() }
            }
        } finally {
            collectors.cancel()
            server.close()
        }
    }

    private companion object {
        /** The scaled ping interval. */
        const val INTERVAL_MS = 200L

        /** Scheduling slack on top of the two-interval bound (a loaded test JVM). */
        const val SLACK_MS = 1_000L

        /** Upper bound on failure time after the upgrade: two intervals plus [SLACK_MS]. */
        const val WINDOW_MAX_MS = 2 * INTERVAL_MS + SLACK_MS

        /** How long the healthy peer must keep the session Listening (more than three intervals). */
        const val HEALTHY_HOLD_MS = 700L
    }
}
