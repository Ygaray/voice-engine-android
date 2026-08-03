package io.github.ygaray.sttengine.internal

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer

/**
 * The test double for [RecognizerHandle], and the fixture the rest of this phase is proved with.
 *
 * **This class gates plans 05, 06 and 08** — they all drive their subjects through it. It is the
 * translation of `web/tests/errors.test.ts`'s `FakeWs` + `setup()` + `reachListening()` harness into
 * Kotlin: record what the subject did, then fire callbacks back into it on demand.
 *
 * It exists because Robolectric cannot do this job. `ShadowSpeechRecognizer` 4.16.1 has **no**
 * `triggerOnSegmentResults` and **no** `triggerOnEndOfSegmentedSession` [VERIFIED by `javap` of
 * `shadows-framework-4.16.1.jar` this session], which are exactly the callbacks rungs (b)/(c) run
 * on — and both rungs measured SHIP on the tester device, so they are real scope, not a maybe. The
 * shadow also has no way to deliver a callback into a *stale* listener, which is the scenario plan
 * 05's epoch fence exists to survive. This fake has neither gap.
 *
 * ## Read this before writing a test that asserts on text
 *
 * **Any test that fires text through this fake must run under Robolectric:**
 *
 * ```kotlin
 * @RunWith(RobolectricTestRunner::class)
 * @Config(sdk = [36])          // 36 is cached in this host's ~/.m2; 33 is NOT and needs network
 * class MyTest { … }
 * ```
 *
 * MEASURED, not assumed: on the plain-JVM mockable `android.jar` that
 * `testOptions.unitTests.isReturnDefaultValues = true` produces, `Bundle.putStringArrayList` is a
 * no-op and `getStringArrayList` returns `null` — a `Bundle` built there carries nothing (probe run
 * during plan 04: `bundle=null roundtrip=null`). A fake firing empty bundles into a subject would
 * make "no text was committed" tests pass **for the wrong reason**, which is the same class of
 * silently-inert assertion plan 02 caught in its own parity gate. So the text-carrying builders
 * here **self-check and throw** rather than let that happen — see [resultsBundle].
 *
 * Everything that does NOT touch bundle contents (call counts, ordering, [lastIntent] identity,
 * [fireError], [fireEndOfSegmentedSession]) works fine on the plain JVM with no Robolectric.
 */
internal class FakeRecognizerHandle : RecognizerHandle {

    // ---------------------------------------------------------------------------------------
    // What the subject did
    // ---------------------------------------------------------------------------------------

    /** Every listener ever registered, oldest first. Index into it to hold a STALE reference. */
    val registeredListeners: MutableList<RecognitionListener> = mutableListOf()

    /** The listener the subject registered most recently — the one [fire] targets by default. */
    val listener: RecognitionListener?
        get() = registeredListeners.lastOrNull()

    /** The last intent passed to [startListening]. Identity-comparable without Robolectric. */
    var lastIntent: Intent? = null
        private set

    /** Every intent passed to [startListening], oldest first — one per session. */
    val startedIntents: MutableList<Intent> = mutableListOf()

    var setListenerCount: Int = 0
        private set
    var startCount: Int = 0
        private set
    var stopCount: Int = 0
        private set
    var cancelCount: Int = 0
        private set
    var destroyCount: Int = 0
        private set

    /**
     * The ordered call log. Order is the whole point of several downstream assertions — D-03's
     * restart sequence requires commit-then-teardown, and "did stop happen before the next start"
     * is not answerable from counters alone.
     */
    val calls: MutableList<String> = mutableListOf()

    fun resetCounts() {
        setListenerCount = 0
        startCount = 0
        stopCount = 0
        cancelCount = 0
        destroyCount = 0
        calls.clear()
    }

    // ---------------------------------------------------------------------------------------
    // RecognizerHandle
    // ---------------------------------------------------------------------------------------

    override fun setListener(listener: RecognitionListener) {
        registeredListeners += listener
        setListenerCount++
        calls += "setListener"
    }

    override fun startListening(intent: Intent) {
        lastIntent = intent
        startedIntents += intent
        startCount++
        calls += "startListening"
    }

    override fun stopListening() {
        stopCount++
        calls += "stopListening"
    }

    override fun cancel() {
        cancelCount++
        calls += "cancel"
    }

    override fun destroy() {
        destroyCount++
        calls += "destroy"
    }

    // ---------------------------------------------------------------------------------------
    // Stimulus — fire callbacks back into the subject
    // ---------------------------------------------------------------------------------------
    //
    // Every fire* method takes an optional `target`. Passing one is how a test delivers a callback
    // into a listener that a NEWER session has already replaced — the late-callback-from-a-cancelled
    // -session scenario plan 05's epoch fence has to survive, and the single thing the Robolectric
    // shadow cannot express at all.

    fun fireReadyForSpeech(params: Bundle? = null, target: RecognitionListener? = null) {
        fire(target) { it.onReadyForSpeech(params) }
    }

    fun fireBeginningOfSpeech(target: RecognitionListener? = null) {
        fire(target) { it.onBeginningOfSpeech() }
    }

    fun fireRmsChanged(level: Float, target: RecognitionListener? = null) {
        fire(target) { it.onRmsChanged(level) }
    }

    fun fireBufferReceived(buffer: ByteArray?, target: RecognitionListener? = null) {
        fire(target) { it.onBufferReceived(buffer) }
    }

    fun fireEndOfSpeech(target: RecognitionListener? = null) {
        fire(target) { it.onEndOfSpeech() }
    }

    /** `onError(code)`. Pass any Int — including one outside the documented set; that is the point. */
    fun fireError(code: Int, target: RecognitionListener? = null) {
        fire(target) { it.onError(code) }
    }

    fun firePartial(vararg texts: String, target: RecognitionListener? = null) {
        val bundle = resultsBundle(texts)
        fire(target) { it.onPartialResults(bundle) }
    }

    fun fireResults(vararg texts: String, target: RecognitionListener? = null) {
        val bundle = resultsBundle(texts)
        fire(target) { it.onResults(bundle) }
    }

    fun fireSegmentResults(vararg texts: String, target: RecognitionListener? = null) {
        val bundle = resultsBundle(texts)
        fire(target) { it.onSegmentResults(bundle) }
    }

    fun fireEndOfSegmentedSession(target: RecognitionListener? = null) {
        fire(target) { it.onEndOfSegmentedSession() }
    }

    fun fireEvent(eventType: Int, params: Bundle? = null, target: RecognitionListener? = null) {
        fire(target) { it.onEvent(eventType, params) }
    }

    fun fireLanguageDetection(results: Bundle, target: RecognitionListener? = null) {
        fire(target) { it.onLanguageDetection(results) }
    }

    // ---------------------------------------------------------------------------------------
    // Degenerate stimulus — the hostile-bundle shapes, which are contract, not edge cases
    // ---------------------------------------------------------------------------------------

    /** Which callback carries the bundle. */
    enum class ResultChannel { PARTIAL, RESULTS, SEGMENT }

    /**
     * The four ways a recognizer bundle can carry no usable text.
     *
     * [EMPTY_BUNDLE] is not hypothetical: it is the MEASURED shape of `onResults` on this phase's
     * tester device — zero keys, on natural silence-end AND on an explicit `stopListening()`
     * (`05-RUNG-VERDICT.md` §"Unplanned finding"). It is distinct from [NULL] and a test that only
     * covers [NULL] has not covered the case that actually happens.
     */
    enum class DegenerateBundle {
        /** The listener is handed `null`. */
        NULL,

        /** A Bundle with ZERO keys — the tester device's real `onResults`. */
        EMPTY_BUNDLE,

        /** `RESULTS_RECOGNITION` present but an empty list. */
        EMPTY_LIST,

        /** `RESULTS_RECOGNITION` present, entries present, every entry blank. */
        BLANK_STRINGS,
    }

    fun fireDegenerate(
        channel: ResultChannel,
        shape: DegenerateBundle,
        target: RecognitionListener? = null,
    ) {
        val bundle: Bundle? =
            when (shape) {
                DegenerateBundle.NULL -> null
                DegenerateBundle.EMPTY_BUNDLE -> Bundle()
                DegenerateBundle.EMPTY_LIST -> rawResultsBundle(emptyList())
                DegenerateBundle.BLANK_STRINGS -> rawResultsBundle(listOf("", "   ", "\t"))
            }
        fire(target) {
            when (channel) {
                // onResults / onPartialResults take an UNANNOTATED Java Bundle, i.e. a Kotlin
                // platform type — null is expressible and must be handled.
                ResultChannel.PARTIAL -> it.onPartialResults(bundle)
                ResultChannel.RESULTS -> it.onResults(bundle)
                // onSegmentResults is declared `@NonNull Bundle` [VERIFIED:
                // RecognitionListener.java:111], so Kotlin types the parameter non-null and a null
                // there is not an expressible shape — an implementer's own parameter null-check
                // would fire before any library code ran. Say so instead of silently substituting
                // a different shape and calling the case covered.
                ResultChannel.SEGMENT ->
                    it.onSegmentResults(
                        requireNotNull(bundle) {
                            "onSegmentResults is declared @NonNull by the platform; " +
                                "DegenerateBundle.NULL is not expressible on ResultChannel.SEGMENT. " +
                                "Use EMPTY_BUNDLE, EMPTY_LIST or BLANK_STRINGS."
                        },
                    )
            }
        }
    }

    /** The tester device's real `onResults`: a Bundle with zero keys. */
    fun fireEmptyResultsBundle(target: RecognitionListener? = null) {
        fireDegenerate(ResultChannel.RESULTS, DegenerateBundle.EMPTY_BUNDLE, target)
    }

    // ---------------------------------------------------------------------------------------
    // Shared steady state
    // ---------------------------------------------------------------------------------------

    /**
     * Drives the subject through the standard session startup so every downstream test starts from
     * ONE shared steady state instead of re-deriving it — the job `reachListening()` does in
     * `web/tests/errors.test.ts`.
     *
     * The subject is not a parameter because the subject registered itself via [setListener]; call
     * the subject's own `start()` first, then this. The order mirrors the device: `onReadyForSpeech`
     * then `onBeginningOfSpeech` (`05-PROBE-REPORT.json` part 2 event traces).
     *
     * @param withSpeech pass `false` to stop after `onReadyForSpeech` — the mic is open but nobody
     *   has spoken yet, which is where the restart-gap tests need to be.
     */
    fun reachListening(
        withSpeech: Boolean = true,
        params: Bundle? = null,
        target: RecognitionListener? = null,
    ) {
        fireReadyForSpeech(params, target)
        if (withSpeech) fireBeginningOfSpeech(target)
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    private inline fun fire(target: RecognitionListener?, block: (RecognitionListener) -> Unit) {
        val sink =
            target
                ?: listener
                ?: error(
                    "FakeRecognizerHandle: no listener registered. The subject must call " +
                        "setListener() before any fire*() — or pass an explicit target to fire " +
                        "into a stale listener.",
                )
        block(sink)
    }

    private fun rawResultsBundle(texts: List<String>): Bundle {
        val bundle = Bundle()
        bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(texts))
        return bundle
    }

    /**
     * Builds a results bundle **and proves it actually carries the text**.
     *
     * The self-check is the important half. Without it, a test running on the plain-JVM mockable
     * `android.jar` would fire a bundle that silently contains nothing, and any assertion of the
     * form "no text was committed" would pass for entirely the wrong reason. That is the
     * inert-assertion failure mode, and it is invisible in review. Here it is a loud throw that
     * names its own fix.
     */
    private fun resultsBundle(texts: Array<out String>): Bundle {
        val bundle = rawResultsBundle(texts.toList())
        val expected = texts.firstOrNull { it.isNotBlank() }
        if (expected != null && RecognizerBundles.firstNonBlankResult(bundle) == null) {
            error(
                "FakeRecognizerHandle: this Bundle did not retain the text it was given " +
                    "(expected \"$expected\", read back null).\n" +
                    "You are running on the plain-JVM mockable android.jar, where " +
                    "Bundle.putStringArrayList is a no-op. Any assertion about recognized text " +
                    "would pass for the wrong reason.\n" +
                    "FIX: annotate this test class:\n" +
                    "    @RunWith(RobolectricTestRunner::class)\n" +
                    "    @Config(sdk = [36])   // 36 is cached locally; 33 is not\n" +
                    "Tests that only assert call counts, ordering or intent identity need neither.",
            )
        }
        return bundle
    }
}
