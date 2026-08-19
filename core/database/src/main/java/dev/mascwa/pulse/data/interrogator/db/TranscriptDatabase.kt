package dev.mascwa.pulse.data.interrogator.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The acoustic interrogator's rolling transcript.
 *
 * ⚠️ **This is a SEPARATE database from [dev.mascwa.pulse.data.jarvis.db.JarvisDatabase], and the
 * separation is load-bearing rather than tidiness.**
 *
 *  1. That database is built with `fallbackToDestructiveMigration()`, which its own comment accepts
 *     because the J.A.R.V.I.S. state it held was small and regenerable. It is no longer only that:
 *     it now carries `knowledge_docs`, the documents the *user* ingested for retrieval. Adding a
 *     table there means bumping its version, and bumping its version destroys those documents.
 *  2. A one-tap purge of a separate file is `deleteDatabase()`. Deleting rows from a shared table
 *     leaves the content in freed SQLite pages until something reuses or vacuums them; deleting the
 *     file removes the bytes. For the one store in this app that holds verbatim speech from people
 *     who did not consent to being recorded, that difference is the whole point.
 *
 * ⚠️ **The text column holds CIPHERTEXT, not speech.** Encryption is applied a layer up, in the app
 * module, because `SecretCrypto` is Keystore-bound and lives there. This module never sees plaintext
 * and has no way to decrypt what it stores.
 *
 * ⚠️ **Timestamps are NOT encrypted, and that is a real if small leak** — they reveal when speech
 * happened and how much of it there was, even though not a word of what was said. They are in the
 * clear because pruning is by age and by count, and a store that cannot prune without decrypting
 * every row would either leak more (holding keys open) or fail to enforce its own retention. Stated
 * rather than glossed: the retention bound is worth more than the metadata it costs.
 */
@Entity(tableName = "utterances")
data class UtteranceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [dev.mascwa.pulse.core.telemetry.TranscriptPolicy]-admitted, redacted, then encrypted. */
    @ColumnInfo(name = "cipher") val cipher: String,
    @ColumnInfo(name = "at_ms") val atMs: Long,
)

@Dao
interface UtteranceDao {

    @Insert
    suspend fun insert(row: UtteranceEntity): Long

    /** Newest first, for the live view. */
    @Query("SELECT * FROM utterances ORDER BY at_ms DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<UtteranceEntity>

    @Query("SELECT COUNT(*) FROM utterances")
    suspend fun count(): Int

    /** The age half of the retention bound. */
    @Query("DELETE FROM utterances WHERE at_ms < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    /**
     * The count half.
     *
     * ⚠️ Keyed on `id NOT IN (newest :keep by at_ms)` rather than on a computed offset, so it is
     * correct whatever order rows were inserted in — a transcriber that emits a late chunk out of
     * order must not be able to evict something newer than itself.
     */
    @Query(
        "DELETE FROM utterances WHERE id NOT IN " +
            "(SELECT id FROM utterances ORDER BY at_ms DESC LIMIT :keep)",
    )
    suspend fun trimToNewest(keep: Int): Int

    /** Everything. The purge control, and what a failed decrypt falls back to. */
    @Query("DELETE FROM utterances")
    suspend fun clear(): Int
}

@Database(entities = [UtteranceEntity::class], version = 1, exportSchema = false)
abstract class TranscriptDatabase : RoomDatabase() {
    abstract fun utteranceDao(): UtteranceDao

    companion object {
        const val FILE = "interrogator_transcript.db"

        fun build(context: Context): TranscriptDatabase =
            Room.databaseBuilder(context.applicationContext, TranscriptDatabase::class.java, FILE)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // ⚠️ Overwrites freed pages with zeroes instead of leaving the old content
                        // readable until something reuses them. It costs write throughput, which for
                        // a store that appends a short row every few seconds and deletes in batches
                        // is not a cost worth caring about, and it is the right default for the one
                        // table in this app holding verbatim speech. It reduces recoverability; it
                        // does not eliminate it, which is why purge deletes the file.
                        db.execSQL("PRAGMA secure_delete = ON")
                    }
                })
                // Destructive on upgrade, deliberately and unlike the shared database: a transcript
                // is capped at a day and is the one thing here nobody should mind losing. Migrating
                // it would be more risk than the data is worth.
                .fallbackToDestructiveMigration()
                .build()

        /** Removes the file rather than the rows. See the class KDoc. */
        fun destroy(context: Context): Boolean =
            context.applicationContext.deleteDatabase(FILE)
    }
}
