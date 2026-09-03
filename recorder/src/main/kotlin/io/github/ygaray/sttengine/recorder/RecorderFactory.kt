package io.github.ygaray.sttengine.recorder

import android.content.Context
import io.github.ygaray.sttengine.recorder.internal.AndroidMediaRecorderSession
import io.github.ygaray.sttengine.recorder.internal.RecorderImpl

/**
 * Build a [Recorder] backed by this device's `android.media.MediaRecorder` — the library's only
 * entry point for the recording capability, mirroring
 * [io.github.ygaray.sttengine.createSttEngine]'s barrel discipline: [RecorderImpl] and the
 * `internal` session seam are deliberately NOT re-exported.
 *
 * **The host must already hold `RECORD_AUDIO`** — this library has no Activity, so it cannot
 * request a runtime permission (same asymmetry as [io.github.ygaray.sttengine.SttEngine], D-11).
 *
 * @param context any context — the application context is taken from it and retained, so passing an
 *   Activity leaks nothing.
 * @param config host-configurable poll cadence and encoding parameters (D-01/D-03, AUDIO-02).
 *   Defaults to a [RecorderConfig] that reproduces this module's behavior as of tag `v0.3.1`.
 *   The `JvmOverloads` annotation on this function is load-bearing, not cosmetic: without it
 *   Kotlin would replace the published one-argument JVM method with a two-argument method plus a
 *   synthetic default-dispatcher, which is an ABI REMOVAL for every consumer already resolved
 *   against `v0.3.1`. With it, both the pre-existing one-argument entry point and this new
 *   two-argument one exist on the ABI, so widening the surface is purely additive.
 */
@JvmOverloads
public fun createRecorder(context: Context, config: RecorderConfig = RecorderConfig()): Recorder {
    val applicationContext = context.applicationContext ?: context
    return RecorderImpl(
        context = applicationContext,
        sessionFactory = AndroidMediaRecorderSession,
        config = config,
    )
}
