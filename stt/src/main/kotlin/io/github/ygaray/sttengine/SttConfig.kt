package io.github.ygaray.sttengine

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
 *   backend, and into the server's config frame on the server backend. v1 accepts `"en"`; the field
 *   exists from day one so v2 multilingual is ADDITIVE rather than a breaking contract change — the
 *   same day-one rationale `web/src/types.ts:37` records.
 * @property prefer Which [Backend] to prefer (SEL-02). Defaults to [BackendPreference.Auto]: try the
 *   server first (bounded timeout), fall back to native silently on timeout/unreachable/busy.
 * @property serverUrl The self-hosted STT server's WebSocket URL (e.g.
 *   `wss://host.ts.net:8444/`), or `null` to disable the server backend entirely — a session with a
 *   `null` `serverUrl` always runs native, byte-identical to the v1.0 default path, regardless of
 *   [prefer]. There is no safe default hostname on Android the way `location.hostname` exists in a
 *   browser (Phase 9 RESEARCH.md Open Question 3), so the host must supply this explicitly to opt in.
 */
public class SttConfig @JvmOverloads constructor(
    public val language: String = "en",
    public val prefer: BackendPreference = BackendPreference.Auto,
    public val serverUrl: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SttConfig) return false
        return language == other.language && prefer == other.prefer && serverUrl == other.serverUrl
    }

    override fun hashCode(): Int {
        var result = language.hashCode()
        result = 31 * result + prefer.hashCode()
        result = 31 * result + (serverUrl?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "SttConfig(language=$language, prefer=$prefer, serverUrl=$serverUrl)"
}
