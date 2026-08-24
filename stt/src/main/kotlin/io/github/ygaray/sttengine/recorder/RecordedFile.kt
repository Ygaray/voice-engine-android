package io.github.ygaray.sttengine.recorder

/**
 * The result of a completed recording session — `{ uri, durationMs, format, createdAt }` (REC-01).
 *
 * **Deliberately NOT a `data class`** (`android/CLAUDE.md` invariant #2), for the same
 * binary-compatibility reason as [RecorderError]: a future optional field must be addable without
 * breaking every already-published consumer's resolved `copy()`/constructor ABI.
 *
 * @property uri An opaque, Recorder-owned locator for the recorded file — an internal, bare
 *   absolute filesystem path (e.g. `/data/user/0/<pkg>/files/<uuid>.m4a`) into the host app's
 *   private storage, with NO `file://` scheme prefix (`File(uri)` — not `Uri.parse(uri)` — is the
 *   correct way to resolve it; `delete()`'s own implementation does exactly this). Never derived
 *   from consumer input (T-08-04).
 * @property durationMs Wall-clock recording time, excluding any time spent [RecorderState.Paused].
 * @property format The concrete container/codec actually produced — always `"audio/mp4"` for this
 *   Recorder, since the session is configured for `MediaRecorder.OutputFormat.MPEG_4` +
 *   `MediaRecorder.AudioEncoder.AAC` (REC-04). Reported from what was configured, not probed from
 *   the file, mirroring the durationMs tracking rationale: re-deriving either from a decode pass is
 *   out of scope for this primitive.
 * @property createdAt An ISO-8601 UTC timestamp captured at [Recorder.start] time.
 */
public class RecordedFile(
    public val uri: String,
    public val durationMs: Long,
    public val format: String,
    public val createdAt: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecordedFile) return false
        return uri == other.uri &&
            durationMs == other.durationMs &&
            format == other.format &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = uri.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "RecordedFile(uri=$uri, durationMs=$durationMs, format=$format, createdAt=$createdAt)"
}
