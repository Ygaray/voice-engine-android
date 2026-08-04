package io.github.ygaray.sttengine.internal

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The platform side of the seam: a thin adapter over a real [SpeechRecognizer].
 *
 * Its ONE job beyond delegation is the **main-thread hop**. `checkIsCalledFromMainThread()` throws
 * `RuntimeException("SpeechRecognizer should be used only from the application's main thread")` and
 * is called from `setRecognitionListener`, `startListening`, `stopListening`, `cancel` and the
 * `create*` factories — but NOT from `destroy()`, `checkRecognitionSupport()` or
 * `triggerModelDownload()` [VERIFIED: `SpeechRecognizerImpl.java:150-290, 428-430`, re-read this
 * session]. `MainThreadContractTest` drives all four throwing entry points from a background thread
 * and would fail on a regression (threat T-05-13); it is a real proof, not a smoke test, because
 * Robolectric shadows only `startListening` — `setRecognitionListener`, `stopListening` and
 * `cancel` run the REAL AOSP implementation including its thread check.
 *
 * [destroy] is exempt at the platform level but is routed identically, for uniformity: a reader
 * should not have to remember which one of five methods is special.
 */
internal class PlatformRecognizerHandle(
    private val delegate: SpeechRecognizer,
) : RecognizerHandle {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * `Dispatchers.Main.immediate` semantics, hand-rolled with a `Handler`.
     *
     * Inline when already on the main looper — required so a recognizer callback (every one of
     * which is delivered on the main looper [VERIFIED: `SpeechRecognizerImpl.java:578, 594-630`])
     * can drive the next call without an extra frame of latency in the restart gap that AND-02
     * requires to be invisible. Posted otherwise.
     *
     * D-09: the library owns its dispatcher. No `Looper`, `Handler` or `Executor` appears in any
     * signature reachable from the public package — this field is private and this file is
     * `internal`.
     *
     * **Ordering note.** Mixing inline and posted execution could in principle reorder a posted
     * call behind a later inline one. It cannot happen here: every call site inside the library is
     * itself on the main looper (callbacks land there, and the engine's state is main-thread
     * confined), so the inline branch is the only one the library ever takes. The posted branch
     * exists for a host that calls `stop()` from its own thread, where there is exactly one call in
     * flight.
     */
    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    override fun setListener(listener: RecognitionListener) {
        onMain { delegate.setRecognitionListener(listener) }
    }

    override fun startListening(intent: Intent) {
        onMain { delegate.startListening(intent) }
    }

    override fun stopListening() {
        onMain { delegate.stopListening() }
    }

    override fun cancel() {
        onMain { delegate.cancel() }
    }

    override fun destroy() {
        onMain { delegate.destroy() }
    }

    /**
     * `SpeechRecognizer.checkRecognitionSupport` (API 33), bridged from its callback into a suspend
     * function and reduced to [LanguageAvailability].
     *
     * Bounded by [LANGUAGE_SUPPORT_TIMEOUT_MS] because this is a Binder round trip into an
     * OEM-controlled process and a preflight that can hang forever would hang `start()` forever —
     * exactly the failure D-10 exists to prevent. A timeout degrades to
     * [LanguageAvailability.UNDETERMINED], i.e. "carry on", never to an error.
     */
    override suspend fun languageAvailability(language: String): LanguageAvailability {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        val support: RecognitionSupport? = withTimeoutOrNull(LANGUAGE_SUPPORT_TIMEOUT_MS) {
            suspendCancellableCoroutine<RecognitionSupport?> { continuation ->
                val callback = object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        if (continuation.isActive) continuation.resume(recognitionSupport)
                    }

                    // A failed capability query is not a dictation failure — report "unknown" and
                    // let the engine proceed. ErrorMapper makes the same call for
                    // ERROR_CANNOT_CHECK_SUPPORT, which it swallows.
                    override fun onError(error: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                // checkRecognitionSupport is NOT one of the entry points that calls
                // checkIsCalledFromMainThread() [VERIFIED: SpeechRecognizerImpl.java:428-430], but
                // it is routed through the same hop as every other delegate call so a reader does
                // not have to remember which of six methods is the exception.
                onMain {
                    delegate.checkRecognitionSupport(intent, DIRECT_EXECUTOR, callback)
                }
            }
        }

        return classify(language, support ?: return LanguageAvailability.UNDETERMINED)
    }

    /**
     * `internal` rather than `private` so [matches] is directly testable.
     *
     * That is not a convenience. The library's default config asks for `"en"` while the phase's
     * tester reports exactly one installed language, `"en-US"` — so this matcher is what decides
     * whether the happy path passes preflight on the only device this phase runs on, and getting it
     * wrong would make a working device report `no-offline-model`. The whole class is `internal`, so
     * nothing here reaches the frozen public surface.
     */
    internal companion object {

        /**
         * Ceiling on the language preflight round trip.
         *
         * **Trade-off:** long enough that a cold recognition service still answers, short enough
         * that a wedged one costs the user two seconds rather than a session. TUNABLE, NOT
         * CONTRACTUAL.
         */
        const val LANGUAGE_SUPPORT_TIMEOUT_MS: Long = 2_000L

        /** Run the callback on whichever thread the service delivers it — we only resume a coroutine. */
        val DIRECT_EXECUTOR: Executor = Executor { it.run() }

        fun classify(requested: String, support: RecognitionSupport): LanguageAvailability = when {
            matches(requested, support.installedOnDeviceLanguages) ->
                LanguageAvailability.INSTALLED

            matches(requested, support.pendingOnDeviceLanguages) ->
                LanguageAvailability.DOWNLOAD_PENDING

            matches(requested, support.supportedOnDeviceLanguages) ->
                LanguageAvailability.SUPPORTED_NOT_INSTALLED

            // The service answered but listed nothing on-device at all. That is a service that does
            // not know, not a service saying "no" — treat it as undetermined so an OEM with an
            // unpopulated support surface can still dictate.
            support.installedOnDeviceLanguages.isEmpty() &&
                support.supportedOnDeviceLanguages.isEmpty() -> LanguageAvailability.UNDETERMINED

            else -> LanguageAvailability.NOT_SUPPORTED
        }

        /**
         * Language-tag matching, tolerant in both directions.
         *
         * `SttConfig.language` defaults to `"en"` (D-13) while the tester reports exactly one
         * installed language, `"en-US"`. A strict equality test would therefore report
         * `no-offline-model` for the library's own default configuration on the only device this
         * phase runs on — a preflight that fails the happy path. So a request matches a candidate
         * when either is a subtag-boundary prefix of the other.
         */
        fun matches(requested: String, candidates: List<String>?): Boolean {
            val want = normalize(requested)
            if (want.isEmpty()) return false
            return candidates.orEmpty().any { candidate ->
                val have = normalize(candidate)
                have == want || have.startsWith("$want-") || want.startsWith("$have-")
            }
        }

        fun normalize(tag: String): String = tag.trim().lowercase().replace('_', '-')
    }
}

/**
 * The default [RecognizerHandleFactory]: the ON-DEVICE recognizer, and nothing else.
 *
 * **Two rules are load-bearing here, and both are prohibitions.**
 *
 * 1. **Never fall back to `SpeechRecognizer.createSpeechRecognizer(context)`.** That is the
 *    network-backed path. Falling back to it when the on-device recognizer is unavailable would
 *    look, to the host, exactly like success — while silently making the engine metered and
 *    non-private, breaking the project's founding free-and-unlimited constraint and its offline
 *    premise at the same time. An unavailable on-device recognizer is a typed
 *    `recognizer-unavailable` failure (D-18), not a reason to switch backends. It also closes a
 *    spoofing path (threat T-05-01): only the system-resolved on-device factory is ever used, no
 *    caller-supplied `ComponentName` is accepted, so a host cannot be steered onto an
 *    attacker-controlled `RecognitionService`. An acceptance criterion greps this source tree for
 *    the network factory and expects zero hits.
 *
 * 2. **Never check availability first and then create.** `createOnDeviceSpeechRecognizer` is
 *    literally `if (!isOnDeviceRecognitionAvailable(context)) throw new UnsupportedOperationException(...)`
 *    [VERIFIED: `SpeechRecognizer.java:403-411`], so the query and the throw are the SAME signal.
 *    Check-then-create adds a time-of-check-to-time-of-use gap and buys nothing. Call the factory;
 *    let the caller catch.
 */
internal object PlatformRecognizerHandleFactory : RecognizerHandleFactory {

    /**
     * @throws UnsupportedOperationException when on-device recognition is unavailable — mapped by
     *   the caller to `recognizer-unavailable` (see `mapPreflightFailure`).
     */
    override fun create(context: Context): RecognizerHandle =
        PlatformRecognizerHandle(SpeechRecognizer.createOnDeviceSpeechRecognizer(context))
}
