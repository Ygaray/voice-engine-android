package io.github.ygaray.sttengine

import android.content.Context
import io.github.ygaray.sttengine.internal.BackendSelector
import io.github.ygaray.sttengine.internal.ContextPermissionChecker
import io.github.ygaray.sttengine.internal.LanguagePacks
import io.github.ygaray.sttengine.internal.OkHttpWebSocketFactory
import io.github.ygaray.sttengine.internal.PlatformRecognizerHandleFactory
import io.github.ygaray.sttengine.internal.SessionLog
import io.github.ygaray.sttengine.internal.SttEngineImpl
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

/**
 * Build an [SttEngine] backed by this device's **on-device** recognition service.
 *
 * This is the library's only entry point — the Kotlin counterpart of `web/src/index.ts`'s
 * `createSttEngine`, and it follows the same barrel discipline: the internal dependency-injection
 * seam that makes the engine testable is deliberately NOT re-exported. `SttEngineImpl`,
 * `RecognizerHandle` and every lifecycle core are `internal`, which with `explicitApi()` and the
 * frozen `.api` dump is mechanical enforcement rather than convention.
 *
 * ```kotlin
 * val engine = createSttEngine(context)
 * lifecycleScope.launch { engine.start() }
 * lifecycleScope.launch { engine.finals.collect { append(it.text) } }
 * ```
 *
 * **The host must already hold `RECORD_AUDIO`.** A library has no Activity, so it cannot request a
 * runtime permission; it only checks, and reports a missing grant as a typed
 * [SttErrorCode.MIC_DENIED] error on [SttEngine.errors] rather than by throwing. This is the one
 * documented contract asymmetry against the web backend, where the browser's own prompt is part of
 * the capture call.
 *
 * No network is used and none can be: the engine constructs only the system-resolved on-device
 * recognizer and never falls back to the network-backed one. An unavailable on-device recognizer is
 * a typed [SttErrorCode.RECOGNIZER_UNAVAILABLE] failure, not a reason to switch backends.
 *
 * @param context any context — the application context is taken from it and retained, so passing an
 *   Activity leaks nothing.
 * @param config recognition settings. [SttConfig.language] is passed straight through to the
 *   recognizer and is checked against the device's installed on-device models before any audio is
 *   captured.
 * @param diagnosticLogging writes a per-session JSONL trace to this app's own external files
 *   directory. **Off by default and it must stay that way**: the trace contains the user's dictated
 *   speech, so a diagnostic that quietly became always-on would turn a private, offline engine into
 *   a transcript on disk. Turn it on for a debugging run, never for a shipped build, and never in a
 *   demo's default configuration.
 */
public fun createSttEngine(
    context: Context,
    config: SttConfig = SttConfig(),
    diagnosticLogging: Boolean = false,
): SttEngine {
    val applicationContext = context.applicationContext ?: context

    // Phase 9 (SEL-01/SEL-02): a BackendSelector is wired ONLY when a server backend is actually
    // reachable in principle — a serverUrl is configured AND the host has not forced on-device.
    // Every other call (the v1.0 default: no serverUrl, or an explicit OnDevice preference)
    // constructs a plain SttEngineImpl exactly as before — byte-identical to v1.0, unchanged by
    // this branch's mere existence.
    val serverEligible = config.serverUrl != null && config.prefer != BackendPreference.OnDevice
    if (serverEligible) {
        return BackendSelector(
            appContext = applicationContext,
            config = config,
            handleFactory = PlatformRecognizerHandleFactory,
            permissions = ContextPermissionChecker(applicationContext),
            log = SessionLog.forContext(applicationContext, enabled = diagnosticLogging),
            // One shared OkHttpClient per engine instance, wrapped behind the WebSocketFactory seam
            // (BackendSelector's own KDoc explains why: OkHttpClient/WebSocket have no fake-friendly
            // seam of their own). Platform-default TLS validation only — no custom TrustManager or
            // hostnameVerifier (T-09-02, RESEARCH.md Security Domain V6): the `*.ts.net` cert is
            // publicly trusted, so a hand-rolled trust control here would be the anti-pattern.
            webSocketFactory = OkHttpWebSocketFactory(OkHttpClient()),
            mainDispatcher = Dispatchers.Main.immediate,
            // TRY-01 (Pitfall 14): the try-server window is threaded from the host's config.
            tryServerTimeoutMs = config.serverTryTimeoutMs,
        )
    }

    return SttEngineImpl(
        appContext = applicationContext,
        config = config,
        handleFactory = PlatformRecognizerHandleFactory,
        permissions = ContextPermissionChecker(applicationContext),
        log = SessionLog.forContext(applicationContext, enabled = diagnosticLogging),
        // Both segmented rungs are ON, and this is the ONE place that decision is expressed.
        //
        // It is a measurement, not a preference: `05-RUNG-DECISION.md` records the probe's answer for
        // each — rung (b) delivered 13 `onSegmentResults` plus `onEndOfSegmentedSession` with
        // `onResults` never firing, and rung (c)'s pipe-fed session produced 6 segment finals at
        // precision 1.000 with the upstream data-loss signature failing to reproduce.
        //
        // These are SEEDS, not verdicts. The engine treats each as an offer that runtime evidence can
        // withdraw: a rung whose degradation trigger fires is dropped for the rest of that engine's
        // life, and the classic restart rung underneath is mandatory, unconditional and unaffected by
        // either flag. So a device that does not behave like the tester falls back within one session
        // rather than dictating into a silently ignored extra.
        supportsSegmented = true,
        supportsRawAudio = true,
        // D-09: the library owns its dispatcher. `.immediate` so a recognizer callback already on
        // the main looper drives the next call without costing an extra frame inside the restart gap
        // that AND-02 requires to be invisible.
        mainDispatcher = Dispatchers.Main.immediate,
    )
}

/**
 * Ask this device's recognition service whether the on-device language packs for [langs] are
 * usable — without starting a dictation session and without the host holding a `SpeechRecognizer`
 * of its own.
 *
 * ```kotlin
 * lifecycleScope.launch {
 *     val packs = checkLanguageAvailability(context)          // {en=INSTALLED, es=DOWNLOADABLE}
 *     if (packs["es"] == LanguageAvailability.DOWNLOADABLE) offerSpanishDownload()
 * }
 * ```
 *
 * **One value per requested key, keyed by the caller's own string**, in the iteration order of
 * [langs]. Only the exact keys `"en"` and `"es"` are recognized. Any other string — including a
 * region-qualified tag such as `en-US` or a case variant such as `EN` — reads
 * [LanguageAvailability.UNSUPPORTED] without contacting the recognition service.
 *
 * **The answer agrees with a native `"auto"` session start on API 34+**: the two use the same query
 * and the same classification, so [LanguageAvailability.INSTALLED] and
 * [LanguageAvailability.UNKNOWN] mean such a session starts, [LanguageAvailability.DOWNLOADABLE]
 * and [LanguageAvailability.PENDING] mean it fails with [SttErrorCode.NO_OFFLINE_MODEL], and
 * [LanguageAvailability.UNSUPPORTED] means it fails with [SttErrorCode.UNSUPPORTED_LANGUAGE] (or,
 * when on-device recognition is unavailable altogether, with
 * [SttErrorCode.RECOGNIZER_UNAVAILABLE]). Below API 34 an `"auto"` session uses a single language,
 * so only that language's value predicts it.
 *
 * **Threading.** Call it from a coroutine — `lifecycleScope`, a `viewModelScope`, a background
 * scope. It enters the main thread itself to talk to the recognition service, and the calling
 * coroutine resumes in its own context. **Never call it from `runBlocking` on the main thread**:
 * that blocks the very thread the answer needs.
 *
 * **When.** Call it when no dictation session is active; it briefly creates and destroys its own
 * on-device recognizer to ask.
 *
 * **Not cached.** Every call asks the recognition service again — query again after a download
 * rather than reusing an earlier answer.
 *
 * It never throws, except for cancellation of the calling coroutine. No `RECORD_AUDIO` grant is
 * needed to ask.
 *
 * @param context any context; only its application context is used, and nothing is retained.
 * @param langs the languages to ask about, as bare keys. Defaults to both supported languages.
 */
public suspend fun checkLanguageAvailability(
    context: Context,
    langs: Set<String> = setOf("en", "es"),
): Map<String, LanguageAvailability> = LanguagePacks().check(context, langs)

/**
 * Ask this device's recognition service to download the on-device language pack for [lang], and
 * learn whether it **accepted** the request — not whether the download has finished.
 *
 * ```kotlin
 * lifecycleScope.launch {
 *     if (checkLanguageAvailability(context)["es"] == LanguageAvailability.DOWNLOADABLE) {
 *         val accepted = requestLanguagePackDownload(context, "es")
 *         // later: checkLanguageAvailability(context) again to see whether it has arrived
 *     }
 * }
 * ```
 *
 * **Keys.** Only the exact keys `"en"` and `"es"` are accepted. Anything else — including a
 * region-qualified tag such as `en-US` or a case variant such as `EN` — returns `false` without
 * contacting the recognition service.
 *
 * **Already there.** If the pack is already installed, or a download of it is already pending, this
 * returns `true` without asking the platform again — so no system prompt appears for a pack the user
 * already has.
 *
 * **What `true` means.** The platform accepted the request. On Android 14 and later that means it
 * acknowledged, scheduled or started the download; a recognition service that cannot report download
 * progress is asked again without progress reporting, and `true` then carries the Android 13 meaning.
 * On Android 13 it only means the request was handed to the platform without an error — that version
 * reports nothing back.
 *
 * **What `false` means.** The request was refused, the language is unsupported on this device, or
 * acceptance was not confirmed within about five seconds — a user-approval prompt can outlast that.
 * Call [checkLanguageAvailability] again rather than retrying this in a loop.
 *
 * **The download itself** needs a network connection and may wait for an unmetered one, and the pack
 * can keep reading [LanguageAvailability.DOWNLOADABLE] while it downloads. No progress is reported —
 * any download UI is the host's job.
 *
 * **Threading.** Call it from a coroutine; it enters the main thread itself to talk to the
 * recognition service. **Never call it from `runBlocking` on the main thread.** Call it when no
 * dictation session is active; it briefly creates and destroys its own on-device recognizer.
 *
 * It never throws, except for cancellation of the calling coroutine. Cancelling does not recall a
 * request already handed to the platform.
 *
 * @param context any context; only its application context is used, and nothing is retained.
 * @param lang the language to download, as a bare key: `"en"` or `"es"`.
 * @return `true` if the platform accepted the request (or the pack is already installed or pending).
 */
public suspend fun requestLanguagePackDownload(context: Context, lang: String): Boolean =
    LanguagePacks().requestDownload(context, lang)
