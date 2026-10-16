package io.github.ygaray.sttengine

import io.github.ygaray.sttengine.corrections.CorrectionRuleset
import io.github.ygaray.sttengine.internal.ServerTryWindow

/**
 * Options for creating an engine. Translated from `web/src/types.ts:32-39`
 * (`SttEngineOptions`), with one field kept and two re-added in Phase 9.
 *
 * A plain class with defaulted parameters, NOT a `data class` (D-12) — see [FinalSegment].
 *
 * **`@JvmOverloads` is required.** Without it, adding [prefer]/[serverUrl] as two more
 * trailing-defaulted parameters would silently drop the existing single-arg `<init>(String)`
 * overload from the frozen `stt.api` dump — Kotlin's "every parameter has a default" special case
 * only auto-generates the zero-arg and full-arg constructors, not every arity in between (Phase 9
 * RESEARCH.md Common Pitfall 4, verified against `stt.api`'s pre-existing three-entry dump).
 *
 * **`serverUrl` is RE-ADDED in Phase 9 (D-01), correcting this class's own earlier KDoc.** That KDoc
 * used to claim a server-backed Android backend would need its OWN factory and config type — it does
 * not: [prefer] and [serverUrl] live on this same `SttConfig`, and [SttEngine.backend] is exposed as
 * Android-only additive observable metadata rather than by widening the shared `state`/`SttEvent`
 * unions. `sampleRate` stays dropped: the wire protocol's sample rate is fixed
 * (`internal.WsProtocol.SAMPLE_RATE`) and is never a host-settable knob on either backend.
 *
 * @property language Passed straight through to `RecognizerIntent.EXTRA_LANGUAGE` on the native
 *   backend, and into the server's config frame on the server backend. Accepts `"en"` or `"auto"`
 *   (LANG-01, bilingual EN<->ES code-switch) on the server backend — `"auto"` opts into a
 *   server-detected-per-segment bilingual session (see [FinalSegment.language]); the server does not
 *   currently accept a bare `"es"` here. On the native backend, `"auto"` on an **API 34+** device
 *   triggers native on-device EN/ES language-switch detection: the per-segment detected language is
 *   stamped onto [FinalSegment.language] as bare `"en"`/`"es"` (from a `HIGHLY_CONFIDENT` detection
 *   event only, else `null` — unknown, not a guess). On a runtime **below API 34**, `"auto"` falls
 *   back to the configured default language, normalized bare (`"en"`), exactly as the server
 *   backend's own `"auto"` falls back when detection is unavailable — set [prefer] to
 *   [BackendPreference.Server] (no silent native fallback) if bilingual dictation must not silently
 *   degrade below that floor. The field exists from day one so v2 multilingual is ADDITIVE rather
 *   than a breaking contract change — the same day-one rationale `web/src/types.ts:37` records.
 * @property prefer Which [Backend] to prefer (SEL-02). Defaults to [BackendPreference.Auto]: try the
 *   server first (bounded timeout), fall back to native silently on timeout/unreachable/busy.
 * @property serverUrl The self-hosted STT server's WebSocket URL (e.g.
 *   `wss://host.ts.net:8444/`), or `null` to disable the server backend entirely — a session with a
 *   `null` `serverUrl` always runs native, byte-identical to the v1.0 default path, regardless of
 *   [prefer]. There is no safe default hostname on Android the way `location.hostname` exists in a
 *   browser (Phase 9 RESEARCH.md Open Question 3), so the host must supply this explicitly to opt in.
 * @property corrections The active correction ruleset (Phase 13, CORR-01/D-05)
 *   [io.github.ygaray.sttengine.corrections.Corrections.apply] replays against every committed
 *   final at each real emit-origin point; partials are never corrected (CORR-03). `null` (the
 *   default) is a zero-cost no-op — a host that never builds a ruleset pays nothing.
 *
 *   WR-03 (13-REVIEW.md): the constructor takes an immutable snapshot (`corrections?.toList()`) of
 *   the host-supplied list rather than aliasing it directly — `CorrectionRuleset` is a plain
 *   `List<CorrectionPair>` typealias with no runtime immutability guarantee, so without this
 *   snapshot a host that reuses the same `MutableList<CorrectionPair>` across a live-updating
 *   correction UI and this config could hand `apply()` a list that mutates out from under it
 *   mid-iteration (`ConcurrentModificationException` or a torn read) — exactly the concurrent-
 *   mutation hazard `Corrections`' own KDoc's "no shared/module-level mutable state" guarantee
 *   (D-04) implicitly assumes does not happen to the ruleset itself.
 * @property vocabulary The host's personal vocabulary/term list (BIAS-01) — biases the live
 *   server-backed session toward these names/jargon terms via the server's Whisper `hotwords`,
 *   the same live-path parity `TranscriptionClient.submitJob`'s batch `vocabulary` param already
 *   has. `null` (the default) is a zero-cost no-op — the WS config frame's `vocabulary` key is
 *   omitted entirely, not sent empty. Takes the same immutable-snapshot treatment [corrections]
 *   already gets, for the same concurrent-mutation reason.
 * @property serverTryTimeoutMs How long, in milliseconds, a server-eligible engine ([serverUrl] set
 *   and [prefer] not [BackendPreference.OnDevice]) keeps trying the server at session start before
 *   giving up (TRY-01). The window bounds the whole connect → config → `ready` sequence, retries
 *   included, as one deadline. A failure before the host answers — DNS failure, no network,
 *   connection refused — gives up immediately rather than waiting out the window; a host that
 *   answers but is not ready yet (for example a cold-starting server) is retried within the window;
 *   a black-holed host — one that never answers at all — waits the full window. When the window
 *   ends without a ready server, [BackendPreference.Auto] falls back to the on-device backend and
 *   [BackendPreference.Server] reports an error. Defaults to 10 000 ms. Any value is clamped into
 *   1 000..10 000 ms rather than rejected, and this property reports the clamped value. No audio is
 *   captured while the window runs, so a longer window means more deaf time when the server is
 *   off: a session started while the server is cold-starting can wait up to 10 s before the
 *   on-device fallback under [BackendPreference.Auto]. Ignored when no server is configured.
 * @property retainForCorpus Opt this SERVER session into the host's voice corpus (CORR-06). Only a
 *   server session retains anything; the default `false` retains nothing and puts no new key on the
 *   wire. When `true`, the session's audio and its committed finals are kept on the server host for
 *   later correction (see [SttEngine.lastSessionId] and `submitCorrection`). A native session — no
 *   [serverUrl], [prefer] set to [BackendPreference.OnDevice], or a mid-session fallback to the
 *   on-device recognizer — and a server that is older or has retention switched off all produce no
 *   record and no error, and the host cannot tell which of these happened from
 *   [SttEngine.lastSessionId] alone (null is "no record claimed"). The corpus lives on the server
 *   host, and the host app owns the user's consent for retaining their voice.
 */
public class SttConfig @JvmOverloads constructor(
    public val language: String = "en",
    public val prefer: BackendPreference = BackendPreference.Auto,
    public val serverUrl: String? = null,
    corrections: CorrectionRuleset? = null,
    vocabulary: List<String>? = null,
    serverTryTimeoutMs: Long = ServerTryWindow.DEFAULT_MS,
    public val retainForCorpus: Boolean = false,
) {
    public val corrections: CorrectionRuleset? = corrections?.toList()
    public val vocabulary: List<String>? = vocabulary?.toList()
    public val serverTryTimeoutMs: Long =
        serverTryTimeoutMs.coerceIn(ServerTryWindow.MIN_MS, ServerTryWindow.MAX_MS)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SttConfig) return false
        return language == other.language &&
            prefer == other.prefer &&
            serverUrl == other.serverUrl &&
            corrections == other.corrections &&
            vocabulary == other.vocabulary &&
            serverTryTimeoutMs == other.serverTryTimeoutMs &&
            retainForCorpus == other.retainForCorpus
    }

    override fun hashCode(): Int {
        var result = language.hashCode()
        result = 31 * result + prefer.hashCode()
        result = 31 * result + (serverUrl?.hashCode() ?: 0)
        result = 31 * result + (corrections?.hashCode() ?: 0)
        result = 31 * result + (vocabulary?.hashCode() ?: 0)
        result = 31 * result + serverTryTimeoutMs.hashCode()
        result = 31 * result + retainForCorpus.hashCode()
        return result
    }

    override fun toString(): String =
        "SttConfig(language=$language, prefer=$prefer, serverUrl=$serverUrl, " +
            "corrections=$corrections, vocabulary=$vocabulary, serverTryTimeoutMs=$serverTryTimeoutMs, " +
            "retainForCorpus=$retainForCorpus)"
}
