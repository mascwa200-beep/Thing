package dev.mascwa.pulse.data.food

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.FoodPortion
import dev.mascwa.pulse.core.telemetry.Micronutrients
import dev.mascwa.pulse.core.telemetry.NutrientSet
import dev.mascwa.pulse.core.telemetry.NutritionDay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * Packaged food, from the Open Food Facts community database.
 *
 * Keyless, open data, and the only source that knows what is actually on a supermarket shelf.
 *
 * ⚠️ **Everything below was probed against the live API before it was written.** Four of its
 * behaviours are not what the shape of the data suggests, and each is noted where it is handled. The
 * probes are recorded rather than the conclusions alone, so the next person can re-run them rather
 * than trust me.
 */
class OpenFoodFactsRepository(
    private val http: HttpClient,
    private val cache: DiskCache,
) {
    /**
     * ⚠️ Open Food Facts asks every client to identify itself, and answers anonymous traffic less
     * reliably. One gate per host, as everywhere else in this module — two at a time, because this is
     * a volunteer-run service and a barcode scan is one request, not a burst.
     */
    private val gate = Semaphore(2)

    private val ua = mapOf("User-Agent" to USER_AGENT)

    // ------------------------------------------------------------------------------- by barcode

    /**
     * The product behind a barcode.
     *
     * ⚠️ **A product that does not exist comes back as HTTP 200 with `status: 0`** — probed, not
     * assumed. So the HTTP code says nothing about whether the barcode was found, and the two
     * outcomes need telling apart: one means the network failed and retrying may work, the other
     * means nobody has ever added this product and the only way forward is to type it in. Returning a
     * bare null would collapse them into the same shrug.
     */
    suspend fun byBarcode(barcode: String, force: Boolean = false): FoodLookup {
        val code = barcode.filter { it.isDigit() }
        if (code.isEmpty()) return FoodLookup.NotInDatabase(barcode)

        val key = "off_product_$code"
        if (!force) {
            cache.read(key, PRODUCT_TTL, Food.serializer())?.let { return classify(it.value) }
        }
        val url = "$PRODUCT_BASE/$code.json?fields=$PRODUCT_FIELDS"
        val body = try {
            gate.withPermit { http.getString(url, ua) }
        } catch (e: Exception) {
            // Serve what we already had rather than nothing — a scanned product does not change.
            cache.readAny(key, Food.serializer())?.let { return classify(it.value) }
            return FoodLookup.Unreachable(e.message ?: "could not reach Open Food Facts")
        }

        val root = runCatching { http.json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return FoodLookup.Unreachable("Open Food Facts sent something unreadable")

        // The status field, not the HTTP code, is what says whether this barcode is known.
        val status = root["status"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val product = root["product"] as? JsonObject
        if (status < 1.0 || product == null) return FoodLookup.NotInDatabase(code)

        val food = parseOffProduct(code, product) ?: return FoodLookup.NotInDatabase(code)
        cache.write(key, food, Food.serializer())
        return classify(food)
    }

    private fun classify(food: Food): FoodLookup =
        if (FoodPortion.isLoggable(food.per100g)) FoodLookup.Found(food) else FoodLookup.NoNutrition(food)

    // --------------------------------------------------------------------------------- by name

    /**
     * Free-text search.
     *
     * ⚠️ **The search index serves a NARROWER field set than the product endpoint** — probed:
     * `nutrition_data_per` and `serving_quantity` come back null for every hit even when explicitly
     * requested. So a search result can be logged by weight but not by the serving, and the portion
     * detail arrives only when the person picks one and [byBarcode] fetches the full record. One
     * extra request on pick, rather than one per result.
     *
     * ⚠️ Results carrying no energy are dropped. One hit in eight came back with nothing at all in
     * it, and a food row that cannot contribute a calorie teaches people that tapping sometimes does
     * nothing.
     */
    suspend fun search(query: String, limit: Int = 25, force: Boolean = false): FoodSearchPage {
        val q = query.trim()
        if (q.length < MIN_QUERY) return FoodSearchPage(q, emptyList())

        val key = "off_search_${q.lowercase()}_$limit"
        if (!force) {
            cache.read(key, SEARCH_TTL, FoodSearchPage.serializer())?.let { return it.value }
        }
        val url = "$SEARCH_BASE?q=${URLEncoder.encode(q, "UTF-8")}&page_size=$limit&fields=$SEARCH_FIELDS"
        val body = try {
            gate.withPermit { http.getString(url, ua) }
        } catch (e: Exception) {
            cache.readAny(key, FoodSearchPage.serializer())?.let { return it.value }
            throw e
        }

        // ⚠️ **An unreadable answer is not "no such food".** This returned an empty page, which the
        // screen renders as "nothing matched" — so a server sending nonsense looked exactly like a
        // successful search for something that does not exist, and the reader would go on to type
        // the name in three more ways. `byBarcode`, fifty lines up in this same file, already tells
        // those two apart (`FoodLookup.Unreachable("…sent something unreadable")`); this did not.
        // Thrown rather than returned, matching the network `catch` above, which serves the cache
        // and then rethrows — so every caller already handles a failure from here.
        val root = runCatching { http.json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw IllegalStateException("Open Food Facts sent something unreadable")
        val hits = (root["hits"] as? kotlinx.serialization.json.JsonArray).orEmpty()

        val foods = hits.mapNotNull { hit ->
            val o = hit as? JsonObject ?: return@mapNotNull null
            val code = o.str("code").ifBlank { return@mapNotNull null }
            parseOffProduct(code, o)
        }.filter { FoodPortion.isLoggable(it.per100g) }

        val page = FoodSearchPage(
            query = q,
            foods = foods,
            // ⚠️ Reported as APPROXIMATE because it is. The endpoint answers `count: 10000` with
            // `is_count_exact: false` for an ordinary two-word query — a ceiling, not a total.
            approximateTotal = root["count"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: foods.size,
            exact = (root["is_count_exact"] as? JsonPrimitive)?.content == "true",
        )
        cache.write(key, page, FoodSearchPage.serializer())
        return page
    }

    private companion object {
        /**
         * ⚠️ Open Food Facts asks that clients identify themselves with a name and a contact. An
         * anonymous or browser-impersonating agent is what gets a volunteer-run service rate-limited.
         */
        const val USER_AGENT = "LCARS-Android/1.0 (github.com/mascwa200-beep/thing)"

        const val PRODUCT_BASE = "https://world.openfoodfacts.org/api/v2/product"

        /**
         * ⚠️ NOT the legacy `cgi/search.pl`, which answers **503** — probed. It is deprecated and
         * overloaded; this is the current search service.
         */
        const val SEARCH_BASE = "https://search.openfoodfacts.org/search"

        const val PRODUCT_FIELDS =
            "code,product_name,product_name_en,brands,quantity,product_quantity,serving_size," +
                "serving_quantity,nutriments,image_small_url"

        // Asking for the two the index does not carry costs nothing and documents the gap.
        const val SEARCH_FIELDS =
            "code,product_name,product_name_en,brands,serving_quantity,nutriments,image_small_url"

        /** A scanned product's nutrition does not change; a week is generous and still bounded. */
        const val PRODUCT_TTL = 7L * 24 * 60 * 60 * 1000
        const val SEARCH_TTL = 6L * 60 * 60 * 1000

        const val MIN_QUERY = 2

        /** Above this a "package" is a case or a pallet, not a thing one person eats from. */
    }
}

// ---------------------------------------------------------------------------------- parsing

/** Above this a "package" is a case or a pallet, not a thing one person eats from. */
private const val MAX_PACKAGE_G = 20_000.0

/**
 * One product record, normalised into the app's own shape.
 *
 * ⚠️ **The `_100g` fields are trustworthy when present, and absent when they are not.** Probed a
 * record entered per serving: `nutrition_data_per: "serving"` with `energy-kcal_100g` **and**
 * `energy-kcal_serving` both null. Open Food Facts declines to answer rather than handing over an
 * ambiguous number — so there is no case where a `_100g` field means something other than per 100
 * grams, and no need to inspect `nutrition_data_per` at all.
 */
internal fun parseOffProduct(id: String, p: JsonObject): Food? {
    val n = (p["nutriments"] as? JsonObject) ?: JsonObject(emptyMap())
    val name = p.str("product_name_en").ifBlank { p.str("product_name") }
    if (name.isBlank()) return null

    val per100g = NutritionDay.Nutrients(
        kcal = n.num("energy-kcal_100g") ?: 0.0,
        proteinG = n.num("proteins_100g") ?: 0.0,
        fatG = n.num("fat_100g") ?: 0.0,
        carbG = n.num("carbohydrates_100g") ?: 0.0,
        fibreG = n.num("fiber_100g") ?: 0.0,
        sugarG = n.num("sugars_100g") ?: 0.0,
        satFatG = n.num("saturated-fat_100g") ?: 0.0,
        // The one unit conversion, and the reason it is a named core function.
        sodiumMg = FoodPortion.sodiumMgFromGrams(n.num("sodium_100g")),
    )

    // ⚠️ An implausible serving weight is DROPPED rather than carried. It is a multiplier: a
    // record claiming a 3 g serving turns "two servings" of a biscuit packet into 28 calories.
    // Losing the serving unit costs one convenience; keeping a wrong one costs the number.
    val serving = p.num("serving_quantity")?.takeUnless { FoodPortion.servingLooksWrong(it) }

    return Food.of(
        id = id,
        name = name.trim(),
        per100g = per100g,
        brand = p.str("brands").substringBefore(",").trim(),
        servingGrams = serving,
        servingLabel = if (serving != null) p.str("serving_size").trim() else "",
        packageGrams = p.num("product_quantity")?.takeIf { it > 0.0 && it < MAX_PACKAGE_G },
        source = NutritionDay.Source.OPEN_FOOD_FACTS,
        imageUrl = p.str("image_small_url"),
        micros = offMicros(n),
        extras = offExtras(n),
    )
}

/**
 * The vitamins and minerals this record publishes.
 *
 * ⚠️ **This path read NONE of them, and the bundled barcode database read all eight from the same
 * source.** So the same product logged with a signal carried fewer nutrients than logged without one
 * — which is not a gap in the data, it is the app disagreeing with itself about one shelf item
 * depending on where the phone happened to be.
 *
 * ⚠️ **Every field is in GRAMS, probed against the live API rather than assumed** — the `_unit` value
 * beside each reads "g", including for calcium and vitamin D. `Micronutrients.fromGrams` is the one
 * place that conversion lives, and it derives the factor from the micronutrient's own declared unit,
 * so it cannot come to disagree with the bound `FoodPortion.maxPer100g` applies to the same figure.
 *
 * ⚠️ **A recorded zero is KEPT and an absent field is DROPPED**, which `num` already distinguishes and
 * this must not flatten. Measured on real US products: a bag of crisps publishes `iron: 0` and a
 * pasta box publishes `vitamin-d: 0` — genuine label statements of none — while most European records
 * publish nothing at all. "Contains none" and "nobody measured" are different things to sum into a
 * day's total.
 */
/**
 * The further nutrients this record carries.
 *
 * ⚠️ **The field list is not written down here — it comes from the declaration.** Each nutrient
 * knows its own Open Food Facts column, and the offline database's builder reads that same
 * declaration, so the network path and the bundled path cannot end up looking at different columns.
 * That divergence is exactly the defect this project corrected when the network lookup was found to
 * be dropping micronutrients the offline one kept.
 *
 * ⚠️ Open Food Facts publishes every one of these in GRAMS, as it does the micronutrients above;
 * [NutrientSet.fromGrams] is the one conversion into the unit each is stored in.
 */
private fun offExtras(n: JsonObject): NutrientSet.Amounts {
    val m = LinkedHashMap<NutrientSet.Nutrient, Double>()
    for (nutrient in NutrientSet.Nutrient.entries) {
        NutrientSet.fromGrams(nutrient, n.num("${nutrient.offField}_100g"))?.let { m[nutrient] = it }
    }
    return NutrientSet.Amounts(m)
}

private fun offMicros(n: JsonObject): Micronutrients.Amounts {
    val m = LinkedHashMap<Micronutrients.Micro, Double>(8)
    fun put(k: Micronutrients.Micro, field: String) {
        Micronutrients.fromGrams(k, n.num("${field}_100g"))?.let { m[k] = it }
    }
    put(Micronutrients.Micro.CALCIUM, "calcium")
    put(Micronutrients.Micro.IRON, "iron")
    put(Micronutrients.Micro.POTASSIUM, "potassium")
    put(Micronutrients.Micro.VITAMIN_A, "vitamin-a")
    put(Micronutrients.Micro.VITAMIN_C, "vitamin-c")
    put(Micronutrients.Micro.VITAMIN_D, "vitamin-d")
    put(Micronutrients.Micro.CHOLESTEROL, "cholesterol")
    put(Micronutrients.Micro.TRANS_FAT, "trans-fat")
    return Micronutrients.Amounts(m)
}

/**
 * ⚠️ Numbers arrive as JSON numbers on the product endpoint and sometimes as strings elsewhere,
 * so both are accepted. A string that is not a number yields null, never zero — zero is a claim
 * that a food contains none of something, which is a different thing from not knowing.
 */
private fun JsonObject.num(key: String): Double? {
    val prim = (this[key] as? JsonPrimitive) ?: return null
    return prim.doubleOrNull ?: prim.content.trim().toDoubleOrNull()
}

private fun JsonObject.str(key: String): String =
    ((this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "").trim()

internal fun kotlinx.serialization.json.JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
