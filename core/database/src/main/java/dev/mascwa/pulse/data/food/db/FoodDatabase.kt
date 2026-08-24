package dev.mascwa.pulse.data.food.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The bundled barcode database: ~4.4 million retail products, answerable with no network at all.
 *
 * ## ⚠️ This is the project's first PREBUILT database, and it is unlike the other two
 *
 * `JarvisDatabase` and `TranscriptDatabase` are both built empty and filled at runtime, and both
 * use `fallbackToDestructiveMigration` because the state they hold is small and regenerable. **This
 * one must not copy either habit.** It ships with its content already inside it, shipped as an
 * asset, and it is never written to — so it is opened read-only, and a schema change means a new
 * asset rather than a migration.
 *
 * Verified against the shipped room-runtime 2.6.1 artifact before this was written:
 * `RoomDatabase.Builder.createFromAsset` exists, and `FileUtil.copy` performs the unpack with
 * `FileChannel.transferFrom` — it streams, so a 240 MB asset is not read into memory to be copied
 * out. That was the one thing that had to be true for this design to be possible at all.
 *
 * ## Why the barcode is the primary key
 *
 * Declaring it `INTEGER PRIMARY KEY` makes it the rowid, which means the table **is** its own
 * B-tree: there is no second index to build, store or keep warm. Measured on real rows, that is the
 * difference between 56.5 bytes a row and roughly a hundred. At 4.4M rows it decides whether the
 * database fits in an application at all.
 *
 * ⚠️ It is also correctness, not just size. The key comes from `BarcodeScan.normalize`, which reads
 * a barcode as a NUMBER — so a US packet's UPC-A and a European database's EAN-13, which differ
 * only by a leading zero, are one key rather than two rows that never meet.
 *
 * ## Why nutrients are integers
 *
 * A REAL is eight bytes whether it holds 0.0 or π. Most products in the corpus have most nutrient
 * columns empty, and SQLite spends about a byte on a NULL — so scaled integers plus honest NULLs
 * cost a fraction of what a row of doubles would. The scales are chosen to be exact for the
 * precision the sources actually publish, so nothing is lost in the rounding: see [FoodRow].
 */
@Entity(tableName = "food")
data class FoodRow(
    /** The normalised numeric barcode. See `BarcodeScan.normalize` — it is the rowid. */
    @PrimaryKey val barcode: Long,

    val name: String?,
    val brand: String?,

    /** Kilocalories per 100 g, unscaled. */
    val kcal: Int?,

    /** Grams per 100 g, x10 — so 12.3 g is stored as 123. */
    val prot: Int?,
    val carb: Int?,
    val fat: Int?,
    val fib: Int?,
    val sug: Int?,
    val sat: Int?,

    /** Milligrams per 100 g, unscaled. ⚠️ Open Food Facts publishes grams; the builder converts. */
    val sod: Int?,

    /** Milligrams per 100 g. */
    val calcium: Int?,

    /** Milligrams per 100 g, x100 — iron is a fraction of a milligram in most foods. */
    val iron: Int?,

    /** Milligrams per 100 g. */
    val potassium: Int?,

    /** Micrograms per 100 g. */
    @ColumnInfo(name = "vit_a") val vitA: Int?,

    /** Milligrams per 100 g, x100. */
    @ColumnInfo(name = "vit_c") val vitC: Int?,

    /** Micrograms per 100 g. */
    @ColumnInfo(name = "vit_d") val vitD: Int?,

    /** Milligrams per 100 g. */
    val chol: Int?,

    /** Grams per 100 g, x10. */
    val transfat: Int?,

    /** One declared serving, in grams. Null where the source never said. */
    @ColumnInfo(name = "serv_g") val servingGrams: Int?,

    /**
     * What one serving IS, in the source's own words — "1 slice (56 g)", "2 Tbsp (30 ml)".
     *
     * ⚠️ Only kept where it says something the gram figure does not. More than half of Open Food
     * Facts' `serving_size` is simply the mass again ("30.0g"), and rendering "1 serving (30 g)"
     * with "30.0g" beneath it is the same number twice, which reads as a fault. The builder drops
     * any label made only of unit words — including the GS1 codes ONZ and OZA, which are an ounce
     * and a fluid ounce and read as gibberish. Present on 20.2% of products carrying nutrition.
     */
    @ColumnInfo(name = "serv_label") val servingLabel: String?,

    /**
     * The whole package, in grams. Null where the source never said.
     *
     * ⚠️ Filled from Open Food Facts' `product_quantity`, which is **already numeric grams** — the
     * project converts it itself, so the free-text `quantity` beside it needs no parser. Present on
     * 29.9% of products carrying nutrition; a zero is stored as null, because a package that weighs
     * nothing would put a "1 package" portion in the picker that resolves to no food at all.
     */
    @ColumnInfo(name = "pack_g") val packageGrams: Int?,

    /** [SOURCE_OFF] or [SOURCE_USDA] — which body published this row. */
    val src: Int,
) {
    /**
     * ⚠️ **Absent nutrition is a first-class state, not a failure.** Measured on the real export,
     * only about a fifth of products carry any numbers — so a database of complete rows only would
     * throw away four scans in five. A row with a name and no numbers still lets the app say
     * "Kellogg's Corn Flakes, nobody recorded the numbers, tap to add them", which is a completely
     * different experience from "unknown barcode".
     */
    val hasNutrition: Boolean get() = (kcal ?: 0) > 0

    companion object {
        const val SOURCE_OFF = 1
        const val SOURCE_USDA = 2
    }
}

@Dao
interface FoodDao {

    /** The whole point: one indexed read, no network, no allocation beyond the row. */
    @Query("SELECT * FROM food WHERE barcode = :barcode LIMIT 1")
    suspend fun byBarcode(barcode: Long): FoodRow?

    /**
     * Name search over the bundled products.
     *
     * ⚠️ **Deliberately a prefix-anchored LIKE and deliberately capped.** There is no full-text
     * index on this table, because one over 4.4M product names would cost more than the table
     * itself and this is the secondary path — the bundled seed and the custom foods answer typed
     * searches, and this is here so a product you have scanned before can also be found by typing.
     * An unanchored `%term%` over 4.4M rows is a full scan and would be visibly slow.
     */
    @Query(
        "SELECT * FROM food WHERE name LIKE :prefix || '%' AND kcal IS NOT NULL " +
            "ORDER BY LENGTH(name) LIMIT :limit"
    )
    suspend fun searchByNamePrefix(prefix: String, limit: Int): List<FoodRow>

    @Query("SELECT COUNT(*) FROM food")
    suspend fun count(): Int

    /** Attribution and build date, so the app can display where this came from. */
    @Query("SELECT value FROM meta WHERE key = :key LIMIT 1")
    suspend fun meta(key: String): String?
}

/** A row of the `meta` table — attribution, build date, corpus counts. */
@Entity(tableName = "meta")
data class FoodMetaRow(@PrimaryKey val key: String, val value: String?)

@Database(entities = [FoodRow::class, FoodMetaRow::class], version = 1, exportSchema = false)
abstract class FoodDatabase : RoomDatabase() {
    abstract fun dao(): FoodDao

    companion object {
        /** The asset CI drops into place before packaging. */
        const val ASSET = "food/food.db"

        @Volatile
        private var instance: FoodDatabase? = null

        /**
         * Open the bundled database, or null if it is not there.
         *
         * ⚠️ **Null is a real answer and callers must handle it.** The asset is fetched by CI rather
         * than committed — GitHub rejects files over 100 MB and this is far past that — so a local
         * developer build, or a CI run where the fetch failed, genuinely has no database. Returning
         * null lets the app fall back to the network path it has always had, which is worse but
         * works, instead of crashing on a missing asset.
         */
        fun open(context: Context): FoodDatabase? {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val app = context.applicationContext
                val present = runCatching {
                    app.assets.open(ASSET).use { it.read() >= 0 }
                }.getOrDefault(false)
                if (!present) return null
                val db = runCatching {
                    Room.databaseBuilder(app, FoodDatabase::class.java, "food.db")
                        .createFromAsset(ASSET)
                        // ⚠️ NOT fallbackToDestructiveMigration, unlike the other two databases in
                        // this project. This one is shipped rather than accumulated: there is
                        // nothing of the user's in it to destroy, and a version bump means a new
                        // asset, so a migration path would be a fiction.
                        .setJournalMode(JournalMode.TRUNCATE)
                        .build()
                }.getOrNull() ?: return null
                instance = db
                return db
            }
        }
    }
}
