package io.github.ygaray.sttengine.transcription.internal

import io.github.ygaray.sttengine.transcription.JobStatusOutcome
import io.github.ygaray.sttengine.transcription.SubmitOutcome
import io.github.ygaray.sttengine.transcription.TranscriptionClient
import io.github.ygaray.sttengine.transcription.TranscriptionErrorCode
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

/**
 * Behavior-preserving extraction of `voicenotes/internal/BatchHttpClient.kt`'s `OkHttpBatchClient`
 * (HUB-01) — same status-code branches, same JSON field names, promoted to a public, independent
 * `:transcription` module surface.
 */
internal class OkHttpTranscriptionClient(
    private val serverUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
) : TranscriptionClient {

    override suspend fun submitJob(file: File, mimeType: String): SubmitOutcome {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mimeType.toMediaType()))
            .build()
        val request = Request.Builder().url("$serverUrl/jobs").post(body).build()
        return try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body.string()
                when (response.code) {
                    202 -> SubmitOutcome.Accepted(JSONObject(bodyString).getString("id"))
                    413 -> SubmitOutcome.Rejected(
                        TranscriptionErrorCode.UPLOAD_TOO_LARGE,
                        errorMessageOrDefault(bodyString, "Upload too large"),
                    )
                    400 -> SubmitOutcome.Rejected(
                        TranscriptionErrorCode.UPLOAD_REJECTED,
                        errorMessageOrDefault(bodyString, "Upload rejected"),
                    )
                    // Any other code (including the server's documented but D-02-unlisted 408)
                    // falls through to Transient — a safe, retryable default.
                    else -> SubmitOutcome.Transient
                }
            }
        } catch (e: Exception) {
            // Any network failure (IOException) OR an unexpected/malformed response body
            // (org.json.JSONException from JSONObject(...)/.getString(...), which is NOT an
            // IOException) — both fall back to Transient/retry per this class's contract, never
            // propagating an uncaught exception out to the caller. This catch-all is the module's
            // entire DoS mitigation (T-136-01) — preserve it byte-for-byte.
            SubmitOutcome.Transient
        }
    }

    override suspend fun getJobStatus(jobId: String): JobStatusOutcome {
        val request = Request.Builder().url("$serverUrl/jobs/$jobId").get().build()
        return try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    404 -> JobStatusOutcome.NotFound
                    200 -> {
                        val json = JSONObject(response.body.string())
                        when (json.optString("status")) {
                            "done" -> JobStatusOutcome.Done(json.getString("transcript"))
                            "failed" -> JobStatusOutcome.Failed(json.optString("error", "Transcription failed"))
                            "processing" -> JobStatusOutcome.Pending
                            else -> JobStatusOutcome.Transient
                        }
                    }
                    else -> JobStatusOutcome.Transient
                }
            }
        } catch (e: Exception) {
            // Same rationale as submitJob's catch above: network failures AND malformed/unexpected
            // JSON bodies both retry rather than crash the caller.
            JobStatusOutcome.Transient
        }
    }

    /**
     * Parses the response body's `"error"` JSON field, falling back to [default] only if that
     * field is absent/empty — matches the live extraction source's `errorMessageOrDefault` (
     * `voicenotes/internal/BatchHttpClient.kt` lines ~127-132) exactly, confirmed by direct read
     * at execution time (not merely RESEARCH.md's snapshot characterization — the two agree).
     */
    private fun errorMessageOrDefault(bodyString: String, default: String): String =
        try {
            JSONObject(bodyString).optString("error").ifEmpty { default }
        } catch (e: Exception) {
            default
        }
}
