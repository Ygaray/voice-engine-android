package io.github.ygaray.sttengine.transcription

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 25 Gate-1 LOOPBACK end-to-end proof, batch half (plan 25-09; ROADMAP success criteria 1 and 4).
 *
 * The real [TranscriptionClient] (CORR-09: `submitJob(..., retainForCorpus = true)`) talks over a real
 * socket to the working-tree batch app that `scripts/run-client-corpus-loopback.sh` started on a
 * loopback ephemeral port with a temp corpus root and a fake recognizer. The job id of a retained
 * clip IS the corpus record id; each test appends an NDJSON fact for
 * `scripts/check-client-corpus-loopback.py` to compare with the record directories on disk.
 *
 * SKIPPED (a JUnit assumption) in every ordinary `testDebugUnitTest` run: it executes only when the
 * Gradle properties `stt.loopback.ready` and `stt.loopback.facts` are passed. Real time throughout
 * (`runBlocking`). Synthetic tone audio only.
 */
class CorpusBatchLoopbackE2ETest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun ready(): JSONObject {
        val path = System.getProperty("stt.loopback.ready").orEmpty()
        assumeTrue("stt.loopback.ready is not set: the loopback proof is skipped", path.isNotEmpty())
        return JSONObject(File(path).readText())
    }

    private fun appendFact(fact: JSONObject) {
        val path = System.getProperty("stt.loopback.facts").orEmpty()
        check(path.isNotEmpty()) { "stt.loopback.facts is not set (the orchestrator passes it with the ready file)" }
        File(path).appendText(fact.toString() + "\n")
    }

    /** A 1-second 16 kHz mono PCM16 synthetic tone as a standard 44-byte-header WAV file. */
    private fun toneWav(): File {
        val rate = 16_000
        val samples = rate
        val data = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            data.putShort((sin(2.0 * PI * 440.0 * i / rate) * 8000.0).toInt().toShort())
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        header.putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(samples * 2)
        val out = ByteArrayOutputStream()
        out.write(header.array())
        out.write(data.array())
        return folder.newFile("loopback-tone.wav").also { it.writeBytes(out.toByteArray()) }
    }

    /** Poll `getJobStatus` every 200 ms of real time for up to 30 seconds until `Done`. */
    private suspend fun awaitDone(client: TranscriptionClient, jobId: String) {
        val deadline = System.currentTimeMillis() + 30_000L
        while (true) {
            when (val status = client.getJobStatus(jobId)) {
                is JobStatusOutcome.Done -> return
                is JobStatusOutcome.Failed -> fail("job $jobId failed: ${status.message}")
                JobStatusOutcome.NotFound -> fail("job $jobId is unknown to the server")
                JobStatusOutcome.Pending, JobStatusOutcome.Transient -> Unit
            }
            check(System.currentTimeMillis() < deadline) { "timed out waiting for job $jobId to reach Done" }
            delay(200)
        }
    }

    @Test
    fun `a retained batch clip reaches Done and its job id is the clip record id`() = runBlocking {
        val httpBase = ready().getString("httpBase")
        val client = createTranscriptionClient(httpBase)
        val outcome = client.submitJob(toneWav(), "audio/wav", "en", emptyList(), retainForCorpus = true)
        assertTrue("expected Accepted, got $outcome", outcome is SubmitOutcome.Accepted)
        val jobId = (outcome as SubmitOutcome.Accepted).jobId
        awaitDone(client, jobId)
        appendFact(JSONObject().put("fact", "retained-id").put("id", jobId).put("kind", "clip"))
    }

    @Test
    fun `a default batch clip reaches Done and retains nothing`() = runBlocking {
        val httpBase = ready().getString("httpBase")
        val client = createTranscriptionClient(httpBase)
        val outcome = client.submitJob(toneWav(), "audio/wav", "en", emptyList())
        assertTrue("expected Accepted, got $outcome", outcome is SubmitOutcome.Accepted)
        val jobId = (outcome as SubmitOutcome.Accepted).jobId
        awaitDone(client, jobId)
        appendFact(JSONObject().put("fact", "control-job").put("id", jobId))
    }
}
