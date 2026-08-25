package io.github.ygaray.sttengine.voicenotes.internal.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The Room DAO backing [io.github.ygaray.sttengine.voicenotes.VoiceNotes.notes] (NOTE-03).
 *
 * [observeAll]'s `ORDER BY createdAt ASC` clause resolves edge-probe NOTE-03 ordering — a
 * deterministic, repeatable order, not SQLite's unspecified default. Its `Flow` emits
 * `emptyList()` when the table is empty, never `null` (edge-probe NOTE-03 empty — Room's own
 * Flow-returning query contract guarantees this).
 */
@Dao
internal interface NoteDao {

    @Query("SELECT * FROM notes ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: String)
}
