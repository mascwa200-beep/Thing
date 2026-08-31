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
 * The barcode database: ~4.5 million retail products, answerable with no network at all.
 *
 * ## ⚠️ This is the project's first PREBUILT database, and it is unlike the other two
 *
 * `JarvisDatabase` and `TranscriptDatabase` are both built empty and filled at runtime. This one
 * arrives with its content already inside it and is never written to by the app — so a schema
 * change means a **new file** rather than a migration.
 *
 * ⚠️ **It arrives two ways now, and the older half of this file's comments only knew about one.**
 * The LCARS application still bundles it as an asset; the standalone nutrition application
 * **downloads it as a pack** (`FoodPack` / `FoodPackRepository`), because at 425 MB it was almost the
 * whole of that APK and the in-app updater re-downloads the entire APK on every published build.
 * [open] handles both, and everything below about `createFromAsset` applies only to the bundled one.
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
     * Name search over the bundled products, as fast as an unindexed table can answer.
     *
     * ⚠️ **CORRECTION, and it was the expensive one.** This note used to call itself "fast enough for
     * a keystroke" and `OfflineFoodStore` called it "a fast indexed prefix scan", eleven lines below
     * its own sibling correctly stating there is no index on 4.4 million product names. There is not:
     * `food` declares `barcode INTEGER PRIMARY KEY` and nothing else, `tools/food/build_food_db.py`
     * emits no `CREATE INDEX`, and `EXPLAIN QUERY PLAN` says `SCAN food`. Two comments in one file
     * contradicted each other and the wrong one was the one justifying a call on every keystroke.
     *
     * ⚠️ **The index was never the expensive part. `ORDER BY LENGTH(name)` was.** Measured on a
     * million synthetic rows at this exact column shape, over sixteen prefixes a person actually
     * types, then scaled to the real 4.45M:
     *
     *     ORDER BY LENGTH(name) LIMIT 20    70.5 ms / 1M   ->  ~314 ms
     *     LIMIT 20, no ORDER BY              8.2 ms / 1M   ->   ~37 ms
     *     LIMIT 400, no ORDER BY            13.6 ms / 1M   ->   ~60 ms
     *
     * The distribution is the finding, not the mean. With the sort, EVERY prefix costs the full scan
     * — 57 to 85 ms per million whether it matches or not — because SQLite cannot know which twenty
     * names are shortest until it has seen all of them (`USE TEMP B-TREE FOR ORDER BY`). Without it,
     * a prefix that matches early-exits at the LIMIT in **0.1 to 0.3 ms**, three orders of magnitude
     * better, and only a prefix matching fewer rows than the cap pays for the whole table.
     *
     * That residual full scan on a selective or missing prefix is the honest floor of having no
     * index, and it is not fixable here. The caller ranks the candidates.
     *
     * ⚠️ Those figures are a **lower bound**: a warm page cache on a fast SSD. A phone reading a
     * 424 MB asset cold from flash is worse, and this app exists to run on the phones where it is
     * worst.
     *
     * ⚠️ **THE INDEX IS NO LONGER REJECTED, and this note's reason for rejecting it was wrong in a
     * way worth recording.** It weighed an FTS5 index at 23.8 bytes a row "on an APK the updater
     * re-downloads on every build". Both halves have since failed: the database left the APK, so the
     * cost is a one-time download; and FTS5 is not available on Android at all — the platform builds
     * its SQLite with FTS3 and FTS4 and no FTS5, so a database containing one would fail every query
     * on every device rather than merely being large. What ships is FTS4, and `OfflineFoodStore`
     * detects it rather than assuming it, because a pack built before it existed still has to work.
     */
    @Query("SELECT * FROM food WHERE name LIKE :prefix || '%' AND kcal IS NOT NULL LIMIT :limit")
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
        /**
         * The asset CI drops into place before packaging, where an application still bundles one.
         *
         * ⚠️ **The standalone nutrition application no longer does, and that is the point of the
         * pack.** Its APK was 189,972,281 bytes of which the overwhelming majority was this file, and
         * the in-app updater re-downloads the WHOLE APK on every published build — so adding barcodes
         * meant re-downloading the entire corpus every time a line of UI changed. The LCARS
         * application still bundles it, deliberately untouched: this change was asked for on the
         * nutrition app and moving both at once would be a behaviour change on a daily driver for no
         * reason anybody asked for. [open] handles both, and which one a build is is decided by
         * whether the asset is there.
         */
        const val ASSET = "food/food.db"

        /**
         * The file the database lives in.
         *
         * ⚠️ Named here so that three things agree: the space guard, Room, and
         * `FoodPackRepository`, which downloads a pack and writes it **to this path directly**
         * rather than through an asset. A fourth spelling of this name anywhere is a database the
         * app writes and never reads.
         */
        const val DB_NAME = "food.db"

        /**
         * What the first open needs on disk, in bytes.
         *
         * ⚠️ **Measured, not guessed: CI prints `food database packaged: 424 MB uncompressed` on
         * every build.** The asset ships deflated to roughly a quarter of that and Room's
         * `createFromAsset` writes the whole uncompressed file out on the first query, through a
         * temporary that is then renamed — so the peak requirement is the full size plus room to
         * move. The margin here is deliberately generous: a phone that unpacks this with twenty
         * megabytes to spare has not been helped.
         *
         * If the corpus grows past this the guard becomes slightly optimistic, which fails the way it
         * always did — mid-copy, reported by the store's own error path. It cannot fail the other way.
         */
        private const val FIRST_OPEN_BYTES = 460L * 1024L * 1024L

        /**
         * Why the last [open] returned null, or null if it did not.
         *
         * ⚠️ Exists because null had exactly one meaning to callers — "the asset is not there" — and
         * now has two. A phone that refused to unpack for want of room is a completely different
         * situation from a build where CI never fetched the database, and the report that reaches me
         * is the only place either becomes visible.
         */
        @Volatile
        var lastOpenNote: String? = null
            private set

        @Volatile
        private var instance: FoodDatabase? = null

        /**
         * Is the file on disk a database this build can actually open — and DELETE it if not.
         *
         * ⚠️ **Without this, `fallbackToDestructiveMigration()` is a loaded gun pointed at a
         * download the person waited several minutes for.** It exists so that a bumped schema
         * replaces a stale *asset*; on a file the app fetched itself it means Room silently deletes
         * several hundred megabytes and creates an EMPTY database in its place, and the scanner then
         * recognises nothing at all with no error anywhere. That fallback stays — it is what makes a
         * bundled asset's version bump work — and this is what stops it ever seeing a file it would
         * destroy without the app knowing.
         *
         * Deleting here rather than merely refusing is deliberate: a stale or truncated file is not
         * a thing to preserve, and leaving it would make the repository's free-space check fail
         * against the room already taken by a database nobody can read.
         *
         * ⚠️ It also catches a **half-written download** — an interrupted fetch leaves a file that
         * exists and has a length, and handing that to Room is an exception at the first query
         * rather than at open.
         */
        private fun usable(file: java.io.File): Boolean {
            val version = runCatching {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                ).use { it.version }
            }.getOrNull()
            if (version == SCHEMA) return true
            lastOpenNote = if (version == null) {
                "the downloaded database was damaged and has been removed; download it again"
            } else {
                "the downloaded database is for an older version of this app; download it again"
            }
            runCatching { file.delete() }
            // ⚠️ Room's own sidecars, or the next open finds a journal describing a file that has
            // gone. `-wal` and `-shm` are absent under TRUNCATE journalling and deleting a file that
            // is not there costs nothing, so this is unconditional rather than conditional.
            runCatching { java.io.File(file.path + "-journal").delete() }
            runCatching { java.io.File(file.path + "-wal").delete() }
            runCatching { java.io.File(file.path + "-shm").delete() }
            return false
        }

        /**
         * The Room version, restated as a constant so the guard above can compare against it.
         *
         * ⚠️ It must equal the `version` in the `@Database` annotation and `FoodPack.SCHEMA`. Three
         * numbers that have to agree is two too many, and the annotation is the one that cannot be
         * read back at runtime — hence a constant beside it rather than a fourth spelling.
         */
        const val SCHEMA: Int = 2

        /**
         * Close whatever is open, so the file underneath can be replaced.
         *
         * ⚠️ **Without this, updating the pack leaves the app reading a database that no longer
         * exists.** [instance] is cached for the life of the process, and SQLite holds an open file
         * descriptor: deleting and renaming underneath it does not fail on Unix — the old inode
         * simply stays alive, unreferenced by any name, until the last handle closes. So every query
         * would keep answering from the OLD corpus, with a new one sitting on disk, until the person
         * happened to kill the app. And the freed space is not freed either, so the next install's
         * free-space check would be measuring room that a deleted file is still occupying.
         *
         * Nulled as well as closed, so the next [open] genuinely re-opens rather than handing back a
         * closed handle. Callers holding a `FoodDao` from before this must not be used again — which
         * is why `NutritionContainer` memoises its store on success only and re-derives it.
         */
        fun close() {
            synchronized(this) {
                runCatching { instance?.close() }
                instance = null
                lastOpenNote = null
            }
        }

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
                val dbFile = runCatching { app.getDatabasePath(DB_NAME) }.getOrNull()
                // ⚠️ `usable` sets [lastOpenNote] when it rejects a file, and that note is more
                // specific than the generic one below — "the download was damaged" against "there is
                // no database". Held here so the general case cannot overwrite the particular one.
                var rejected: String? = null
                val onDisk = dbFile != null && dbFile.exists() && dbFile.length() > 0 &&
                    usable(dbFile).also { if (!it) rejected = lastOpenNote }
                val bundled = runCatching {
                    app.assets.open(ASSET).use { it.read() >= 0 }
                }.getOrDefault(false)

                // ⚠️ **This is the single most dangerous branch in the file, and it is a REFUSAL.**
                // With neither a downloaded database nor a bundled asset, `databaseBuilder` would
                // happily create an EMPTY one — Room runs `onCreate`, emits the schema, and every
                // barcode from then on answers "no such product" from a table that is genuinely
                // empty. No error, no exception, nothing in a log: a scanner that reads every packet
                // perfectly and recognises none of them, permanently. Returning null is what makes
                // the surface say "the food database has not been downloaded yet" instead.
                if (!onDisk && !bundled) {
                    lastOpenNote = rejected ?: "the food database has not been downloaded yet"
                    return null
                }

                // ⚠️ Only the first unpack needs the room, and only from an asset: a downloaded pack
                // is already written to this exact path by `FoodPackRepository`, so there is nothing
                // left to copy. Checking unconditionally would refuse to open a database already
                // sitting on the disk on a phone that is merely full, which takes the feature away
                // at exactly the wrong moment.
                if (bundled && !onDisk && dbFile != null) {
                    val free = runCatching { (dbFile.parentFile ?: app.filesDir).usableSpace }.getOrDefault(-1L)
                    if (free in 0 until FIRST_OPEN_BYTES) {
                        lastOpenNote = "unpacking it needs about ${FIRST_OPEN_BYTES / (1024 * 1024)} MB " +
                            "and this phone has ${free / (1024 * 1024)} MB free"
                        return null
                    }
                }
                val db = runCatching {
                    Room.databaseBuilder(app, FoodDatabase::class.java, DB_NAME)
                        // ⚠️ Conditional, and harmless either way once the file exists — Room's
                        // `SQLiteCopyOpenHelper` copies only when the destination is absent. A build
                        // with no asset must NOT name one: `createFromAsset` on a missing asset
                        // throws at open time rather than falling back.
                        .apply { if (bundled) createFromAsset(ASSET) }
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
                }.getOrNull()
                if (db == null) {
                    lastOpenNote = "the database would not open"
                    return null
                }
                lastOpenNote = null
                instance = db
                return db
            }
        }
    }
}
