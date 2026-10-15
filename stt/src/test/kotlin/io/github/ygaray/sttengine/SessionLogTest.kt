package io.github.ygaray.sttengine

import io.github.ygaray.sttengine.internal.Rung
import io.github.ygaray.sttengine.internal.SessionLog
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The opt-in JSONL diagnostic sink.
 *
 * Two independent things are under test here and only one of them is a feature.
 *
 * The **schema** is the input contract for plan 08's `SessionLogAnalyzer`, which scores the AND-02
 * verdict off these files. A renamed field there is not a cosmetic change, it is a broken verdict —
 * so the field names are asserted as an exact set per line kind rather than spot-checked.
 *
 * The **default** is a security property (T-05-04). This sink records the user's dictated speech,
 * which is the most sensitive content this product handles, so "disabled unless explicitly asked
 * for" has to be proven rather than reviewed. The first test drives a complete engine session and
 * asserts that not one byte reaches the filesystem.
 *
 * Robolectric because `org.json` is one of the classes the plain-JVM mockable `android.jar` stubs
 * out; a test asserting on parsed lines there would parse nothing and pass anyway.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SessionLogTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    // --- the default is OFF, and it is proven end to end -----------------------------------------

    @Test
    fun `left at its default a full session creates no file and writes no line`() = runTest {
        val log = SessionLog(directory = folder.root, sessionStartEpochMs = FIXED_EPOCH_MS)
        val h = EngineHarness(testScheduler, log = log)
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.firePartial("the morning light")
        runCurrent()
        h.fake.fireResults("the morning light")
        advanceUntilIdle()
        h.engine.stop()
        advanceUntilIdle()

        assertNull("a disabled log must not even resolve a file", log.file)
        assertEquals(
            "a disabled log must leave the directory untouched",
            emptyList<String>(),
            folder.root.list()!!.toList(),
        )
    }

    @Test
    fun `the enabled flag defaults to false`() {
        val log = SessionLog(directory = folder.root, sessionStartEpochMs = FIXED_EPOCH_MS)
        log.state(EngineState.Listening)
        log.rung(Rung.RestartingSingleSession)
        log.error(SttError(SttErrorCode.INTERNAL_ERROR, "boom", platformDetail = 4))
        assertEquals(emptyList<String>(), folder.root.list()!!.toList())
    }

    // --- the schema plan 08 is written against ---------------------------------------------------

    @Test
    fun `an enabled log writes one json object per line, named for the session start epoch`() {
        val log = enabledLog()

        log.state(EngineState.Listening)
        log.rung(Rung.RestartingSingleSession)

        val file = log.file
        assertTrue("an enabled log must create its file", file != null && file.isFile)
        assertEquals("stt-session-$FIXED_EPOCH_MS.jsonl", file!!.name)
        assertEquals(2, lines(log).size)
    }

    @Test
    fun `every line kind carries exactly the fields the analyzer reads`() {
        val log = enabledLog()

        log.state(EngineState.Listening)
        log.rung(Rung.SilenceSegmented)
        log.partial(segmentId = 0, text = "the morning")
        log.final(FinalSegment("the morning light", 0))
        log.restart(epoch = 1L, reason = "NormalFinal", delayMs = 200L, advanceRung = false)
        log.ready(epoch = 1L, deafWindowMs = 198L)
        log.error(SttError(SttErrorCode.BUSY, "busy", platformDetail = 8))

        val parsed = lines(log).map { JSONObject(it) }
        assertEquals(
            listOf("state", "rung", "partial", "final", "restart", "ready", "error"),
            parsed.map { it.getString("kind") },
        )

        val expected = mapOf(
            "state" to setOf("t", "kind", "value"),
            "rung" to setOf("t", "kind", "value"),
            "partial" to setOf("t", "kind", "segmentId", "text"),
            "final" to setOf("t", "kind", "segmentId", "text"),
            "restart" to setOf("t", "kind", "epoch", "reason", "delayMs", "advanceRung"),
            "ready" to setOf("t", "kind", "epoch", "deafWindowMs"),
            "error" to setOf("t", "kind", "code", "platformDetail"),
        )
        parsed.forEach { line ->
            val kind = line.getString("kind")
            assertEquals("field set for kind=$kind", expected.getValue(kind), line.fieldNames())
        }

        // Values, not just keys — a schema whose fields are all present and all wrong is worse than
        // a missing one, because the analyzer would score it rather than reject it.
        val byKind = parsed.associateBy { it.getString("kind") }
        assertEquals("listening", byKind.getValue("state").getString("value"))
        assertEquals("SilenceSegmented", byKind.getValue("rung").getString("value"))
        assertEquals("the morning", byKind.getValue("partial").getString("text"))
        assertEquals(0, byKind.getValue("final").getInt("segmentId"))
        assertEquals("the morning light", byKind.getValue("final").getString("text"))
        assertEquals(200L, byKind.getValue("restart").getLong("delayMs"))
        assertFalse(byKind.getValue("restart").getBoolean("advanceRung"))
        assertEquals(198L, byKind.getValue("ready").getLong("deafWindowMs"))
        assertEquals("busy", byKind.getValue("error").getString("code"))
        assertEquals(8, byKind.getValue("error").getInt("platformDetail"))
    }

    @Test
    fun `a server-attempt line carries exactly its fields and absent codes stay json null`() {
        val log = enabledLog()
        log.serverAttempt(
            attempt = 2,
            outcome = "Retryable",
            exception = "IOException",
            sawOpen = false,
            closeCode = null,
            responseCode = 503,
        )

        val line = JSONObject(lines(log).single())
        assertEquals(
            setOf("t", "kind", "attempt", "class", "exception", "sawOpen", "closeCode", "responseCode"),
            line.fieldNames(),
        )
        assertEquals("server-attempt", line.getString("kind"))
        assertEquals(2, line.getInt("attempt"))
        assertEquals("Retryable", line.getString("class"))
        assertEquals("IOException", line.getString("exception"))
        assertFalse(line.getBoolean("sawOpen"))
        assertTrue("closeCode must be a present json null", line.has("closeCode") && line.isNull("closeCode"))
        assertEquals(503, line.getInt("responseCode"))
    }

    @Test
    fun `a server-attempt line with no exception keeps the key as json null`() {
        val log = enabledLog()
        log.serverAttempt(1, "WindowExpired", null, false, null, null)

        val line = JSONObject(lines(log).single())
        assertTrue(line.has("exception") && line.isNull("exception"))
        assertTrue(line.has("responseCode") && line.isNull("responseCode"))
    }

    @Test
    fun `a disabled log writes no server-attempt line`() {
        val log = SessionLog(directory = folder.root, sessionStartEpochMs = FIXED_EPOCH_MS)
        log.serverAttempt(1, "Retryable", "IOException", true, 1006, null)
        assertNull(log.file)
        assertEquals(emptyList<String>(), folder.root.list()!!.toList())
    }

    @Test
    fun `a null platformDetail stays a present json null rather than a missing key`() {
        val log = enabledLog()
        log.error(SttError(SttErrorCode.MIC_DENIED, "denied"))

        val line = JSONObject(lines(log).single())
        assertTrue("the key must exist so the schema is stable", line.has("platformDetail"))
        assertTrue("and it must be json null", line.isNull("platformDetail"))
    }

    @Test
    fun `t is milliseconds since the session started and never goes backwards`() {
        var now = 1_000L
        val log = SessionLog(
            enabled = true,
            directory = folder.root,
            sessionStartEpochMs = FIXED_EPOCH_MS,
            monotonicMs = { now },
        )
        log.state(EngineState.Connecting)
        now = 1_250L
        log.state(EngineState.Listening)

        val ts = lines(log).map { JSONObject(it).getLong("t") }
        assertEquals(listOf(0L, 250L), ts)
    }

    // --- a diagnostic may never break dictation ----------------------------------------------------

    @Test
    fun `an unwritable target directory degrades to a no-op instead of throwing`() {
        // A regular file where a directory is expected: mkdirs() cannot succeed and opening a child
        // path underneath it fails at the filesystem level.
        val notADirectory = folder.newFile("this-is-a-file")
        val log = SessionLog(
            enabled = true,
            directory = File(notADirectory, "logs"),
            sessionStartEpochMs = FIXED_EPOCH_MS,
        )

        log.state(EngineState.Listening)
        log.final(FinalSegment("the morning light", 0))
        log.error(SttError(SttErrorCode.INTERNAL_ERROR, "boom", platformDetail = 4))

        assertTrue("nothing above may observe the failure", notADirectory.isFile)
    }

    @Test
    fun `a null directory degrades to a no-op instead of throwing`() {
        val log = SessionLog(enabled = true, directory = null, sessionStartEpochMs = FIXED_EPOCH_MS)
        log.state(EngineState.Listening)
        assertNull(log.file)
    }

    // --- the engine actually feeds it ---------------------------------------------------------------

    @Test
    fun `an enabled log records a real session's states, partials, finals and restarts`() = runTest {
        val log = enabledLog()
        val h = EngineHarness(testScheduler, log = log)
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.firePartial("the morning light")
        runCurrent()
        h.fake.fireError(ERROR_NO_MATCH)
        advanceUntilIdle()
        h.fake.reachListening()
        runCurrent()

        val kinds = lines(log).map { JSONObject(it).getString("kind") }
        assertTrue("state transitions are recorded", kinds.contains("state"))
        assertTrue("the selected rung is recorded", kinds.contains("rung"))
        assertTrue("partials are recorded", kinds.contains("partial"))
        assertTrue("the flushed final is recorded", kinds.contains("final"))
        assertTrue("the restart decision is recorded", kinds.contains("restart"))
        assertTrue("ready-for-speech is recorded", kinds.contains("ready"))

        val restart = lines(log).map { JSONObject(it) }.first { it.getString("kind") == "restart" }
        assertEquals("AfterError", restart.getString("reason"))
        // The deaf window is measurable only on a ready that FOLLOWED a restart; the session's very
        // first ready reports the sentinel instead of a fabricated zero.
        val deafWindows = lines(log).map { JSONObject(it) }
            .filter { it.getString("kind") == "ready" }
            .map { it.getLong("deafWindowMs") }
        assertEquals(listOf(-1L, 0L), deafWindows.map { if (it < 0) -1L else 0L })
    }

    // --- helpers ------------------------------------------------------------------------------------

    private fun enabledLog(): SessionLog =
        SessionLog(enabled = true, directory = folder.root, sessionStartEpochMs = FIXED_EPOCH_MS)

    private fun lines(log: SessionLog): List<String> =
        log.file?.readLines()?.filter { it.isNotBlank() } ?: emptyList()

    /** `JSONObject.keySet()` does not exist on Android's org.json — only `keys()`. */
    private fun JSONObject.fieldNames(): Set<String> = keys().asSequence().toSet()

    private companion object {
        const val FIXED_EPOCH_MS = 1_770_000_000_000L
        const val ERROR_NO_MATCH = 7
    }
}
