package io.github.ygaray.sttengine.voicenotes.internal

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Room
import io.github.ygaray.sttengine.voicenotes.NoteRecord
import io.github.ygaray.sttengine.voicenotes.internal.db.NoteDatabase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The ONE shared seam [VoiceNotesImpl] and [DrainWorker] both use.
 *
 * WorkManager's default reflection-based `WorkerFactory` constructs [DrainWorker] via its bare
 * `(Context, WorkerParameters)` constructor — there is no constructor-injection point a
 * system-created `Worker` can receive. A process-singleton `object` is the standard, minimal way
 * to share dependencies (the database, the HTTP client, the notification bus) with a
 * system-constructed `Worker` without standing up a custom `WorkerFactory`.
 */
internal object VoiceNotesRuntime {

    @Volatile
    private var realDatabase: NoteDatabase? = null

    /**
     * Test-only escape hatch, checked FIRST by [database] — lets a test inject an in-memory Room
     * database for isolation without ever touching the real process-singleton path.
     */
    @VisibleForTesting
    internal var testDatabase: NoteDatabase? = null

    /**
     * A double-checked-lock-lazy accessor: builds the real on-disk `voicenotes.db` once per
     * process the first time it's needed, unless [testDatabase] is set.
     */
    internal fun database(context: Context): NoteDatabase {
        testDatabase?.let { return it }
        return realDatabase ?: synchronized(this) {
            realDatabase ?: Room.databaseBuilder(
                context.applicationContext,
                NoteDatabase::class.java,
                "voicenotes.db",
            ).build().also { realDatabase = it }
        }
    }

    /** Mutable, swappable by tests — production default is [OkHttpBatchClient]. */
    internal var httpClient: BatchHttpClient = OkHttpBatchClient()

    /**
     * Delivered when a consumer is alive AND persisted onto the corresponding [NoteRecord]
     * (NOTE-04) — lossless, so a transcript committed while no consumer was collecting is never
     * silently dropped.
     */
    internal val noteTranscribed: MutableSharedFlow<NoteRecord> =
        MutableSharedFlow(extraBufferCapacity = 64)

    /**
     * Updated ONLY on a successful ([io.github.ygaray.sttengine.voicenotes.NoteStatus.Done])
     * terminal drain completion — mirrors the web `note.ts`'s own `lastSyncedAt` semantics
     * exactly (never on [io.github.ygaray.sttengine.voicenotes.NoteStatus.Failed]).
     */
    internal val lastSyncedAt: MutableStateFlow<String?> = MutableStateFlow(null)
}
