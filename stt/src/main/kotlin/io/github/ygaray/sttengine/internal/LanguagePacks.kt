package io.github.ygaray.sttengine.internal

import android.content.Context
import io.github.ygaray.sttengine.LanguageAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * THE one pack-availability query. Both the public preflight ([LanguagePacks.check]) and a native
 * `"auto"` session start ([SttEngineImpl.start]) call exactly this, and nothing else, to learn the
 * state of the bilingual allowlist's on-device packs.
 *
 * **Why one query.** If the preflight and `start()` each built their own question, the two could
 * diverge — a different language in the query intent, a different classification — and the host
 * would be told a pack is usable when a session start would then fail on it. Sharing this function
 * makes the agreement structural rather than a property two call sites have to keep in sync. It
 * always asks for the FULL [RecognizerIntents.AUTO_SWITCH_ALLOWED_LANGUAGES] list, even when a
 * caller only cares about one language, so the platform query intent is byte-identical on both
 * paths. If the recognition service ever proves to scope its answer to the intent's language, the
 * documented correction (one query per language) changes only
 * [PlatformRecognizerHandle.languageAvailability]'s List override; both paths follow automatically.
 *
 * **The one failure fold.** A query that throws a non-cancellation [RuntimeException] is a query
 * that could not answer, so every allowlist tag reads [LanguageAvailability.UNKNOWN] — and UNKNOWN
 * lets a session start. The fold lives HERE rather than in either caller so both callers read the
 * same answer for the same failure: a preflight that said UNKNOWN while `start()` let the exception
 * escape would be exactly the disagreement this function exists to prevent.
 * [CancellationException] is a [RuntimeException] subclass and is always rethrown first — a
 * cancelled caller is never turned into a result.
 */
internal suspend fun RecognizerHandle.autoPackAvailability(): Map<String, LanguageAvailability> {
    return try {
        languageAvailability(RecognizerIntents.AUTO_SWITCH_ALLOWED_LANGUAGES)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: RuntimeException) {
        RecognizerIntents.AUTO_SWITCH_ALLOWED_LANGUAGES.associateWith { LanguageAvailability.UNKNOWN }
    }
}

/**
 * The host-facing language-pack operations behind the public top-level functions in
 * `SttEngineFactory.kt`.
 *
 * Each call works on its own **transient** recognizer handle: created on the main thread, used for
 * one question, destroyed before the call returns. Nothing is cached and the instance holds no state
 * beyond its two injected collaborators — installed state can change under the library (a download
 * finishing), so every call asks the recognition service afresh, and two concurrent calls each own
 * their own handle.
 *
 * The transient handle only ever answers support queries and, for [requestDownload], asks for one
 * model download: no listener is registered and no recognition is started on it.
 *
 * @param handleFactory the ON-DEVICE recognizer factory. Injected so tests never touch a platform
 *   factory.
 * @param mainDispatcher the thread the recognizer lives on. The whole create/use/destroy lifecycle
 *   runs inside ONE `withContext` on it.
 */
internal class LanguagePacks(
    private val handleFactory: RecognizerHandleFactory = PlatformRecognizerHandleFactory,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {

    /**
     * One [LanguageAvailability] per key in [langs], keyed by the caller's own string, in the
     * caller's set order.
     *
     * - An empty [langs] answers an empty map without touching the platform.
     * - A key the bilingual allowlist does not recognize reads [LanguageAvailability.UNSUPPORTED].
     *   When no key is recognized, no recognizer is constructed at all.
     * - A recognizer that cannot be constructed (on-device recognition unavailable) makes every key
     *   UNSUPPORTED — a session start would fail with `recognizer-unavailable`.
     * - A query that times out, errors, comes back empty or throws reads UNKNOWN for every
     *   recognized key (via [autoPackAvailability]).
     * - A transient handle whose cleanup fails leaves the recognition service in an unknown state,
     *   so the computed answer is not trusted: recognized keys read UNKNOWN.
     */
    suspend fun check(context: Context, langs: Set<String>): Map<String, LanguageAvailability> {
        if (langs.isEmpty()) return emptyMap()

        val tagByKey: Map<String, String?> = langs.associateWith { allowlistTagFor(it) }
        val allUnsupported = langs.associateWith { LanguageAvailability.UNSUPPORTED }
        if (tagByKey.values.all { it == null }) return allUnsupported

        val cleanupFailed = tagByKey.mapValues { (_, tag) ->
            if (tag == null) LanguageAvailability.UNSUPPORTED else LanguageAvailability.UNKNOWN
        }

        return withTransientHandle(
            context = context,
            unavailable = allUnsupported,
            cleanupFailed = cleanupFailed,
        ) { handle ->
            // No second catch around the query: a throwing query already comes back from
            // autoPackAvailability() as UNKNOWN for every tag.
            val byTag = handle.autoPackAvailability()
            tagByKey.mapValues { (_, tag) ->
                if (tag == null) {
                    LanguageAvailability.UNSUPPORTED
                } else {
                    byTag[tag] ?: LanguageAvailability.UNKNOWN
                }
            }
        }
    }

    /**
     * Asks the platform to download [lang]'s on-device pack, and answers whether it ACCEPTED.
     *
     * Query-then-trigger on ONE transient handle, through the same [autoPackAvailability] question
     * [check] and a native `"auto"` start ask:
     * - A key the bilingual allowlist does not recognize → `false` before anything is constructed,
     *   so no host-supplied string ever reaches the platform — only the allowlist's exact tag does.
     * - INSTALLED or PENDING → `true` with NO platform trigger: there is nothing to download, and a
     *   trigger could show system UI or spend the user's data for nothing.
     * - UNSUPPORTED → `false`.
     * - DOWNLOADABLE or UNKNOWN → [RecognizerHandle.triggerModelDownload] with the exact tag, and its
     *   answer. A trigger that throws reads `false`. The platform adapter does not return until its
     *   request has been DISPATCHED (the platform only enqueues it), so the `destroy()` that follows
     *   here can never overtake it; and on API 33, where nothing reports back, an UNKNOWN that came
     *   from a query with no answer at all reads `false` without a trigger, because there is no proof
     *   the recognizer ever connected (see `PlatformRecognizerHandle.triggerModelDownload`).
     * - A recognizer that cannot be constructed → `false`.
     * - A handle whose cleanup fails → `false` ("not confirmed — re-query"), whatever the path.
     *
     * The caller's cancellation always propagates — it is never turned into `false` — and the handle
     * is still destroyed. A request already handed to the platform is not recalled.
     *
     * The block runs inside [withTransientHandle]'s main-thread `withContext`, which is what lets the
     * platform adapter's main-thread-only trigger pass its check.
     */
    suspend fun requestDownload(context: Context, lang: String): Boolean {
        val tag = allowlistTagFor(lang) ?: return false

        return withTransientHandle(
            context = context,
            unavailable = false,
            cleanupFailed = false,
        ) { handle ->
            // No catch around the query: a throwing query already comes back from
            // autoPackAvailability() as UNKNOWN for every tag, and UNKNOWN attempts the trigger.
            when (handle.autoPackAvailability()[tag] ?: LanguageAvailability.UNKNOWN) {
                LanguageAvailability.INSTALLED, LanguageAvailability.PENDING -> true
                LanguageAvailability.UNSUPPORTED -> false
                LanguageAvailability.DOWNLOADABLE, LanguageAvailability.UNKNOWN -> try {
                    handle.triggerModelDownload(tag)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: RuntimeException) {
                    false
                }
            }
        }
    }

    /**
     * The single create/use/destroy skeleton for a transient recognizer handle.
     *
     * The whole lifecycle runs inside ONE `withContext(mainDispatcher)`, so [block] always runs on
     * the main thread — the recognizer's factories and several of its calls require it.
     *
     * - Construction throwing [UnsupportedOperationException] (on-device recognition unavailable)
     *   returns [unavailable]; nothing was created, so nothing is destroyed.
     * - Otherwise [block] runs, and the handle is destroyed **best-effort** afterwards:
     *   - [block] returned and `destroy()` then throws a [RuntimeException] → [cleanupFailed] is
     *     returned instead of the block's value. The never-throws promise holds.
     *   - [block] threw (cancellation included) → `destroy()` still runs; a destroy failure is
     *     attached to the block's exception with `addSuppressed`, and the block's exception
     *     propagates. A cancelled caller's [CancellationException] therefore stays the primary
     *     exception.
     */
    private suspend fun <T> withTransientHandle(
        context: Context,
        unavailable: T,
        cleanupFailed: T,
        block: suspend (RecognizerHandle) -> T,
    ): T = withContext(mainDispatcher) {
        val handle = try {
            handleFactory.create(context.applicationContext ?: context)
        } catch (unsupported: UnsupportedOperationException) {
            return@withContext unavailable
        }

        val result = try {
            block(handle)
        } catch (failure: Throwable) {
            try {
                handle.destroy()
            } catch (cleanup: RuntimeException) {
                attachCleanupFailure(failure, cleanup)
            }
            throw failure
        }

        try {
            handle.destroy()
        } catch (cleanup: RuntimeException) {
            return@withContext cleanupFailed
        }
        result
    }

    private companion object {

        /**
         * Attaches a cleanup failure to the exception [block][withTransientHandle] threw, as a
         * suppressed exception — never as the primary one.
         *
         * When that exception is a cancellation, the cleanup failure is ALSO attached to every
         * cancellation further down its cause chain. With coroutine debug mode on (stack-trace
         * recovery, common in debug builds), a suspended call is resumed with a COPY of the job's
         * cancellation whose `cause` is the original, and the job then completes with the original.
         * Attaching only to the copy would silently drop the cleanup failure in exactly the builds
         * a developer debugs with. Without debug mode the chain is the single original exception.
         */
        fun attachCleanupFailure(failure: Throwable, cleanup: Throwable) {
            failure.addSuppressed(cleanup)
            if (failure !is CancellationException) return
            val seen = mutableSetOf(failure)
            var link = failure.cause
            while (link is CancellationException && seen.add(link)) {
                link.addSuppressed(cleanup)
                link = link.cause
            }
        }

        /**
         * The bilingual allowlist tag a host key names, or null when it names none.
         *
         * Strict on purpose: a key maps only when it EXACTLY equals a tag's primary subtag (the text
         * before the first hyphen) — case-sensitive and untrimmed. So the bare primary subtags map
         * and everything else, including region-qualified tags, case variants and padded keys, reads
         * UNSUPPORTED. The accepted keys are part of the host-confirmed public shape; widening them
         * would change that shape and needs the host's re-confirmation first.
         *
         * Derived from [RecognizerIntents.AUTO_SWITCH_ALLOWED_LANGUAGES] — the single definition of
         * the allowlist — so there is no second list of tags or keys to drift.
         */
        fun allowlistTagFor(key: String): String? =
            RecognizerIntents.AUTO_SWITCH_ALLOWED_LANGUAGES.firstOrNull { tag ->
                tag.substringBefore('-') == key
            }
    }
}
