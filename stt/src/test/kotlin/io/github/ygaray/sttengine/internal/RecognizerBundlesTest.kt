package io.github.ygaray.sttengine.internal

import android.os.Bundle
import android.speech.SpeechRecognizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The T-05-12 mitigation, asserted rather than asserted-about.
 *
 * Plan 04's task-1 acceptance criterion requires that a null bundle, a missing results key, an
 * empty list and a list of blank strings each yield "no text" rather than an exception, "asserted
 * by a unit test in this plan or plan 06". Doing it here rather than deferring it keeps the
 * guard and its proof in the same commit.
 *
 * Robolectric is required: `Bundle` is a no-op stub on the plain-JVM mockable `android.jar` (see
 * [FakeRecognizerHandle]'s header), so a JVM-only version of this file would assert null on every
 * case and pass for the wrong reason. `sdk = 35` matches the tester device's `SDK_INT` and runs on
 * this project's Java 17 — see `MainThreadContractTest` for why it is not 36.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecognizerBundlesTest {

    private fun bundleOfResults(vararg texts: String?): Bundle =
        Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(texts.toList()))
        }

    // --- the four hostile shapes -----------------------------------------------------------

    @Test
    fun `a null bundle yields no text`() {
        assertNull(RecognizerBundles.firstNonBlankResult(null))
    }

    @Test
    fun `a bundle with ZERO keys yields no text -- the tester device's real onResults`() {
        // MEASURED: on the Samsung SM-S908U the onResults Bundle came back with keys=[] both on
        // natural silence-end and on an explicit stopListening() (05-RUNG-VERDICT.md). This is the
        // case that actually happens, not a hypothetical.
        val empty = Bundle()
        assertEquals(0, empty.keySet().size)
        assertNull(RecognizerBundles.firstNonBlankResult(empty))
    }

    @Test
    fun `a bundle whose results list is empty yields no text`() {
        assertNull(RecognizerBundles.firstNonBlankResult(bundleOfResults()))
    }

    @Test
    fun `a bundle whose results are all blank yields no text`() {
        assertNull(RecognizerBundles.firstNonBlankResult(bundleOfResults("", "   ", "\t", "\n")))
    }

    @Test
    fun `a null entry inside the results list yields no text`() {
        assertNull(RecognizerBundles.firstNonBlankResult(bundleOfResults(null, null)))
    }

    // --- and the shapes that DO carry text -------------------------------------------------

    @Test
    fun `the first entry is returned when it is non-blank`() {
        assertEquals("the morning", RecognizerBundles.firstNonBlankResult(bundleOfResults("the morning", "the mourning")))
    }

    @Test
    fun `blank and null leading entries are skipped rather than returned`() {
        assertEquals(
            "the morning",
            RecognizerBundles.firstNonBlankResult(bundleOfResults("", null, "   ", "the morning")),
        )
    }

    @Test
    fun `whitespace inside a hypothesis is preserved -- only blankness is filtered`() {
        assertEquals(" the  morning ", RecognizerBundles.firstNonBlankResult(bundleOfResults(" the  morning ")))
    }

    // --- confidence scores, read exactly as defensively ------------------------------------

    @Test
    fun `confidence scores tolerate a null bundle, a missing key and an empty array`() {
        assertNull(RecognizerBundles.confidenceScores(null))
        assertNull(RecognizerBundles.confidenceScores(Bundle()))
        assertNull(
            RecognizerBundles.confidenceScores(
                Bundle().apply { putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf()) },
            ),
        )
    }

    @Test
    fun `confidence scores are returned when present`() {
        val bundle = Bundle().apply {
            putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(0.9f, 0.4f))
        }
        assertArrayEquals(floatArrayOf(0.9f, 0.4f), RecognizerBundles.confidenceScores(bundle), 0.0001f)
    }

    /**
     * The extras are read from a bundle populated with the platform's own key CONSTANTS, so a
     * typo'd key string would fail here rather than silently return null forever.
     */
    @Test
    fun `the keys read are the platform's own constants`() {
        assertEquals("results_recognition", SpeechRecognizer.RESULTS_RECOGNITION)
        assertEquals("confidence_scores", SpeechRecognizer.CONFIDENCE_SCORES)
    }
}
