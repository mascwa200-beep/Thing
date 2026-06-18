package dev.mascwa.pulse.data.jarvis.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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

/** Durable agent notes/facts with lexical (FTS4) retrieval. */
@Dao
interface AgentNoteDao {
    @Insert
    suspend fun insert(note: AgentNoteEntity): Long

    /** Lexical search; [match] must already be sanitized into valid FTS MATCH syntax. */
    @Query(
        "SELECT n.* FROM agent_notes n JOIN agent_notes_fts fts ON n.id = fts.rowid " +
            "WHERE agent_notes_fts MATCH :match ORDER BY n.timestamp DESC LIMIT :limit",
    )
    suspend fun search(match: String, limit: Int): List<AgentNoteEntity>

    @Query("SELECT * FROM agent_notes ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AgentNoteEntity>

    /** Live list of all notes, newest first (for the Memory manager screen). */
    @Query("SELECT * FROM agent_notes ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<AgentNoteEntity>>

    @Query("SELECT * FROM agent_notes WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): AgentNoteEntity?

    /** Edit a note in place; Room's FTS triggers keep the lexical index in sync. */
    @Update
    suspend fun update(note: AgentNoteEntity)

    @Query("DELETE FROM agent_notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM agent_notes")
    suspend fun clear()
}

/** The knowledge library (docs RAG): chunked documents with lexical (FTS4) retrieval. */
@Dao
interface KnowledgeDocDao {
    @Insert
    suspend fun insert(doc: KnowledgeDocEntity): Long

    @Insert
    suspend fun insertAll(docs: List<KnowledgeDocEntity>)

    /** Lexical search; [match] must already be sanitized into valid FTS MATCH syntax. */
    @Query(
        "SELECT k.* FROM knowledge_docs k JOIN knowledge_docs_fts fts ON k.id = fts.rowid " +
            "WHERE knowledge_docs_fts MATCH :match ORDER BY k.timestamp DESC LIMIT :limit",
    )
    suspend fun search(match: String, limit: Int): List<KnowledgeDocEntity>

    @Query("SELECT COUNT(*) FROM knowledge_docs")
    suspend fun count(): Int

    @Query("SELECT COUNT(DISTINCT title) FROM knowledge_docs")
    suspend fun docCount(): Int

    /** Distinct document titles (for self-inspection / edit targeting). */
    @Query("SELECT DISTINCT title FROM knowledge_docs ORDER BY title")
    suspend fun titles(): List<String>

    /** All chunk texts for a title, in storage order (to reconstruct a doc for diff/rollback). */
    @Query("SELECT text FROM knowledge_docs WHERE title = :title ORDER BY id")
    suspend fun textByTitle(title: String): List<String>

    @Query("DELETE FROM knowledge_docs WHERE title = :title")
    suspend fun deleteByTitle(title: String)

    @Query("DELETE FROM knowledge_docs WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Query("DELETE FROM knowledge_docs")
    suspend fun clear()
}
