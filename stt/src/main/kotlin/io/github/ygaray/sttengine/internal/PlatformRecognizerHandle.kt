package io.github.ygaray.sttengine.internal

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.ygaray.sttengine.LanguageAvailability
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
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
 *
 * **The one deliberate exception to the hop is [triggerModelDownload]**, which REQUIRES the main
 * thread and asserts it instead: a posted download call could not report its own outcome, so its
 * Boolean would stop meaning anything. Its KDoc has the full reasoning.
 */
internal class PlatformRecognizerHandle(
    private val delegate: SpeechRecognizer,
    /**
     * The platform support query, `SpeechRecognizer.checkRecognitionSupport`. Internal, and exists
     * only so a test can make that platform call THROW: `SpeechRecognizer`'s constructor is
     * package-private (no subclass to override it) and Robolectric's shadow never throws from this
     * call. Production always takes the default.
     */
    private val checkSupport: (Intent, Executor, RecognitionSupportCallback) -> Unit =
        delegate::checkRecognitionSupport,
    /**
     * The device's API level, injectable so a test can drive the API-33 download branch under a
     * newer Robolectric sdk (Robolectric shadows only the 1-arg `triggerModelDownload`). Production
     * always takes the default.
     */
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    /**
     * The platform's listener-less `SpeechRecognizer.triggerModelDownload(Intent)` (API 33).
     * Internal, and exists only so a test can stand in for the platform's queue-then-dispatch
     * behavior, which Robolectric's shadow does not model (it records the intent synchronously).
     * Production always takes the default.
     */
    private val handOver: (Intent) -> Unit = delegate::triggerModelDownload,
    /**
     * The platform's listener overload of `triggerModelDownload` (API 34), restated over the
     * platform-free [DownloadOutcomeSink]. Internal, and exists only so a test can script the
     * listener's callbacks: the overload is unshadowed and its listener type is above this module's
     * floor. `null` — the production default — means the real platform overload.
     */
    private val listenerTrigger: ((Intent, DownloadOutcomeSink) -> Unit)? = null,
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
     * [LanguageAvailability.UNKNOWN], i.e. "carry on", never to an error.
     */
    override suspend fun languageAvailability(language: String): LanguageAvailability {
        val support = querySupport(language) ?: return LanguageAvailability.UNKNOWN
        return classify(language, support)
    }

    /**
     * The NBIL-10 dual-language extension: classifies every entry in [languages] from ONE
     * `checkRecognitionSupport` round trip rather than one per language.
     *
     * **Assumption A3 (RESEARCH.md Open Question 1, unverified until Phase 19's real-mic run):** the
     * platform's `checkRecognitionSupport` response lists (`installedOnDeviceLanguages`,
     * `supportedOnDeviceLanguages`, `pendingOnDeviceLanguages`) are assumed DEVICE-WIDE — not scoped
     * to the single language named in the query intent's `EXTRA_LANGUAGE` extra — so one response
     * can classify every requested language. If that assumption proves wrong, the documented
     * correction is to fall back to one [querySupport] call per language, matching the single-language
     * override's behavior exactly (no protocol change needed — only this function's body).
     */
    override suspend fun languageAvailability(languages: List<String>): Map<String, LanguageAvailability> {
        if (languages.isEmpty()) return emptyMap()
        val support = querySupport(languages.first())
            ?: return languages.associateWith { LanguageAvailability.UNKNOWN }
        return languages.associateWith { classify(it, support) }
    }

    /**
     * The intent-build + `suspendCancellableCoroutine` + `withTimeoutOrNull` machinery shared by
     * both [languageAvailability] overrides. Returns the raw platform answer — `null` on a timeout
     * or a failed query — leaving classification to the caller.
     *
     * A support call that throws synchronously is a query that could not answer — the same reading
     * as `onError` — so it also resumes `null`, and both overloads report
     * [LanguageAvailability.UNKNOWN].
     */
    private suspend fun querySupport(language: String): RecognitionSupport? {
        val intent = supportIntent(language)

        return withTimeoutOrNull(LANGUAGE_SUPPORT_TIMEOUT_MS) {
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
                //
                // The try/catch sits INSIDE the onMain lambda on purpose. Called off the main
                // looper, onMain POSTS the block, so a catch around it would never see a throw: the
                // exception would escape on the main looper and this query would hang until the
                // timeout. Inside, both the inline and the posted path fold it.
                onMain {
                    try {
                        checkSupport(intent, DIRECT_EXECUTOR, callback)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: RuntimeException) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        }
    }

    /**
     * The one intent that names an on-device pack to the platform — shared by the support query and
     * the model download, so the two always ask about the same thing.
     */
    private fun supportIntent(language: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    /**
     * `SpeechRecognizer.triggerModelDownload` for [language] (an exact tag such as `es-US`), reduced
     * to "did the platform accept the request?".
     *
     * - **API 34+:** the listener overload, through [triggerWithListener] and [awaitDownloadOutcome]
     *   — the first listener callback decides, bounded by [DOWNLOAD_ACCEPT_TIMEOUT_MS]. A service
     *   that answers `onError(15)` has no listener-based download hook and started NOTHING, so the
     *   request is repeated without a listener through [handOverWithoutListener] and reported with
     *   the API-33 semantics.
     * - **API 33:** the fire-and-forget overload, through [handOverWithoutListener]. There is no
     *   outcome to wait for, so `true` means only that the request was handed to the platform without
     *   throwing. A throw is `false`.
     *
     * The 34-only overload is never reached below 34: the branch is on [sdkInt], and the call is
     * isolated in a `@TargetApi` helper.
     *
     * **MAIN-THREAD-ONLY — this method asserts instead of hopping, unlike every other delegate call
     * here.** A posted call cannot report its own outcome, and it would let a later platform throw
     * escape on the main looper, outside every catch below. Called on main, every synchronous throw
     * lands inside a catch. The only production caller, `LanguagePacks.requestDownload`, runs inside
     * its transient handle's `withContext(mainDispatcher)`, so the check never fires in production;
     * an off-main caller is a programming error and gets an [IllegalStateException] before any intent
     * is built.
     */
    override suspend fun triggerModelDownload(language: String): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) { "triggerModelDownload must be called on the main thread" }
        val intent = supportIntent(language)

        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return triggerWithListener(intent)

        return handOverWithoutListener(intent)
    }

    /**
     * The API-34 listener path. Registration runs synchronously on the main thread, so a synchronous
     * platform throw is caught by [awaitDownloadOutcome] and resolves [DownloadOutcome.REFUSED].
     *
     * [DownloadOutcome.LISTENER_UNSUPPORTED] (`onError(15)`) is NOT acceptance: AOSP's default
     * listener hook answers 15 without forwarding to the hook that downloads. On API 34+ the
     * listener-less overload dispatches to that 2-arg hook, so the request is repeated through it.
     */
    private suspend fun triggerWithListener(intent: Intent): Boolean =
        when (awaitDownloadOutcome(DOWNLOAD_ACCEPT_TIMEOUT_MS) { sink -> registerDownloadListener(intent, sink) }) {
            DownloadOutcome.ACCEPTED -> true
            DownloadOutcome.LISTENER_UNSUPPORTED -> handOverWithoutListener(intent)
            DownloadOutcome.REFUSED, DownloadOutcome.NOT_CONFIRMED -> false
        }

    /**
     * The listener-less overload: hands [intent] to the platform with no way to hear back. `true`
     * means only that the hand-over did not throw; a throw is `false`.
     */
    private suspend fun handOverWithoutListener(intent: Intent): Boolean =
        try {
            handOver(intent)
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: RuntimeException) {
            false
        }

    private fun registerDownloadListener(intent: Intent, sink: DownloadOutcomeSink) {
        val scripted = listenerTrigger
        if (scripted != null) scripted(intent, sink) else platformDownloadWithListener(intent, sink)
    }

    /**
     * The real API-34 listener overload. The listener forwards every callback, verbatim, to the
     * platform-free sink.
     *
     * `@TargetApi`, not `@RequiresApi`, on purpose: the guard at the only call site is on the
     * injected [sdkInt], which lint cannot read as a version check, so a propagated requirement would
     * flag that guarded call as `NewApi`. The helper is private and has exactly that one caller.
     */
    @SuppressLint("UseRequiresApi")
    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun platformDownloadWithListener(intent: Intent, sink: DownloadOutcomeSink) {
        delegate.triggerModelDownload(
            intent,
            DIRECT_EXECUTOR,
            object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) = sink.onProgress()
                override fun onSuccess() = sink.onSuccess()
                override fun onScheduled() = sink.onScheduled()
                override fun onError(error: Int) = sink.onError(error)
            },
        )
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

        /**
         * Ceiling on waiting for the API-34 download listener's first callback.
         *
         * **Trade-off:** long enough to catch a prompt `onSuccess` / `onScheduled`, short enough
         * that the worst case — a 2 s support query plus this — stays inside a broadcast receiver's
         * `goAsync` budget. A user-approval prompt may outlast it, which is why a `false` here means
         * "not confirmed — re-query", never "refused". TUNABLE, NOT CONTRACTUAL.
         */
        const val DOWNLOAD_ACCEPT_TIMEOUT_MS: Long = 5_000L

        /** Run the callback on whichever thread the service delivers it — we only resume a coroutine. */
        val DIRECT_EXECUTOR: Executor = Executor { it.run() }

        fun classify(requested: String, support: RecognitionSupport): LanguageAvailability = when {
            matches(requested, support.installedOnDeviceLanguages) ->
                LanguageAvailability.INSTALLED

            matches(requested, support.pendingOnDeviceLanguages) ->
                LanguageAvailability.PENDING

            matches(requested, support.supportedOnDeviceLanguages) ->
                LanguageAvailability.DOWNLOADABLE

            // The service answered but listed nothing on-device at all. That is a service that does
            // not know, not a service saying "no" — treat it as undetermined so an OEM with an
            // unpopulated support surface can still dictate.
            support.installedOnDeviceLanguages.isEmpty() &&
                support.supportedOnDeviceLanguages.isEmpty() -> LanguageAvailability.UNKNOWN

            else -> LanguageAvailability.UNSUPPORTED
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
