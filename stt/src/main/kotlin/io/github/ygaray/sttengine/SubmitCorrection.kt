package io.github.ygaray.sttengine

import android.content.Context
import io.github.ygaray.sttengine.internal.CorpusCorrections

/**
 * Send the user's corrected text for one retained session or clip back to the corpus, and learn
 * whether the server stored it.
 *
 * ```kotlin
 * lifecycleScope.launch {
 *     engine.stop()
 *     val id = engine.lastSessionId ?: return@launch          // null: nothing was retained
 *     when (submitCorrection(context, batchBaseUrl, id, editedText, language = "en")) {
 *         CorrectionSubmitResult.ACCEPTED -> markSynced()
 *         CorrectionSubmitResult.UNREACHABLE -> retryLater()   // may already be stored
 *         CorrectionSubmitResult.UNKNOWN_SESSION -> reportOnce() // no such record, or a wrong base URL
 *         else -> dropIt()
 *     }
 * }
 * ```
 *
 * **Where the id comes from.** For a live dictation session it is [SttEngine.lastSessionId], read
 * after `stop()` (it is null unless the session was retained and ended cleanly). For a batch clip it
 * is the job id from `submitJob`, and only once `getJobStatus` reports `Done`.
 *
 * **`httpBaseUrl`** is the batch base URL the host already gives `createTranscriptionClient`, for
 * example `https://host.example.ts.net/SttBatch`. The request is
 * `POST {httpBaseUrl}/corpus/{sessionId}/revisions`. A base with or without a trailing slash works.
 * Use an `https://` base: the corrected text is the user's edited transcript and goes out in the
 * request body, so a cleartext `http://` base would send it unencrypted (Android's default network
 * security policy blocks cleartext for most apps, but do not rely on that).
 *
 * **One corrected text per call, and nothing is overwritten.** Each accepted call appends one
 * revision to the record. The request body is exactly `{corrected, language?}`: the server stamps
 * the revision time, so the client never sends a timestamp, and a body that carried `createdAt`
 * would be refused by the server with a 400 ([CorrectionSubmitResult.REJECTED]). The body is capped
 * at 64 KiB by the server; a larger text is [CorrectionSubmitResult.REJECTED]. The client does not
 * repeat that limit.
 *
 * **`language`** is the bare `"en"` or `"es"` the server accepts. It is passed through unchecked, so
 * a value the server does not accept is [CorrectionSubmitResult.REJECTED] by the server, not by the
 * client. Omit it when the language is not known.
 *
 * **Results.**
 * - [CorrectionSubmitResult.ACCEPTED]: any 2xx; a revision was appended.
 * - [CorrectionSubmitResult.UNKNOWN_SESSION]: a 404, no such record. A wrong [httpBaseUrl] (a missing
 *   mount path, a wrong port, a proxy answering 404, an older server without the route) answers 404
 *   too and the body is not read, so the two cannot be told apart: log or surface it once rather than
 *   dropping it silently, because a 404 on the first correction after a configuration change usually
 *   means the base URL.
 * - [CorrectionSubmitResult.REJECTED]: any other 4xx, a blank corrected text or id, an id that is
 *   a dot segment (`.` or `..`), or a base URL that is not a URL. None of these sends a request.
 * - [CorrectionSubmitResult.UNREACHABLE]: a 408, a 5xx, a timeout, an IO or TLS failure, or any
 *   unexpected 1xx or 3xx answer (redirects are never followed).
 *
 * **There is no retry and no idempotency key.** One call sends at most one request: the HTTP client
 * does not replay the POST after a dropped connection and does not follow redirects, because a
 * replay would append a duplicate revision. [CorrectionSubmitResult.UNREACHABLE] therefore does not
 * prove that nothing was stored; a host retry can append an identical revision, which the corpus
 * tolerates.
 *
 * **Threading and time.** Main-safe: the call hops to a background dispatcher itself, so it can be
 * called from any coroutine, including one on the main thread. It is bounded by a whole-call timeout
 * of about 15 seconds. It never throws on network errors, but it does propagate cancellation of the
 * calling coroutine (a cancelled caller is never turned into [CorrectionSubmitResult.UNREACHABLE]).
 *
 * **Privacy.** The corrected text is never logged, echoed, or placed in an exception message, and
 * only a status code is read back. The id is a capability to append to that record: the route has
 * no authentication beyond the tailnet, and there is no read route, so do not publish ids.
 *
 * @param context accepted for symmetry with the other top-level calls; currently unused and never
 *   retained.
 * @param httpBaseUrl the batch base URL (see above).
 * @param sessionId the record id (see above). Blank, `"."` and `".."` return
 *   [CorrectionSubmitResult.REJECTED] without a request.
 * @param corrected the user's corrected text; blank returns [CorrectionSubmitResult.REJECTED] without
 *   a request.
 * @param language the bare language key `"en"` or `"es"`, or null to omit it.
 */
public suspend fun submitCorrection(
    @Suppress("UNUSED_PARAMETER") context: Context,
    httpBaseUrl: String,
    sessionId: String,
    corrected: String,
    language: String? = null,
): CorrectionSubmitResult = CorpusCorrections().submit(httpBaseUrl, sessionId, corrected, language)
