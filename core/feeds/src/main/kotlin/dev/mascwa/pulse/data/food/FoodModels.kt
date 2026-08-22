package dev.mascwa.pulse.data.food

import dev.mascwa.pulse.core.telemetry.FoodPortion
import dev.mascwa.pulse.core.telemetry.NutritionDay
import kotlinx.serialization.Serializable

/**
 * A food this app can log, in the app's own shape rather than a source's.
 *
 * ⚠️ **The nutrient fields are flat doubles rather than a [NutritionDay.Nutrients], and that is a
 * module boundary rather than a style choice.** `:core:telemetry` deliberately carries no
 * kotlinx-serialization dependency — it is the pure, dependency-light module both applications rest
 * on — so a `@Serializable` class here cannot hold one of its types directly. This repo already
 * solved the same problem once, with `SessionHours` as a flat mirror of the market core's `Windows`.
 * [per100g] converts at the boundary, in one place.
 *
 * ⚠️ Every figure is **per one hundred grams**, whatever the source called it, and that normalisation
 * happens once at the parser. Carrying a source's own units further is how a 30 g biscuit ends up
 * logged as a whole packet; the portion conversion has exactly one home, in [FoodPortion.eaten].
 */
@Serializable
data class Food(
    /** The barcode where there is one, otherwise the source's own identifier. Stable, and the cache key. */
    val id: String,
    val name: String,
    val brand: String = "",
    val kcal: Double = 0.0,
    val proteinG: Double = 0.0,
    val fatG: Double = 0.0,
    val carbG: Double = 0.0,
    val fibreG: Double = 0.0,
    val sugarG: Double = 0.0,
    val satFatG: Double = 0.0,
    val sodiumMg: Double = 0.0,
    val servingGrams: Double? = null,
    val servingLabel: String = "",
    val packageGrams: Double? = null,
    /** [NutritionDay.Source] by name — the enum lives in the unserializable core. */
    val sourceName: String = NutritionDay.Source.OPEN_FOOD_FACTS.name,
    val imageUrl: String = "",
) {
    val per100g: NutritionDay.Nutrients
        get() = NutritionDay.Nutrients(kcal, proteinG, fatG, carbG, fibreG, sugarG, satFatG, sodiumMg)

    val sizes: FoodPortion.Sizes
        get() = FoodPortion.Sizes(servingGrams, servingLabel, packageGrams)

    val source: NutritionDay.Source
        get() = runCatching { NutritionDay.Source.valueOf(sourceName) }
            .getOrDefault(NutritionDay.Source.OPEN_FOOD_FACTS)

    /** "Ferrero · Nutella" — brand first, because that is how a shelf is scanned. */
    val display: String get() = if (brand.isBlank()) name else "$brand · $name"

    companion object {
        /** The one place a [NutritionDay.Nutrients] becomes a storable [Food]. */
        fun of(
            id: String,
            name: String,
            per100g: NutritionDay.Nutrients,
            brand: String = "",
            servingGrams: Double? = null,
            servingLabel: String = "",
            packageGrams: Double? = null,
            source: NutritionDay.Source = NutritionDay.Source.OPEN_FOOD_FACTS,
            imageUrl: String = "",
        ) = Food(
            id = id,
            name = name,
            brand = brand,
            kcal = per100g.kcal,
            proteinG = per100g.proteinG,
            fatG = per100g.fatG,
            carbG = per100g.carbG,
            fibreG = per100g.fibreG,
            sugarG = per100g.sugarG,
            satFatG = per100g.satFatG,
            sodiumMg = per100g.sodiumMg,
            servingGrams = servingGrams,
            servingLabel = servingLabel,
            packageGrams = packageGrams,
            sourceName = source.name,
            imageUrl = imageUrl,
        )
    }
}

/**
 * What came back from a lookup, and **why**, when nothing did.
 *
 * ⚠️ The distinction is not decorative. Open Food Facts answers a barcode it has never heard of with
 * **HTTP 200 and `status: 0`** — probed, not assumed — so "the request failed" and "that product is
 * not in the database" arrive as the same HTTP code and are completely different things to tell
 * somebody standing in a supermarket. One means try again; the other means type it in yourself.
 */
sealed interface FoodLookup {
    data class Found(val food: Food) : FoodLookup

    /** The source answered, and has no such product. Retrying will not help. */
    data class NotInDatabase(val barcode: String) : FoodLookup

    /**
     * The record exists but carries no usable nutrition.
     *
     * ⚠️ A real state, not a defensive one: one search result in eight came back with no energy and
     * no macros at all — a record somebody created and never filled in.
     */
    data class NoNutrition(val food: Food) : FoodLookup

    /** The request did not complete. Worth another try. */
    data class Unreachable(val reason: String) : FoodLookup
}

/**
 * A page of search results.
 *
 * ⚠️ [approximateTotal] is exactly that, and the name is load-bearing. The search endpoint reports
 * `count: 10000` with `is_count_exact: false` for an ordinary two-word query — a ceiling, not a
 * total. Rendering it as "10,000 results" would be a fabricated number on a screen whose whole job is
 * to be trusted about numbers.
 */
@Serializable
data class FoodSearchPage(
    val query: String,
    val foods: List<Food>,
    val approximateTotal: Int = 0,
    val exact: Boolean = false,
)
