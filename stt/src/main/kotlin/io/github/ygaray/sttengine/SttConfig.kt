package io.github.ygaray.sttengine

import io.github.ygaray.sttengine.corrections.CorrectionRuleset

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
 *   currently accept a bare `"es"` here. `"auto"` has **no meaning on the native backend**: it is
 *   passed straight through to `RecognizerIntent.EXTRA_LANGUAGE`, which is not a valid device locale,
 *   so a `prefer = Auto` session that silently falls back to native with `language = "auto"` set will
 *   fail native recognition rather than bilingual-switch — set [prefer] to [BackendPreference.Server]
 *   (no silent native fallback) if bilingual dictation must not silently degrade. The field exists
 *   from day one so v2 multilingual is ADDITIVE rather than a breaking contract change — the same
 *   day-one rationale `web/src/types.ts:37` records.
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
 */
public class SttConfig @JvmOverloads constructor(
    public val language: String = "en",
    public val prefer: BackendPreference = BackendPreference.Auto,
    public val serverUrl: String? = null,
    corrections: CorrectionRuleset? = null,
    vocabulary: List<String>? = null,
) {
    public val corrections: CorrectionRuleset? = corrections?.toList()
    public val vocabulary: List<String>? = vocabulary?.toList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SttConfig) return false
        return language == other.language &&
            prefer == other.prefer &&
            serverUrl == other.serverUrl &&
            corrections == other.corrections &&
            vocabulary == other.vocabulary
    }

    override fun hashCode(): Int {
        var result = language.hashCode()
        result = 31 * result + prefer.hashCode()
        result = 31 * result + (serverUrl?.hashCode() ?: 0)
        result = 31 * result + (corrections?.hashCode() ?: 0)
        result = 31 * result + (vocabulary?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "SttConfig(language=$language, prefer=$prefer, serverUrl=$serverUrl, " +
            "corrections=$corrections, vocabulary=$vocabulary)"
}
