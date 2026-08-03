package io.github.ygaray.sttengine.internal

import android.os.BadParcelableException
import android.os.Bundle
import android.speech.SpeechRecognizer

/**
 * Null-safe extraction from recognizer result bundles. **Every** callback path reads through here.
 *
 * **Why this file exists at all.** A `Bundle` arriving from `RecognitionListener` is cross-process
 * input handed over Binder by an OEM-controlled process — the library's only untrusted input
 * channel (threat T-05-12, 05-RESEARCH §Security Domain V5). It can be null, it can carry no keys
 * at all, its results list can be empty, and its strings can be blank. Not one of those is
 * hypothetical:
 *
 * - **Zero keys is the MEASURED behaviour of `onResults` on the phase's tester device.** Both on
 *   natural silence-end and on an explicit `stopListening()`, the `onResults` Bundle came back with
 *   `keys: []`, while partial bundles dumped by the same code carried three populated keys
 *   (`.planning/phases/05-android-library/05-RUNG-VERDICT.md`). Reading a final out of `onResults`
 *   without this guard would have been a `NullPointerException` in a platform callback — i.e. a
 *   host-app crash — on the very first real dictation.
 * - Blank-string lists are the documented shape of a no-match result.
 *
 * **The Kotlin not-null assertion operator is banned in this file** and the ban is checked
 * mechanically: an acceptance criterion greps this file for that operator and expects a count of
 * ZERO. Note that the operator is therefore never written out here, not even inside this comment —
 * a gate that its own documentation trips is not a gate. Using it here would convert an OEM's
 * laziness into the host's crash.
 *
 * Not an `object` with state and not an extension on `Bundle?` — plain functions on a namespace, so
 * a test can call them with a literal `null` and read like the thing it proves.
 */
internal object RecognizerBundles {

    /**
     * The best available text in a results/partial/segment bundle, or `null` when there is none.
     *
     * "Best available" is `RESULTS_RECOGNITION[0]` after blank entries are discarded: the platform
     * documents that list as ordered most-to-least confident, so the first non-blank entry is the
     * recognizer's own top hypothesis. Trailing candidates are deliberately not surfaced — v1
     * dictation has no n-best UI, and inventing one here would put a choice in the contract that
     * D-13 keeps out of it.
     *
     * Returns `null` — never throws, never returns a blank string — for each of:
     * a null bundle, a bundle with no `RESULTS_RECOGNITION` key, an empty list, and a list whose
     * entries are all blank.
     */
    fun firstNonBlankResult(bundle: Bundle?): String? {
        val results = readStringArrayList(bundle, SpeechRecognizer.RESULTS_RECOGNITION) ?: return null
        return results.firstOrNull { !it.isNullOrBlank() }
    }

    /**
     * The confidence scores parallel to [firstNonBlankResult]'s list, or `null` when absent.
     *
     * Read exactly as defensively as the text: the platform documents this extra as OPTIONAL — a
     * recognition service may omit it entirely — so its absence is normal, not an error. An empty
     * array is normalised to `null` so a caller has one emptiness check instead of two.
     *
     * Not consumed by v1 (the contract has no confidence field). It is read here, next to the text
     * it belongs to, so that the day a caller wants it there is no second place that learns how to
     * parse a hostile bundle.
     */
    fun confidenceScores(bundle: Bundle?): FloatArray? {
        if (bundle == null) return null
        val scores =
            try {
                bundle.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            } catch (e: BadParcelableException) {
                // See readStringArrayList for why this is caught rather than propagated.
                null
            }
        return if (scores == null || scores.isEmpty()) null else scores
    }

    /**
     * The one place a key is actually read off an untrusted bundle.
     *
     * [BadParcelableException] is caught deliberately and narrowly. `Bundle` unparcels lazily on
     * first `get`, so a malformed payload from the recognition service surfaces *here*, inside a
     * platform callback on the main looper, where an escape kills the host app rather than the
     * engine. Treating it as "no text" is the same disposition the empty-bundle case already has,
     * so the caller needs no new branch. Nothing broader is caught: a `RuntimeException` from our
     * own code above this line is a bug and must stay loud.
     *
     * A type mismatch needs no catch — `Bundle.getStringArrayList` already absorbs the
     * `ClassCastException` internally and returns null.
     */
    private fun readStringArrayList(bundle: Bundle?, key: String): List<String?>? {
        if (bundle == null) return null
        return try {
            bundle.getStringArrayList(key)
        } catch (e: BadParcelableException) {
            null
        }
    }
}
