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
 */
public class FinalSegment(
    public val text: String,
    public val segmentId: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FinalSegment) return false
        return text == other.text && segmentId == other.segmentId
    }

    override fun hashCode(): Int = 31 * text.hashCode() + segmentId

    override fun toString(): String = "FinalSegment(text=$text, segmentId=$segmentId)"
}
