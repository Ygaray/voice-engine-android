package io.github.ygaray.sttengine.transcription

// Source: mockwebserver3's builder-pattern API — see github.com/square/okhttp mockwebserver/README.md
import java.io.File
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D-02's wire-level proof: a real [MockWebServer] instance stands in for the Phase-7 server, and
 * the real [io.github.ygaray.sttengine.transcription.internal.OkHttpTranscriptionClient] (reached
 * only through [createTranscriptionClient], never an interface fake) talks to it over real HTTP.
 *
 * Task 1 writes exactly the one `202 Accepted parses id` tracer test below. Task 3 expands this
 * same file (same `@Before`/`@After` fixture, no second test class) to D-02's full 7-case matrix.
 */
class TranscriptionClientWireTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TranscriptionClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = createTranscriptionClient(serverUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `202 Accepted parses id`() = runTest {
        server.enqueue(MockResponse.Builder().code(202).body("""{"id":"job-abc"}""").build())
        val outcome = client.submitJob(tempAudioFile(), "audio/mp4")
        assertTrue(outcome is SubmitOutcome.Accepted)
        assertEquals("job-abc", (outcome as SubmitOutcome.Accepted).jobId)
    }

    @Test
    fun `400 maps to Rejected UPLOAD_REJECTED`() = runTest {
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":"malformed-upload"}""").build())
        val outcome = client.submitJob(tempAudioFile(), "audio/mp4")
        assertTrue(outcome is SubmitOutcome.Rejected)
        assertEquals(TranscriptionErrorCode.UPLOAD_REJECTED, (outcome as SubmitOutcome.Rejected).code)
        // Message-parity (cross-AI review finding, cycle 1): errorMessageOrDefault parses the
        // response body's "error" JSON field, not the raw body string — assert the exact parsed
        // value, matching Task 1's actual implementation.
        assertEquals("malformed-upload", outcome.message)
    }

    @Test
    fun `413 maps to Rejected UPLOAD_TOO_LARGE`() = runTest {
        server.enqueue(MockResponse.Builder().code(413).body("""{"error":"payload-too-large"}""").build())
        val outcome = client.submitJob(tempAudioFile(), "audio/mp4")
        assertTrue(outcome is SubmitOutcome.Rejected)
        assertEquals(TranscriptionErrorCode.UPLOAD_TOO_LARGE, (outcome as SubmitOutcome.Rejected).code)
        assertEquals("payload-too-large", outcome.message)
    }

    @Test
    fun `200 processing maps to Pending`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"status":"processing"}""").build())
        assertTrue(client.getJobStatus("job-abc") is JobStatusOutcome.Pending)
    }

    @Test
    fun `200 done parses transcript`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"status":"done","transcript":"hello"}""").build())
        val outcome = client.getJobStatus("job-abc")
        assertTrue(outcome is JobStatusOutcome.Done)
        assertEquals("hello", (outcome as JobStatusOutcome.Done).transcript)
    }

    @Test
    fun `200 failed parses error message`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"status":"failed","error":"transcription failed"}""").build())
        val outcome = client.getJobStatus("job-abc")
        assertTrue(outcome is JobStatusOutcome.Failed)
        assertEquals("transcription failed", (outcome as JobStatusOutcome.Failed).message)
    }

    @Test
    fun `404 maps to NotFound`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":"not-found"}""").build())
        assertTrue(client.getJobStatus("job-abc") is JobStatusOutcome.NotFound)
    }

    private fun tempAudioFile(): File =
        File.createTempFile("clip", ".m4a").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
}
