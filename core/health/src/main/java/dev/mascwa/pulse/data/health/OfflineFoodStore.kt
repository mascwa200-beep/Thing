package dev.mascwa.pulse.data.health

import androidx.sqlite.db.SimpleSQLiteQuery
import dev.mascwa.pulse.core.telemetry.BarcodeScan
import dev.mascwa.pulse.core.telemetry.FoodSearch
import dev.mascwa.pulse.core.telemetry.Micronutrients
import dev.mascwa.pulse.core.telemetry.NutrientSet
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.data.food.Food
import dev.mascwa.pulse.data.food.db.FoodDatabase
import dev.mascwa.pulse.data.food.db.FoodRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The bundled barcode database, in the app's own shape.
 *
 * One job: turn a scanned code into a [Food] with no network at all. Everything downstream — the
 * portion arithmetic, the log, the coach — is untouched, because what comes out of here is the same
 * [Food] the network path has always produced.
 *
 * ⚠️ **The scaled integers are unpacked here and nowhere else.** The database stores nutrients as
 * integers because a REAL costs eight bytes whether it holds 0.0 or π, and at 4.4 million rows that
 * decides whether the thing fits in an application at all. The scales are recorded on [FoodRow] and
 * the multiplications below are their only inverse; a second copy of them anywhere would be a second
 * chance to divide somebody's sodium by ten.
 */
class OfflineFoodStore(
    private val db: FoodDatabase,

    /**
     * Told when the database could not answer, with the operation's name and what threw.
     *
     * ⚠️ **A lambda rather than a dependency on the crash reporter, for the reason `HealthDeps` gives
     * for its own four.** That class lives in `:core:update` and this module has no business
     * depending on the updater to say a query failed. Each application passes its own.
     *
     * ⚠️ **Defaulted to nothing so adding it changed no construction** — but both applications DO
     * pass one, because a silent version of this is the defect being fixed rather than a fallback.
     */
    private val onFailure: (String, Throwable) -> Unit = { _, _ -> },
) {

    /**
     * Run one query, and SAY SO when it cannot run.
     *
     * ⚠️ **This exists because every path in this file used to answer a broken database and an empty
     * one identically, and on a cheap phone the broken case is the likely one.** The bundled asset is
     * unpacked by Room on the FIRST query — 424 MB of it — and the ordinary way that fails is a
     * phone with no room left. It throws out of the DAO call, `runCatching{}.getOrNull()` turned it
     * into null, and from there every scan reported "not in the database", every offline search
     * returned nothing, and the app fell back to the network — so with a connection it half-worked
     * and nobody ever learned the offline half was dead. Permanently, and with nothing anywhere
     * saying why.
     *
     * ⚠️ `CancellationException` is RETHROWN. A search is cancelled on every keystroke by design, and
     * a helper that swallowed it would both report a fault that is not one and break the structured
     * concurrency of the caller. This is the one thing `runCatching` gets wrong and the reason this
     * is not simply `runCatching`.
     *
     * ⚠️ Still never throws anything else: a database that will not open must not take the network
     * path down with it. The report is what makes the failure visible — `CrashReporter.reportNonFatal`
     * is rate-limited to one per tag per process (a repeat is a single set lookup), so calling this
     * on every keystroke costs nothing and reports once.
     */
    private inline fun <T> guard(op: String, fallback: T, block: () -> T): T =
        try {
            block()
        } catch (cancel: kotlin.coroutines.cancellation.CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            onFailure(op, t)
            fallback
        }

    /**
     * The product with this barcode, or null if the bundle has never heard of it.
     *
     * ⚠️ **A row with no nutrition is still returned.** Measured on the real export, only about a
     * fifth of products carry numbers, so returning null for the rest would throw away four scans in
     * five. Naming the product and saying nobody recorded its numbers is a completely different
     * experience from not recognising it, and [FoodRepository] draws that distinction with
     * `FoodLookup.NoNutrition`.
     */
    suspend fun byBarcode(barcode: String): Food? = withContext(Dispatchers.IO) {
        val key = BarcodeScan.normalize(barcode) ?: return@withContext null
        val row = guard("barcode", null) { db.dao().byBarcode(key) } ?: return@withContext null
        // ⚠️ The further nutrients are a SECOND read, and only on this path. A scan is one product
        // and the person is about to look at it; a search is twenty rows nobody will read a
        // magnesium figure off, and doing this per row would be a query per result per keystroke.
        // `extrasFor` is public so a detail opened from a search result can ask for them.
        toFood(row, barcode, extras(key))
    }

    /**
     * The further nutrients recorded for one barcode, or nothing.
     *
     * ⚠️ **An empty record is the ordinary answer, not a failure.** Roughly two products in three
     * carry none of these at all, and the surface must render that as silence rather than as zeroes.
     */
    suspend fun extrasFor(barcode: String): NutrientSet.Amounts = withContext(Dispatchers.IO) {
        val key = BarcodeScan.normalize(barcode) ?: return@withContext NutrientSet.Amounts()
        extras(key)
    }

    private suspend fun extras(key: Long): NutrientSet.Amounts {
        val rows = guard("extras", emptyList()) { db.dao().extrasFor(key) }
        if (rows.isEmpty()) return NutrientSet.Amounts()
        val m = LinkedHashMap<NutrientSet.Nutrient, Double>(rows.size)
        for (r in rows) {
            // ⚠️ An id this build does not know is DROPPED rather than guessed at. The database is
            // a shipped asset and the app that reads it can be older or newer than the build that
            // wrote it, so a nutrient added after this APK was compiled will appear here — and a
            // figure whose unit and scale are unknown is not a figure.
            val n = NutrientSet.byId(r.nutrient) ?: continue
            m[n] = NutrientSet.read(n, r.value)
        }
        return NutrientSet.Amounts(m)
    }

    /**
     * Bundled products whose names begin with [query].
     *
     * ⚠️ Deliberately the weakest of the three as-you-type paths, and deliberately last: a prefix scan
     * is what a keystroke can afford, and the seed and your own foods answer typed searches properly.
     * What it CANNOT do is find "Coca-Cola Zero Sugar" from "coke zero" — measured on real names,
     * word-anywhere matching finds three to ten times as much ("greek yogurt": 48 by prefix against
     * 405 by words) — and [searchAllProducts] is the deliberate one-shot scan that does.
     *
     * ⚠️ **CORRECTION to what this note used to say.** It claimed a full-text index "would cost more
     * than the table". Measured, it costs about a third; the real reason there is none is on
     * [searchAllProducts].
     */
    suspend fun searchByName(query: String, limit: Int = SEARCH_LIMIT): List<Food> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.length < MIN_PREFIX) return@withContext emptyList()
            guard("prefix", emptyList()) { db.dao().searchByNamePrefix(q, limit) }
                .map { toFood(it, it.barcode.toString()) }
        }

    /**
     * Every bundled product whose name contains **all** of [query]'s words, ranked.
     *
     * ⚠️ **This is a deliberate, one-shot search and not an as-you-type one, and that is the whole
     * design.** There is no index on 4.4 million product names, so this is a full table scan: measured
     * at the real column shape, ~320 ms per million rows, so roughly **one to one and a half seconds**
     * on this hardware and slower on a phone reading it cold. Acceptable for a button somebody pressed;
     * completely unacceptable for a keystroke, which is why [searchByName] — a fast indexed prefix
     * scan — remains what the search field calls.
     *
     * ⚠️ **THE MEASUREMENT THAT DECIDED AGAINST AN INDEX, recorded so it is not re-litigated from
     * intuition.** Sized on 113,612 real product names:
     *
     *     FTS5 with detail=none            +23.8 B/row  ->  ~107 MB at 4.45M rows
     *     (word, barcode) WITHOUT ROWID    +61.4 B/row  ->  ~276 MB
     *     no index, this scan                       0
     *
     * The APK already carries 285 MB and the in-app updater re-downloads all of it on **every** build,
     * so 107 MB in perpetuity buys a lower-latency version of an action somebody deliberately took. The
     * semantics are identical either way — all words anywhere in the name — so what the index buys is
     * only speed. If the wait proves intolerable on the device, the index is a builder change plus one
     * query and the cost above is already known.
     *
     * ⚠️ **Ranked by the same `FoodSearch.score` as the seed and your own foods**, unlike
     * [searchByName]. Returning 4.4 million rows in table order would bury the right answer under
     * every product that happens to sort early — and since the rows have to be read anyway, ranking
     * them is nearly free.
     *
     * ⚠️ **The words are filtered by SQLite and scored in Kotlin**, which is the same "reject cheaply,
     * parse the survivors" discipline `FoodRepository.searchSeed` uses over the bundled corpus. A
     * broad query still matches thousands of rows, so [SCAN_CAP] bounds what crosses the boundary and
     * [Scan.truncated] SAYS SO — a silently truncated list reads as "this is everything there is".
     */
    suspend fun searchAllProducts(query: String, limit: Int = SEARCH_LIMIT): Scan =
        withContext(Dispatchers.IO) {
            val terms = FoodSearch.tokens(query)
            if (terms.isEmpty()) return@withContext Scan(emptyList(), false)
            val hits = guard("scan", null) {
                db.dao().searchByNameWords(
                    SimpleSQLiteQuery(
                        "SELECT * FROM food WHERE kcal IS NOT NULL AND name IS NOT NULL " +
                            "AND ${sqlFor(terms)} LIMIT $SCAN_CAP",
                    ),
                )
            } ?: return@withContext Scan(emptyList(), false)

            val scored = hits.mapNotNull { row ->
                val name = row.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val entry = FoodSearch.Entry(row.barcode.toString(), name, row.brand.orEmpty(), "")
                val score = FoodSearch.score(entry, terms)
                if (score > 0.0) FoodSearch.Hit(entry, score) to row else null
            }
            Scan(
                foods = scored.sortedWith(compareBy(FoodSearch.ORDER) { it.first })
                    .take(limit)
                    .map { toFood(it.second, it.second.barcode.toString()) },
                truncated = hits.size >= SCAN_CAP,
            )
        }

    /**
     * What a full scan found, and whether it had to stop early.
     *
     * ⚠️ [truncated] is not decoration. "These are the best matches" and "these are the best of the
     * first few thousand rows that matched" are different claims, and only one of them is true when a
     * one-word query matches a tenth of a supermarket.
     */
    data class Scan(val foods: List<Food>, val truncated: Boolean)

    /**
     * ⚠️ **Built here rather than passed as a bind list, because the NUMBER of terms varies and SQLite
     * has no array parameter.** Every term is matched with `instr` against a lower-cased name — a
     * `LIKE '%x%'` cannot use an index either, so nothing is lost, and `instr` needs no escaping of
     * `%` or `_`, which a user typing "50% cocoa" would otherwise smuggle into the pattern.
     *
     * The terms themselves come from `FoodSearch.tokens`, which keeps only letters and digits, so no
     * quote can reach here — and they are still embedded through a single-quote doubling to make that
     * true by construction rather than by trust.
     */
    private fun sqlFor(terms: List<String>): String =
        terms.joinToString(" AND ") { t ->
            "instr(lower(name), '${t.replace("'", "''")}') > 0"
        }

    /** How many products are bundled, for the attribution line. Null if the database will not answer. */
    suspend fun count(): Int? = withContext(Dispatchers.IO) {
        guard("count", null) { db.dao().count() }
    }

    /** A value from the database's own `meta` table — the build date, the source, the licence. */
    suspend fun meta(key: String): String? = withContext(Dispatchers.IO) {
        guard("meta", null) { db.dao().meta(key) }
    }

    private companion object {
        const val SEARCH_LIMIT = 25

        /**
         * How many name-matching rows a full scan will carry into Kotlin before it stops.
         *
         * ⚠️ Measured on real names: "greek yogurt" matches 3,621 rows in a million and "cheerios"
         * 2,220, so a broad query at 4.4 million matches tens of thousands. Four thousand `Food`
         * objects is a few hundred kilobytes and comfortably more than anybody reads; past that the
         * scan is spending memory to rank rows nobody will see. Reaching this sets [Scan.truncated].
         */
        const val SCAN_CAP = 4_000

        /**
         * ⚠️ A prefix scan on two characters over 4.4M rows visits an enormous slice of the B-tree
         * for a result nobody can read. Three is where it stops being a scan of the whole alphabet.
         */
        const val MIN_PREFIX = 3

        /** Grams are stored x10, so 12.3 g is 123. */
        const val G_SCALE = 10.0

        /** Milligrams stored x100, for iron and vitamin C — a fraction of a milligram in most foods. */
        const val MG_CENTI = 100.0

        /**
         * Micrograms stored x100, for vitamin D alone.
         *
         * ⚠️ **The inverse of `SCALE_MICRO_CENTI` in `tools/food/build_food_db.py`, and the two must
         * move in one commit.** The reference intake is 15 µg a day, so this field carries fractions
         * of a microgram per hundred grams: as a whole integer, a fortified yogurt at 0.4 µg stored as
         * 0 and vanished. Vitamin A is deliberately NOT scaled — foods run to hundreds of micrograms
         * against a 900 µg guideline, so a whole microgram there is finer than the sources publish.
         */
        const val UG_CENTI = 100.0
    }

    /**
     * ⚠️ **Sodium is already in milligrams and must NOT go through `FoodPortion.sodiumMgFromGrams`.**
     * Open Food Facts publishes that field in grams and USDA publishes it in milligrams; the builder
     * converts OFF's at ingest so exactly one unit reaches this table. Applying the OFF conversion
     * again here would divide every bundled product's sodium by a thousand — which does not look like
     * an error on screen, it looks like a low-salt food, and the coach would act on it.
     *
     * ⚠️ The barcode as scanned is kept as the id rather than the normalised key. The id is what the
     * log stores and what a later lookup uses, and a US packet's printed UPC-A is what somebody would
     * recognise if they ever saw it. `normalize` resolves the spellings; it does not replace them.
     */
    private fun toFood(
        row: FoodRow,
        scanned: String,
        extras: NutrientSet.Amounts = NutrientSet.Amounts(),
    ): Food = Food.of(
        id = scanned.filter { it.isDigit() }.ifEmpty { row.barcode.toString() },
        name = row.name?.takeIf { it.isNotBlank() } ?: "Product ${row.barcode}",
        brand = row.brand.orEmpty(),
        per100g = NutritionDay.Nutrients(
            kcal = row.kcal?.toDouble() ?: 0.0,
            proteinG = scaled(row.prot, G_SCALE),
            fatG = scaled(row.fat, G_SCALE),
            carbG = scaled(row.carb, G_SCALE),
            fibreG = scaled(row.fib, G_SCALE),
            sugarG = scaled(row.sug, G_SCALE),
            satFatG = scaled(row.sat, G_SCALE),
            sodiumMg = row.sod?.toDouble() ?: 0.0,
        ),
        servingGrams = row.servingGrams?.toDouble()?.takeIf { it > 0.0 },
        servingLabel = row.servingLabel.orEmpty(),
        packageGrams = row.packageGrams?.toDouble()?.takeIf { it > 0.0 },
        source = when (row.src) {
            FoodRow.SOURCE_USDA -> NutritionDay.Source.USDA
            else -> NutritionDay.Source.OPEN_FOOD_FACTS
        },
        micros = micros(row),
        extras = extras,
    )

    /**
     * The vitamins and minerals this row records — and nothing for the ones it does not.
     *
     * ⚠️ **A null column is left out of the map, never entered as zero.** Measured over the whole
     * corpus, three quarters of products carry no calcium figure; entering those as 0.0 would put a
     * measurement nobody took on screen with the same confidence as one they did, and summing them
     * into a day's total would understate it while presenting it as complete.
     */
    private fun micros(row: FoodRow): Micronutrients.Amounts {
        val m = LinkedHashMap<Micronutrients.Micro, Double>(8)
        fun put(k: Micronutrients.Micro, v: Int?, scale: Double) {
            if (v != null) m[k] = v / scale
        }
        put(Micronutrients.Micro.CALCIUM, row.calcium, 1.0)
        put(Micronutrients.Micro.IRON, row.iron, MG_CENTI)
        put(Micronutrients.Micro.POTASSIUM, row.potassium, 1.0)
        put(Micronutrients.Micro.VITAMIN_A, row.vitA, 1.0)
        put(Micronutrients.Micro.VITAMIN_C, row.vitC, MG_CENTI)
        put(Micronutrients.Micro.VITAMIN_D, row.vitD, UG_CENTI)
        put(Micronutrients.Micro.CHOLESTEROL, row.chol, 1.0)
        put(Micronutrients.Micro.TRANS_FAT, row.transfat, G_SCALE)
        return Micronutrients.Amounts(m)
    }

    /** A scaled integer back to the real number, keeping null as "nobody recorded this" → 0.0. */
    private fun scaled(v: Int?, scale: Double): Double = if (v == null) 0.0 else v / scale
}
