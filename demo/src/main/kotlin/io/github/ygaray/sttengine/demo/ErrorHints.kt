package io.github.ygaray.sttengine.demo

import io.github.ygaray.sttengine.SttErrorCode

/**
 * Friendly, host-owned hint copy for each error code — the Kotlin counterpart of `ERROR_HINTS` in
 * `web/demo/main.ts:45-58`.
 *
 * **This lives in the demo and it must stay here.** The error taxonomy is frozen and shared across
 * both backends; hint text is not part of it. `web/src/errors.ts` says so explicitly, and the reason
 * is that a hint is advice to a *particular* user of a *particular* app — "allow the mic in your
 * browser settings" is wrong on Android, "open Settings > Voice input" is wrong on the web. Baking
 * copy into the contract would either lie to one platform or force every consumer to ship the same
 * wrong sentence.
 *
 * A hint is strictly additive: the loud banner always renders the raw kebab-case code and the
 * engine's own message first, so a code with no hint here still renders completely. An unknown code
 * — which [SttErrorCode] permits by design, being a value class rather than an enum — simply gets no
 * hint rather than crashing an exhaustive `when`.
 */
internal object ErrorHints {

    private val HINTS: Map<String, String> = mapOf(
        // --- the Android-reachable failures, in rough order of how often a first run hits them ---
        SttErrorCode.MIC_DENIED.value to
            "Microphone access was denied. Grant it in Settings > Apps > STT Engine Demo > " +
            "Permissions, then tap Start again.",
        SttErrorCode.RECOGNIZER_UNAVAILABLE.value to
            "No on-device recognition service resolved on this device. Check the diagnostics row " +
            "below — if it lists no service, speech recognition is not installed or is disabled.",
        SttErrorCode.NO_OFFLINE_MODEL.value to
            "The language pack is not on the device yet. Open the system speech settings and " +
            "download offline recognition for this language, then tap Start again.",
        SttErrorCode.UNSUPPORTED_LANGUAGE.value to
            "This device's recognizer does not support that language at all — downloading will not " +
            "help. Pick a language the device lists as supported.",
        SttErrorCode.AUDIO_ERROR.value to
            "Audio capture failed. Something else may be holding the microphone — close other " +
            "recording apps and try again.",
        SttErrorCode.INTERNAL_ERROR.value to
            "The recognizer reported an error the engine could not classify. The raw platform code " +
            "is shown above; include it in any bug report.",

        // --- the shared codes that are server-originated on the web backend --------------------
        // Unreachable on Android today (there is no server in the path), and kept anyway: the
        // taxonomy is shared, a host may render either backend, and a hint map with holes in it is
        // how a future code ships with no copy at all.
        SttErrorCode.VERSION_MISMATCH.value to
            "App and server speak different protocol versions. Update to continue.",
        SttErrorCode.BUSY.value to
            "Another dictation session is active — only one runs at a time. Try again in a moment.",
        SttErrorCode.INVALID_CONFIG.value to
            "The session config was rejected — likely a client bug.",
        SttErrorCode.UNSUPPORTED_SAMPLE_RATE.value to
            "Audio format not supported by the recognizer.",
        SttErrorCode.PROTOCOL_VIOLATION.value to
            "Unexpected protocol state. Restart the app and try again.",
        SttErrorCode.SERVER_UNREACHABLE.value to
            "Cannot reach the dictation server. This build recognizes on-device, so seeing this " +
            "code at all is a bug.",
        SttErrorCode.RECONNECT_EXHAUSTED.value to
            "Lost the connection and could not recover. Tap Start again.",
        SttErrorCode.SEND_BACKPRESSURE.value to
            "Audio was produced faster than it could be sent. Dictation stopped.",
    )

    /** The hint for [code], or `null` when there is none — never a placeholder, never a guess. */
    fun forCode(code: SttErrorCode): String? = HINTS[code.value]
}
