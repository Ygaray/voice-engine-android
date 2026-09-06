package io.github.ygaray.sttengine.corrections

/**
 * An append-only correction/corpus revision record (Phase 12, CORPUS-01) — the Kotlin twin of the
 * web `@voice-engine/web` `CorrectionRevision` interface (`web/src/corrections-types.ts`). Every
 * instance is one immutable revision keyed to the same audio: a correction is always a NEW
 * revision, never a mutation of a prior one.
 *
 * **Deliberately NOT a `data class`** (`android/CLAUDE.md` invariant #2), for the same
 * binary-compatibility reason as [io.github.ygaray.sttengine.voicenotes.NoteRecord]/
 * [io.github.ygaray.sttengine.FinalSegment]: a future optional field must be addable without
 * breaking every already-published consumer's resolved `copy()`/constructor ABI. Since this is a
 * plain class, it has no `copy()`; do not add a `replace`/`update`/`copy`-like helper anywhere on
 * this type — the absence of a mutation path is CORPUS-01's enforcement mechanism, mechanically
 * backed by `:corrections:apiCheck` (T-12-02).
 *
 * @property recognized What the engine originally recognized.
 * @property corrected What the host/user corrected it to.
 * @property audioUri An opaque, host-issued reference to the audio this revision was corrected
 *   against — embedded exactly as the host supplies it. Never validated, parsed, or dereferenced
 *   by the engine (mirrors [io.github.ygaray.sttengine.voicenotes.NoteRecord.uri]'s opaque-locator
 *   framing).
 * @property createdAt ISO-8601 UTC timestamp of when this revision was recorded.
 */
public class CorrectionRevision(
    public val recognized: String,
    public val corrected: String,
    public val audioUri: String? = null,
    public val createdAt: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CorrectionRevision) return false
        return recognized == other.recognized &&
            corrected == other.corrected &&
            audioUri == other.audioUri &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = recognized.hashCode()
        result = 31 * result + corrected.hashCode()
        result = 31 * result + (audioUri?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "CorrectionRevision(recognized=$recognized, corrected=$corrected, audioUri=$audioUri, " +
            "createdAt=$createdAt)"
}
