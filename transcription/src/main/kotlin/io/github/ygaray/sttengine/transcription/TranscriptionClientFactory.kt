package io.github.ygaray.sttengine.transcription

import io.github.ygaray.sttengine.transcription.internal.OkHttpTranscriptionClient

/**
 * Build a [TranscriptionClient] against the Phase-7 batch endpoint — the library's only entry
 * point for this capability, mirroring `createRecorder`/`createVoiceNotes`'s barrel discipline.
 *
 * @param serverUrl the batch endpoint's base URL (e.g. `https://host.ts.net:8446`) — `POST
 *   {serverUrl}/jobs` / `GET {serverUrl}/jobs/{id}` are built from it directly. No tailnet default
 *   is baked in, matching `createVoiceNotes`'s own "the host must supply it explicitly" contract.
 */
public fun createTranscriptionClient(serverUrl: String): TranscriptionClient =
    OkHttpTranscriptionClient(serverUrl)
