package dev.mascwa.pulse.data.health

import android.content.Context
import dev.mascwa.pulse.core.telemetry.FoodSearch
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
        val local = mine + seed
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

    /** A barcode, which only the online database can answer. */
    suspend fun byBarcode(barcode: String): FoodLookup = off.byBarcode(barcode)

    private companion object {
        const val SEED_ASSET = "food/seed.tsv"

        /**
         * ⚠️ Must match `tools/food/build_seed.py`. A row with fewer columns is skipped rather than
         * read short — a truncated line read positionally would put a serving weight in the sodium
         * field, which is a wrong number rather than a missing one.
         */
        const val COLUMNS = 13

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
        )
    }
}
