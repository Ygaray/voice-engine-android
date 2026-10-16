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

    // CORR-09 tracer (D-11, D-06): a retained batch submit through the NEW overload sends the
    // opt-in part as the text "true" and returns the job id, which IS the session id.
    @Test
    fun `retained submit sends the retainForCorpus part and returns the job id`() = runTest {
        server.enqueue(MockResponse.Builder().code(202).body("""{"id":"job-abc","retained":true}""").build())
        val outcome = client.submitJob(tempAudioFile(), "audio/mp4", retainForCorpus = true)
        assertEquals(SubmitOutcome.Accepted("job-abc"), outcome)
        val requestBody = server.takeRequest().body!!.utf8()
        val partIndex = requestBody.indexOf("name=\"retainForCorpus\"")
        assertTrue("expected a form part named \"retainForCorpus\"", partIndex >= 0)
        val valueStart = requestBody.indexOf("\r\n\r\n", partIndex) + 4
        val valueEnd = requestBody.indexOf("\r\n", valueStart)
        assertEquals("true", requestBody.substring(valueStart, valueEnd))
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

    // WR-01 (136-REVIEW.md): exercise the module's actual DoS mitigation (T-136-01) — a
    // malformed/non-JSON body must degrade to Transient, never propagate the JSONException
    // uncaught. Without these, a future narrowing of `catch (e: Exception)` to
    // `catch (e: IOException)` would silently reopen T-136-01 with no test catching it.
    @Test
    fun `malformed 202 body degrades to Transient, not an exception`() = runTest {
        server.enqueue(MockResponse.Builder().code(202).body("not-json").build())
        val outcome = client.submitJob(tempAudioFile(), "audio/mp4")
        assertTrue(outcome is SubmitOutcome.Transient)
    }

    @Test
    fun `malformed 200 body degrades to Transient, not an exception`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("not-json").build())
        val outcome = client.getJobStatus("job-abc")
        assertTrue(outcome is JobStatusOutcome.Transient)
    }

    // HUB-03 (147-02): the language widening's wire-level proof. Test A is the tracer's
    // end-to-end assertion, B pins the default-on-the-wire guarantee both live SecondBrain call
    // sites depend on, and C pins the part-ordering interop guarantee with the Plan 01 server.
    @Test
    fun `explicit language reaches the wire as a form part`() = runTest {
        server.enqueue(MockResponse.Builder().code(202).body("""{"id":"job-abc"}""").build())
        client.submitJob(tempAudioFile(), "audio/mp4", "es")
        val requestBody = server.takeRequest().body!!.utf8()
        assertTrue(
            "expected the request body to carry a form part named \"language\" with value \"es\"",
            requestBody.contains("name=\"language\"") && requestBody.contains("es"),
        )
    }

    @Test
    fun `default language reaches the wire when omitted`() = runTest {
        server.enqueue(MockResponse.Builder().code(202).body("""{"id":"job-abc"}""").build())
        // The exact two-argument form both live SecondBrain call sites use.
        client.submitJob(tempAudioFile(), "audio/mp4")
        val requestBody = server.takeRequest().body!!.utf8()
        // WR-02 (147-REVIEW.md): a loose requestBody.contains("en") passes trivially because the
        // file part's Content-Disposition always contains "filename" (which itself contains
        // "en"), so this couldn't actually catch a default-language regression. Pin the exact
        // value of the "language" form part instead, the way the "language part precedes the
        // file part" test below extracts indices.
        val languagePartIndex = requestBody.indexOf("name=\"language\"")
        assertTrue("expected the request body to carry a form part named \"language\"", languagePartIndex >= 0)
        val valueStart = requestBody.indexOf("\r\n\r\n", languagePartIndex) + 4
        val valueEnd = requestBody.indexOf("\r\n", valueStart)
        assertEquals(
            "expected the English default \"en\" as the language part's value when the caller omits the argument",
            "en",
            requestBody.substring(valueStart, valueEnd),
        )
    }

    @Test
    fun `language part precedes the file part in the request body`() = runTest {
        server.enqueue(MockResponse.Builder().code(202).body("""{"id":"job-abc"}""").build())
        client.submitJob(tempAudioFile(), "audio/mp4", "es")
        val requestBody = server.takeRequest().body!!.utf8()
        val languageIndex = requestBody.indexOf("name=\"language\"")
        val fileIndex = requestBody.indexOf("name=\"file\"")
        assertTrue("expected the language part to be present", languageIndex >= 0)
        assertTrue("expected the file part to be present", fileIndex >= 0)
        assertTrue(
            "expected the language part (index $languageIndex) to appear before the file part " +
                "(index $fileIndex) so a server whose pre-file scan stops at the file part still " +
                "sees it",
            languageIndex < fileIndex,
        )
    }

    // Compile-shape guards (147-02, Task 2). Task 1 already proved the wire; the value of these
    // three is that they stop COMPILING — not merely running red — the moment someone later makes
    // `language` required, reorders the parameters, or splits submitJob into ambiguous overloads.
    // That failure would otherwise surface for the first time at a consumer's repin, one repo and
    // one phase away from its cause, after an immutable tag had already frozen it. D and E reuse
    // this class's own mock-server fixture (they need a real executed outcome, not just a compile),
    // and F is grouped alongside them rather than in TranscriptionModuleBoundaryTest so the whole
    // language-widening compile-shape guarantee lives in one place next to the wire tests it backs.
    //
    // - Test D makes impossible: a future change requiring `language` (removing its default),
    //   which would break both live SecondBrain call sites (both pass exactly file + mimeType).
    // - Test E makes impossible: a future change that drops or reorders the third parameter in a
    //   way that breaks an explicit three-argument caller.
    // - Test F makes impossible: a future change that leaves the interface implementable for
    //   callers but not for implementors (a real hazard when a defaulted parameter lands on an
    //   abstract member) — an implementor written against the widened signature must still satisfy
    //   the interface and be callable with the two-argument shape.

    @Test
    fun `two-argument call shape still compiles and is accepted`() = runTest {
        server.enqueue(MockResponse.Builder().code(202).body("""{"id":"job-abc"}""").build())
        val outcome = client.submitJob(tempAudioFile(), "audio/mp4")
        assertTrue(outcome is SubmitOutcome.Accepted)
    }

    @Test
    fun `three-argument call shape compiles and is accepted`() = runTest {
        server.enqueue(MockResponse.Builder().code(202).body("""{"id":"job-abc"}""").build())
        val outcome = client.submitJob(tempAudioFile(), "audio/mp4", "es")
        assertTrue(outcome is SubmitOutcome.Accepted)
    }

    @Test
    fun `an anonymous implementor still satisfies the widened interface`() = runTest {
        val fakeClient = object : TranscriptionClient {
            override suspend fun submitJob(
                file: File,
                mimeType: String,
                language: String,
                vocabulary: List<String>,
            ): SubmitOutcome = SubmitOutcome.Accepted("fake-job")

            override suspend fun getJobStatus(jobId: String): JobStatusOutcome =
                JobStatusOutcome.Pending
        }
        val outcome = fakeClient.submitJob(tempAudioFile(), "audio/mp4")
        assertTrue(outcome is SubmitOutcome.Accepted)
        assertEquals("fake-job", (outcome as SubmitOutcome.Accepted).jobId)
    }

    // BIAS-01 (15-05): the vocabulary parameter's wire-level proof. Mirrors the language test trio
    // above — presence-and-shape, true absence (not merely a default value), and the full
    // three-way language < vocabulary < file ordering (Codex review finding #15).
    @Test
    fun `explicit vocabulary reaches the wire as a JSON-array form part`() = runTest {
        server.enqueue(MockResponse.Builder().code(202).body("""{"id":"job-abc"}""").build())
        client.submitJob(tempAudioFile(), "audio/mp4", vocabulary = listOf("Margolotte", "SecondBrain"))
        val requestBody = server.takeRequest().body!!.utf8()
        assertTrue(
            "expected the request body to carry a form part named \"vocabulary\"",
            requestBody.contains("name=\"vocabulary\""),
        )
        assertTrue(
            "expected the vocabulary part's value to be the JSON array [\"Margolotte\",\"SecondBrain\"]",
            requestBody.contains("""["Margolotte","SecondBrain"]"""),
        )
    }

    @Test
    fun `omitted vocabulary sends no vocabulary form part`() = runTest {
        server.enqueue(MockResponse.Builder().code(202).body("""{"id":"job-abc"}""").build())
        client.submitJob(tempAudioFile(), "audio/mp4")
        val requestBody = server.takeRequest().body!!.utf8()
        assertTrue(
            "expected NO \"vocabulary\" form part when vocabulary is omitted (true absence, not a default value)",
            !requestBody.contains("name=\"vocabulary\""),
        )
    }

    @Test
    fun `vocabulary part sits between language and file when present`() = runTest {
        server.enqueue(MockResponse.Builder().code(202).body("""{"id":"job-abc"}""").build())
        client.submitJob(tempAudioFile(), "audio/mp4", language = "es", vocabulary = listOf("Yahir"))
        val requestBody = server.takeRequest().body!!.utf8()
        val languageIndex = requestBody.indexOf("name=\"language\"")
        val vocabularyIndex = requestBody.indexOf("name=\"vocabulary\"")
        val fileIndex = requestBody.indexOf("name=\"file\"")
        assertTrue("expected the language part to be present", languageIndex >= 0)
        assertTrue("expected the vocabulary part to be present", vocabularyIndex >= 0)
        assertTrue("expected the file part to be present", fileIndex >= 0)
        assertTrue(
            "expected the full ordering language ($languageIndex) < vocabulary ($vocabularyIndex) " +
                "< file ($fileIndex), the locked cross-client convention (Plan 03/04)",
            languageIndex < vocabularyIndex && vocabularyIndex < fileIndex,
        )
    }

    private fun tempAudioFile(): File =
        File.createTempFile("clip", ".m4a").apply {
            deleteOnExit() // IN-02 (136-REVIEW.md): avoid leaking stray clip*.m4a files per test run
            writeBytes(byteArrayOf(0, 1, 2, 3))
        }
}
