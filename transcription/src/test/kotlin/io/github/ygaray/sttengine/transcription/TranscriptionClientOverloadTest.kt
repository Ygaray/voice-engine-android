package io.github.ygaray.sttengine.transcription

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-18 (N-1): the `retainForCorpus` opt-in is a NEW overload of [TranscriptionClient.submitJob]
 * ADDED beside the unchanged four-parameter member. These tests pin the default-body behavior an
 * implementer that never heard of the flag gets (false delegates, true is a typed refusal that
 * uploads nothing), the JVM-default mode that keeps pre-compiled implementers off
 * `AbstractMethodError`, and Kotlin's overload resolution for every call shape.
 */
class TranscriptionClientOverloadTest {

    /** Overrides ONLY the four-parameter member (the shape of every pre-existing implementer). */
    private class LegacyFake : TranscriptionClient {
        var invocations: Int = 0
        var lastLanguage: String? = null
        var lastVocabulary: List<String>? = null
        var lastMimeType: String? = null
        var lastFile: File? = null

        override suspend fun submitJob(
            file: File,
            mimeType: String,
            language: String,
            vocabulary: List<String>,
        ): SubmitOutcome {
            invocations++
            lastFile = file
            lastMimeType = mimeType
            lastLanguage = language
            lastVocabulary = vocabulary
            return SubmitOutcome.Accepted("legacy-job")
        }

        override suspend fun getJobStatus(jobId: String): JobStatusOutcome = JobStatusOutcome.Pending
    }

    /** Overrides BOTH members and records which one ran. */
    private class RecordingFake : TranscriptionClient {
        val calls: MutableList<String> = mutableListOf()
        var lastLanguage: String? = null
        var lastVocabulary: List<String>? = null

        override suspend fun submitJob(
            file: File,
            mimeType: String,
            language: String,
            vocabulary: List<String>,
        ): SubmitOutcome {
            calls += "legacy"
            lastLanguage = language
            lastVocabulary = vocabulary
            return SubmitOutcome.Accepted("legacy")
        }

        override suspend fun submitJob(
            file: File,
            mimeType: String,
            language: String,
            vocabulary: List<String>,
            retainForCorpus: Boolean,
        ): SubmitOutcome {
            calls += "new(retain=$retainForCorpus)"
            lastLanguage = language
            lastVocabulary = vocabulary
            return SubmitOutcome.Accepted("new")
        }

        override suspend fun getJobStatus(jobId: String): JobStatusOutcome = JobStatusOutcome.Pending
    }

    @Test
    fun `non-overriding fake flagless call reaches the four-parameter member once`() = runTest {
        val fake = LegacyFake()
        val outcome = fake.submitJob(tempAudioFile(), "audio/mp4")
        assertEquals(SubmitOutcome.Accepted("legacy-job"), outcome)
        assertEquals(1, fake.invocations)
    }

    @Test
    fun `non-overriding fake flag false delegates with identical arguments`() = runTest {
        val fake = LegacyFake()
        val file = tempAudioFile()
        val outcome = fake.submitJob(file, "audio/mp4", "es", listOf("a", "b"), retainForCorpus = false)
        assertEquals(SubmitOutcome.Accepted("legacy-job"), outcome)
        assertEquals(1, fake.invocations)
        assertEquals(file, fake.lastFile)
        assertEquals("audio/mp4", fake.lastMimeType)
        assertEquals("es", fake.lastLanguage)
        assertEquals(listOf("a", "b"), fake.lastVocabulary)
    }

    @Test
    fun `non-overriding fake flag true is rejected without calling the member`() = runTest {
        val fake = LegacyFake()
        val outcome = fake.submitJob(tempAudioFile(), "audio/mp4", retainForCorpus = true)
        assertTrue("expected a typed Rejected, got $outcome", outcome is SubmitOutcome.Rejected)
        outcome as SubmitOutcome.Rejected
        assertEquals(TranscriptionErrorCode.UPLOAD_REJECTED, outcome.code)
        assertTrue(outcome.message, outcome.message.contains("retainForCorpus"))
        assertTrue(outcome.message, outcome.message.contains("nothing was uploaded"))
        assertEquals("the four-parameter member must NOT be invoked for true", 0, fake.invocations)
    }

    @Test
    fun `overriding fake receives the flag through the five-parameter overload`() = runTest {
        val fake = RecordingFake()
        val outcome = fake.submitJob(tempAudioFile(), "audio/mp4", "es", listOf("x"), retainForCorpus = true)
        assertEquals(SubmitOutcome.Accepted("new"), outcome)
        assertEquals(listOf("new(retain=true)"), fake.calls)
        assertEquals("es", fake.lastLanguage)
        assertEquals(listOf("x"), fake.lastVocabulary)
    }

    @Test
    fun `JVM default pin - the new overload is a default method and the four-parameter member stays abstract`() {
        val methods = TranscriptionClient::class.java.methods.filter { it.name == "submitJob" }
        val newOverload = methods.single { m ->
            m.parameterTypes.size == 6 && m.parameterTypes[4] == java.lang.Boolean.TYPE
        }
        val legacyOverload = methods.single { m ->
            m.parameterTypes.size == 5 && m.parameterTypes[3] == java.util.List::class.java
        }
        assertTrue(
            "the five-parameter submitJob must compile to a real JVM default method (-jvm-default=enable); " +
                "a switch to DefaultImpls-only would make pre-compiled implementers throw AbstractMethodError",
            newOverload.isDefault,
        )
        assertFalse("the four-parameter submitJob must stay non-default", legacyOverload.isDefault)
        assertTrue(
            "the four-parameter submitJob must stay abstract",
            java.lang.reflect.Modifier.isAbstract(legacyOverload.modifiers),
        )
    }

    // Overload resolution (D-11 + D-18). The file compiling at all is the proof that none of these
    // call shapes is ambiguous; the recorded values prove WHICH member each shape binds to.

    @Test
    fun `legacy shapes bind to the four-parameter member - positional`() = runTest {
        val fake = RecordingFake()
        val f = tempAudioFile()
        fake.submitJob(f, "audio/mp4")
        fake.submitJob(f, "audio/mp4", "es")
        fake.submitJob(f, "audio/mp4", "es", listOf("a"))
        assertEquals(listOf("legacy", "legacy", "legacy"), fake.calls)
    }

    @Test
    fun `legacy shapes bind to the four-parameter member - named language and vocabulary`() = runTest {
        val fake = RecordingFake()
        val f = tempAudioFile()
        fake.submitJob(f, "audio/mp4", language = "es")
        fake.submitJob(f, "audio/mp4", vocabulary = listOf("a"))
        fake.submitJob(f, "audio/mp4", language = "es", vocabulary = listOf("a"))
        assertEquals(listOf("legacy", "legacy", "legacy"), fake.calls)
    }

    @Test
    fun `flag shapes bind to the five-parameter member with the flag they were given`() = runTest {
        val fake = RecordingFake()
        val f = tempAudioFile()
        fake.submitJob(f, "audio/mp4", retainForCorpus = true)
        fake.submitJob(f, "audio/mp4", retainForCorpus = false)
        fake.submitJob(f, "audio/mp4", "es", listOf("a"), true)
        fake.submitJob(f, "audio/mp4", language = "es", retainForCorpus = true)
        assertEquals(
            listOf("new(retain=true)", "new(retain=false)", "new(retain=true)", "new(retain=true)"),
            fake.calls,
        )
        assertEquals("es", fake.lastLanguage)
    }

    private fun tempAudioFile(): File =
        File.createTempFile("clip", ".m4a").apply {
            deleteOnExit()
            writeBytes(byteArrayOf(0, 1, 2, 3))
        }
}
