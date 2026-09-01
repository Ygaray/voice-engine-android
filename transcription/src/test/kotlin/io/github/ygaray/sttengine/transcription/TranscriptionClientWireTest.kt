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

    private fun tempAudioFile(): File =
        File.createTempFile("clip", ".m4a").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
}
