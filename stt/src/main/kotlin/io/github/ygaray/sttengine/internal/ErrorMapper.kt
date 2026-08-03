package io.github.ygaray.sttengine.internal

import io.github.ygaray.sttengine.SttError
import io.github.ygaray.sttengine.SttErrorCode

/**
 * What the session should DO about a recognizer failure.
 *
 * Deliberately separate from the error CODE. "Which typed error does the host see" and "does the
 * session continue" are different questions with different answers — `ERROR_NO_MATCH` restarts and
 * shows the host nothing, `ERROR_RECOGNIZER_BUSY` shows the host `busy` and stops, and
 * `ERROR_TOO_MANY_REQUESTS` does either depending on how much budget is left. Collapsing the two
 * into one enum would have forced a code onto the cases that must stay invisible.
 *
 * Plan 05's restart policy and plan 06's engine branch on exactly these six members. The three-way
 * split that matters to them is **swallow / restart / fatal**; [BackoffAndRestart] and
 * [RebindAndRestart] are restarts that differ in what the policy must do FIRST (wait, versus
 * rebuild the recognizer), and [RestartOnce] is a restart the policy must not repeat.
 */
internal sealed interface Disposition {

    /** Absorb it. No restart, no host-visible event. The session carries on as if nothing happened. */
    data object Swallow : Disposition

    /** Absorb it and start the next session. The ordinary end of an utterance — invisible per AND-02. */
    data object SwallowAndRestart : Disposition

    /** Restart, but only this once — a second occurrence in the same session must be fatal. */
    data object RestartOnce : Disposition

    /** Wait out the backoff, then restart. The service is refusing new sessions. */
    data object BackoffAndRestart : Disposition

    /** Rebuild the recognizer, then restart. The service process died and the binding is stale. */
    data object RebindAndRestart : Disposition

    /** Terminal. Emit [error] to the host and stop. */
    class Fatal(val error: SttError) : Disposition {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Fatal && error == other.error)

        override fun hashCode(): Int = error.hashCode()

        override fun toString(): String = "Fatal($error)"
    }
}

/**
 * The session state the disposition depends on. Passing it in — instead of letting the mapper read
 * mutable session state — is what keeps the mapper a pure function, and therefore what makes the
 * most safety-critical table in the library provable without a device or an Android runtime.
 *
 * @property selfInflictedTeardown true when this engine has just called `cancel()` or `destroy()`,
 *   so an incoming `ERROR_CLIENT` is our own doing rather than a failure.
 * @property audioErrorAlreadyOccurred true when `ERROR_AUDIO` has already been restarted from once
 *   in this session; a second one is not a glitch.
 * @property restartBudgetRemaining true while the bounded recovery budget still has attempts left.
 *   **Not consulted for `ERROR_RECOGNIZER_BUSY`** — Phase 3 D-04 puts `busy` off the budget, and
 *   `ErrorMapperTest` proves the mapper's output for that code is identical whichever way this
 *   flag is set.
 */
internal class ErrorContext(
    val selfInflictedTeardown: Boolean = false,
    val audioErrorAlreadyOccurred: Boolean = false,
    val restartBudgetRemaining: Boolean = true,
)

/** The failure conditions that never arrive through `onError` at all — D-18's second table. */
internal sealed interface PreflightFailure {

    /**
     * `isOnDeviceRecognitionAvailable()` is false, or `createOnDeviceSpeechRecognizer()` threw
     * `UnsupportedOperationException`. These are the SAME signal
     * [VERIFIED: `SpeechRecognizer.java:403-411`], so they share one condition here.
     */
    data object RecognizerUnavailable : PreflightFailure

    /** `RECORD_AUDIO` is not granted. The host must request it — a library has no Activity (D-11). */
    data object RecordAudioDenied : PreflightFailure

    /** In `getSupportedOnDeviceLanguages()` but not in `getInstalledOnDeviceLanguages()`. */
    class LanguageNotInstalled(val language: String) : PreflightFailure

    /** In `getPendingOnDeviceLanguages()` — a download is already scheduled. */
    class LanguageDownloadPending(val language: String) : PreflightFailure
}

/**
 * D-18's disposition table, as a pure function.
 *
 * ## Why the mapping is on raw integer literals
 *
 * Not on the `SpeechRecognizer.ERROR_*` symbols, and this is deliberate (05-RESEARCH §Pitfall 10).
 * Two of the constants are above this module's `minSdk 33` — `ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS`
 * is API 34 — so referencing them draws a `NewApi` lint warning. More fundamentally, this taxonomy
 * is about integers *received* across a Binder boundary, not APIs *called*: a device running any
 * API level can hand us any integer, and the AOSP values are stable. The symbolic name lives in a
 * comment on every branch so the table stays readable. (`ErrorMapperTest` runs on the plain JVM
 * partly because of this — there is no Android runtime to need.)
 *
 * ## Why the `when` must have an `else`
 *
 * `RecognitionListener.onError`'s own javadoc requires callers to handle integer values outside its
 * documented set. An exhaustive-enum design would make every future OEM code a crash in a host app
 * (threat T-05-02). The `else` maps anything unrecognised to `internal-error` while preserving the
 * raw value in [SttError.platformDetail], which is exactly why `SttErrorCode` was made a value
 * class rather than an enum in plan 02 — the set has to stay open.
 *
 * ## The two mappings that look wrong and are not
 *
 * - **`ERROR_SERVER_DISCONNECTED(11)` → `server-unreachable`.** On a backend with no server this
 *   reads like a lie. It is not: the recognition service genuinely IS a separate process — on the
 *   tester device, `com.google.android.tts/…GoogleTTSRecognitionService` (`05-PROBE-REPORT.json`)
 *   — and a process that can die and take the binding with it is precisely what
 *   `server-unreachable` names. The frozen code fits the offline reality; no new code was needed.
 * - **A spent rebind budget → `reconnect-exhausted`.** Same reasoning one level up: the bounded
 *   recovery attempts really are reconnections, to a service rather than to a socket.
 *
 * ## Why three codes had to be ADDED rather than coerced (D-14)
 *
 * The dominant first-run failure on a fresh device is a missing language pack. Its correct host
 * response is to trigger a model download — the opposite of giving up. Coercing
 * `ERROR_LANGUAGE_UNAVAILABLE(13)` into the frozen `unsupported-language` would have told every
 * host to give up on a condition that resolves itself with one download, which is exactly the
 * silently-wrong-code failure the project's loud-failures rule forbids. `no-offline-model`,
 * `recognizer-unavailable` and `audio-error` name conditions the shared taxonomy has no word for,
 * and they are kept OUT of the TypeScript union (widening a closed union breaks exhaustive handling
 * in every already-shipped web host).
 */
internal object ErrorMapper {

    /**
     * @param rawError the integer exactly as `RecognitionListener.onError` delivered it — including
     *   values the platform does not document.
     */
    fun map(rawError: Int, context: ErrorContext = ErrorContext()): Disposition =
        when (rawError) {
            // ERROR_NETWORK_TIMEOUT — fatal, and LOUD, because on a backend that is supposed to be
            // on-device its very arrival proves the session was not.
            1 -> fatal(
                SttErrorCode.SERVER_UNREACHABLE,
                "recognizer error 1 (ERROR_NETWORK_TIMEOUT): the recognition service timed out " +
                    "against a network backend — this session was not running on-device",
                rawError,
            )

            // ERROR_NETWORK — the same signal, same reasoning.
            2 -> fatal(
                SttErrorCode.SERVER_UNREACHABLE,
                "recognizer error 2 (ERROR_NETWORK): the recognition service reported a network " +
                    "failure — this session was not running on-device",
                rawError,
            )

            // ERROR_AUDIO — one restart is worth trying (a transient capture hiccup); a second in
            // the same session is a real audio-path failure and must reach the host.
            3 ->
                if (context.audioErrorAlreadyOccurred) {
                    fatal(
                        SttErrorCode.AUDIO_ERROR,
                        "recognizer error 3 (ERROR_AUDIO): audio capture failed again after a " +
                            "restart, so the audio path is not recoverable in this session",
                        rawError,
                    )
                } else {
                    Disposition.RestartOnce
                }

            // ERROR_SERVER — the recognition service failed on its own side.
            4 -> fatal(
                SttErrorCode.INTERNAL_ERROR,
                "recognizer error 4 (ERROR_SERVER): the recognition service reported a " +
                    "server-side failure",
                rawError,
            )

            // ERROR_CLIENT — our own cancel()/destroy() provokes exactly this. Surfacing it then
            // would turn every ordinary restart into a user-visible failure.
            5 ->
                if (context.selfInflictedTeardown) {
                    Disposition.Swallow
                } else {
                    fatal(
                        SttErrorCode.INTERNAL_ERROR,
                        "recognizer error 5 (ERROR_CLIENT): the recognition service reported a " +
                            "client-side failure that this engine did not cause",
                        rawError,
                    )
                }

            // ERROR_SPEECH_TIMEOUT — silence ran long. The ordinary end of an utterance.
            6 -> Disposition.SwallowAndRestart

            // ERROR_NO_MATCH — nothing recognisable. Also ordinary: it was the terminal of 1 of the
            // 14 utterances in the device probe's long-silence run. Fatal here would end normal
            // dictation on the first unrecognised pause.
            7 -> Disposition.SwallowAndRestart

            // ERROR_RECOGNIZER_BUSY — terminal, and OFF the retry budget (Phase 3 D-04). Note that
            // `context` is not read on this branch: retrying a busy recognizer is what keeps it
            // busy, and spending recovery budget on it would convert an honest `busy` into a
            // misleading `reconnect-exhausted`.
            8 -> fatal(
                SttErrorCode.BUSY,
                "recognizer error 8 (ERROR_RECOGNIZER_BUSY): the recognition service is already " +
                    "serving another session",
                rawError,
            )

            // ERROR_INSUFFICIENT_PERMISSIONS — the host holds the permission (D-11), so only the
            // host can fix this.
            9 -> fatal(
                SttErrorCode.MIC_DENIED,
                "recognizer error 9 (ERROR_INSUFFICIENT_PERMISSIONS): RECORD_AUDIO is not granted " +
                    "to the host app",
                rawError,
            )

            // ERROR_TOO_MANY_REQUESTS (API 31) — rate limited. Back off while budget remains.
            10 ->
                if (context.restartBudgetRemaining) {
                    Disposition.BackoffAndRestart
                } else {
                    fatal(
                        SttErrorCode.RECONNECT_EXHAUSTED,
                        "recognizer error 10 (ERROR_TOO_MANY_REQUESTS): the recognition service " +
                            "kept refusing new sessions and the recovery budget is spent",
                        rawError,
                    )
                }

            // ERROR_SERVER_DISCONNECTED (API 31) — the service PROCESS died; the binding is stale,
            // so a plain restart would fail the same way. Rebuild the recognizer, then restart.
            11 ->
                if (context.restartBudgetRemaining) {
                    Disposition.RebindAndRestart
                } else {
                    fatal(
                        SttErrorCode.SERVER_UNREACHABLE,
                        "recognizer error 11 (ERROR_SERVER_DISCONNECTED): the recognition service " +
                            "process kept disconnecting and the rebind budget is spent",
                        rawError,
                    )
                }

            // ERROR_LANGUAGE_NOT_SUPPORTED (API 31) — the genuinely correct analog of the frozen
            // code. This one really is "give up".
            12 -> fatal(
                SttErrorCode.UNSUPPORTED_LANGUAGE,
                "recognizer error 12 (ERROR_LANGUAGE_NOT_SUPPORTED): the requested language is not " +
                    "supported by this recognition service",
                rawError,
            )

            // ERROR_LANGUAGE_UNAVAILABLE (API 31) — fatal but ACTIONABLE. See the class KDoc for
            // why this is not coerced into `unsupported-language`.
            13 -> fatal(
                SttErrorCode.NO_OFFLINE_MODEL,
                "recognizer error 13 (ERROR_LANGUAGE_UNAVAILABLE): the requested language is " +
                    "supported but its on-device model is not installed — the host can trigger a " +
                    "model download",
                rawError,
            )

            // ERROR_CANNOT_CHECK_SUPPORT (API 33) — a diagnosis failed, not dictation. Never let a
            // failed capability query stop a session that is otherwise working.
            14 -> Disposition.Swallow

            // ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS (API 34) — we lost the progress feed, not the
            // download. The model may well arrive anyway.
            15 -> Disposition.Swallow

            // REQUIRED. onError's javadoc obliges callers to handle undocumented integers, and an
            // OEM can ship one. The raw value is preserved so a bug report keeps full fidelity.
            else -> fatal(
                SttErrorCode.INTERNAL_ERROR,
                "recognizer error $rawError: undocumented value, outside the platform's " +
                    "documented ERROR_* set",
                rawError,
            )
        }

    /**
     * D-18's second table: the failures that are diagnosed before or instead of a session, and so
     * never arrive through `onError`. Used by plan 06's preflight.
     *
     * [SttError.platformDetail] is null for all of these on purpose — no platform integer caused
     * them, and inventing one would put a fiction in every bug report that quotes it.
     */
    fun mapPreflight(failure: PreflightFailure): SttError =
        when (failure) {
            PreflightFailure.RecognizerUnavailable ->
                SttError(
                    SttErrorCode.RECOGNIZER_UNAVAILABLE,
                    "on-device recognition is not available on this device; this engine does not " +
                        "fall back to the network-backed recognizer",
                )

            PreflightFailure.RecordAudioDenied ->
                SttError(
                    SttErrorCode.MIC_DENIED,
                    "RECORD_AUDIO is not granted; the host app must request it before starting " +
                        "the engine",
                )

            is PreflightFailure.LanguageNotInstalled ->
                SttError(
                    SttErrorCode.NO_OFFLINE_MODEL,
                    "the on-device model for \"${failure.language}\" is supported but not " +
                        "installed; the host can trigger a model download",
                )

            is PreflightFailure.LanguageDownloadPending ->
                SttError(
                    SttErrorCode.NO_OFFLINE_MODEL,
                    "the on-device model for \"${failure.language}\" is already scheduled for " +
                        "download; retry once it completes",
                )
        }

    private fun fatal(code: SttErrorCode, message: String, rawError: Int): Disposition.Fatal =
        Disposition.Fatal(SttError(code, message, platformDetail = rawError))
}
