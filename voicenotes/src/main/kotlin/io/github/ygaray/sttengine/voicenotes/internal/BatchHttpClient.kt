package io.github.ygaray.sttengine.voicenotes.internal

import io.github.ygaray.sttengine.voicenotes.NoteErrorCode
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

/**
 * The drain worker's HTTP seam against Phase 7's real batch endpoint (D-02 batch-client,
 * refreshed 2026-08-24): `POST {serverUrl}/jobs` (multipart, field name EXACTLY `"file"` — the
 * server checks `part.name === "file"` verbatim) -> 202 `{id}` -> `GET {serverUrl}/jobs/{id}` ->
 * `{status: processing|done|failed}`.
 */
internal interface BatchHttpClient {
    suspend fun postJob(file: File, serverUrl: String, format: String): PostJobOutcome
    suspend fun getJob(jobId: String, serverUrl: String): JobOutcome
}

/** The outcome of a `POST /jobs` attempt. */
internal sealed interface PostJobOutcome {
    /** `202` — the server accepted the upload and assigned a job id. */
    data class Accepted(val jobId: String) : PostJobOutcome

    /** `413`/`400` — the server rejected the upload outright. A permanent, typed failure. */
    data class Rejected(val code: NoteErrorCode, val message: String) : PostJobOutcome

    /** Any network failure or unexpected status — retry, do not mark the note failed. */
    data object Transient : PostJobOutcome
}

/** The outcome of a `GET /jobs/{id}` poll. */
internal sealed interface JobOutcome {
    /** `{status:'done', transcript}` — terminal success. */
    data class Done(val transcript: String) : JobOutcome

    /** `{status:'failed', error}` — terminal, typed permanent failure. */
    data class Failed(val error: String) : JobOutcome

    /** `404` — the job reference was lost (TTL eviction or server restart), not "failed." */
    data object NotFound : JobOutcome

    /** `{status:'processing'}` — still running; retry later. */
    data object Pending : JobOutcome

    /** Network failure — retry, do not touch the note's persisted state. */
    data object Transient : JobOutcome
}

/** The production [BatchHttpClient], backed by OkHttp. */
internal class OkHttpBatchClient(
    private val client: OkHttpClient = OkHttpClient(),
) : BatchHttpClient {

    override suspend fun postJob(file: File, serverUrl: String, format: String): PostJobOutcome {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                // The note's real MIME type (e.g. "audio/mp4"), not a generic octet-stream — the
                // rest of the codebase treats NoteRecord.format as a first-class, verbatim-carried
                // field, and the batch endpoint may dispatch/validate on Content-Type.
                file.asRequestBody(format.toMediaType()),
            )
            .build()
        val request = Request.Builder()
            .url("$serverUrl/jobs")
            .post(body)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body.string()
                when (response.code) {
                    202 -> PostJobOutcome.Accepted(JSONObject(bodyString).getString("id"))
                    413 -> PostJobOutcome.Rejected(
                        NoteErrorCode.UPLOAD_TOO_LARGE,
                        errorMessageOrDefault(bodyString, "Upload too large"),
                    )
                    400 -> PostJobOutcome.Rejected(
                        NoteErrorCode.UPLOAD_REJECTED,
                        errorMessageOrDefault(bodyString, "Upload rejected"),
                    )
                    else -> PostJobOutcome.Transient
                }
            }
        } catch (e: Exception) {
            // Any network failure (IOException) OR an unexpected/malformed response body
            // (org.json.JSONException from JSONObject(...)/.getString(...), which is NOT an
            // IOException) — both fall back to Transient/retry per this class's own documented
            // contract, never propagating an uncaught exception out of doWork().
            PostJobOutcome.Transient
        }
    }

    override suspend fun getJob(jobId: String, serverUrl: String): JobOutcome {
        val request = Request.Builder()
            .url("$serverUrl/jobs/$jobId")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    404 -> JobOutcome.NotFound
                    200 -> {
                        val json = JSONObject(response.body.string())
                        when (json.optString("status")) {
                            "done" -> JobOutcome.Done(json.getString("transcript"))
                            "failed" -> JobOutcome.Failed(json.optString("error", "Transcription failed"))
                            "processing" -> JobOutcome.Pending
                            else -> JobOutcome.Transient
                        }
                    }
                    else -> JobOutcome.Transient
                }
            }
        } catch (e: Exception) {
            // Same rationale as postJob's catch above: network failures AND malformed/unexpected
            // JSON bodies both retry rather than crash doWork().
            JobOutcome.Transient
        }
    }

    private fun errorMessageOrDefault(bodyString: String, default: String): String =
        try {
            JSONObject(bodyString).optString("error").ifEmpty { default }
        } catch (e: Exception) {
            default
        }
}
