package io.github.ygaray.sttengine

/**
 * An immutable committed transcript segment. Extensible for v2: additional optional fields
 * (e.g. timestamps) may be added without breaking the contract (LANG-03).
 *
 * That sentence is carried across verbatim from `web/src/types.ts:13-20`, because it is the
 * justification for the rule this file obeys:
 *
 * **This is deliberately NOT a `data class` (D-12).** A `data class` regenerates `copy()` and a
 * synthetic constructor keyed to its exact parameter list. Adding the v2 timestamp field the
 * comment above promises would therefore change `copy()`'s signature and BREAK BINARY
 * COMPATIBILITY for every already-published consumer — the precise outcome `apiCheck` exists to
 * prevent. A plain class with hand-written `equals`/`hashCode`/`toString` gives a host everything
 * a data class would, and stays additively extensible.
 *
 * @property text The committed text. Immutable — a final is never revised.
 * @property segmentId Library-owned, monotonic, **starts at 0** (mirroring `segmenter.py:111`).
 *   Opaque and per-session: it is NOT comparable across engines, backends or sessions.
 * @property language The detected language label (LANG-02/LANG-03) for a bilingual `"auto"`
 *   session — `en`/`es` on the server backend (per-segment server detection) or on the native
 *   backend on an API 34+ device (per-segment on-device detection, from a `HIGHLY_CONFIDENT`
 *   event only); `null` when no label was produced, which is always the case for a non-`"auto"`
 *   session on either backend.
 * @property languageSource The provenance of [language]; see [LanguageSource]. It is only ever set
 *   together with a language: a source never appears on a segment that has no language. The engine
 *   sets both together, so a segment from the native engine or from a current server carries either
 *   both or neither. A segment from a server that predates this field can carry a language and no
 *   source, which means no claim and is never a fallback — treat `null` as "unknown origin". The
 *   source is [LanguageSource.DETECTED] for a confident on-device detection or a server detection
 *   (including a label the server kept because a challenger did not clear its switch margin), and
 *   [LanguageSource.FALLBACK] for the configured-default stamp on a runtime without detection or for
 *   a server segment that had no usable detection evidence. **Equality includes the source**: a
 *   segment that carries one does not equal an otherwise identical segment built without it.
 *
 * **`@JvmOverloads` is required** on this constructor, exactly as [SttConfig] documents: without
 * it, adding [language] (and later [languageSource]) as trailing-defaulted parameters would silently
 * drop the existing 2-arg `<init>(String, Int)` overload from the frozen `stt.api` dump — Kotlin's
 * "every parameter has a default" special case only auto-generates the zero-arg and full-arg
 * constructors, not every arity in between.
 */
public class FinalSegment @JvmOverloads constructor(
    public val text: String,
    public val segmentId: Int,
    public val language: String? = null,
    public val languageSource: LanguageSource? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FinalSegment) return false
        return text == other.text &&
            segmentId == other.segmentId &&
            language == other.language &&
            languageSource == other.languageSource
    }

    // The source folds in by enum NAME, not identity: an enum's own hashCode is an identity hash that
    // changes between JVM runs, which would make a segment's hash unstable.
    override fun hashCode(): Int =
        31 * (31 * (31 * text.hashCode() + segmentId) + (language?.hashCode() ?: 0)) +
            (languageSource?.name?.hashCode() ?: 0)

    override fun toString(): String =
        "FinalSegment(text=$text, segmentId=$segmentId, language=$language, languageSource=$languageSource)"
}
