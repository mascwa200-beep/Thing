package dev.mascwa.pulse.data.food.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * The bundled barcode database: ~4.4 million retail products, answerable with no network at all.
 *
 * ## ⚠️ This is the project's first PREBUILT database, and it is unlike the other two
 *
 * `JarvisDatabase` and `TranscriptDatabase` are both built empty and filled at runtime. This one
 * ships with its content already inside it, as an asset, and is never written to — so a schema
 * change means a **new asset** rather than a migration.
 *
 * ⚠️ **CORRECTION to what this paragraph used to say.** It claimed this database "must not copy
 * either habit" and in particular must not use `fallbackToDestructiveMigration`. That was wrong,
 * and the version-2 bump is what proved it: without that call Room refuses to replace the copied
 * file and then demands a migration that cannot exist, so the app throws on every phone that
 * already has the old asset. The reasoning was right — a migration path here would be a fiction —
 * and the conclusion drawn from it was backwards. The mechanism is spelled out at the call site,
 * read out of the room-runtime bytecode.
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
     * Name search over the bundled products, fast enough for a keystroke.
     *
     * ⚠️ **Deliberately a prefix-anchored LIKE and deliberately capped.** An unanchored `%term%` over
     * 4.4M rows is a full table scan, and one of those per keystroke would be visibly slow — so this
     * is what the search FIELD calls, and `OfflineFoodStore.searchAllProducts` is the deliberate
     * one-shot scan that finds the rest.
     *
     * ⚠️ **CORRECTION to what this note used to claim.** It said a full-text index "would cost more
     * than the table itself". Measured on 113,612 real product names, an FTS5 index with
     * `detail=none` costs **23.8 bytes a row against the table's 68.7** — about a third, not more. The
     * reason there is still no index is different and is recorded on `searchAllProducts`: it is ~107 MB
     * on an APK the in-app updater re-downloads in full on every build, to make an action somebody
     * deliberately took faster rather than possible.
     */
    @Query(
        "SELECT * FROM food WHERE name LIKE :prefix || '%' AND kcal IS NOT NULL " +
            "ORDER BY LENGTH(name) LIMIT :limit"
    )
    suspend fun searchByNamePrefix(prefix: String, limit: Int): List<FoodRow>

    /**
     * Every row whose name satisfies a caller-built predicate. The full-scan path.
     *
     * ⚠️ **A raw query because the number of search terms varies and SQLite has no array parameter.**
     * The predicate is assembled by `OfflineFoodStore.sqlFor` from `FoodSearch.tokens`, which keeps
     * only letters and digits — so nothing a person can type reaches SQL as syntax. That is stated
     * where the predicate is built as well as here, because a raw query is the one place in this app
     * where it would matter.
     *
     * ⚠️ `kcal IS NOT NULL` matches the prefix path: a row with a name and no numbers is worth
     * returning for a SCAN (that is the "recognised, tap to add the numbers" case) but not for a
     * search somebody expects to log from. Both paths agree, so a product does not appear in one and
     * vanish from the other.
     */
    @RawQuery
    suspend fun searchByNameWords(query: SupportSQLiteQuery): List<FoodRow>

    /**
     * Every further nutrient recorded for one product, in whatever order the table holds them.
     *
     * ⚠️ **An empty list is the ordinary answer and never an error.** Roughly two products in three
     * have none of these, and the surface must render that as nothing rather than as zeroes — a food
     * with no magnesium row has not been measured for magnesium, which is a different fact from
     * containing none. This is the same rule `Micronutrients` argues at length for its eight.
     *
     * A separate read rather than a join: the caller already has the [FoodRow], the common case is
     * a single scanned barcode, and joining would repeat the whole product row once per nutrient.
     */
    @Query("SELECT * FROM food_extra WHERE barcode = :barcode")
    suspend fun extrasFor(barcode: Long): List<FoodExtraRow>

    @Query("SELECT COUNT(*) FROM food")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM food_extra")
    suspend fun extraCount(): Int

    /** Attribution and build date, so the app can display where this came from. */
    @Query("SELECT value FROM meta WHERE key = :key LIMIT 1")
    suspend fun meta(key: String): String?
}

/**
 * One further nutrient of one product — the sparse layer beside [FoodRow]'s sixteen.
 *
 * ## ⚠️ A side table because 29 more columns would cost 128 MB of nothing
 *
 * Measured, not reasoned about. SQLite spends a byte of record header on a column even when it is
 * NULL, so at 4,524,449 rows the widened shape costs about a byte per row per column whatever it
 * holds. Built both ways over 200,000 realistic rows and extrapolated:
 *
 * | | cost above the plain food table |
 * |---|---|
 * | 29 always-NULL columns | **+127.9 MB**, carrying nothing |
 * | this side table | **+44.7 MB**, carrying every figure there is |
 *
 * The densest of the twenty-nine is recorded on 5.7% of products and most are near 2%, so the
 * sparse shape is not a marginal win — it is the difference between the feature being affordable
 * and not.
 *
 * ⚠️ **`WITHOUT ROWID`, and that halves it again.** With an ordinary rowid table the pair would need
 * a separate unique index, and the index would then hold a second copy of both key columns: measured
 * at **4.27 MB against 1.98 MB** for the same 121,147 rows. The primary key IS the B-tree here, the
 * same reasoning that makes `barcode INTEGER PRIMARY KEY` the right shape for [FoodRow].
 *
 * ⚠️ Room does not emit `WITHOUT ROWID` and does not need to: this table arrives inside the
 * prebuilt asset, and Room's schema validation reads `PRAGMA table_info`, which says nothing about
 * rowid-ness. The builder is what declares it.
 *
 * @param nutrient `NutrientSet.Nutrient.id` — a permanent number, never the enum's ordinal. It is
 *   written into an asset of millions of rows, so reordering that enum would silently re-label
 *   every value here; `NutrientSetTest` pins the whole mapping to stop it.
 * @param value the figure in that nutrient's own unit, scaled by `NutrientSet.Unit.scale`.
 */
@Entity(tableName = "food_extra", primaryKeys = ["barcode", "nutrient"])
data class FoodExtraRow(
    val barcode: Long,
    val nutrient: Int,
    val value: Int,
)

/** A row of the `meta` table — attribution, build date, corpus counts. */
@Entity(tableName = "meta")
data class FoodMetaRow(@PrimaryKey val key: String, val value: String?)

@Database(
    entities = [FoodRow::class, FoodExtraRow::class, FoodMetaRow::class],
    version = 2,
    exportSchema = false,
)
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
                        // ⚠️ **This is what makes a version bump WORK, and without it the bump
                        // crashes every phone that already unpacked the asset.** Read out of the
                        // shipped room-runtime 2.6.1 bytecode rather than recalled, because the
                        // comment that used to sit here asserted the opposite:
                        //
                        //   SQLiteCopyOpenHelper.verifyDatabaseFile — file absent, copy; else read
                        //   the on-device version; equal, return; else if
                        //   DatabaseConfiguration.isMigrationRequired(current, wanted) return
                        //   WITHOUT copying; only otherwise deleteDatabase() and copy the asset.
                        //
                        //   isMigrationRequired = requireMigration && current not in
                        //   migrationNotRequiredFrom — so with no fallback it is TRUE, the fresh
                        //   asset is never copied, and Room then opens the stale file, wants a
                        //   migration from 1 to 2, finds none and throws.
                        //
                        // fallbackToDestructiveMigration() sets requireMigration = false (and
                        // allowDestructiveMigrationOnDowngrade = true), so the answer is false, the
                        // old file is deleted and the new asset lands. On a database that is shipped
                        // rather than accumulated that is not destruction — there is nothing of the
                        // user's in here — it is the only way to hand them the new content at all.
                        .fallbackToDestructiveMigration()
                        .setJournalMode(JournalMode.TRUNCATE)
                        .build()
                }.getOrNull() ?: return null
                instance = db
                return db
            }
        }
    }
}
