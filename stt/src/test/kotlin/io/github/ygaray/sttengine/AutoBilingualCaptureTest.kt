package io.github.ygaray.sttengine

import android.os.Bundle
import android.speech.SpeechRecognizer
import io.github.ygaray.sttengine.corrections.CorrectionPair
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `SttConfig(language = "auto")` on the native backend (Phase 18, NBIL-01..NBIL-09) — offline EN/ES
 * detection on API 34+, stamped onto [FinalSegment.language] as bare `"en"`/`"es"` from a
 * `HIGHLY_CONFIDENT` (`confidenceLevel == 3`) detection event only, else `null`; runtime-gated on the
 * injected `sdkInt` with a JVM-testable sub-34 fallback. See `18-CONTEXT.md` D-01..D-03.
 *
 * `@Config(sdk = [35])` matches [io.github.ygaray.sttengine.NativeLanguageResolutionTest]'s header
 * exactly — 35 is the tester's own `SDK_INT` and is cached locally; the sub-34 branch is driven by
 * [EngineHarness]'s injected `sdkInt` parameter, never by lowering Robolectric's own SDK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoBilingualCaptureTest {

    @Test
    fun `an API-34+ auto session stamps a confidence-3 detected language onto the committed final`() =
        runTest {
            val h = EngineHarness(testScheduler, config = SttConfig(language = "auto"))
            h.collectInto(backgroundScope)
            h.reachListening()

            h.fake.fireLanguageDetection(highlyConfidentDetection("es-US"))
            h.fake.fireSegmentResults("hola mundo")
            advanceUntilIdle()

            assertEquals(1, h.finals.size)
            assertEquals("hola mundo", h.finals[0].text)
            assertEquals("es", h.finals[0].language)
        }

    // --- Task 2: detection-mapping semantics (NBIL-02/NBIL-09) -------------------------------------

    @Test
    fun `a segment final with no preceding detection commits with a null language`() = runTest {
        val h = EngineHarness(testScheduler, config = SttConfig(language = "auto"))
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.fireSegmentResults("no detection ever fired")
        advanceUntilIdle()

        assertEquals(1, h.finals.size)
        assertEquals(null, h.finals[0].language)
    }

    @Test
    fun `a below-highly-confident detection is ignored, the label stays null`() = runTest {
        val h = EngineHarness(testScheduler, config = SttConfig(language = "auto"))
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.fireLanguageDetection(
            detectionBundle("es-US", SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT),
        )
        h.fake.fireSegmentResults("below threshold")
        advanceUntilIdle()

        assertEquals(1, h.finals.size)
        assertEquals(null, h.finals[0].language)
    }

    @Test
    fun `a confidence-3 detection outside the en-es allowlist normalizes to null`() = runTest {
        val h = EngineHarness(testScheduler, config = SttConfig(language = "auto"))
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.fireLanguageDetection(highlyConfidentDetection("de-DE"))
        h.fake.fireSegmentResults("nicht englisch oder spanisch")
        advanceUntilIdle()

        assertEquals(1, h.finals.size)
        assertEquals(null, h.finals[0].language)
    }

    @Test
    fun `per-segment reset - a detection-free segment after a labeled one commits null`() = runTest {
        val h = EngineHarness(testScheduler, config = SttConfig(language = "auto"))
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.fireLanguageDetection(highlyConfidentDetection("es-US"))
        h.fake.fireSegmentResults("primero")
        advanceUntilIdle()
        h.fake.fireSegmentResults("segundo")
        advanceUntilIdle()

        assertEquals(2, h.finals.size)
        assertEquals(listOf("es", null), h.finals.map { it.language })
    }

    // PROVISIONAL — NBIL-09: a continuous multi-segment session stamps a correct,
    // independently-changing per-segment label (D-05, pending Phase 19's real-mic confirmation).
    @Test
    fun `a continuous session with three segments stamps each one's own independently-changing label`() =
        runTest {
            val h = EngineHarness(testScheduler, config = SttConfig(language = "auto"))
            h.collectInto(backgroundScope)
            h.reachListening()

            h.fake.fireLanguageDetection(highlyConfidentDetection("en-US"))
            h.fake.fireSegmentResults("first segment in english")
            advanceUntilIdle()

            h.fake.fireLanguageDetection(highlyConfidentDetection("es-US"))
            h.fake.fireSegmentResults("segundo segmento en espanol")
            advanceUntilIdle()

            h.fake.fireSegmentResults("third segment with no detection")
            advanceUntilIdle()

            assertEquals(3, h.finals.size)
            assertEquals(listOf("en", "es", null), h.finals.map { it.language })
        }

    @Test
    fun `the corrections branch carries the detected label alongside the corrected text`() = runTest {
        val h = EngineHarness(
            testScheduler,
            config = SttConfig(language = "auto", corrections = listOf(CorrectionPair("teh", "the"))),
        )
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.fireLanguageDetection(highlyConfidentDetection("en-US"))
        h.fake.fireSegmentResults("teh cat sat")
        advanceUntilIdle()

        assertEquals(1, h.finals.size)
        assertEquals("the cat sat", h.finals[0].text)
        assertEquals("en", h.finals[0].language)
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun highlyConfidentDetection(detectedLanguage: String): Bundle =
        detectionBundle(detectedLanguage, SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_HIGHLY_CONFIDENT)

    private fun detectionBundle(detectedLanguage: String, confidenceLevel: Int): Bundle = Bundle().apply {
        putString(SpeechRecognizer.DETECTED_LANGUAGE, detectedLanguage)
        putInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL, confidenceLevel)
    }
}
