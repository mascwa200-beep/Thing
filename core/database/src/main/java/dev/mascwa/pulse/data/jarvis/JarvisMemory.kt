package dev.mascwa.pulse.data.jarvis

import dev.mascwa.pulse.data.jarvis.db.AgentNoteEntity
import dev.mascwa.pulse.data.jarvis.db.ConversationHistoryEntity
import dev.mascwa.pulse.data.jarvis.db.JarvisDatabase
import dev.mascwa.pulse.data.jarvis.db.NoteSource
import dev.mascwa.pulse.data.jarvis.db.Speaker
import dev.mascwa.pulse.data.jarvis.db.SystemStateEntity
import kotlinx.coroutines.flow.Flow

/**
 * Repository over the [JarvisDatabase]. The single point the engine, telemetry
 * and UI use to read/write operational state and the conversation context window.
 */
class JarvisMemory(db: JarvisDatabase) {

    private val states = db.systemStateDao()
    private val convo = db.conversationDao()
    private val notes = db.agentNoteDao()

    /** Live conversation history (oldest → newest) for the console. */
    val history: Flow<List<ConversationHistoryEntity>> = convo.observeAll()

    /** Live key/value telemetry baselines. */
    val systemStates: Flow<List<SystemStateEntity>> = states.observeAll()

    suspend fun setState(key: String, value: String) =
        states.upsert(SystemStateEntity(key, value, System.currentTimeMillis()))

    suspend fun getState(key: String): String? = states.get(key)?.stateValue

    suspend fun append(speaker: String, text: String, tag: String? = null): Long =
        convo.insert(
            ConversationHistoryEntity(
                timestamp = System.currentTimeMillis(),
                speaker = speaker,
                messageText = text,
                semanticTag = tag,
            ),
        )

    /** The most recent [limit] turns, oldest → newest, for context-window injection. */
    suspend fun recentContext(limit: Int = 12): List<ConversationHistoryEntity> =
        convo.recent(limit).asReversed()

    suspend fun clearHistory() = convo.clear()

    // ---- Durable agent memory (notes / facts) — the on-device RAG store ----

    /** Save a durable note/fact the agent can later recall. */
    suspend fun remember(text: String, source: String = NoteSource.USER): Long =
        notes.insert(AgentNoteEntity(timestamp = System.currentTimeMillis(), noteText = text.trim(), source = source))

    /**
     * Lexically retrieve the notes most relevant to [query]. The query is sanitized into safe FTS
     * MATCH syntax (alphanumeric terms OR-ed together) so arbitrary user text can never break it.
     */
    suspend fun recall(query: String, limit: Int = 5): List<AgentNoteEntity> {
        val match = query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
            .distinct()
            .take(8)
            // Quote each term so an FTS reserved word (or/and/not/near) is treated as a literal.
            .joinToString(" OR ") { "\"$it\"" }
        if (match.isBlank()) return emptyList()
        return runCatching { notes.search(match, limit) }.getOrDefault(emptyList())
    }

    /** Most recent notes regardless of relevance. */
    suspend fun recentNotes(limit: Int = 10): List<AgentNoteEntity> = notes.recent(limit)

    /** Live list of all durable notes, newest first — for the Memory manager screen. */
    val notesFlow: Flow<List<AgentNoteEntity>> = notes.observeAll()

    /** Edit a note's text in place (keeps id/source/timestamp). No-op if the note is gone. */
    suspend fun updateNote(id: Long, text: String) {
        val existing = notes.getById(id) ?: return
        notes.update(existing.copy(noteText = text.trim()))
    }

    /** Delete a single note. */
    suspend fun deleteNote(id: Long) = notes.deleteById(id)

    suspend fun clearNotes() = notes.clear()

    companion object {
        const val SPEAKER_USER = Speaker.USER
        const val SPEAKER_JARVIS = Speaker.JARVIS
    }
}
