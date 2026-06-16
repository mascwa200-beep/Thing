package dev.mascwa.pulse.data.jarvis.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** The on-device J.A.R.V.I.S. Matrix state machine. */
@Database(
    entities = [
        SystemStateEntity::class,
        ConversationHistoryEntity::class,
        AgentNoteEntity::class,
        AgentNoteFts::class,
    ],
    // v2 adds the agent notes + FTS tables. We keep fallbackToDestructiveMigration (below), so the
    // small J.A.R.V.I.S. DB is recreated once on upgrade rather than risking a hand-written FTS migration.
    version = 2,
    exportSchema = false,
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun systemStateDao(): SystemStateDao
    abstract fun conversationDao(): ConversationDao
    abstract fun agentNoteDao(): AgentNoteDao

    companion object {
        fun build(context: Context): JarvisDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                JarvisDatabase::class.java,
                "jarvis_matrix.db",
            ).fallbackToDestructiveMigration().build()
    }
}
