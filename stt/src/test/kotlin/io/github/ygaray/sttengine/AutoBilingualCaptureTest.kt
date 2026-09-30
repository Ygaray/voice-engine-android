package io.github.ygaray.sttengine

import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.ygaray.sttengine.corrections.CorrectionPair
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `WR-03 - a later out-of-allowlist confidence-3 detection does not blank an earlier valid label`() =
        runTest {
            val h = EngineHarness(testScheduler, config = SttConfig(language = "auto"))
            h.collectInto(backgroundScope)
            h.reachListening()

            h.fake.fireLanguageDetection(highlyConfidentDetection("es-US"))
            h.fake.fireLanguageDetection(highlyConfidentDetection("de-DE"))
            h.fake.fireSegmentResults("una fraseta seguida de ruido fuera de la lista")
            advanceUntilIdle()

            assertEquals(1, h.finals.size)
            assertEquals("es", h.finals[0].language)
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

    // --- Task 3: sub-34 fallback, allowlist, non-"auto" no-regression (NBIL-04/NBIL-07) ------------

    @Test
    fun `on injected sdkInt below 34, an auto session sets no switch extras and falls back to bare en`() =
        runTest {
            val h = EngineHarness(testScheduler, config = SttConfig(language = "auto"), sdkInt = 33)
            h.collectInto(backgroundScope)
            h.reachListening()

            val intent = h.fake.lastIntent
            assertEquals(false, intent?.hasExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH))
            assertEquals("en-US", intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))

            h.fake.fireSegmentResults("sub 34 fallback")
            advanceUntilIdle()

            assertEquals(1, h.finals.size)
            assertEquals("en", h.finals[0].language)
        }

    @Test
    fun `sub-34 auto ignores a fired detection - the final still falls back to bare en`() = runTest {
        val h = EngineHarness(testScheduler, config = SttConfig(language = "auto"), sdkInt = 33)
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.fireLanguageDetection(highlyConfidentDetection("es-US"))
        h.fake.fireSegmentResults("detection fired but must be ignored below 34")
        advanceUntilIdle()

        assertEquals(1, h.finals.size)
        assertEquals("en", h.finals[0].language)
    }

    @Test
    fun `a non-auto session sets no switch extras and every final has a null language (NBIL-07)`() =
        runTest {
            val h = EngineHarness(testScheduler, config = SttConfig(language = "en"), sdkInt = 35)
            h.collectInto(backgroundScope)
            h.reachListening()

            val intent = h.fake.lastIntent
            assertEquals(false, intent?.hasExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH))
            assertEquals("en-US", intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))

            h.fake.fireSegmentResults("non auto session")
            advanceUntilIdle()

            assertEquals(1, h.finals.size)
            assertEquals(null, h.finals[0].language)
        }

    // --- Task 2: runtime LANGUAGE_SWITCH_RESULT_SKIPPED_NO_MODEL (NBIL-10) -------------------------

    @Test
    fun `a live auto session receiving a SKIPPED_NO_MODEL switchResult fails loudly with one NO_OFFLINE_MODEL error`() =
        runTest {
            val h = EngineHarness(testScheduler, config = SttConfig(language = "auto"))
            h.collectInto(backgroundScope)
            h.reachListening()

            h.fake.fireLanguageDetection(skippedNoModelBundle(detectedLanguage = "es-US"))
            advanceUntilIdle()

            assertEquals(1, h.errors.size)
            assertEquals(SttErrorCode.NO_OFFLINE_MODEL, h.errors.single().code)
            assertTrue(h.errors.single().message.contains("es-US"))
            assertEquals(EngineState.Error, h.engine.state.value)
        }

    @Test
    fun `the same SKIPPED_NO_MODEL event with no DETECTED_LANGUAGE key still fails safely, no crash`() =
        runTest {
            val h = EngineHarness(testScheduler, config = SttConfig(language = "auto"))
            h.collectInto(backgroundScope)
            h.reachListening()

            val bundle = Bundle().apply {
                putInt(SpeechRecognizer.LANGUAGE_SWITCH_RESULT, SpeechRecognizer.LANGUAGE_SWITCH_RESULT_SKIPPED_NO_MODEL)
                // Deliberately no DETECTED_LANGUAGE key.
            }
            h.fake.fireLanguageDetection(bundle)
            advanceUntilIdle()

            assertEquals(1, h.errors.size)
            assertEquals(SttErrorCode.NO_OFFLINE_MODEL, h.errors.single().code)
            assertEquals(EngineState.Error, h.engine.state.value)
        }

    @Test
    fun `a confidence-3 detection with no SKIPPED_NO_MODEL switchResult never triggers fail()`() =
        runTest {
            val h = EngineHarness(testScheduler, config = SttConfig(language = "auto"))
            h.collectInto(backgroundScope)
            h.reachListening()

            h.fake.fireLanguageDetection(highlyConfidentDetection("en-US"))
            advanceUntilIdle()

            assertEquals("a plain confidence-3 detection must not fail() the session", 0, h.errors.size)
            assertEquals(EngineState.Listening, h.engine.state.value)
        }

    @Test
    fun `a second SKIPPED_NO_MODEL event after the first still yields exactly one error (idempotent)`() =
        runTest {
            val h = EngineHarness(testScheduler, config = SttConfig(language = "auto"))
            h.collectInto(backgroundScope)
            h.reachListening()

            val target = h.listenerFor(0)
            h.fake.fireLanguageDetection(skippedNoModelBundle(detectedLanguage = "es-US"), target = target)
            advanceUntilIdle()
            h.fake.fireLanguageDetection(skippedNoModelBundle(detectedLanguage = "es-US"), target = target)
            advanceUntilIdle()

            assertEquals(1, h.errors.size)
        }

    // --- helpers -----------------------------------------------------------------------------------

    private fun highlyConfidentDetection(detectedLanguage: String): Bundle =
        detectionBundle(detectedLanguage, SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_HIGHLY_CONFIDENT)

    private fun detectionBundle(detectedLanguage: String, confidenceLevel: Int): Bundle = Bundle().apply {
        putString(SpeechRecognizer.DETECTED_LANGUAGE, detectedLanguage)
        putInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL, confidenceLevel)
    }

    private fun skippedNoModelBundle(detectedLanguage: String): Bundle = Bundle().apply {
        putString(SpeechRecognizer.DETECTED_LANGUAGE, detectedLanguage)
        putInt(SpeechRecognizer.LANGUAGE_SWITCH_RESULT, SpeechRecognizer.LANGUAGE_SWITCH_RESULT_SKIPPED_NO_MODEL)
    }
}
