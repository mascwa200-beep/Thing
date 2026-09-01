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
            if (broken == null) broken = t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
            onFailure(op, t)
            fallback
        }

    /**
     * Why the bundle could not be read, once it has failed once.
     *
     * ⚠️ **[guard] made the failure visible to ME and to nobody using the app.** It reports to the
     * crash console, which is the right place for a stack trace and the wrong place for somebody
     * standing in a supermarket: every path here still answers `null`, and `null` is what the bundle
     * says when it simply does not hold that product. So a database that will not open and a barcode
     * nobody has ever added render identically — "not in the packaged-food database" — and that
     * sentence is a claim about 4.4 million rows the app did not in fact look at.
     *
     * ⚠️ The FIRST reason is kept rather than the latest. What matters is why it stopped working,
     * and after the first failure every later query fails for its own downstream reason; keeping the
     * newest would overwrite "no space left on device" with whatever the next call happened to say.
     *
     * ⚠️ `@Volatile` and unlocked: written from whichever coroutine failed first, read by the
     * repository on another. A race costs one duplicate assignment of two equally true reasons.
     */
    @Volatile
    private var broken: String? = null

    /** The reason the bundle is unusable, or null while it is answering. */
    val unavailable: String? get() = broken

    /**
     * Whether this database carries the name index, worked out once and remembered.
     *
     * ⚠️ **Detected rather than assumed, because a pack built before the index existed still has to
     * work.** The database is downloaded separately from the app now, so the two versions are
     * genuinely independent: a phone can hold a corpus built last month and an app built today. A
     * hard dependency would turn that ordinary situation into a search that throws.
     *
     * ⚠️ **The `meta` row rather than `sqlite_master`, and it is free.** The builder records how
     * many products it indexed, so one existing typed query answers "is there an index" and "how
     * big is it" together — no new DAO surface, no raw query, and the answer comes from the step
     * that actually did the work rather than from the schema's opinion of itself. It is also the
     * only way to count a contentless FTS4 table: `SELECT COUNT(*)` on one is a "SQL logic error",
     * which is how the check that tried it found out.
     *
     * A plain field rather than a mutex: the worst a race can do is ask the same question twice.
     */
    @Volatile
    private var indexed: Boolean? = null

    private suspend fun hasIndex(): Boolean {
        indexed?.let { return it }
        val answer = guard("index", null) { db.dao().meta("search_rows") } != null
        indexed = answer
        return answer
    }

    /**
     * The MATCH expression for already-tokenised terms.
     *
     * ⚠️ **Every term carries a trailing `*`, and that is what keeps this as admissive as the scan
     * it replaces.** `FoodSearch.wordMatch` accepts a corpus word up to three characters longer than
     * the query token — cook/cooked, roast/roasted — and a bare `MATCH 'roast'` is exact-token only,
     * so it would silently lose every inflection the scorer was written to allow. A prefix query
     * over-admits instead (`roast*` also reaches "roastery"), and `FoodSearch.score` refuses those a
     * moment later. That is the same "reject cheaply, judge properly" split the rest of this file
     * uses, with SQLite doing the cheap half.
     *
     * ⚠️ What it does NOT reach is a match in the MIDDLE of a word, which `instr` did — and nothing
     * is lost, because `wordMatch` scores those zero and the row was being read and thrown away.
     *
     * ⚠️ Nothing needs escaping and nothing is interpolated: `FoodSearch.tokens` keeps only letters
     * and digits, and the whole expression is a bound parameter rather than part of the SQL.
     */
    private fun matchFor(terms: List<String>): String = terms.joinToString(" ") { "$it*" }

    private fun indexedSql(limit: Int): String =
        "SELECT f.* FROM food f JOIN food_fts x ON f.barcode = x.docid " +
            "WHERE x.food_fts MATCH ? AND f.kcal IS NOT NULL AND f.name IS NOT NULL " +
            "LIMIT $limit"

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
     * ⚠️ **CORRECTION to what this note used to say, twice over.** It claimed a full-text index
     * "would cost more than the table"; measured, it costs about a third. And there IS one now — see
     * [searchAllProducts] for why the argument against it was wrong — so the paragraphs below
     * describe what this method does only on a database built before the index existed.
     *
     * ⚠️ **SECOND CORRECTION, and this one was costing ~314 ms of a keystroke's answer.** This method
     * used to be described as "a fast indexed prefix scan" — see [searchAllProducts] eleven lines
     * below, which correctly says in the same file that there is no index on 4.4 million product
     * names. There is not. What made it slow, though, was not the missing index but the SQL's
     * `ORDER BY LENGTH(name)`, which forces SQLite to read every row before it can know which are
     * shortest. The measurement and the numbers are on `FoodDao.searchByNamePrefix`; the ranking it
     * used to do now happens here, over a bounded candidate set, which is the same preference at
     * roughly a fifth of the cost and a thousandth on a prefix that matches early.
     *
     * ⚠️ **Shorter names first, exactly as the SQL did — deliberately NOT `FoodSearch.score`.** That
     * scorer is built for whole words: `wordMatch` allows a three-character stem gap, so "chic" would
     * match "chicken" and "cho" would NOT match "chocolate", and ranking by it here would score zero
     * for rows SQLite had legitimately matched and silently drop them. A partial word is not a word.
     * Ranking the three paths differently is the price of the prefix path answering a fragment.
     *
     * ⚠️ **The candidates are the lowest barcodes that match, because an unindexed scan reads in
     * rowid order and the barcode IS the rowid.** A barcode's leading digits are a country prefix, so
     * on a very common prefix the pool leans towards one region rather than being a fair sample. Said
     * rather than hidden: it is a consequence of having no index, the alternative was a third of a
     * second per search, and [searchAllProducts] is the path that reads everything.
     */
    suspend fun searchByName(query: String, limit: Int = SEARCH_LIMIT): List<Food> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.length < MIN_PREFIX) return@withContext emptyList()
            // ⚠️ **With an index this stops being the weakest path and becomes the best one.** Every
            // limitation described above is a consequence of having to answer a keystroke without an
            // index: the whole-name anchor that cannot find "Coca-Cola Zero Sugar" from "coke zero",
            // the 400-row cap, and the country-prefix bias that cap inherits from reading in rowid
            // order. A prefix MATCH has none of them and costs about half a millisecond.
            //
            // ⚠️ **Still ranked by name length, NOT by `FoodSearch.score`, and the paragraph above
            // says why**: the last word of a half-typed query is a fragment, `wordMatch` allows only
            // a three-character stem gap, so scoring "cho" against "chocolate" returns zero and would
            // drop every row SQLite had legitimately matched. A partial word is not a word. What
            // changes here is which candidates get ranked, not how.
            val terms = FoodSearch.tokens(q)
            val rows = if (terms.isNotEmpty() && hasIndex()) {
                guard("prefix-indexed", emptyList()) {
                    db.dao().searchByNameWords(
                        SimpleSQLiteQuery(indexedSql(PREFIX_CANDIDATES), arrayOf(matchFor(terms))),
                    )
                }
            } else {
                guard("prefix", emptyList()) { db.dao().searchByNamePrefix(q, PREFIX_CANDIDATES) }
            }
            rows.sortedBy { it.name?.length ?: Int.MAX_VALUE }
                .take(limit)
                .map { toFood(it, it.barcode.toString()) }
        }

    /**
     * Every bundled product whose name contains **all** of [query]'s words, ranked.
     *
     * ⚠️ **This is a deliberate, one-shot search and not an as-you-type one, and that is the whole
     * design.** There is no index on 4.4 million product names, so this is a full table scan: measured
     * at the real column shape, ~320 ms per million rows, so roughly **one to one and a half seconds**
     * on this hardware and slower on a phone reading it cold. Acceptable for a button somebody pressed;
     * completely unacceptable for a keystroke, which is why [searchByName] remains what the search
     * field calls.
     *
     * ⚠️ This sentence used to end "— a fast indexed prefix scan —", contradicting its own paragraph
     * above. [searchByName] shares this table and its lack of an index; what makes it affordable is
     * that it stops at [PREFIX_CANDIDATES] instead of reading everything, which this cannot do because
     * a word can appear anywhere in a name and the last row is as likely to match as the first.
     *
     * ⚠️ **THERE IS NOW AN INDEX, and the note that used to sit here decided against one for two
     * reasons that were both wrong.** It is kept in outline because the shape of the argument still
     * matters, and because being wrong quietly is how a measurement gets re-litigated from intuition.
     *
     * It weighed FTS5 at +23.8 B/row against "an APK the updater re-downloads on every build".
     *
     *   - **FTS5 is not a slower option on Android, it is an impossible one.** The platform's SQLite
     *     is built with `SQLITE_ENABLE_FTS3`, `FTS3_BACKWARDS` and `FTS4` and no FTS5 — read out of
     *     `platform/external/sqlite`'s `dist/Android.bp`, not recalled. SQLite parses the whole
     *     schema when it prepares its first statement, so a database containing an `fts5` table
     *     would fail EVERY query on EVERY device. Shipping it would have been catastrophic rather
     *     than merely large.
     *   - **The database is no longer in the APK.** It is fetched once as its own pack, so the cost
     *     is a one-time download rather than one paid again on every build of the interface.
     *
     * What is there instead is FTS4, contentless, `matchinfo=fts3`. Sized on 70,415 real names it came
     * to 28.8 bytes a row and was still falling with scale — 33.3 at ten thousand rows, 30.4 at fifty,
     * 28.8 at seventy, as the term dictionary amortises — so that was recorded as a ceiling. The real
     * build settled it lower still: **24.1 bytes a row, 58 MB over 2,541,457 products, built in seven
     * seconds.** The build logs it every run rather than leaving this comment to claim it.
     *
     * And the speed, the same ten realistic queries at that size: **18.9 ms scanning, 0.7 ms through
     * `MATCH`**. The scan is linear in the table, so at 4.5 million rows it is past a second on a warm
     * desktop SSD; `MATCH` barely moves. That is what the paragraph above is describing when it calls
     * this a button rather than a keystroke, and it is no longer true of either.
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
            val useIndex = hasIndex()
            val hits = guard("scan", null) {
                db.dao().searchByNameWords(
                    if (useIndex) {
                        SimpleSQLiteQuery(indexedSql(SCAN_CAP), arrayOf(matchFor(terms)))
                    } else {
                        SimpleSQLiteQuery(
                            "SELECT * FROM food WHERE kcal IS NOT NULL AND name IS NOT NULL " +
                                "AND ${sqlFor(terms)} LIMIT $SCAN_CAP",
                        )
                    },
                )
            } ?: return@withContext Scan(emptyList(), false, unavailable = broken)

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
    data class Scan(
        val foods: List<Food>,
        val truncated: Boolean,
        /**
         * Why the scan did not happen, when it did not.
         *
         * ⚠️ Defaulted to null so nothing that already builds a [Scan] moved. Without it an empty
         * result reads as "no bundled product has all of those words", which is a statement about
         * 4.4 million rows that a database refusing to open never looked at.
         */
        val unavailable: String? = null,
    )

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
         * How many prefix matches [searchByName] reads before ranking them and keeping [SEARCH_LIMIT].
         *
         * ⚠️ **This number is what lets SQLite stop early, which is the whole of the fix.** With the
         * old `ORDER BY LENGTH(name)` no cap could help — the sort has to see every row. With the sort
         * gone, the cap is the point at which the scan is allowed to end. Measured on a million rows
         * at the real column shape and scaled to 4.45M:
         *
         *     LIMIT   20   ->  ~3 ms mean
         *     LIMIT  200   -> ~29 ms
         *     LIMIT  400   -> ~35 ms
         *     LIMIT 2000   -> ~93 ms
         *
         * 400 is the knee: sixteen times [SEARCH_LIMIT], so the shortest-name preference has a real
         * pool to choose from, for six milliseconds more than 200 and a third of what 2,000 costs.
         *
         * ⚠️ A prefix matching FEWER than this scans the whole table whatever the cap is — the floor
         * of having no index, worth ~300 ms scaled, and unreachable from this constant.
         */
        const val PREFIX_CANDIDATES = 400

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
