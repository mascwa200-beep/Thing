package dev.mascwa.pulse.data.health

import android.content.Context
import dev.mascwa.pulse.core.telemetry.FoodSearch
import dev.mascwa.pulse.core.telemetry.Micronutrients
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
     * The bundled barcode database, or null on a build where the asset never arrived.
     *
     * ⚠️ Null is a real state, not a defensive one. The 240 MB database is fetched by CI rather than
     * committed — GitHub rejects files that size — so a local developer build genuinely has no
     * bundle, and every path here has to work without it. What that costs is offline scanning; what
     * it must never cost is the app starting.
     */
    private val offline: OfflineFoodStore? = null,
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
        // deliberate. It is a name-prefix scan with no ranking behind it — the seed and your own
        // foods are scored by `FoodSearch` — so putting 4.4 million packaged rows above them would
        // bury a lab analysis of "Egg, whole, raw" under every product whose name starts "Egg".
        val bundled = offline?.searchByName(query).orEmpty()
        val seen = (mine + seed).mapTo(HashSet()) { it.id }
        val local = mine + seed + bundled.filterNot { it.id in seen }
        if (!online) return Results(query, local)
        return try {
            val page = off.search(query, limit = OFF_LIMIT)
            // ⚠️ De-duplicated by id, not by name. Two records can legitimately share a name and
            // differ in their numbers, and collapsing those hides the choice rather than tidying it.
            val ids = local.mapTo(HashSet()) { it.id }
            Results(query, local + page.foods.filterNot { it.id in ids }, onlineConsulted = true)
        } catch (e: Exception) {
            Results(query, local, onlineFailure = e.message ?: "could not reach the food database",
                onlineConsulted = true)
        }
    }

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
        val local = offline?.byBarcode(barcode)
        // The whole point: a complete bundled row answers with no request at all.
        if (local != null && local.per100g.kcal > 0.0) return FoodLookup.Found(local)

        // Either nothing local, or a row with a name and no numbers — worth asking whether the live
        // database has since filled it in. `off.byBarcode` reports its own failures rather than
        // throwing, so its answer is always one of the four states.
        val online = off.byBarcode(barcode)
        if (online is FoodLookup.Found) return online

        return when {
            local != null -> FoodLookup.NoNutrition(local)
            else -> online
        }
    }

    private companion object {
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
}
