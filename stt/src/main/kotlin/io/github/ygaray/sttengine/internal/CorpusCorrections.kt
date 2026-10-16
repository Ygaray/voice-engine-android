package io.github.ygaray.sttengine.internal

import io.github.ygaray.sttengine.CorrectionSubmitResult
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject

/**
 * The transport behind the public top-level `submitCorrection` in `SubmitCorrection.kt`: one
 * `POST {base}/corpus/{id}/revisions` per call, mapped to a [CorrectionSubmitResult].
 *
 * Same shape as [LanguagePacks]: the public function is a one-line facade over this internal class,
 * whose collaborators are constructor parameters so a test can inject a client or a dispatcher.
 *
 * ## Why the transport is this careful
 *
 * The route is append-only. OkHttp's DEFAULT behaviour is to silently replay a request after a
 * connection failure and to follow a redirect, and a replayed or re-sent POST would append a
 * DUPLICATE revision of the user's correction (and a redirect would send the corrected text to
 * whatever origin answered). So [DEFAULT_CLIENT] turns all of that off, the body is one-shot, and
 * this class never retries: one call is at most one request.
 *
 * ## Never throws, except for cancellation
 *
 * [CancellationException] is rethrown FIRST: a cancelled caller is never turned into a result.
 * Every other exception becomes a typed result. The corrected text is never logged, never placed in
 * an exception message and never echoed; only the status code of the answer is read.
 *
 * @param client the HTTP client. Defaults to [DEFAULT_CLIENT]; a test injects one built from
 *   `DEFAULT_CLIENT.newBuilder()`.
 * @param io the dispatcher the request is built and sent on, so the call is main-safe.
 */
internal class CorpusCorrections(
    private val client: OkHttpClient = DEFAULT_CLIENT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun submit(
        httpBaseUrl: String,
        sessionId: String,
        corrected: String,
        language: String?,
    ): CorrectionSubmitResult {
        // Local refusals: no request. A dot segment would be resolved by OkHttp's path builder and
        // collapse the path (`.` drops itself, `..` pops `corpus`), so it never reaches the builder.
        if (corrected.isBlank() || sessionId.isBlank() || sessionId == "." || sessionId == "..") {
            return CorrectionSubmitResult.REJECTED
        }
        return try {
            withContext(io) { send(httpBaseUrl, sessionId, corrected, language) }
        } catch (cancellation: CancellationException) {
            // A cancelled caller is not UNREACHABLE. Rethrown before the broad catch below.
            throw cancellation
        } catch (failure: Exception) {
            // Any other failure: no answer was obtained, so the outcome is unknown. The exception is
            // dropped on purpose; nothing in it is logged or surfaced.
            CorrectionSubmitResult.UNREACHABLE
        }
    }

    private suspend fun send(
        httpBaseUrl: String,
        sessionId: String,
        corrected: String,
        language: String?,
    ): CorrectionSubmitResult {
        // The base is parsed exactly as given (no manual slash trimming): OkHttp represents a root
        // path and a trailing slash as an empty last segment, which addPathSegment replaces. Only a
        // malformed base is REJECTED; nothing else is mapped that way.
        val base = try {
            httpBaseUrl.toHttpUrl()
        } catch (malformed: IllegalArgumentException) {
            return CorrectionSubmitResult.REJECTED
        }

        // addPathSegment percent-encodes `/`, `?`, `#` and `%` so the id is always ONE segment. There
        // is deliberately no raw segment-count check (see the Task 1 review note in the plan).
        val url = base.newBuilder()
            .addPathSegment("corpus")
            .addPathSegment(sessionId)
            .addPathSegment("revisions")
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestBody(corrected, language))
            .build()

        return mapStatus(execute(request))
    }

    /**
     * Sends [request] once and returns the status code, never the body. Cancelling the calling
     * coroutine cancels the in-flight call, so a stalled response does not outlive its caller.
     */
    private suspend fun execute(request: Request): Int = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    // Read the code and close; the body is never read or logged.
                    val code = response.use { it.code }
                    cont.resume(code)
                }
            },
        )
    }

    internal companion object {

        /**
         * One client per process. The route is append-only, so replay and redirect re-POST are
         * disabled ON PURPOSE:
         * - `retryOnConnectionFailure(false)`: OkHttp otherwise silently retries a request after a
         *   connection failure, which would append a duplicate revision;
         * - `followRedirects(false)` and `followSslRedirects(false)`: a 3xx is never followed, so the
         *   corrected text is never re-POSTed to another origin;
         * - `callTimeout(15 s)`: one bound for the whole call, so a hung server never hangs a caller.
         *
         * Platform-default TLS validation only: no custom TrustManager or hostname verifier.
         */
        internal val DEFAULT_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        private const val CALL_TIMEOUT_SECONDS: Long = 15L

        /**
         * The request body: exactly `{corrected, language?}`. `language` is included only when
         * non-null and is passed through unchecked. The client never sends a timestamp: the server
         * stamps the revision time and rejects a client-sent `createdAt` with 400.
         */
        internal fun requestBody(corrected: String, language: String?): RequestBody {
            val json = JSONObject().put("corrected", corrected)
            if (language != null) json.put("language", language)
            return OneShotJsonBody(json.toString())
        }

        /**
         * The single status-to-result mapping.
         * 2xx ACCEPTED; 404 UNKNOWN_SESSION; 408 UNREACHABLE; every other 4xx REJECTED; 5xx
         * UNREACHABLE; anything else (1xx, 3xx) UNREACHABLE, because a redirect is never followed.
         */
        internal fun mapStatus(code: Int): CorrectionSubmitResult = when (code) {
            in 200..299 -> CorrectionSubmitResult.ACCEPTED
            404 -> CorrectionSubmitResult.UNKNOWN_SESSION
            408 -> CorrectionSubmitResult.UNREACHABLE
            in 400..499 -> CorrectionSubmitResult.REJECTED
            else -> CorrectionSubmitResult.UNREACHABLE
        }
    }
}

/**
 * A JSON request body that reports [isOneShot], so OkHttp can never transparently resend it
 * (a one-shot body is not retried after a connection failure and is not re-sent on a redirect).
 * `internal` so the test can assert the property directly.
 */
internal class OneShotJsonBody(json: String) : RequestBody() {

    private val mediaType: MediaType = "application/json; charset=utf-8".toMediaType()

    private val bytes: ByteArray = json.toByteArray(Charsets.UTF_8)

    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = bytes.size.toLong()

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        sink.write(bytes)
    }
}
