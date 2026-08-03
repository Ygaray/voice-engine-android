package io.github.ygaray.sttengine.internal

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer

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
