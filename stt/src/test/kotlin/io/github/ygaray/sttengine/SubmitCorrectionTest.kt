package io.github.ygaray.sttengine

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.internal.CorpusCorrections
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The `submitCorrection` transport matrix (Phase 25, CORR-08; D-05, D-10, D-16).
 *
 * The default-client rows call the PUBLIC [submitCorrection] against a local mockwebserver3 server;
 * the injected-client rows call [CorpusCorrections] directly. The no-replay guarantee is asserted by
 * CONFIGURATION (the `client configuration` rows and their negative controls); the dropped-response
 * row is behavioral evidence only. Every row also feeds the fixed marker below, and an `@After`
 * asserts it never reaches `android.util.Log`.
 *
 * Robolectric (sdk 35) for `org.json` and `ShadowLog`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubmitCorrectionTest {

    private lateinit var server: MockWebServer
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val id = "0123456789abcdef0123456789abcdef"
    private val marker = "corrected-text-marker-7f3a"
    private val text = "fixed text $marker"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @After
    fun correctedTextWasNeverLogged() {
        val leaked = ShadowLog.getLogs().filter { it.msg?.contains(marker) == true || it.throwable?.message?.contains(marker) == true }
        assertTrue("the corrected text must never reach the log: $leaked", leaked.isEmpty())
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun base(path: String = "/SttBatch"): String = "http://${server.hostName}:${server.port}$path"

    private fun enqueue(code: Int) {
        server.enqueue(MockResponse.Builder().code(code).body("""{"ok":true,"revision":1}""").build())
    }

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(30_000) { block() } }

    private fun submit(
        baseUrl: String = base(),
        sessionId: String = id,
        corrected: String = text,
        language: String? = "en",
    ): CorrectionSubmitResult = blocking { submitCorrection(context, baseUrl, sessionId, corrected, language) }

    private fun nonEmptySegments(url: okhttp3.HttpUrl): List<String> = url.pathSegments.filter { it.isNotEmpty() }

    /** The parsed request body, asserting the invariant that the client never sends `createdAt`. */
    private fun bodyOf(request: RecordedRequest): JSONObject {
        val json = JSONObject(checkNotNull(request.body).utf8())
        assertFalse("the client must never send createdAt (the server stamps it)", json.has("createdAt"))
        return json
    }

    private fun keysOf(json: JSONObject): Set<String> = json.keys().asSequence().toSet()

    /** The configuration that makes a replay or a redirect re-POST impossible. Fails on a plain client. */
    private fun assertNoReplayConfiguration(client: OkHttpClient) {
        assertFalse("retryOnConnectionFailure must be off", client.retryOnConnectionFailure)
        assertFalse("followRedirects must be off", client.followRedirects)
        assertFalse("followSslRedirects must be off", client.followSslRedirects)
        assertTrue(
            "callTimeoutMillis must be a real bound (1..30000), was ${client.callTimeoutMillis}",
            client.callTimeoutMillis in 1..30_000,
        )
    }

    /** A request body that OkHttp can never transparently resend, carrying JSON. */
    private fun assertOneShotJson(body: RequestBody) {
        assertTrue("the body must be one-shot", body.isOneShot())
        val type = body.contentType()
        assertNotNull(type)
        assertEquals("application/json", "${type!!.type}/${type.subtype}")
    }

    // --- statuses --------------------------------------------------------------------------------

    @Test
    fun `every status class maps to its result`() {
        val expected = listOf(
            201 to CorrectionSubmitResult.ACCEPTED,
            200 to CorrectionSubmitResult.ACCEPTED,
            404 to CorrectionSubmitResult.UNKNOWN_SESSION,
            400 to CorrectionSubmitResult.REJECTED,
            409 to CorrectionSubmitResult.REJECTED,
            413 to CorrectionSubmitResult.REJECTED,
            401 to CorrectionSubmitResult.REJECTED,
            408 to CorrectionSubmitResult.UNREACHABLE,
            500 to CorrectionSubmitResult.UNREACHABLE,
            503 to CorrectionSubmitResult.UNREACHABLE,
        )
        for ((status, result) in expected) {
            enqueue(status)
            assertEquals("status $status", result, submit())
        }
        assertEquals("one request per call", expected.size, server.requestCount)
    }

    @Test
    fun `redirect not followed 302 with a Location is UNREACHABLE and the target is never requested`() {
        val target = server.url("/elsewhere").toString()
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", target).build())
        // A followed redirect would consume this and bump the count to 2.
        enqueue(201)

        assertEquals(CorrectionSubmitResult.UNREACHABLE, submit())

        assertEquals(1, server.requestCount)
        assertEquals("POST", server.takeRequest().method)
    }

    // --- transport failures ----------------------------------------------------------------------

    @Test
    fun `connection refused is UNREACHABLE`() {
        val deadBase = base()
        server.close()

        assertEquals(CorrectionSubmitResult.UNREACHABLE, submit(baseUrl = deadBase))
    }

    @Test
    fun `stall against a short call timeout is UNREACHABLE`() {
        server.enqueue(MockResponse.Builder().code(201).onResponseStart(SocketEffect.Stall).build())
        val client = CorpusCorrections.DEFAULT_CLIENT.newBuilder().callTimeout(400, TimeUnit.MILLISECONDS).build()

        val result = blocking { CorpusCorrections(client).submit(base(), id, text, "en") }

        assertEquals(CorrectionSubmitResult.UNREACHABLE, result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `dropped response closed after the request is read is UNREACHABLE with exactly one request`() {
        server.enqueue(MockResponse.Builder().code(201).onResponseStart(SocketEffect.CloseSocket()).build())
        // Had the client replayed the POST, this would have answered it (ACCEPTED, requestCount 2).
        enqueue(201)

        assertEquals(CorrectionSubmitResult.UNREACHABLE, submit())

        assertEquals("no replay: the POST went out exactly once", 1, server.requestCount)
    }

    // --- request shape ---------------------------------------------------------------------------

    private fun assertShapeForBase(baseUrl: String, basePrefix: List<String>) {
        enqueue(201)

        assertEquals(CorrectionSubmitResult.ACCEPTED, submit(baseUrl = baseUrl))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        // Compared after the TEST drops empty segments in its own normalization: root, no-slash and
        // trailing-slash bases must all give the base's non-empty segments plus one clean suffix.
        assertEquals(basePrefix + listOf("corpus", id, "revisions"), nonEmptySegments(request.url))
        assertFalse("no empty or doubled segment on the wire: ${request.requestLine}", request.requestLine.contains("//"))
        assertTrue(checkNotNull(request.headers["Content-Type"]).startsWith("application/json"))
        val body = bodyOf(request)
        assertEquals(setOf("corrected", "language"), keysOf(body))
        assertEquals(text, body.getString("corrected"))
        assertEquals("en", body.getString("language"))
    }

    @Test
    fun `root base yields one clean suffix`() =
        assertShapeForBase("http://${server.hostName}:${server.port}", emptyList())

    @Test
    fun `no-slash base yields one clean suffix`() = assertShapeForBase(base("/SttBatch"), listOf("SttBatch"))

    @Test
    fun `trailing-slash base yields one clean suffix`() = assertShapeForBase(base("/SttBatch/"), listOf("SttBatch"))

    @Test
    fun `without a language the body is exactly corrected`() {
        enqueue(201)

        assertEquals(CorrectionSubmitResult.ACCEPTED, submit(language = null))

        val body = bodyOf(server.takeRequest())
        assertEquals(setOf("corrected"), keysOf(body))
        assertEquals(text, body.getString("corrected"))
    }

    @Test
    fun `unchecked language is sent unchanged`() {
        enqueue(201)

        assertEquals(CorrectionSubmitResult.ACCEPTED, submit(language = "fr"))

        assertEquals("fr", bodyOf(server.takeRequest()).getString("language"))
    }

    @Test
    fun `unicode quotes and newlines round-trip intact`() {
        val awkward = "héllo \"quoted\" \\ back\nline two\ttab 日本語 😀 / slash $marker"
        enqueue(201)

        assertEquals(CorrectionSubmitResult.ACCEPTED, submit(corrected = awkward))

        assertEquals(awkward, bodyOf(server.takeRequest()).getString("corrected"))
    }

    @Test
    fun `never sends createdAt even when the corrected text mentions it`() {
        enqueue(201)
        enqueue(201)

        assertEquals(CorrectionSubmitResult.ACCEPTED, submit(corrected = "the createdAt field is server stamped $marker", language = null))
        assertEquals(CorrectionSubmitResult.ACCEPTED, submit(corrected = "createdAt", language = "en"))

        val first = JSONObject(checkNotNull(server.takeRequest().body).utf8())
        val second = JSONObject(checkNotNull(server.takeRequest().body).utf8())
        for (json in listOf(first, second)) {
            assertFalse("createdAt must not be a key", json.has("createdAt"))
        }
        assertEquals(setOf("corrected"), keysOf(first))
        assertEquals(setOf("corrected", "language"), keysOf(second))
        assertEquals("the createdAt field is server stamped $marker", first.getString("corrected"))
    }

    // --- local refusals: zero requests -----------------------------------------------------------

    @Test
    fun `blank corrected text and blank id are REJECTED without a request`() {
        for (blank in listOf("", " ", "\n\t ")) {
            assertEquals(CorrectionSubmitResult.REJECTED, submit(corrected = blank))
            assertEquals(CorrectionSubmitResult.REJECTED, submit(sessionId = blank))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `dot segment ids are REJECTED without a request`() {
        assertEquals(CorrectionSubmitResult.REJECTED, submit(sessionId = "."))
        assertEquals(CorrectionSubmitResult.REJECTED, submit(sessionId = ".."))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `malformed base URL is REJECTED without a request`() {
        assertEquals(CorrectionSubmitResult.REJECTED, submit(baseUrl = "not a url"))
        assertEquals(CorrectionSubmitResult.REJECTED, submit(baseUrl = ""))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `hostile id is percent-encoded into exactly one segment`() {
        val base = base()
        for (hostile in listOf("a/b", "a?b#c", "%2e%2e")) {
            enqueue(201)

            assertEquals("id $hostile", CorrectionSubmitResult.ACCEPTED, submit(baseUrl = base, sessionId = hostile))

            val request = server.takeRequest()
            assertEquals(
                "id $hostile",
                listOf("SttBatch", "corpus", hostile, "revisions"),
                nonEmptySegments(request.url),
            )
            assertNull("no query for id $hostile", request.url.query)
            assertFalse("no fragment on the wire for id $hostile", checkNotNull(request.requestLine).contains('#'))
        }
        assertEquals(3, server.requestCount)
    }

    // --- client configuration, asserted directly -------------------------------------------------

    @Test
    fun `client configuration forbids replay and redirects and bounds the call`() {
        assertNoReplayConfiguration(CorpusCorrections.DEFAULT_CLIENT)
    }

    @Test
    fun `one-shot body is what the transport builds`() {
        assertOneShotJson(CorpusCorrections.requestBody(text, "en"))
        assertOneShotJson(CorpusCorrections.requestBody(text, null))
    }

    @Test
    fun `plain client rejected by the configuration assertion`() {
        try {
            assertNoReplayConfiguration(OkHttpClient())
            fail("a plain OkHttpClient (retry on, redirects on) must fail the configuration assertion")
        } catch (expected: AssertionError) {
            // The helper can fail, so a green configuration row means something.
        }
    }

    @Test
    fun `reusable body rejected by the one-shot assertion`() {
        val reusable = "{}".toRequestBody("application/json".toMediaType())
        try {
            assertOneShotJson(reusable)
            fail("a reusable body must fail the one-shot assertion")
        } catch (expected: AssertionError) {
            // The helper can fail.
        }
    }

    // --- append semantics ------------------------------------------------------------------------

    @Test
    fun `two calls make two requests and two ACCEPTED with no dedupe`() {
        enqueue(201)
        enqueue(201)

        assertEquals(CorrectionSubmitResult.ACCEPTED, submit())
        assertEquals(CorrectionSubmitResult.ACCEPTED, submit())

        assertEquals(2, server.requestCount)
    }

    // --- cancellation ----------------------------------------------------------------------------

    @Test
    fun `cancellation of the caller while the response stalls ends cancelled and delivers no result`() {
        server.enqueue(MockResponse.Builder().code(201).onResponseStart(SocketEffect.Stall).build())

        runBlocking {
            withTimeout(30_000) {
                val call = async(Dispatchers.Default) {
                    submitCorrection(context, base(), id, text, "en")
                }
                val deadline = System.currentTimeMillis() + 5_000
                while (server.requestCount < 1) {
                    check(System.currentTimeMillis() < deadline) { "the request never reached the server" }
                    delay(10)
                }

                call.cancel()
                // The default call timeout is 15 s: finishing well inside it shows the in-flight call
                // was cancelled with the caller, not waited out.
                withTimeout(5_000) { call.join() }

                assertTrue("the caller must end cancelled, not UNREACHABLE", call.isCancelled)
                try {
                    call.await()
                    fail("a cancelled caller must not receive a result")
                } catch (expected: CancellationException) {
                    // expected
                }
            }
        }
    }

    // --- never throws ----------------------------------------------------------------------------

    @Test
    fun `injected client throws IllegalStateException and RuntimeException are both UNREACHABLE`() {
        enqueue(201)
        for (failure in listOf<RuntimeException>(IllegalStateException("boom $marker"), RuntimeException("boom $marker"))) {
            val client = CorpusCorrections.DEFAULT_CLIENT.newBuilder()
                .addInterceptor(Interceptor { throw failure })
                .build()

            val result = blocking { CorpusCorrections(client).submit(base(), id, text, "en") }

            assertEquals(CorrectionSubmitResult.UNREACHABLE, result)
        }
        assertEquals("the interceptor failed before any request left", 0, server.requestCount)
    }

    @Test
    fun `an IOException from the client is UNREACHABLE`() {
        val client = CorpusCorrections.DEFAULT_CLIENT.newBuilder()
            .addInterceptor(Interceptor { throw IOException("no route") })
            .build()

        assertEquals(CorrectionSubmitResult.UNREACHABLE, blocking { CorpusCorrections(client).submit(base(), id, text, "en") })
    }

    // --- main safety -----------------------------------------------------------------------------

    @Test
    fun `the request runs off the calling thread when called from the main thread`() {
        enqueue(201)
        val mainThread = Looper.getMainLooper().thread
        assertSame(mainThread, Thread.currentThread())
        val executedOn = AtomicReference<Thread>()
        val client = CorpusCorrections.DEFAULT_CLIENT.newBuilder()
            .addInterceptor(Interceptor { chain ->
                executedOn.set(Thread.currentThread())
                chain.proceed(chain.request())
            })
            .build()

        val result = blocking { CorpusCorrections(client).submit(base(), id, text, "en") }

        assertEquals(CorrectionSubmitResult.ACCEPTED, result)
        assertNotNull(executedOn.get())
        assertNotEquals("HTTP must not execute on the main thread", mainThread, executedOn.get())
    }

    // --- privacy ---------------------------------------------------------------------------------

    @Test
    fun `corrected text is never logged on success or on any failure path`() {
        enqueue(201)
        enqueue(500)
        enqueue(400)
        val results = listOf(submit(), submit(), submit(), submit(baseUrl = "not a url"), submit(corrected = " "))
        server.close()
        val afterShutdown = submit()

        for (result in results + afterShutdown) {
            assertFalse(result.name.contains(marker))
            assertFalse(result.toString().contains(marker))
        }
        // The @After hook asserts the log; here the log must also not have grown a line for the call.
        assertTrue(ShadowLog.getLogs().none { it.msg?.contains(marker) == true })
    }
}
