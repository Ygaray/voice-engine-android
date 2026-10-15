package io.github.ygaray.sttengine.internal

import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.FinalSegment
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
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Phase 21 tracer (SRV-05): the whole client half of the server path over a REAL OkHttp socket
 * against a local mockwebserver3 WebSocket server — connect → config → `ready` → pumped PCM frames →
 * `stop` token → the server's flushed final → the server's close 1000 → the client's close-1000
 * reply.
 *
 * Fakes prove policy; this proves the wire. The test never hands a frame to the backend itself:
 * every binary message the server records was read from the session's [PcmSource] by the pump.
 *
 * Real time throughout — `runBlocking` plus a dedicated single-thread executor as the backend's
 * `mainDispatcher` — because real socket callbacks arrive in real time and a virtual-clock
 * `withTimeoutOrNull` inside `stop()` would expire instantly. Robolectric (sdk 35) only because the
 * config frame is built with `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerBackendRealSocketTest {

    private lateinit var server: MockWebServer
    private lateinit var executor: ExecutorService
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private val client = OkHttpClient()

    /** Every message the SERVER received, in arrival order: `text:<payload>` / `binary:<size>`. */
    private val received: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Close codes the SERVER's listener observed from the client (onClosing then onClosed). */
    private val serverClosingCodes: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    private val serverClosedCodes: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        executor = Executors.newSingleThreadExecutor()
        mainDispatcher = executor.asCoroutineDispatcher()
    }

    @After
    fun tearDown() {
        server.close()
        mainDispatcher.close()
        executor.awaitTermination(2, TimeUnit.SECONDS)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    /** Answers the config frame with `ready`; on `stop`, flushes a final then closes 1000. */
    private fun enqueueServer() {
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received += "text:$text"
                        if (text.startsWith("{")) {
                            webSocket.send("""{"type":"ready"}""")
                        } else if (text == ServerBackend.STOP_TOKEN) {
                            webSocket.send("""{"type":"final","text":"tail","segmentId":4}""")
                            webSocket.close(1000, null)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        received += "binary:${bytes.size}"
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        serverClosingCodes += code
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        serverClosedCodes += code
                    }
                },
            ).build(),
        )
    }

    private fun wsUrl(): String = server.url("/").toString().replaceFirst("http", "ws")

    private fun newBackend(sources: FinitePcmSourceFactory): ServerBackend = ServerBackend(
        webSocketFactory = OkHttpWebSocketFactory(client),
        serverUrl = wsUrl(),
        language = "en",
        mainDispatcher = mainDispatcher,
        pcmSourceFactory = sources,
    )

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            delay(10)
        }
    }

    private fun binaryMessages(): List<String> =
        synchronized(received) { received.filter { it.startsWith("binary:") } }

    @Test
    fun `pumped PCM reaches the real server as whole 1024-byte binary frames after ready`() =
        runBlocking {
            withTimeout(10_000) {
                enqueueServer()
                val sources = finitePcmSourceFactory(frames = 10)
                val backend = newBackend(sources)

                val connected = withContext(mainDispatcher) { backend.connect() }
                assertTrue("connect() must resolve true on the server's ready", connected)

                awaitUntil("10 binary frames at the server") { binaryMessages().size >= 10 }
                // Give a stray 11th frame a chance to show up before asserting "exactly".
                delay(100)

                assertEquals(List(10) { "binary:1024" }, binaryMessages())
                withContext(mainDispatcher) { backend.abandon() }
            }
        }

    @Test
    fun `stop sends the token after the last frame, emits the flushed final and replies close 1000`() =
        runBlocking {
            withTimeout(10_000) {
                enqueueServer()
                val sources = finitePcmSourceFactory(frames = 10)
                val backend = newBackend(sources)
                val finals: MutableList<FinalSegment> = Collections.synchronizedList(mutableListOf())
                val collectorScope = CoroutineScope(SupervisorJob() + mainDispatcher)
                collectorScope.launch { backend.finals.collect { finals += it } }

                try {
                    assertTrue(withContext(mainDispatcher) { backend.connect() })
                    // The pump has read every frame; whether each one has reached the server yet is
                    // deliberately NOT awaited — stop() itself must drain them ahead of the token.
                    awaitUntil("the source to be exhausted") { sources.last.exhausted }

                    val startedNs = System.nanoTime()
                    withContext(mainDispatcher) { backend.stop() }
                    val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000

                    assertTrue(
                        "stop() must return inside the flush bound, took ${elapsedMs}ms",
                        elapsedMs < ServerBackend.STOP_FLUSH_TIMEOUT_MS,
                    )

                    awaitUntil("the flushed final on finals") { finals.isNotEmpty() }
                    delay(100)
                    assertEquals(listOf(FinalSegment("tail", 4)), finals.toList())
                    assertEquals(EngineState.Idle, backend.state.value)

                    val log = synchronized(received) { received.toList() }
                    val stopIndex = log.indexOf("text:${ServerBackend.STOP_TOKEN}")
                    val lastBinary = log.indexOfLast { it.startsWith("binary:") }
                    assertTrue("server never saw the stop token: $log", stopIndex >= 0)
                    assertEquals("every pumped frame must reach the server", 10, binaryMessages().size)
                    assertTrue(
                        "stop token ($stopIndex) must follow the last binary frame ($lastBinary): $log",
                        stopIndex > lastBinary,
                    )

                    // The client answered the server's close 1000 (OkHttp does not auto-reply).
                    awaitUntil("the server to see the client's close reply") {
                        serverClosingCodes.isNotEmpty() || serverClosedCodes.isNotEmpty()
                    }
                    assertTrue(
                        "server must observe the client's close 1000: closing=$serverClosingCodes " +
                            "closed=$serverClosedCodes",
                        (serverClosingCodes + serverClosedCodes).contains(1000),
                    )
                } finally {
                    collectorScope.cancel()
                }
            }
        }
}
