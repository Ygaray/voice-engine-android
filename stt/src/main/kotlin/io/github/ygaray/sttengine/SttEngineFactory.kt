package io.github.ygaray.sttengine

import android.content.Context
import io.github.ygaray.sttengine.internal.BackendSelector
import io.github.ygaray.sttengine.internal.ContextPermissionChecker
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
