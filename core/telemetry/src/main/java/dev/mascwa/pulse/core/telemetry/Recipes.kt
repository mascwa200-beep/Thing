package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A dish somebody makes more than once, and what a portion of it is worth.
 *
 * The everyday gap in food logging: a bolognese is eleven ingredients, and logging it as eleven
 * entries every Tuesday is what makes people stop logging. Built once, a recipe is a single entry
 * afterwards.
 *
 * Nothing here converts anything itself. Component nutrition goes through [FoodPortion.eaten] and
 * scaling through [NutritionDay.Nutrients.scaled], which is deliberate — that function's own note
 * says having exactly one caller-facing name for the conversion is what stops a per-100-gram figure
 * being logged as a per-serving one, and a recipe is precisely where that mistake would hide.
 *
 * ⚠️ **The one thing that is easy to get backwards, and would be wrong on every cooked dish.** A stew
 * simmered for an hour loses water: the same calories in less mass, so its per-100-gram density goes
 * UP. [cookedYieldG] therefore changes the DENSITY and never the TOTAL. Dividing the totals by the
 * raw weight instead would under-report every cooked recipe by however much water boiled off, and
 * silently — the number would simply look a bit low.
 */
object Recipes {

    /**
     * One ingredient, at the weight that went in.
     *
     * [per100g] rather than a `Food`, because `Food` lives in `:core:feeds`, which depends on this
     * module rather than the other way round. The app maps a food into this at the boundary.
     */
    data class Component(
        val foodId: String,
        val name: String,
        val per100g: NutritionDay.Nutrients,
        val grams: Double,
    )

    data class Recipe(
        val id: String,
        val name: String,
        val components: List<Component> = emptyList(),
        /**
         * What the finished dish weighed, if it was weighed.
         *
         * Null means "assume nothing was lost or added", which is right for a sandwich and wrong for
         * anything simmered. See the class note: this moves the density, never the total.
         */
        val cookedYieldG: Double? = null,
        /** How many portions it is meant to divide into. At least one. */
        val servings: Int = 1,
        val note: String = "",
    )

    /** What went in, before anything evaporated or was absorbed. */
    fun rawGrams(r: Recipe): Double = r.components.sumOf { it.grams.coerceAtLeast(0.0) }

    /**
     * What the dish weighs when it is served.
     *
     * ⚠️ A yield LARGER than the raw weight is legitimate, not a mistake — rice and pasta absorb
     * water, and refusing it would make the feature useless for half of what people cook. Absurd
     * values are flagged by [problems] rather than rejected here.
     */
    fun yieldGrams(r: Recipe): Double =
        r.cookedYieldG?.takeIf { it.isFinite() && it > 0.0 } ?: rawGrams(r)

    /** Everything in the pot. Independent of yield, which is the whole point. */
    fun total(r: Recipe): NutritionDay.Nutrients =
        r.components.fold(NutritionDay.Nutrients()) { acc, cc ->
            acc + FoodPortion.eaten(cc.per100g, cc.grams)
        }

    /**
     * The dish as a food in its own right, or null when there is nothing to divide by.
     *
     * ⚠️ Null rather than zero. An empty recipe has no density, and a `Nutrients()` full of zeros
     * would log as a real food worth nothing — which is a wrong entry, not a missing one.
     */
    fun per100g(r: Recipe): NutritionDay.Nutrients? {
        val y = yieldGrams(r)
        if (y <= 0.0 || r.components.isEmpty()) return null
        return total(r).scaled(FoodPortion.PER / y)
    }

    /** One portion, or null when the recipe cannot say what a portion is. */
    fun perServing(r: Recipe): NutritionDay.Nutrients? {
        if (r.components.isEmpty()) return null
        val n = r.servings.coerceAtLeast(1)
        return total(r).scaled(1.0 / n)
    }

    /** A weighed helping. */
    fun eatenGrams(r: Recipe, grams: Double): NutritionDay.Nutrients? =
        per100g(r)?.let { FoodPortion.eaten(it, grams) }

    /**
     * A counted helping — "two of the four portions".
     *
     * ⚠️ This and [eatenGrams] must agree for the same amount of food, and a test pins it. They are
     * two routes to one number and the commonest way a recipe feature goes wrong is that one of them
     * quietly uses the raw weight while the other uses the cooked one.
     */
    fun eatenServings(r: Recipe, servings: Double): NutritionDay.Nutrients? =
        perServing(r)?.scaled(servings.coerceAtLeast(0.0))

    /** What one portion weighs, so a serving can be described in grams as well as in portions. */
    fun servingGrams(r: Recipe): Double? {
        val y = yieldGrams(r)
        if (y <= 0.0 || r.components.isEmpty()) return null
        return y / r.servings.coerceAtLeast(1)
    }

    // ------------------------------------------------------------------------------- honesty

    /** A yield this far from the raw weight is a typo rather than cooking. */
    const val YIELD_SUSPICIOUS_BELOW = 0.25
    const val YIELD_SUSPICIOUS_ABOVE = 4.0

    /** Above this many portions the arithmetic is fine and the intent probably is not. */
    const val SERVINGS_SUSPICIOUS_ABOVE = 50

    /**
     * Everything doubtful about a recipe, said plainly. Empty means nothing looks wrong.
     *
     * ⚠️ Warnings, never refusals, except for the one case that genuinely cannot produce a number.
     * People cook strange things, and a recipe builder that argues with its user about a reduction is
     * one they stop using — but a per-portion calorie figure that is out by a factor of four is worth
     * a sentence, because this tab tells a real person how much to eat.
     */
    fun problems(r: Recipe): List<String> = buildList {
        if (r.name.isBlank()) add("This recipe has no name yet.")
        if (r.components.isEmpty()) {
            add("Nothing in it yet, so there is no nutrition to work out.")
            return@buildList
        }
        val raw = rawGrams(r)
        if (raw <= 0.0) {
            add("Every ingredient weighs nothing, so there is no nutrition to work out.")
            return@buildList
        }
        r.cookedYieldG?.let { y ->
            if (!y.isFinite() || y <= 0.0) {
                add("The finished weight is not a weight, so the raw total is being used instead.")
            } else {
                val ratio = y / raw
                if (ratio < YIELD_SUSPICIOUS_BELOW) {
                    add(
                        "The finished dish is much lighter than what went in " +
                            "(${y.roundToInt()} g from ${raw.roundToInt()} g). Worth a second look.",
                    )
                } else if (ratio > YIELD_SUSPICIOUS_ABOVE) {
                    add(
                        "The finished dish is much heavier than what went in " +
                            "(${y.roundToInt()} g from ${raw.roundToInt()} g). Worth a second look.",
                    )
                }
            }
        }
        if (r.servings > SERVINGS_SUSPICIOUS_ABOVE) {
            add("${r.servings} portions is a lot — check that is what you meant.")
        }
        // ⚠️ The same sanity bound the food half applies to a packaged serving, applied to a portion
        // of a dish, and for the same reason: a portion weight is a multiplier, so a wrong one is
        // wrong by a factor rather than by a rounding.
        servingGrams(r)?.let { g ->
            if (FoodPortion.servingLooksWrong(g)) {
                add("A portion works out at ${g.roundToInt()} g, which does not look like a portion.")
            }
        }
        // Energy that the macros cannot account for usually means one ingredient was entered in the
        // wrong unit. `NutritionDay` already knows how to spot it; this just asks.
        val t = total(r)
        val implied = NutritionDay.energyFromMacros(t)
        if (t.kcal > 0.0 && implied > 0.0 && abs(implied - t.kcal) / t.kcal > ENERGY_MISMATCH) {
            add(
                "The calories and the macros disagree by more than a quarter " +
                    "(${t.kcal.roundToInt()} against ${implied.roundToInt()} from the macros). " +
                    "Usually one ingredient is in the wrong unit.",
            )
        }
    }

    /** How far the stated energy may drift from what the macros imply before it is worth saying. */
    const val ENERGY_MISMATCH = 0.25

    /** "Serves 4 · 180 g each · 420 kcal a portion" — the whole recipe in one line. */
    fun summary(r: Recipe): String? {
        val per = perServing(r) ?: return null
        val g = servingGrams(r)
        val portion = if (g != null) " · ${g.roundToInt()} g each" else ""
        return "Serves ${r.servings.coerceAtLeast(1)}$portion · ${per.kcal.roundToInt()} kcal a portion"
    }
}
