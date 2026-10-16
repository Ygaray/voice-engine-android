package io.github.ygaray.sttengine.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.CorrectionSubmitResult
import io.github.ygaray.sttengine.LanguageAvailability
import io.github.ygaray.sttengine.PreflightHandle
import io.github.ygaray.sttengine.SttConfig
import io.github.ygaray.sttengine.submitCorrection
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Phase 25 client tracer (CORR-06 + CORR-07 + CORR-08): the whole client round trip over REAL
 * sockets, with nothing between the opt-in and the correction faked.
 *
 * ```
 * SttConfig(retainForCorpus = true) -> BackendSelector's DEFAULT server factory -> config frame
 *   -> server `ready` carrying a sessionId -> clean stop (close 1000) -> selector.lastSessionId
 *   -> submitCorrection(...) -> POST /corpus/<id>/revisions -> 201 -> ACCEPTED
 * ```
 *
 * One local mockwebserver3 server answers both halves: the WebSocket upgrade for the dictation
 * session and then the HTTP correction. The server path's microphone is replaced through
 * [DebugAudioSourceHolder.factory] (restored afterwards) so a finite source ends the pump.
 *
 * Real time throughout (`runBlocking` plus a single-thread executor as the selector's main
 * dispatcher): real socket callbacks arrive in real time and the flush wait in `stop()` is a real
 * timeout. Robolectric (sdk 35) only because the config frame and the body use `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CorpusClientRoundTripTest {

    private lateinit var server: MockWebServer
    private lateinit var executor: ExecutorService
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private val client = OkHttpClient()
    private val sessionId = "0123456789abcdef0123456789abcdef"
    private var previousFactory: PcmSourceFactory? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        executor = Executors.newSingleThreadExecutor()
        mainDispatcher = executor.asCoroutineDispatcher()
        previousFactory = DebugAudioSourceHolder.factory
    }

    @After
    fun tearDown() {
        DebugAudioSourceHolder.factory = previousFactory
        server.close()
        mainDispatcher.close()
        executor.awaitTermination(2, TimeUnit.SECONDS)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    /** First the WebSocket upgrade (ready with an id, close 1000 on stop), then the 201 for the POST. */
    private fun enqueueSessionThenCorrection() {
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.startsWith("{")) {
                            webSocket.send("""{"type":"ready","sessionId":"$sessionId"}""")
                        } else if (text == ServerBackend.STOP_TOKEN) {
                            webSocket.close(1000, null)
                        }
                    }
                },
            ).build(),
        )
        server.enqueue(MockResponse.Builder().code(201).body("""{"ok":true,"revision":1}""").build())
    }

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            delay(10)
        }
    }

    @Test
    fun `a retained session's id flows from ready through lastSessionId into an ACCEPTED correction`() =
        runBlocking {
            withTimeout(20_000) {
                enqueueSessionThenCorrection()
                val context: Context = ApplicationProvider.getApplicationContext()
                val pcm = finitePcmSourceFactory(frames = 4)
                DebugAudioSourceHolder.factory = pcm

                val wsUrl = server.url("/").toString().replaceFirst("http", "ws")
                val selector = BackendSelector(
                    appContext = context,
                    config = SttConfig(serverUrl = wsUrl, retainForCorpus = true),
                    handleFactory = RecognizerHandleFactory {
                        PreflightHandle(FakeRecognizerHandle(), LanguageAvailability.INSTALLED)
                    },
                    permissions = PermissionChecker { true },
                    webSocketFactory = OkHttpWebSocketFactory(client),
                    mainDispatcher = mainDispatcher,
                    // serverBackendFactory deliberately NOT overridden: the DEFAULT factory is under test.
                )

                selector.start()
                awaitUntil("the pump to read the finite source") { pcm.opened.isNotEmpty() && pcm.last.exhausted }
                selector.stop()

                val id = selector.lastSessionId
                assertEquals("the retained id must be readable after a clean stop", sessionId, id)

                val httpBase = server.url("/SttBatch").toString()
                val result = submitCorrection(context, httpBase, checkNotNull(id), "fixed text", "en")
                assertEquals(CorrectionSubmitResult.ACCEPTED, result)

                // Request 1 is the WebSocket upgrade (GET); request 2 is the correction.
                val upgrade = server.takeRequest()
                assertEquals("GET", upgrade.method)
                val post = server.takeRequest()
                assertEquals("POST", post.method)
                assertEquals("POST /SttBatch/corpus/$sessionId/revisions HTTP/1.1", post.requestLine)
                val body = JSONObject(checkNotNull(post.body).utf8())
                val keys = body.keys().asSequence().toSet()
                assertEquals(setOf("corrected", "language"), keys)
                assertEquals("fixed text", body.getString("corrected"))
                assertEquals("en", body.getString("language"))
                assertNotNull(post.headers["Content-Type"])
                assertTrue(post.headers["Content-Type"]!!.startsWith("application/json"))
            }
        }
}
