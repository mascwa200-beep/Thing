package dev.mascwa.pulse.data.jarvis.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Telemetry baselines / current operational state. */
@Dao
interface SystemStateDao {
    @Query("SELECT * FROM system_states ORDER BY state_key")
    fun observeAll(): Flow<List<SystemStateEntity>>

    @Query("SELECT * FROM system_states WHERE state_key = :key LIMIT 1")
    fun observe(key: String): Flow<SystemStateEntity?>

    @Query("SELECT * FROM system_states WHERE state_key = :key LIMIT 1")
    suspend fun get(key: String): SystemStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SystemStateEntity)
}

/** Tokenized conversational exchanges (local context window). */
@Dao
interface ConversationDao {
    @Query("SELECT * FROM context_history ORDER BY timestamp ASC, id ASC")
    fun observeAll(): Flow<List<ConversationHistoryEntity>>

    @Query("SELECT * FROM context_history ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ConversationHistoryEntity>

    @Insert
    suspend fun insert(turn: ConversationHistoryEntity): Long

    @Query("DELETE FROM context_history")
    suspend fun clear()
}
