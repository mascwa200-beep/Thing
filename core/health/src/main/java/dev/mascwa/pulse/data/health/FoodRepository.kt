package dev.mascwa.pulse.data.health

import android.content.Context
import dev.mascwa.pulse.core.telemetry.FoodSearch
import dev.mascwa.pulse.core.telemetry.Micronutrients
import dev.mascwa.pulse.core.telemetry.NutrientSet
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.data.food.Food
import dev.mascwa.pulse.data.food.FoodLookup
import dev.mascwa.pulse.data.food.OpenFoodFactsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader

/**
 * Every food this app can find, from whichever source knows about it.
 *
 * Two sources with complementary blind spots, which is why both are here:
 *
 *  - the **bundled seed** — 13,186 generic foods from USDA's public-domain FoodData Central,
 *    laboratory analyses of things like "Chicken breast, baked, skin not eaten" and "Oil, olive".
 *    Offline, instant, and the half most cooking is made of;
 *  - **Open Food Facts** — packaged goods, organised around barcodes, which is the half a
 *    supermarket shelf is made of and which no bundle could ever hold.
 *
 * ⚠️ **The seed is searched first and always, and the network is an addition rather than a
 * fallback.** A phone in a basement supermarket has no signal, and the commonest thing anybody logs
 * is an egg. Making the offline half conditional on the online half failing would put the
 * fastest, most reliable answers behind the slowest, least reliable request.
 */
class FoodRepository(
    private val context: Context,
    private val off: OpenFoodFactsRepository,
    private val custom: CustomFoodStore,
    /**
     * The barcode database, or null when there is not one.
     *
     * ⚠️ Null is a real state, not a defensive one. The 425 MB database is fetched rather than
     * committed — GitHub rejects files that size — so a local developer build genuinely has none,
     * and every path here has to work without it. What that costs is offline scanning; what it must
     * never cost is the app starting.
     *
     * ⚠️ **A supplier and not a value, and that is a correctness change rather than a style one.**
     * The standalone nutrition application now DOWNLOADS the corpus on first run instead of shipping
     * it, so the answer changes while the process is alive. Held as a value, whatever this resolved
     * to at construction — null, on the run where somebody first downloads it — would be the answer
     * for the rest of the process, and the person would finish waiting several minutes for a
     * database the app then went on insisting it did not have until they killed it and came back.
     */
    private val offline: () -> OfflineFoodStore? = { null },
) {

    /** What a search turned up, and honestly what it could not reach. */
    data class Results(
        val query: String,
        val foods: List<Food>,
        /**
         * ⚠️ Null when the network was not consulted at all, which is a different thing from
         * consulting it and getting nothing. A screen that renders those two the same way tells
         * somebody their packaged food does not exist when in fact nobody asked.
         */
        val onlineFailure: String? = null,
        val onlineConsulted: Boolean = false,
        /**
         * Why the bundled 4.4-million-product database did not get a say, when it did not.
         *
         * ⚠️ The same distinction [onlineFailure] draws, for the half that is supposed to work with
         * no network at all — and the more consequential one on a cheap phone, because that is where
         * it fails. A bundle that will not open answers every query with the same nothing an
         * absent product does, so without this the screen reports a genuinely empty result and the
         * offline half is dead permanently with nothing anywhere saying so.
         */
        val bundleFailure: String? = null,
    )

    // ------------------------------------------------------------------------------ the seed

    /**
     * Foods from the bundle whose names could answer [query], ranked.
     *
     * ⚠️ The corpus is scanned as **raw text** and only survivors are kept, which is the same
     * "reject cheaply, parse the survivors" discipline `SurvivalContentRepository` uses over guide
     * bodies and the reason the seed ships line-oriented rather than as JSON.
     *
     * ⚠️ **Each line is SCORED inside the loop, and only a scoring line is retained.** An earlier
     * version collected everything [FoodSearch.couldMatch] admitted and ranked afterwards, and
     * measuring it showed why that is not the same thing: the cheap reject is a substring test and
     * deliberately over-admits, so the query "an" admitted **9,595 lines and held 1.2 MB** — and
     * scored exactly none of them. Debouncing does not save it, because typing "chicken" passes
     * through "ch", "chi" and "chic" on the way. Memory is now bounded by what actually matched.
     */
    suspend fun searchSeed(query: String, limit: Int = SEED_LIMIT): List<Food> =
        withContext(Dispatchers.IO) {
            val terms = FoodSearch.tokens(query)
            if (terms.isEmpty()) return@withContext emptyList()
            val hits = ArrayList<Pair<FoodSearch.Hit, String>>()
            runCatching {
                context.assets.open(SEED_ASSET).bufferedReader().use { r: BufferedReader ->
                    r.forEachLine { line ->
                        if (!FoodSearch.couldMatch(line, terms)) return@forEachLine
                        val f = line.split('\t')
                        if (f.size < COLUMNS) return@forEachLine
                        val entry = FoodSearch.Entry(f[0], f[1], "", f[2])
                        val score = FoodSearch.score(entry, terms)
                        if (score > 0.0) hits += FoodSearch.Hit(entry, score) to line
                    }
                }
            }
            // ⚠️ The core's own comparator, so this path and FoodSearch.rank cannot disagree about
            // which row comes first for the same query.
            hits.sortedWith(compareBy(FoodSearch.ORDER) { it.first })
                .take(limit)
                .mapNotNull { parseSeedLine(it.second) }
        }

    /** One bundled food by its seed id, for re-reading something already logged. */
    suspend fun seedFood(id: String): Food? = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open(SEED_ASSET).bufferedReader().use { r ->
                r.lineSequence()
                    .firstOrNull { it.startsWith("$id\t") }
                    ?.let(::parseSeedLine)
            }
        }.getOrNull()
    }

    // --------------------------------------------------------------------------------- merged

    /**
     * Search everything reachable.
     *
     * The bundle answers first and its results are never held back waiting for a request. When
     * [online] is set the packaged-food database is consulted too and its hits are appended —
     * appended rather than interleaved, because the two are answering different questions and a
     * shuffled list makes it impossible to tell a lab analysis from a photographed label.
     */
    suspend fun search(query: String, online: Boolean): Results {
        // ⚠️ Your own foods lead, and the cap in CustomFoodStore is what makes that safe. A list you
        // wrote yourself is a handful of items you named, so anything in it that matches at all is
        // worth putting in front of thirteen thousand generic rows — but only a handful, or a long
        // personal list would bury the database behind it. They are ranked by the SAME scorer as the
        // seed, so this is an ordering decision and not a second, disagreeing search.
        val mine = custom.search(query)
        val seed = searchSeed(query)
        // ⚠️ The bundled products come LAST of the three local sources, and that ordering is
        // deliberate. It is a name-prefix scan ranked only by name length — the seed and your own
        // foods are scored by `FoodSearch`, which needs whole words and so cannot rank a fragment —
        // so putting 4.4 million packaged rows above them would bury a lab analysis of "Egg, whole,
        // raw" under every product whose name starts "Egg".
        val bundled = offline()?.searchByName(query).orEmpty()
        val seen = (mine + seed).mapTo(HashSet()) { it.id }
        val local = mine + seed + bundled.filterNot { it.id in seen }
        // ⚠️ Read AFTER the queries, not before: the bundle is unpacked lazily on its first query,
        // so asking beforehand always says "fine" on the very run where it is about to fail.
        val bundleFailure = offline()?.unavailable
        if (!online) return Results(query, local, bundleFailure = bundleFailure)
        return try {
            val page = off.search(query, limit = OFF_LIMIT)
            // ⚠️ De-duplicated by id, not by name. Two records can legitimately share a name and
            // differ in their numbers, and collapsing those hides the choice rather than tidying it.
            val ids = local.mapTo(HashSet()) { it.id }
            Results(
                query, local + page.foods.filterNot { it.id in ids },
                onlineConsulted = true, bundleFailure = bundleFailure,
            )
        } catch (e: Exception) {
            Results(
                query, local,
                onlineFailure = e.message ?: "could not reach the food database",
                onlineConsulted = true, bundleFailure = bundleFailure,
            )
        }
    }

    /**
     * The same food, with its further nutrients fetched if it does not already carry them.
     *
     * ⚠️ **A product found by NAME arrived with none of them, and a product found by BARCODE arrived
     * with all of them — the same product, two different records, and only the second one worth
     * anything.** `OfflineFoodStore.toFood` takes its extras as a defaulted parameter; the barcode
     * path passes them and both search paths do not, because reading the side table for every one
     * of twenty-five results is work for rows nobody will log. That was the right call about WHEN,
     * and it left nothing to do it LATER: `extrasFor` was made public with a comment saying "so a
     * detail opened from a search result can ask for them", and had no caller anywhere.
     *
     * So the figures were fetched at the moment of a scan and silently absent at the moment of a
     * search, and the record kept whichever it happened to get. This is the later.
     *
     * ⚠️ **Called when a food is LOGGED, never while a list is browsed.** `food_extra` has a
     * composite primary key on `(barcode, nutrient)` and is `WITHOUT ROWID`, so this is one indexed
     * lookup rather than a scan — but twenty-five of them per keystroke would still be work for
     * results that are mostly read and discarded.
     *
     * Unchanged, and cheaply so, when the food already has them (a recipe component, a saved food,
     * an Open Food Facts hit), when there is no bundled database, or when the product has none —
     * which is roughly two in three, so an empty answer is the ordinary one and not a failure.
     */
    suspend fun withExtras(food: Food): Food {
        if (food.extras.isNotEmpty()) return food
        val store = offline() ?: return food
        val fetched = store.extrasFor(food.id).values
        if (fetched.isEmpty()) return food
        return food.copy(extras = fetched.mapKeys { (n, _) -> n.name })
    }

    /**
     * Every bundled product whose name holds all of [query]'s words — the deliberate full scan.
     *
     * ⚠️ Kept as its own method rather than folded into [search], because it is not the same kind of
     * request. [search] answers a keystroke and must return in moments; this reads the whole 4.4
     * million-row table and takes about a second. A surface that could not tell them apart would
     * either make typing slow or make this unreachable.
     *
     * Empty and not-truncated on a build with no bundle, which is the same honest nothing every other
     * path here returns for that case.
     */
    suspend fun searchAllBundled(query: String): OfflineFoodStore.Scan =
        offline()?.searchAllProducts(query) ?: OfflineFoodStore.Scan(emptyList(), false)

    /**
     * A barcode, answered from the bundle first and the network only if the bundle cannot.
     *
     * ⚠️ **This used to be one line delegating straight to the network, and that was the whole
     * defect.** A supermarket aisle is the place a phone is least likely to have signal and the
     * place a barcode is most likely to be scanned, so the feature failed exactly where it was
     * needed. The bundled database now answers ~4.4 million retail products with no request at all.
     *
     * The ordering matches the search path directly above: local first and always, network as an
     * addition rather than a fallback. Putting the fastest and most reliable answer behind the
     * slowest and least reliable one would be the same mistake in the other direction.
     *
     * ⚠️ **A row with no numbers is still an answer.** Only about a fifth of the corpus carries
     * nutrition, so a bundle of complete rows only would miss four scans in five. Recognising the
     * product and saying its numbers were never recorded is a different and far more useful thing
     * than not recognising it — and if the network can fill that gap, it is still asked.
     *
     * ⚠️ **A recognised product with no numbers outranks a failed request**, which is the one
     * judgement call here. When the bundle knows the name and the network could not be reached, this
     * returns `NoNutrition` rather than `Unreachable`: `Unreachable` renders as "try again" and shows
     * nothing, while `NoNutrition` names the product and offers to take the numbers. Showing somebody
     * a blank failure for a product the app can name is the worse of the two, and scanning again
     * later still reaches the network.
     */
    suspend fun byBarcode(barcode: String): FoodLookup {
        val local = offline()?.byBarcode(barcode)
        // The whole point: a complete bundled row answers with no request at all.
        if (local != null && local.per100g.kcal > 0.0) return FoodLookup.Found(local)

        // Either nothing local, or a row with a name and no numbers — worth asking whether the live
        // database has since filled it in. `off.byBarcode` reports its own failures rather than
        // throwing, so its answer is always one of the four states.
        val online = off.byBarcode(barcode)
        if (online is FoodLookup.Found) return online

        if (local != null) return FoodLookup.NoNutrition(local)
        // ⚠️ `NotInDatabase` on its own claims the bundle was searched. When the bundle could not be
        // opened it was not, so the reason travels with the answer — the network's verdict still
        // stands (retrying it will not help), and the offline half's silence gets named rather than
        // passed off as an absent product.
        val bundleFailure = offline()?.unavailable
        return when {
            bundleFailure != null && online is FoodLookup.NotInDatabase ->
                online.copy(offlineUnavailable = bundleFailure)
            else -> online
        }
    }

    private companion object {
        /**
         * The 13,186 generic USDA foods, bundled.
         *
         * ⚠️ **It is an asset of THIS module, not of either application, and that is what makes
         * the standalone app able to find a food by name at all.** It used to sit in `:app`'s
         * assets, so `:nutrition` shipped without it: searching "chicken breast" found nothing
         * generic, describing a meal matched nothing (that path searches the seed only, by
         * design) and a meal photograph's proposals matched nothing — each swallowed and
         * rendered as "no such food". An Android library's assets are merged into every app
         * that depends on it, so one committed copy beside the code that reads it reaches both,
         * including a local build, with nothing in a workflow needing to know.
         */
        const val SEED_ASSET = "food/seed.tsv"

        /**
         * ⚠️ Must match `tools/food/build_seed.py`. A row with fewer columns is skipped rather than
         * read short — a truncated line read positionally would put a serving weight in the sodium
         * field, which is a wrong number rather than a missing one.
         *
         * ⚠️ 13 for a long time, then 21 when the eight micronutrient columns were appended. They go
         * at the END on purpose: `FoodSearch.Entry` reads fields 0, 1 and 2 positionally, so growing
         * the row anywhere else would silently re-point the ranker's name and category at nutrients.
         */
        const val COLUMNS = 21

        const val SEED_LIMIT = 40
        const val OFF_LIMIT = 20
    }

    /**
     * One line of the bundled corpus.
     *
     * ⚠️ **Sodium here is already in milligrams and must NOT go through
     * `FoodPortion.sodiumMgFromGrams`.** USDA publishes it in mg; Open Food Facts publishes the same
     * field in grams. Two sources, one field, two units — applying the OFF conversion to this would
     * divide every bundled food's sodium by a thousand, which looks like nothing at all on screen.
     * `build_seed.py` asserts the unit on every record so a future USDA release that changes it
     * fails the build rather than shipping the error.
     */
    private fun parseSeedLine(line: String): Food? {
        val f = line.split('\t')
        if (f.size < COLUMNS) return null
        fun num(i: Int) = f[i].toDoubleOrNull() ?: 0.0
        return Food.of(
            id = f[0],
            name = f[1],
            per100g = NutritionDay.Nutrients(
                kcal = num(3), proteinG = num(4), fatG = num(5), carbG = num(6),
                fibreG = num(7), sugarG = num(8), satFatG = num(9), sodiumMg = num(10),
            ),
            servingGrams = f[11].toDoubleOrNull(),
            servingLabel = f[12],
            source = NutritionDay.Source.OFFLINE,
            micros = seedMicros(f),
            extras = seedExtras(f),
        )
    }

    /**
     * The vitamins and minerals this line records — and nothing for the ones it does not.
     *
     * ⚠️ **An empty field is ABSENT, not zero, and `toDoubleOrNull` is what draws the line.** The
     * macros above deliberately fall back to 0.0 because a missing macro is effectively zero for
     * logging and `Nutrients` has no absent state. A micronutrient is the opposite case and it is the
     * whole reason [Micronutrients.Amounts] is a map: writing 0.0 for calcium a laboratory never
     * measured puts a figure nobody took on screen as confidently as one they did, and sums it into a
     * day's total while presenting that total as complete.
     *
     * ⚠️ A real recorded **zero stays** — plenty of foods genuinely contain no vitamin C, and USDA says
     * so explicitly. "Measured as none" and "not measured" are different facts.
     *
     * ⚠️ The order is the on-disk contract with `MICRO_FIELDS` in `tools/food/build_seed.py`. The
     * builder writes them in this order and asserts the column count; getting them out of step would
     * report a food's potassium as its calcium, which is a wrong number rather than a missing one.
     */
    private fun seedMicros(f: List<String>): Micronutrients.Amounts {
        val m = LinkedHashMap<Micronutrients.Micro, Double>(8)
        fun put(k: Micronutrients.Micro, i: Int) {
            f.getOrNull(i)?.toDoubleOrNull()?.let { m[k] = it }
        }
        put(Micronutrients.Micro.CALCIUM, 13)
        put(Micronutrients.Micro.IRON, 14)
        put(Micronutrients.Micro.POTASSIUM, 15)
        put(Micronutrients.Micro.VITAMIN_A, 16)
        put(Micronutrients.Micro.VITAMIN_C, 17)
        put(Micronutrients.Micro.VITAMIN_D, 18)
        put(Micronutrients.Micro.CHOLESTEROL, 19)
        put(Micronutrients.Micro.TRANS_FAT, 20)
        return Micronutrients.Amounts(m)
    }

    /**
     * The twenty-nine further nutrients this line records — added sugars, the individual sugars, the
     * rest of the B vitamins, the trace minerals, water.
     *
     * ⚠️ **The column order is [NutrientSet.Nutrient.id], not declaration order, and that is the
     * whole contract.** `build_seed.py` sorts by the same id. [seedMicros] above has to hand-write
     * its eight indices because `Micronutrients.Micro` carries no id and its builder keeps a
     * hand-ordered list to match; this set was given permanent explicit ids in `NutrientSet.kt`
     * precisely so an asset could be ordered by something that cannot change when somebody
     * alphabetises the enum. Derived beats hand-kept wherever the data allows it.
     *
     * ⚠️ **All twenty-nine have a column even though USDA publishes twenty-six.** Added sugars,
     * iodine and polyols are empty on every row — measured, not assumed — and that is exactly what
     * an unrecorded measurement is supposed to look like here. Writing only the subset would make
     * "which ones, in what order" a second implicit contract for this function to get right.
     *
     * ⚠️ **Read with `getOrNull`, and [COLUMNS] deliberately still requires only 21.** A line whose
     * trailing nutrients are absent would end in a run of tabs, and anything that trimmed trailing
     * whitespace — an editor, a future `.gitattributes` — would shorten it. Gating the whole food on
     * the full width would then make every such food VANISH from the bundle; tolerating a short line
     * loses only the nutrients that were not there anyway.
     *
     * ⚠️ Measured rather than assumed, and it corrects the sentence above: **no line in the shipped
     * seed ends in a tab today** — water is the last column and USDA records it on all 13,186 foods,
     * so there is nothing at the end to trim. The guard is kept because a future food without a
     * water figure reintroduces the case immediately, and because the builder asserting the full
     * width and the parser tolerating less is belt and braces rather than one loose check.
     *
     * ⚠️ An empty field is ABSENT, not zero, for the reason [seedMicros] argues at length; a
     * recorded zero stays.
     */
    private fun seedExtras(f: List<String>): NutrientSet.Amounts {
        val m = LinkedHashMap<NutrientSet.Nutrient, Double>(SEED_EXTRAS.size)
        SEED_EXTRAS.forEachIndexed { i, n ->
            f.getOrNull(EXTRA_BASE + i)?.toDoubleOrNull()?.let { m[n] = it }
        }
        return NutrientSet.Amounts(m)
    }
}

/**
 * The further nutrients in the order the seed writes them, computed once.
 *
 * ⚠️ Sorted rather than taken as declared: `entries` follows declaration order, and the point of
 * keying on the id is that reordering the enum must not silently re-map every column in a shipped
 * asset. If the two ever disagree this is where it shows.
 */
private val SEED_EXTRAS: List<NutrientSet.Nutrient> =
    NutrientSet.Nutrient.entries.sortedBy { it.id }

/** The first further-nutrient column: 13 head/macro/serving fields plus the eight micronutrients. */
private const val EXTRA_BASE = 21
