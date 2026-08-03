package io.github.ygaray.sttengine

import android.content.Context
import io.github.ygaray.sttengine.internal.ContextPermissionChecker
import io.github.ygaray.sttengine.internal.PlatformRecognizerHandleFactory
import io.github.ygaray.sttengine.internal.SttEngineImpl
import kotlinx.coroutines.Dispatchers

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
 */
public fun createSttEngine(context: Context, config: SttConfig = SttConfig()): SttEngine {
    val applicationContext = context.applicationContext ?: context
    return SttEngineImpl(
        appContext = applicationContext,
        config = config,
        handleFactory = PlatformRecognizerHandleFactory,
        permissions = ContextPermissionChecker(applicationContext),
        // The ladder's terminal rung only. The segmented rungs both measured SHIP on the phase's
        // tester and plan 09 turns them on from the probe verdict by flipping these two flags —
        // nothing in the session loop changes.
        supportsSegmented = false,
        supportsRawAudio = false,
        // D-09: the library owns its dispatcher. `.immediate` so a recognizer callback already on
        // the main looper drives the next call without costing an extra frame inside the restart gap
        // that AND-02 requires to be invisible.
        mainDispatcher = Dispatchers.Main.immediate,
    )
}
