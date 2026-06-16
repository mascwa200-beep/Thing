package dev.mascwa.pulse.data.jarvis

import dev.mascwa.pulse.data.jarvis.db.ConversationHistoryEntity
import dev.mascwa.pulse.data.jarvis.db.JarvisDatabase
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

    companion object {
        const val SPEAKER_USER = Speaker.USER
        const val SPEAKER_JARVIS = Speaker.JARVIS
    }
}
