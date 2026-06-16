package dev.mascwa.pulse.data.jarvis.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** The on-device J.A.R.V.I.S. Matrix state machine. */
@Database(
    entities = [SystemStateEntity::class, ConversationHistoryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun systemStateDao(): SystemStateDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        fun build(context: Context): JarvisDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                JarvisDatabase::class.java,
                "jarvis_matrix.db",
            ).fallbackToDestructiveMigration().build()
    }
}
