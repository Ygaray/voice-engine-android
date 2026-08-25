package io.github.ygaray.sttengine.voicenotes.internal.db

import android.util.Log
import io.github.ygaray.sttengine.voicenotes.NoteErrorCode
import io.github.ygaray.sttengine.voicenotes.NoteRecord
import io.github.ygaray.sttengine.voicenotes.NoteStatus

private const val TAG = "NoteMapper"

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
    // worth failing loud on — but "loud" means surfaced on THIS row, not an uncaught exception
    // thrown into the caller's `.map { }` operator. `notes`/`pendingCount` are collected via
    // `.stateIn(scope, SharingStarted.Eagerly, ...)` on a bare `SupervisorJob` with no installed
    // `CoroutineExceptionHandler` (VoiceNotesImpl.kt) — an uncaught exception there reaches the
    // thread's default handler, which on Android kills the whole host process. Coerce to
    // [NoteStatus.Failed] instead, and log so the corruption is still discoverable.
    status = NoteStatus.entries.firstOrNull { it.wireName == status } ?: NoteStatus.Failed.also {
        Log.e(TAG, "Unrecognized note status '$status' for note $id — coercing to Failed")
    },
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
