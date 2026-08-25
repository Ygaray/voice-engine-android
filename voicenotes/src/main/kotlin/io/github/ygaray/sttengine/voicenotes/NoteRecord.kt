package io.github.ygaray.sttengine.voicenotes

/**
 * A durable voice-note record — the queryable lifecycle NOTE-03 requires, exposed through
 * [VoiceNotes.notes].
 *
 * **Deliberately NOT a `data class`** (`android/CLAUDE.md` invariant #2), for the same
 * binary-compatibility reason as [io.github.ygaray.sttengine.recorder.RecordedFile]/
 * [io.github.ygaray.sttengine.FinalSegment]: a future optional field must be addable without
 * breaking every already-published consumer's resolved `copy()`/constructor ABI. Since this is a
 * plain class, it has no `copy()` — [io.github.ygaray.sttengine.voicenotes.internal.db.NoteMapper]
 * and the drain worker (plan 10-04) must construct a new `NoteRecord(...)` listing every field
 * explicitly whenever a note's state needs to change; do not add a `copy()`-like helper, which
 * would itself become a frozen ABI surface.
 *
 * @property id A client-generated, self-issued identifier (never derived from any external input,
 *   mirroring [io.github.ygaray.sttengine.recorder.RecordedFile.uri]'s T-08-04 rule).
 * @property uri [io.github.ygaray.sttengine.recorder.RecordedFile.uri] embedded VERBATIM (D-01) —
 *   the Recorder's own opaque, Recorder-owned locator. Never reconstructed from [id] or any other
 *   field for a filesystem path (T-10-07).
 * @property format [io.github.ygaray.sttengine.recorder.RecordedFile.format] embedded verbatim —
 *   always `"audio/mp4"` for this Recorder (MPEG_4 + AAC).
 * @property createdAt [io.github.ygaray.sttengine.recorder.RecordedFile.createdAt] embedded
 *   verbatim — an ISO-8601 UTC timestamp captured at record time.
 * @property durationMs [io.github.ygaray.sttengine.recorder.RecordedFile.durationMs] embedded
 *   verbatim — wall-clock recording time.
 * @property status The note's current lifecycle stage — see [NoteStatus].
 * @property jobId The batch endpoint's job id once `POST /jobs` has succeeded, persisted BEFORE
 *   any polling begins (Pattern 3, idempotent-drain) — `null` until then, and cleared again if a
 *   `GET /jobs/{id}` 404s after eviction/server-restart (the job reference was lost, not the
 *   transcription).
 * @property transcript Non-null exactly when [status] is [NoteStatus.Done] — persisted BEFORE
 *   `noteTranscribed` is emitted (NOTE-04, persist-before-notify).
 * @property errorCode Non-null exactly when [status] is [NoteStatus.Failed] — see [NoteErrorCode].
 * @property errorMessage A human-readable detail accompanying [errorCode], non-null exactly when
 *   [status] is [NoteStatus.Failed].
 */
public class NoteRecord(
    public val id: String,
    public val uri: String,
    public val format: String,
    public val createdAt: String,
    public val durationMs: Long,
    public val status: NoteStatus,
    public val jobId: String? = null,
    public val transcript: String? = null,
    public val errorCode: NoteErrorCode? = null,
    public val errorMessage: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NoteRecord) return false
        return id == other.id &&
            uri == other.uri &&
            format == other.format &&
            createdAt == other.createdAt &&
            durationMs == other.durationMs &&
            status == other.status &&
            jobId == other.jobId &&
            transcript == other.transcript &&
            errorCode == other.errorCode &&
            errorMessage == other.errorMessage
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + uri.hashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + (jobId?.hashCode() ?: 0)
        result = 31 * result + (transcript?.hashCode() ?: 0)
        result = 31 * result + (errorCode?.hashCode() ?: 0)
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "NoteRecord(id=$id, uri=$uri, format=$format, createdAt=$createdAt, durationMs=$durationMs, " +
            "status=$status, jobId=$jobId, transcript=$transcript, errorCode=$errorCode, " +
            "errorMessage=$errorMessage)"
}
