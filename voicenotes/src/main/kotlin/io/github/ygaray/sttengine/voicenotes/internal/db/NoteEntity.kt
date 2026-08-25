package io.github.ygaray.sttengine.voicenotes.internal.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The Room persistence-layer representation of a voice note. `internal data class` is fine here
 * (unlike the public [io.github.ygaray.sttengine.voicenotes.NoteRecord]) — a Room entity is a
 * persistence-layer detail, never the public ABI, mirroring the internal/public split every other
 * Room-backed Android project uses.
 *
 * [status] is stored as [io.github.ygaray.sttengine.voicenotes.NoteStatus.wireName] (a plain
 * string column); [errorCode] is stored as
 * [io.github.ygaray.sttengine.voicenotes.NoteErrorCode.value] (or `null`) — both mapped back to
 * their typed form at [NoteMapper]'s boundary, never exposed as raw strings beyond this `internal`
 * package.
 */
@Entity(tableName = "notes")
internal data class NoteEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val format: String,
    val createdAt: String,
    val durationMs: Long,
    val status: String,
    val jobId: String?,
    val transcript: String?,
    val errorCode: String?,
    val errorMessage: String?,
)
