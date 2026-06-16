package dev.mascwa.pulse.data.jarvis.db

import androidx.room.ColumnInfo
import androidx.room.Entity
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
