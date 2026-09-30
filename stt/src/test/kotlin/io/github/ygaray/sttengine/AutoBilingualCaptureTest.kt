package io.github.ygaray.sttengine

import android.os.Bundle
import android.speech.SpeechRecognizer
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

    // --- helpers -----------------------------------------------------------------------------------

    private fun highlyConfidentDetection(detectedLanguage: String): Bundle = Bundle().apply {
        putString(SpeechRecognizer.DETECTED_LANGUAGE, detectedLanguage)
        putInt(
            SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL,
            SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_HIGHLY_CONFIDENT,
        )
    }
}
