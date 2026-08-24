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
 */
public fun createRecorder(context: Context): Recorder {
    val applicationContext = context.applicationContext ?: context
    return RecorderImpl(
        context = applicationContext,
        sessionFactory = AndroidMediaRecorderSession,
    )
}
