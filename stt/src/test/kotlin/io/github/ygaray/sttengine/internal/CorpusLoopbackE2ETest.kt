package io.github.ygaray.sttengine.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.BackendPreference
import io.github.ygaray.sttengine.CorrectionSubmitResult
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.LanguageAvailability
import io.github.ygaray.sttengine.PreflightHandle
import io.github.ygaray.sttengine.SttConfig
import io.github.ygaray.sttengine.submitCorrection
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 25 Gate-1 LOOPBACK end-to-end proof, live half (plan 25-09; ROADMAP success criteria 1, 3, 4).
 *
 * The Phase 25 client code (`SttConfig(retainForCorpus = true)`, `SttEngine.lastSessionId`,
 * `submitCorrection`) is driven over REAL sockets against the working-tree server code, started by
 * `scripts/run-client-corpus-loopback.sh` on loopback ephemeral ports with a temp corpus root and a
 * fake recognizer. Each test appends NDJSON facts (what the client did and was told) to the file the
 * orchestrator names; `scripts/check-client-corpus-loopback.py` then compares them with the record
 * directories the server wrote.
 *
 * SKIPPED (a JUnit assumption) in every ordinary `testDebugUnitTest` run: it executes only when the
 * Gradle properties `stt.loopback.ready` and `stt.loopback.facts` are passed (forwarded as system
 * properties by this module's build file). Synthetic tone audio only; no device, no real voice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CorpusLoopbackE2ETest {

    private lateinit var executor: ExecutorService
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private val client = OkHttpClient()
    private var previousFactory: PcmSourceFactory? = null

    @Before
    fun setUp() {
        executor = Executors.newSingleThreadExecutor()
        mainDispatcher = executor.asCoroutineDispatcher()
        previousFactory = DebugAudioSourceHolder.factory
    }

    @After
    fun tearDown() {
        DebugAudioSourceHolder.factory = previousFactory
        mainDispatcher.close()
        executor.awaitTermination(2, TimeUnit.SECONDS)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    // -- harness plumbing -----------------------------------------------------------------------

    private fun readyFile(): File {
        val path = System.getProperty("stt.loopback.ready").orEmpty()
        assumeTrue("stt.loopback.ready is not set: the loopback proof is skipped", path.isNotEmpty())
        return File(path)
    }

    private fun readyJson(): JSONObject = JSONObject(readyFile().readText())

    private fun appendFact(fact: JSONObject) {
        val path = System.getProperty("stt.loopback.facts").orEmpty()
        check(path.isNotEmpty()) { "stt.loopback.facts is not set (the orchestrator passes it with the ready file)" }
        File(path).appendText(fact.toString() + "\n")
    }

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 15_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            delay(20)
        }
    }

    /**
     * One live session against the launcher's WebSocket server: connect with [retain], stream about
     * two seconds of synthetic tone, stop. Returns `lastSessionId` read after `stop()`.
     */
    private suspend fun liveSession(context: Context, retain: Boolean): String? {
        val ready = readyJson()
        val pcm = ToneSourceFactory(frames = 62)
        DebugAudioSourceHolder.factory = pcm
        val selector = BackendSelector(
            appContext = context,
            config = SttConfig(
                serverUrl = ready.getString("wsUrl"),
                prefer = BackendPreference.Server,
                retainForCorpus = retain,
            ),
            handleFactory = RecognizerHandleFactory {
                PreflightHandle(FakeRecognizerHandle(), LanguageAvailability.INSTALLED)
            },
            permissions = PermissionChecker { true },
            webSocketFactory = OkHttpWebSocketFactory(client),
            mainDispatcher = mainDispatcher,
            // serverBackendFactory deliberately NOT overridden: the DEFAULT factory is under test.
        )
        selector.start()
        awaitUntil("the Server backend to be listening") {
            selector.backend.value == Backend.Server && selector.state.value == EngineState.Listening
        }
        awaitUntil("the pump to drain the finite tone source") { pcm.opened.isNotEmpty() && pcm.last.exhausted }
        selector.stop()
        // The server releases its single session slot just after it closes.
        delay(500)
        return selector.lastSessionId
    }

    /** A retained live session; asserts the id and records the `retained-id` fact. */
    private suspend fun retainedLiveId(context: Context): String {
        val id = liveSession(context, retain = true)
        assertNotNull("a retained session must publish its id after a clean stop", id)
        assertTrue("the id is 32 lowercase hex: $id", Regex("[0-9a-f]{32}").matches(id!!))
        appendFact(JSONObject().put("fact", "retained-id").put("id", id).put("kind", "dictation"))
        return id
    }

    private fun httpBase(): String = readyJson().getString("httpBase")

    // -- tests ----------------------------------------------------------------------------------

    @Test
    fun `a retained live session leaves a dictation record named by lastSessionId`() = runBlocking {
        readyFile()
        withTimeout(60_000) {
            val context: Context = ApplicationProvider.getApplicationContext()
            retainedLiveId(context)
            Unit
        }
    }

    @Test
    fun `a default session retains nothing and lastSessionId stays null`() = runBlocking {
        readyFile()
        withTimeout(60_000) {
            val context: Context = ApplicationProvider.getApplicationContext()
            val id = liveSession(context, retain = false)
            assertNull("a session that did not opt in must not claim a record", id)
            appendFact(JSONObject().put("fact", "control-null"))
        }
    }

    @Test
    fun `corrections append exactly what was sent to the retained record`() = runBlocking {
        readyFile()
        withTimeout(90_000) {
            val context: Context = ApplicationProvider.getApplicationContext()
            val id = retainedLiveId(context)
            val texts = listOf("loopback-correction-1", "loopback-correction-2")
            val first = submitCorrection(context, httpBase(), id, texts[0], "en")
            val second = submitCorrection(context, httpBase(), id, texts[1])
            assertEquals(CorrectionSubmitResult.ACCEPTED, first)
            assertEquals(CorrectionSubmitResult.ACCEPTED, second)
            appendFact(
                JSONObject()
                    .put("fact", "correction")
                    .put("id", id)
                    .put("texts", JSONArray(texts))
                    .put("languages", JSONArray().put("en").put(JSONObject.NULL))
                    .put("results", JSONArray().put(first.name).put(second.name)),
            )
        }
    }

    @Test
    fun `an unknown id comes back UNKNOWN_SESSION and creates nothing`() = runBlocking {
        readyFile()
        withTimeout(30_000) {
            val context: Context = ApplicationProvider.getApplicationContext()
            val id = UUID.randomUUID().toString().replace("-", "")
            val result = submitCorrection(context, httpBase(), id, "loopback-unknown", "en")
            assertEquals(CorrectionSubmitResult.UNKNOWN_SESSION, result)
            appendFact(JSONObject().put("fact", "unknown-id").put("id", id).put("result", result.name))
        }
    }

    @Test
    fun `an oversize body is REJECTED by the real 64 KiB cap`() = runBlocking {
        readyFile()
        withTimeout(90_000) {
            val context: Context = ApplicationProvider.getApplicationContext()
            val id = retainedLiveId(context)
            val result = submitCorrection(context, httpBase(), id, "x".repeat(70_000), "en")
            assertEquals(CorrectionSubmitResult.REJECTED, result)
            appendFact(JSONObject().put("fact", "oversize").put("id", id).put("result", result.name))
        }
    }

    /**
     * The clip correction (D-10, D-11): the id is the retained BATCH job id that
     * [CorpusBatchLoopbackE2ETest][io.github.ygaray.sttengine.transcription] recorded as a `clip`
     * fact. The orchestrator runs the `:transcription` batch test BEFORE this one (two separate
     * Gradle invocations); in stage `live` this test is not selected.
     */
    @Test
    fun `a retained clip id accepts a correction after the batch step`() = runBlocking {
        readyFile()
        withTimeout(30_000) {
            val context: Context = ApplicationProvider.getApplicationContext()
            val factsPath = System.getProperty("stt.loopback.facts").orEmpty()
            val clipId = File(factsPath).readLines()
                .filter { it.isNotBlank() }
                .map { JSONObject(it) }
                .firstOrNull { it.optString("fact") == "retained-id" && it.optString("kind") == "clip" }
                ?.getString("id")
            if (clipId == null) {
                fail(
                    "no retained clip fact found: scripts/run-client-corpus-loopback.sh must run the " +
                        ":transcription CorpusBatchLoopbackE2ETest BEFORE this :stt test (stage all)",
                )
                return@withTimeout
            }
            val text = "loopback-clip-correction"
            val result = submitCorrection(context, httpBase(), clipId, text, "en")
            assertEquals(CorrectionSubmitResult.ACCEPTED, result)
            appendFact(
                JSONObject()
                    .put("fact", "correction")
                    .put("id", clipId)
                    .put("texts", JSONArray().put(text))
                    .put("languages", JSONArray().put("en"))
                    .put("results", JSONArray().put(result.name)),
            )
        }
    }

    @Test
    fun `a blank corrected text is REJECTED without a request`() = runBlocking {
        readyFile()
        withTimeout(30_000) {
            val context: Context = ApplicationProvider.getApplicationContext()
            val result = submitCorrection(context, httpBase(), "0123456789abcdef0123456789abcdef", "   ", "en")
            assertEquals(CorrectionSubmitResult.REJECTED, result)
            appendFact(JSONObject().put("fact", "blank").put("result", result.name))
        }
    }
}

/** A finite source of a synthetic 440 Hz tone (16 kHz PCM16 mono), `frames` reads of 1 024 bytes. */
private class ToneSource(frames: Int) : PcmSource {
    private var remaining: Long = frames.toLong() * 1024
    private var sampleIndex = 0L

    @Volatile var exhausted: Boolean = false
        private set

    @Volatile private var stopped = false

    override fun read(buffer: ByteArray): Int {
        if (stopped) return -1
        if (remaining <= 0L) {
            exhausted = true
            return -1
        }
        val count = minOf(1024L, buffer.size.toLong(), remaining).toInt() and 1.inv()
        var i = 0
        while (i < count) {
            val v = (sin(2.0 * PI * 440.0 * sampleIndex / 16_000.0) * 8000.0).toInt()
            buffer[i] = (v and 0xFF).toByte()
            buffer[i + 1] = ((v shr 8) and 0xFF).toByte()
            sampleIndex++
            i += 2
        }
        remaining -= count
        return count
    }

    override fun stop() {
        stopped = true
    }

    override fun release() = Unit
}

private class ToneSourceFactory(private val frames: Int) : PcmSourceFactory {
    val opened: MutableList<ToneSource> = java.util.concurrent.CopyOnWriteArrayList()
    val last: ToneSource get() = opened.last()

    override fun open(sampleRateHz: Int): PcmSource = ToneSource(frames).also { opened += it }
}
