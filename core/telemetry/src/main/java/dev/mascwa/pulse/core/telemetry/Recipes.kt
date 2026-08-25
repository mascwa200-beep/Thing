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
 * UP. [Recipe.cookedYieldG] therefore changes the DENSITY and never the TOTAL. Dividing the totals by
 * the raw weight instead would under-report every cooked recipe by however much water boiled off, and
 * silently — the number would simply look a bit low.
 *
 * ⚠️ **This type carries two things, told apart by [Kind].** A dish, and a group of foods somebody
 * eats together. They are the same data — a named list of foods at weights — and differ only in what
 * a helping means, which is why they share a store, a builder and a picker rather than existing as
 * two parallel halves of one feature. Everything below that behaves differently says so at the point
 * where it does.
 */
object Recipes {

    /**
     * What a saved list of foods IS, which decides how logging it behaves.
     *
     * ⚠️ **The difference is at the log site, not in the data.** Both kinds are a named list of foods
     * at weights — which is why they share this type, the store, the builder and the food picker. What
     * differs is what a helping means:
     *
     * - [RECIPE] is a **density**. It becomes one entry, because a bolognese is not eleven foods on the
     *   plate, it is one dish, and its per-100-gram figure is the whole point of having built it.
     * - [MEAL] is **several foods eaten together**. It becomes one entry per food, because porridge and
     *   a banana and a coffee remain three foods however often they arrive at the same time — and a day
     *   that cannot break down by food is a day the macro panel cannot explain.
     */
    enum class Kind { RECIPE, MEAL }

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
        /**
         * The ingredient's vitamins and minerals per 100 g, where its source recorded any.
         *
         * ⚠️ **Defaulted, so every recipe already saved decodes unchanged** — the store persists this
         * type and an added field without a default would make the whole list undecodable. Asserted by
         * a test against a recipe built the old way.
         *
         * ⚠️ Empty is a real state and the commonest one for older recipes: they were built before
         * anything carried micronutrients, so their component list genuinely has none. That is why
         * [totalMicros] adds a union rather than refusing on a partial dish.
         */
        val micros: Micronutrients.Amounts = Micronutrients.Amounts(),
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
        /**
         * Whether this is a dish or a group of foods. See [Kind].
         *
         * ⚠️ **Defaulted, so every recipe already saved decodes unchanged** — the store persists this
         * type, and a field without a default would make somebody's whole recipe book undecodable.
         * Asserted by a test against a blob written before this field existed.
         */
        val kind: Kind = Kind.RECIPE,
    )

    /** True for a group of foods rather than a dish. Reads better than `== Kind.MEAL` at call sites. */
    fun isMeal(r: Recipe): Boolean = r.kind == Kind.MEAL

    /** What went in, before anything evaporated or was absorbed. */
    fun rawGrams(r: Recipe): Double = r.components.sumOf { it.grams.coerceAtLeast(0.0) }

    /**
     * What the dish weighs when it is served.
     *
     * ⚠️ A yield LARGER than the raw weight is legitimate, not a mistake — rice and pasta absorb
     * water, and refusing it would make the feature useless for half of what people cook. Absurd
     * values are flagged by [problems] rather than rejected here.
     *
     * ⚠️ **A [Kind.MEAL] IGNORES its stored yield, rather than merely not setting one.** Nothing cooks
     * a group of foods down, so there is no water to lose — and a recipe switched to a meal still
     * carries whatever yield it was built with. Reading it would scale every one of that meal's
     * per-food entries by a number that describes a pot nobody made.
     */
    fun yieldGrams(r: Recipe): Double =
        r.cookedYieldG?.takeIf { !isMeal(r) && it.isFinite() && it > 0.0 } ?: rawGrams(r)

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

    // ------------------------------------------------------------------ vitamins and minerals
    //
    // ⚠️ **A LITERAL MIRROR of the five functions above — same operations, same order, same guards
    // — and that is the point rather than an accident.** A dish logged as a recipe used to show no
    // micronutrients at all while its ingredients logged individually showed them, so the same food
    // read differently depending on how it was entered. What must never happen is the two halves
    // scaling by different amounts: a helping's calcium would then describe a different portion than
    // its calories, on one row, with nothing to say which was right. `RecipesTest` pins them together.

    /** Everything in the pot, for the micronutrients its ingredients recorded. */
    fun totalMicros(r: Recipe): Micronutrients.Amounts =
        r.components.fold(Micronutrients.Amounts()) { acc, cc ->
            acc + FoodPortion.eatenMicros(cc.micros, cc.grams)
        }

    /** The dish's own density, or null when there is nothing to divide by. */
    fun per100gMicros(r: Recipe): Micronutrients.Amounts? {
        val y = yieldGrams(r)
        if (y <= 0.0 || r.components.isEmpty()) return null
        return totalMicros(r).scaled(FoodPortion.PER / y)
    }

    /** One portion, or null when the recipe cannot say what a portion is. */
    fun perServingMicros(r: Recipe): Micronutrients.Amounts? {
        if (r.components.isEmpty()) return null
        return totalMicros(r).scaled(1.0 / r.servings.coerceAtLeast(1))
    }

    /** A weighed helping. */
    fun eatenGramsMicros(r: Recipe, grams: Double): Micronutrients.Amounts? =
        per100gMicros(r)?.let { FoodPortion.eatenMicros(it, grams) }

    /** A counted helping. */
    fun eatenServingsMicros(r: Recipe, servings: Double): Micronutrients.Amounts? =
        perServingMicros(r)?.scaled(servings.coerceAtLeast(0.0))

    // ---------------------------------------------------------------------------- a meal, logged
    //
    // A [Kind.MEAL] does not become one entry. It becomes one per food, so INTAKE still shows what
    // was actually eaten and the macro panel can still say which food the protein came from.

    /** One food out of a meal, at the weight eaten and with its nutrition already converted. */
    data class Portion(
        val foodId: String,
        val name: String,
        val grams: Double,
        val nutrients: NutritionDay.Nutrients,
        val micros: Micronutrients.Amounts,
    )

    /**
     * Every food in a meal, at what was eaten of it.
     *
     * [scale] is how much of the whole meal was had — 1.0 for all of it, 0.5 for half. It multiplies
     * the WEIGHTS, so everything downstream follows from the one conversion rather than from a second
     * copy of it.
     *
     * ⚠️ Each food goes through [FoodPortion.eaten] and [FoodPortion.eatenMicros] — the identical pair
     * of calls the single-food log path makes. That is the whole reason this lives here rather than as
     * a loop in the view model: the app has exactly one definition of "this much of that food", and a
     * meal must not become the second.
     *
     * ⚠️ **The property a test pins:** the portions sum to [total] scaled by [scale]. Two ways of
     * reading one meal — as a list of foods and as a set of numbers — must not be able to disagree.
     *
     * ⚠️ Deliberately independent of [yieldGrams] and [servings]. A meal is not divided into helpings
     * and has nothing to evaporate; routing it through the density would make the same food come out
     * differently depending on a field a meal never fills in.
     */
    fun eatenComponents(r: Recipe, scale: Double = 1.0): List<Portion> {
        val s = if (scale.isFinite() && scale >= 0.0) scale else 0.0
        return r.components.mapNotNull { cc ->
            val grams = cc.grams.coerceAtLeast(0.0) * s
            // A weightless ingredient contributes nothing, and an entry of nothing is a row somebody
            // has to delete rather than a record of anything.
            if (grams <= 0.0) return@mapNotNull null
            Portion(
                foodId = cc.foodId,
                name = cc.name,
                grams = grams,
                nutrients = FoodPortion.eaten(cc.per100g, grams),
                micros = FoodPortion.eatenMicros(cc.micros, grams),
            )
        }
    }

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
        val meal = isMeal(r)
        if (r.name.isBlank()) {
            add(if (meal) "This meal has no name yet." else "This recipe has no name yet.")
        }
        if (r.components.isEmpty()) {
            add("Nothing in it yet, so there is no nutrition to work out.")
            return@buildList
        }
        val raw = rawGrams(r)
        if (raw <= 0.0) {
            add(
                if (meal) "Every food in it weighs nothing, so there is no nutrition to work out."
                else "Every ingredient weighs nothing, so there is no nutrition to work out.",
            )
            return@buildList
        }
        // ⚠️ **A meal is not checked for yield or portion count, and that is not laziness.** Neither
        // question applies to a group of foods: nothing was cooked down, and it is not divided into
        // helpings. A warning that cannot apply is worse than no warning — it teaches somebody to
        // scroll past the panel, on the one tab that tells a real person how much to eat.
        if (!meal) r.cookedYieldG?.let { y ->
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
        if (!meal && r.servings > SERVINGS_SUSPICIOUS_ABOVE) {
            add("${r.servings} portions is a lot — check that is what you meant.")
        }
        // ⚠️ The same sanity bound the food half applies to a packaged serving, applied to a portion
        // of a dish, and for the same reason: a portion weight is a multiplier, so a wrong one is
        // wrong by a factor rather than by a rounding.
        if (!meal) {
            servingGrams(r)?.let { g ->
                if (FoodPortion.servingLooksWrong(g)) {
                    add("A portion works out at ${g.roundToInt()} g, which does not look like a portion.")
                }
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

    /**
     * "Serves 4 · 180 g each · 420 kcal a portion" — the whole recipe in one line.
     *
     * ⚠️ A meal gets a different sentence, because the recipe one would be a lie in three places at
     * once: it does not serve four, it has no per-portion weight, and its calories are the whole
     * thing rather than a share of it.
     */
    fun summary(r: Recipe): String? {
        if (isMeal(r)) {
            if (r.components.isEmpty()) return null
            val n = r.components.size
            val kcal = total(r).kcal.roundToInt()
            return "$n ${if (n == 1) "food" else "foods"} · ${rawGrams(r).roundToInt()} g · $kcal kcal"
        }
        val per = perServing(r) ?: return null
        val g = servingGrams(r)
        val portion = if (g != null) " · ${g.roundToInt()} g each" else ""
        return "Serves ${r.servings.coerceAtLeast(1)}$portion · ${per.kcal.roundToInt()} kcal a portion"
    }
}
