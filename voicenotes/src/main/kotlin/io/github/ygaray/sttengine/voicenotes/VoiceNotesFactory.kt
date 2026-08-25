package io.github.ygaray.sttengine.voicenotes

import android.content.Context
import io.github.ygaray.sttengine.recorder.createRecorder
import io.github.ygaray.sttengine.voicenotes.internal.VoiceNotesImpl

/**
 * Build a [VoiceNotes] backed by this device's Recorder + a Room-backed persisted note queue +
 * WorkManager-driven drain — the library's only entry point for the capability, mirroring
 * [io.github.ygaray.sttengine.recorder.createRecorder]'s barrel discipline: [VoiceNotesImpl],
 * [io.github.ygaray.sttengine.voicenotes.internal.VoiceNotesRuntime],
 * [io.github.ygaray.sttengine.voicenotes.internal.BatchHttpClient], and the Room internals are
 * deliberately NOT re-exported.
 *
 * **NOTE-05 asymmetry, stated here too (mirrors [VoiceNotes]'s own doc comment):** this Android
 * implementation drains via `WorkManager`, in the BACKGROUND, surviving app close — the OPPOSITE
 * of the web `note.ts` backend, which drains only while its page is open.
 *
 * @param context any context — the application context is taken from it and retained, so passing
 *   an Activity leaks nothing.
 * @param serverUrl the Phase 7 batch endpoint's base URL (e.g. `https://host.ts.net:8446`) —
 *   `POST {serverUrl}/jobs` / `GET {serverUrl}/jobs/{id}` are built from it directly. No
 *   tailnet-default is baked in here (unlike [io.github.ygaray.sttengine.createSttEngine]'s own
 *   `wss://<hostname>:8444/` default) — flagged as a follow-up once the batch endpoint's deployed
 *   default port/scheme for Android's tailnet access pattern is confirmed.
 */
public fun createVoiceNotes(context: Context, serverUrl: String): VoiceNotes {
    val applicationContext = context.applicationContext ?: context
    val recorder = createRecorder(applicationContext)
    return VoiceNotesImpl(
        context = applicationContext,
        recorder = recorder,
        serverUrl = serverUrl,
    )
}
