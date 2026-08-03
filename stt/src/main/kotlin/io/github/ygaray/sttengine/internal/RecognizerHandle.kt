package io.github.ygaray.sttengine.internal

import android.content.Context
import android.content.Intent
import android.speech.RecognitionListener

/**
 * The seam. ONE narrow interface between the library's session logic and
 * `android.speech.SpeechRecognizer`.
 *
 * This is the single most valuable structural decision in the phase (05-RESEARCH §Pattern 1). The
 * platform class cannot be meaningfully unit-tested: it is created by static factories that
 * hard-throw off the main thread, and Robolectric's `ShadowSpeechRecognizer` has **no**
 * `triggerOnSegmentResults` and **no** `triggerOnEndOfSegmentedSession` [VERIFIED by `javap` of
 * `shadows-framework-4.16.1.jar`], which are exactly the callbacks rungs (b)/(c) depend on. With
 * this interface in the middle, everything ABOVE it is driven by [FakeRecognizerHandle] on the host
 * with no device; everything BELOW it is the ~120-line [PlatformRecognizerHandle] adapter that
 * Robolectric does cover.
 *
 * **It mirrors the platform one to one — no cleverness.** Five members, same names, same argument
 * types, same semantics. A seam that "improves" on the platform is a seam whose fake proves the
 * wrong thing.
 *
 * **What this interface deliberately does NOT promise.** It does not promise that final text
 * arrives via `RecognitionListener.onResults`. On the phase's tester device the `onResults` Bundle
 * comes back with **zero keys** — on natural silence-end AND on an explicit [stopListening] — while
 * partial bundles from the same code path carry three populated keys
 * (`.planning/phases/05-android-library/05-RUNG-VERDICT.md` §"Unplanned finding"). A seam shaped
 * around "the final text comes from `onResults`" would have made the commit-the-last-non-blank-partial
 * policy (plan 05) structurally inexpressible. Because this interface only carries *callbacks*, the
 * layer above is free to decide which callback commits text.
 *
 * Everything in this package is `internal`: with `explicitApi()` on and the API dump frozen in plan
 * 11, `internal` is the mechanical enforcement of the same discipline the web barrel enforces by
 * convention (`web/src/index.ts:1-8` — the DI seam is intentionally not re-exported).
 */
internal interface RecognizerHandle {

    /**
     * Registers the callback sink. Must be reachable from any thread — the implementation is
     * responsible for the main-thread hop (`SpeechRecognizerImpl.setRecognitionListener` calls
     * `checkIsCalledFromMainThread()`).
     */
    fun setListener(listener: RecognitionListener)

    /** Starts one recognition session with the given rung extras. */
    fun startListening(intent: Intent)

    /** Asks the recognizer to finish the current utterance and deliver its final. */
    fun stopListening()

    /** Abandons the current session. Produces a self-inflicted `ERROR_CLIENT(5)` — see `ErrorMapper`. */
    fun cancel()

    /** Releases the underlying platform object. Not reusable afterwards. */
    fun destroy()
}

/**
 * Constructs a [RecognizerHandle]. Injected so tests never touch a platform factory.
 *
 * **Main-thread required.** `SpeechRecognizer`'s `create*` factories call
 * `checkIsCalledFromMainThread()` [VERIFIED: `SpeechRecognizerImpl.java:150-290`], so [create] must
 * be invoked from the main thread. This is *documented* rather than *hopped* on purpose: [create]
 * returns a value synchronously, so an internal hop would have to block, and blocking the main
 * thread on a task posted to the main thread is a deadlock. The engine drives the recognizer from
 * the main looper anyway (D-09), so there is no call site that needs the hop.
 *
 * @throws UnsupportedOperationException when on-device recognition is unavailable on this device.
 *   This is the API 31 contract: `createOnDeviceSpeechRecognizer` throws
 *   `UnsupportedOperationException("On-device recognition is not available")` [VERIFIED:
 *   `SpeechRecognizer.java:403-411`]. Callers map it to `recognizer-unavailable` via
 *   `mapPreflightFailure`.
 */
internal fun interface RecognizerHandleFactory {
    fun create(context: Context): RecognizerHandle
}

/**
 * Answers "does this process hold `RECORD_AUDIO` right now?".
 *
 * A seam rather than a direct `Context.checkSelfPermission` call so the denial path is testable
 * without depending on Robolectric's `ShadowInstrumentation.denyPermissions` internals.
 *
 * The library only ever **checks**. Requesting is the host's job, because a library has no Activity
 * to request from (D-11) — the one contract asymmetry against the web engine.
 */
internal fun interface PermissionChecker {
    fun hasRecordAudio(): Boolean
}
