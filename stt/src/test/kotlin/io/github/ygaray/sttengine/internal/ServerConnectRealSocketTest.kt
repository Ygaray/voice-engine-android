package io.github.ygaray.sttengine.internal

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
 * TRY-02 (D-06) over a REAL OkHttp 5.2.1 socket: which exception, response code, close code or error
 * frame each failure shape actually produces, and how [classifyConnectFailure] classifies the
 * [ServerBackend.connectObservation] it leaves behind. Fakes prove the selector's policy; this proves
 * the classifier's inputs are what OkHttp really raises.
 *
 * Harness copied from [ServerBackendRealSocketTest]: real time, `runBlocking`, a single-thread
 * executor as the backend's `mainDispatcher`, Robolectric sdk 35 for `org.json`. The server binds to
 * `127.0.0.1` and the client dials that literal address, so OkHttp has exactly one route and cannot
 * turn an EOF into a second-route `ConnectException`.
 *
 * A real-socket TLS-verification case is deliberately absent: building a test certificate needs
 * `okhttp-tls`, which is not in the version catalog. `ServerConnectFailureTest` pins the TLS rule
 * with the documented JDK, Conscrypt and OkHttp exception shapes instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerConnectRealSocketTest {

    private lateinit var server: MockWebServer
    private lateinit var executor: ExecutorService
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName(LOOPBACK), 0)
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

    private fun serverUrl(): String = "ws://$LOOPBACK:${server.port}/"

    private fun newBackend(url: String): ServerBackend = ServerBackend(
        webSocketFactory = OkHttpWebSocketFactory(client),
        serverUrl = url,
        language = "en",
        mainDispatcher = mainDispatcher,
        pcmSourceFactory = finitePcmSourceFactory(frames = 0),
    )

    /** `connect()` on the backend's own dispatcher, bounded so a missing resolution fails the
     *  assertion (`null`) instead of hanging the suite. */
    private suspend fun connect(backend: ServerBackend, boundMs: Long = 5_000L): Boolean? =
        withTimeoutOrNull(boundMs) { withContext(mainDispatcher) { backend.connect() } }

    private suspend fun observe(backend: ServerBackend): ConnectObservation =
        withContext(mainDispatcher) { backend.connectObservation }

    private fun enqueueUpgrade(listener: WebSocketListener) {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(listener).build())
    }

    @Test
    fun `a refused connect is a ConnectException and fast-fails`() = runBlocking {
        withTimeout(10_000) {
            val port = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { it.localPort }
            val backend = newBackend("ws://$LOOPBACK:$port/")

            assertEquals(false, connect(backend))
            val observation = observe(backend)
            assertTrue(
                "expected ConnectException, got ${observation.throwable}",
                observation.throwable is ConnectException,
            )
            assertFalse(observation.sawOpen)
            assertEquals(ConnectFailureClass.FastFail, classifyConnectFailure(observation))
        }
    }

    @Test
    fun `an unknown host fast-fails`() = runBlocking {
        withTimeout(10_000) {
            val backend = newBackend("ws://stt-test-host.invalid/")

            assertEquals(false, connect(backend, boundMs = 8_000L))
            val observation = observe(backend)
            // UnknownHostException from the resolver, or ConnectException where a resolver answers
            // with an address nothing listens on — both are pre-answer, both fast-fail.
            assertTrue(
                "expected UnknownHostException or ConnectException, got ${observation.throwable}",
                observation.throwable is UnknownHostException || observation.throwable is ConnectException,
            )
            assertEquals(ConnectFailureClass.FastFail, classifyConnectFailure(observation))
        }
    }

    @Test
    fun `a 503 upgrade answer carries the response code and is retryable`() = runBlocking {
        withTimeout(10_000) {
            server.enqueue(MockResponse.Builder().code(503).build())
            val backend = newBackend(serverUrl())

            assertEquals(false, connect(backend))
            val observation = observe(backend)
            assertEquals(503, observation.responseCode)
            assertFalse(observation.sawOpen)
            assertEquals(ConnectFailureClass.Retryable, classifyConnectFailure(observation))
        }
    }

    @Test
    fun `EOF at request start is an IOException before open and is retryable`() = runBlocking {
        withTimeout(10_000) {
            // Enqueued more than once: should OkHttp recover by retrying the route, every retry
            // meets the same EOF instead of blocking on an empty queue.
            repeat(3) {
                server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
            }
            val backend = newBackend(serverUrl())

            assertEquals(false, connect(backend))
            val observation = observe(backend)
            assertTrue(
                "expected an IOException, got ${observation.throwable}",
                observation.throwable is IOException,
            )
            assertFalse(
                "a cold-start EOF must not look like a connect-phase failure",
                observation.throwable is ConnectException,
            )
            assertFalse(observation.sawOpen)
            assertNull(observation.responseCode)
            assertEquals(ConnectFailureClass.Retryable, classifyConnectFailure(observation))
        }
    }

    @Test
    fun `a busy close 1013 after open fast-fails`() = runBlocking {
        withTimeout(10_000) {
            enqueueUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.close(1013, "busy")
                    }
                },
            )
            val backend = newBackend(serverUrl())

            assertEquals(false, connect(backend))
            val observation = observe(backend)
            assertEquals(1013, observation.closeCode)
            assertTrue(observation.sawOpen)
            assertEquals(ConnectFailureClass.FastFail, classifyConnectFailure(observation))
        }
    }

    @Test
    fun `a config reject error frame before ready fast-fails`() = runBlocking {
        withTimeout(10_000) {
            enqueueUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.send("""{"type":"error","code":"unsupported-language","message":"x"}""")
                        webSocket.close(1008, null)
                    }
                },
            )
            val backend = newBackend(serverUrl())

            assertEquals(false, connect(backend))
            val observation = observe(backend)
            assertEquals("unsupported-language", observation.serverErrorCode)
            assertTrue(observation.sawOpen)
            assertEquals(ConnectFailureClass.FastFail, classifyConnectFailure(observation))
        }
    }

    @Test
    fun `a normal close 1000 before ready resolves connect promptly and is retryable`() = runBlocking {
        withTimeout(10_000) {
            enqueueUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.close(1000, null)
                    }
                },
            )
            val backend = newBackend(serverUrl())

            val startedNs = System.nanoTime()
            // Before this plan a pre-ready 1000 was "an ordinary teardown" and connect() hung until
            // the caller's window — bounded at 2 s here, so a regression reads as `null`.
            val connected = connect(backend, boundMs = 2_000L)
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000

            assertEquals(false, connected)
            assertTrue("connect() must resolve well before 2 s, took ${elapsedMs}ms", elapsedMs < 2_000L)
            val observation = observe(backend)
            assertEquals(1000, observation.closeCode)
            assertTrue(observation.sawOpen)
            assertEquals(ConnectFailureClass.Retryable, classifyConnectFailure(observation))
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}
