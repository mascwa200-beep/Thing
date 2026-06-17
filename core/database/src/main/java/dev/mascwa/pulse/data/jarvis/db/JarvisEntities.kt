package dev.mascwa.pulse.data.jarvis.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * Persistent operational memory for the J.A.R.V.I.S. Matrix. Two tables, mirroring
 * the spec: a key/value [system_states] baseline store and an append-only
 * [context_history] of tokenized exchanges used for local context-window compaction.
 */

@Entity(tableName = "system_states")
data class SystemStateEntity(
    @PrimaryKey @ColumnInfo(name = "state_key") val stateKey: String,
    @ColumnInfo(name = "state_value") val stateValue: String,
    @ColumnInfo(name = "last_updated") val lastUpdated: Long,
)

@Entity(tableName = "context_history")
data class ConversationHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "speaker") val speaker: String,
    @ColumnInfo(name = "message_text") val messageText: String,
    @ColumnInfo(name = "semantic_tag") val semanticTag: String? = null,
)

/** Canonical speaker tags for [ConversationHistoryEntity.speaker]. */
object Speaker {
    const val USER = "user"
    const val JARVIS = "jarvis"
    const val SYSTEM = "system"
}

/**
 * Durable notes/facts the agent can save and later retrieve (the on-device "memory"/RAG store).
 * Retrieval is lexical via the FTS4 mirror [AgentNoteFts] — no embeddings on-device.
 */
@Entity(tableName = "agent_notes")
data class AgentNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "note_text") val noteText: String,
    @ColumnInfo(name = "source") val source: String = "user", // user | observation | inference
)

/** Full-text index over [AgentNoteEntity.noteText] (external-content FTS; rowid = note id). */
@Fts4(contentEntity = AgentNoteEntity::class)
@Entity(tableName = "agent_notes_fts")
data class AgentNoteFts(
    @ColumnInfo(name = "note_text") val noteText: String,
)

/** Sources for [AgentNoteEntity.source]. */
object NoteSource {
    const val USER = "user"
    const val OBSERVATION = "observation"
    const val INFERENCE = "inference"
}

/**
 * The on-device knowledge library (the "docs RAG"): user-loaded documents, split into chunks so the
 * relevant pieces can be lexically retrieved (via the FTS4 mirror [KnowledgeDocFts]) and injected
 * into the model's prompt at question time. This is retrieval — the frozen model is never trained.
 * Each row is one chunk; [title] groups the chunks of a single source document.
 */
@Entity(tableName = "knowledge_docs")
data class KnowledgeDocEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "source") val source: String = "", // url, filename, or "paste"
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "text") val text: String,
)

/** Full-text index over [KnowledgeDocEntity] title + text (external-content FTS; rowid = doc id). */
@Fts4(contentEntity = KnowledgeDocEntity::class)
@Entity(tableName = "knowledge_docs_fts")
data class KnowledgeDocFts(
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "text") val text: String,
)
