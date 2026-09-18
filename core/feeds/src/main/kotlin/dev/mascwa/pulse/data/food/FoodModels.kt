package dev.mascwa.pulse.data.food

import dev.mascwa.pulse.core.telemetry.FoodPortion
import dev.mascwa.pulse.core.telemetry.Micronutrients
import dev.mascwa.pulse.core.telemetry.NutrientSet
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

    /**
     * Vitamins and minerals per 100 g, keyed by [Micronutrients.Micro] name — and **only those the
     * source actually recorded**.
     *
     * ⚠️ A map rather than more flat doubles, and that is not a style choice. Measured over the
     * bundled corpus, three product records in four say nothing about calcium; as a `Double = 0.0`
     * beside the macros, every one of them would read "0 mg calcium" — a measurement nobody took,
     * printed as confidently as one they did. A missing key cannot be mistaken for a zero.
     *
     * ⚠️ Keyed by String, not by the enum, for the same reason [sourceName] is: this is an on-disk
     * contract. An enum-keyed serializer throws on a value it does not know, so renaming a
     * micronutrient would make every previously cached food undecodable. An unknown key here is
     * simply dropped by [microsPer100g].
     */
    val micros: Map<String, Double> = emptyMap(),

    /**
     * Every FURTHER nutrient per 100 g, keyed by `NutrientSet.Nutrient` name — added sugars, the
     * individual sugars, the rest of the B vitamins, the trace minerals, water.
     *
     * ⚠️ Same shape and same reasoning as [micros], one tier sparser. The densest of these is
     * recorded on 5.7% of products and most are near 2%, so a flat double defaulting to zero would
     * be a false statement about ninety-eight products in a hundred.
     *
     * ⚠️ Keyed by String and **defaulted**, both deliberately: this is an on-disk contract, so an
     * enum-keyed serializer would throw on a name it did not know, and a non-defaulted field would
     * make every food cached before this existed undecodable.
     */
    val extras: Map<String, Double> = emptyMap(),
) {
    val per100g: NutritionDay.Nutrients
        get() = NutritionDay.Nutrients(kcal, proteinG, fatG, carbG, fibreG, sugarG, satFatG, sodiumMg)

    /** The micronutrients this record carries, in the core's own type. Unknown keys are dropped. */
    val microsPer100g: Micronutrients.Amounts
        get() = Micronutrients.Amounts(
            micros.mapNotNull { (k, v) ->
                runCatching { Micronutrients.Micro.valueOf(k) }.getOrNull()?.let { it to v }
            }.toMap()
        )

    /** The further nutrients this record carries, in the core's own type. Unknown keys are dropped. */
    val extrasPer100g: NutrientSet.Amounts
        get() = NutrientSet.Amounts(
            extras.mapNotNull { (k, v) ->
                runCatching { NutrientSet.Nutrient.valueOf(k) }.getOrNull()?.let { it to v }
            }.toMap()
        )

    val sizes: FoodPortion.Sizes
        get() = FoodPortion.Sizes(servingGrams, servingLabel, packageGrams)

    val source: NutritionDay.Source
        get() = runCatching { NutritionDay.Source.valueOf(sourceName) }
            .getOrDefault(NutritionDay.Source.OPEN_FOOD_FACTS)

    /** "Ferrero · Nutella" — brand first, because that is how a shelf is scanned. */
    val display: String get() = if (brand.isBlank()) name else "$brand · $name"

    companion object {
        /**
         * The one place a [NutritionDay.Nutrients] becomes a storable [Food].
         *
         * ⚠️ **Every figure passes [FoodPortion.sane] on the way in, and this is the only place that
         * has to be true.** All four parsers — the bundled seed, the barcode database, Open Food
         * Facts and a food you saved yourself — construct through here and nothing else calls the
         * data class directly, so a source that publishes five kilograms of protein per hundred
         * grams cannot reach the log however it arrives. Doing it at each parser instead would be
         * four copies of one rule and a fifth parser later that forgets it.
         *
         * ⚠️ It sanitises **silently**, which is right for a parser and wrong for a person. A path
         * where somebody typed the numbers must ask [FoodPortion.densityLooksWrong] *before* getting
         * here and say what is wrong, because the likeliest cause is the weight they entered beside
         * them — see `CustomFoodStore`. This is the backstop, not the conversation.
         */
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
            micros: Micronutrients.Amounts = Micronutrients.Amounts(),
            extras: NutrientSet.Amounts = NutrientSet.Amounts(),
        ): Food {
            val n = FoodPortion.sane(per100g)
            return Food(
                id = id,
                name = name,
                brand = brand,
                kcal = n.kcal,
                proteinG = n.proteinG,
                fatG = n.fatG,
                carbG = n.carbG,
                fibreG = n.fibreG,
                sugarG = n.sugarG,
                satFatG = n.satFatG,
                sodiumMg = n.sodiumMg,
                servingGrams = servingGrams,
                servingLabel = servingLabel,
                packageGrams = packageGrams,
                sourceName = source.name,
                imageUrl = imageUrl,
                micros = FoodPortion.saneMicros(micros).values.entries
                    .associate { it.key.name to it.value },
                extras = NutrientSet.sane(extras).values.entries
                    .associate { it.key.name to it.value },
            )
        }
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

    /**
     * The source answered, and has no such product. Retrying will not help.
     *
     * ⚠️ [offlineUnavailable] is why the BUNDLED database did not get a say, and it is defaulted to
     * null because the ordinary case is that it did. Without it this state claims 4.4 million
     * bundled rows were searched — the sentence a reader gets is "not in the packaged-food
     * database" — when a bundle that would not open answers every query with the same null a
     * genuinely absent product does. On a cheap phone the likely cause is no space left, which is
     * worth saying out loud rather than reporting a product as unknown.
     */
    data class NotInDatabase(
        val barcode: String,
        val offlineUnavailable: String? = null,
    ) : FoodLookup

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
