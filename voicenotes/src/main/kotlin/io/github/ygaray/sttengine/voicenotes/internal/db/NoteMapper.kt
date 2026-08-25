package io.github.ygaray.sttengine.voicenotes.internal.db

import io.github.ygaray.sttengine.voicenotes.NoteErrorCode
import io.github.ygaray.sttengine.voicenotes.NoteRecord
import io.github.ygaray.sttengine.voicenotes.NoteStatus

/**
 * The exclusive boundary between the Room-internal [NoteEntity] and the public
 * [NoteRecord] — neither [NoteEntity], [NoteDao], nor [NoteDatabase] may appear outside this
 * `internal` package (verified by `:voicenotes:apiCheck` never gaining these symbols).
 */
internal fun NoteEntity.toRecord(): NoteRecord = NoteRecord(
    id = id,
    uri = uri,
    format = format,
    createdAt = createdAt,
    durationMs = durationMs,
    // Never a raw Enum.valueOf: an unrecognized wire string is a genuine data-corruption signal
    // worth failing loud on, not silently defaulting to some arbitrary status.
    status = NoteStatus.entries.first { it.wireName == status },
    jobId = jobId,
    transcript = transcript,
    errorCode = errorCode?.let { NoteErrorCode(it) },
    errorMessage = errorMessage,
)

internal fun NoteRecord.toEntity(): NoteEntity = NoteEntity(
    id = id,
    uri = uri,
    format = format,
    createdAt = createdAt,
    durationMs = durationMs,
    status = status.wireName,
    jobId = jobId,
    transcript = transcript,
    errorCode = errorCode?.value,
    errorMessage = errorMessage,
)
