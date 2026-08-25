package io.github.ygaray.sttengine.voicenotes.internal.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The Room database for the note queue (D-03). Built WITHOUT
 * `.fallbackToDestructiveMigration()` (RESEARCH's State-of-the-Art callout: acceptable for a
 * throwaway cache, wrong for a note queue holding the user's own recordings/transcripts) — a
 * future schema version bump requires an explicit `Migration`, and fails loudly at open time
 * rather than silently deleting every stored note.
 *
 * Construct via
 * `Room.databaseBuilder(context, NoteDatabase::class.java, "voicenotes.db").build()` at the
 * composition-root level (plan 10-04) — deliberately never calling `fallbackToDestructiveMigration()`
 * on that builder.
 */
@Database(entities = [NoteEntity::class], version = 1, exportSchema = true)
internal abstract class NoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
}
