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
 * @property language The server-detected language label (LANG-02/LANG-03) for a server-backed
 *   bilingual `"auto"` session — `null` when the server frame did not carry one, which is always
 *   the case for a native on-device session (native never detects language).
 *
 * **`@JvmOverloads` is required** on this constructor, exactly as [SttConfig] documents: without
 * it, adding [language] as a trailing-defaulted parameter would silently drop the existing
 * 2-arg `<init>(String, Int)` overload from the frozen `stt.api` dump — Kotlin's "every parameter
 * has a default" special case only auto-generates the zero-arg and full-arg constructors, not
 * every arity in between.
 */
public class FinalSegment @JvmOverloads constructor(
    public val text: String,
    public val segmentId: Int,
    public val language: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FinalSegment) return false
        return text == other.text && segmentId == other.segmentId && language == other.language
    }

    override fun hashCode(): Int = 31 * (31 * text.hashCode() + segmentId) + (language?.hashCode() ?: 0)

    override fun toString(): String = "FinalSegment(text=$text, segmentId=$segmentId, language=$language)"
}
