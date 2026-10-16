package io.github.ygaray.sttengine.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.BackendPreference
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.LanguageAvailability
import io.github.ygaray.sttengine.PreflightHandle
import io.github.ygaray.sttengine.SttConfig
import java.io.File
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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    // -- tests ----------------------------------------------------------------------------------

    @Test
    fun `a retained live session leaves a dictation record named by lastSessionId`() = runBlocking {
        readyFile()
        withTimeout(60_000) {
            val context: Context = ApplicationProvider.getApplicationContext()
            val id = liveSession(context, retain = true)
            assertNotNull("a retained session must publish its id after a clean stop", id)
            assertTrue("the id is 32 lowercase hex: $id", Regex("[0-9a-f]{32}").matches(id!!))
            appendFact(JSONObject().put("fact", "retained-id").put("id", id).put("kind", "dictation"))
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
