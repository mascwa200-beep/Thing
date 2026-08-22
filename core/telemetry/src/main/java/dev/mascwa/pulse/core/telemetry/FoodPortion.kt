package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How much of a food was actually eaten, and what that portion contains.
 *
 * Food data arrives per 100 grams. A person eats "two slices" or "a 30 g handful" or "half the
 * packet". This is the one place those two are reconciled, and it is pure so that the conversion can
 * be tested rather than trusted.
 *
 * ⚠️ **Every rule here was measured against the live Open Food Facts API, not recalled.** The probes
 * are recorded on each rule because several of them contradict what the shape of the data suggests.
 */
object FoodPortion {

    /** A hundred grams, which is the unit every food record in this app is stored in. */
    const val PER: Double = 100.0

    /**
     * The units a portion can be expressed in.
     *
     * ⚠️ [SERVING] is not a unit of mass and cannot be converted without the food's own serving
     * weight. A food that does not declare one simply cannot be logged by the serving, and the
     * surface has to say so rather than invent a number — which is why [gramsFor] returns null
     * rather than falling back to anything.
     */
    enum class Unit(val label: String) {
        GRAM("g"),
        MILLILITRE("ml"),
        SERVING("serving"),
        PACKAGE("package"),
    }

    /**
     * What a food knows about its own portions.
     *
     * [servingGrams] and [packageGrams] are nullable because most records carry neither, and a
     * missing one has to stay missing all the way to the surface.
     */
    data class Sizes(
        val servingGrams: Double? = null,
        val servingLabel: String = "",
        val packageGrams: Double? = null,
    )

    /** A quantity the person chose. */
    data class Portion(val amount: Double, val unit: Unit)

    // ------------------------------------------------------------------------------- conversion

    /**
     * The mass of [portion], or null when this food cannot express it.
     *
     * Millilitres are treated as grams. ⚠️ That is an approximation and it is the right one here:
     * it is exact for water, within a few per cent for milk, juice and soft drinks — which is
     * essentially everything logged by volume — and the alternative is a density table this app has
     * no source for. It is wrong for oil, and the comment exists so nobody is surprised by that.
     */
    fun gramsFor(portion: Portion, sizes: Sizes): Double? {
        val a = portion.amount
        if (!a.isFinite() || a < 0.0) return null
        return when (portion.unit) {
            Unit.GRAM, Unit.MILLILITRE -> a
            Unit.SERVING -> sizes.servingGrams?.takeIf { it.isFinite() && it > 0.0 }?.let { a * it }
            Unit.PACKAGE -> sizes.packageGrams?.takeIf { it.isFinite() && it > 0.0 }?.let { a * it }
        }
    }

    /** Which units this food can actually offer. GRAM is always one of them; the rest are earned. */
    fun unitsFor(sizes: Sizes): List<Unit> = buildList {
        add(Unit.GRAM)
        if ((sizes.servingGrams ?: 0.0) > 0.0) add(Unit.SERVING)
        if ((sizes.packageGrams ?: 0.0) > 0.0) add(Unit.PACKAGE)
    }

    /**
     * What [grams] of a food whose figures are per 100 g actually contains.
     *
     * The single conversion in the whole food half, deliberately. `Nutrients.scaled` takes a factor
     * and knows nothing about where the factor came from; getting the factor right is this function's
     * only job, and having exactly one caller-facing name for it is what stops a per-100-gram figure
     * being logged as a per-serving one.
     */
    fun eaten(per100g: NutritionDay.Nutrients, grams: Double): NutritionDay.Nutrients {
        if (!grams.isFinite() || grams <= 0.0) return NutritionDay.Nutrients()
        return per100g.scaled(grams / PER)
    }

    /**
     * The other direction: label figures for a stated weight, back to the per-hundred-gram form
     * every source in this app is normalised to.
     *
     * ⚠️ **Null when the weight is unknown, and that refusal is the whole point.** A saved food is
     * a density — it has to be, because logging it later means scaling it to whatever is on the
     * plate — and a density cannot be recovered from "320 calories" alone. The tempting fallback is
     * to treat the figures as if they were already per hundred grams, which produces a food that
     * looks right in the list and is wrong by whatever factor the real portion happened to be. A
     * refusal the surface can explain is worth more than a number nobody can check.
     *
     * ⚠️ Returning [NutritionDay.Nutrients] rather than zero for a bad weight, unlike [eaten],
     * because the two answer different questions: eating nothing genuinely is zero nutrition, and
     * defining a food out of nothing is not a food.
     */
    fun per100gFrom(eaten: NutritionDay.Nutrients, grams: Double): NutritionDay.Nutrients? {
        if (!grams.isFinite() || grams <= 0.0) return null
        return eaten.scaled(PER / grams)
    }

    // ------------------------------------------------------------------------ sanity of the data

    /**
     * The narrowest and widest a single serving may plausibly weigh.
     *
     * ⚠️ Measured, and the low bound is why this exists. Open Food Facts is crowd-entered, and the
     * probe that prompted this found a packet of biscuits declaring a **3 gram** serving — roughly a
     * tenth of one biscuit — alongside a per-serving energy figure computed faithfully from it. A
     * serving weight is a multiplier, so a wrong one is wrong by a factor, not by a rounding.
     *
     * The bounds are wide on purpose: a stick of gum is a couple of grams and a family lasagne is
     * over a kilo, and the point is to catch the absurd rather than to have an opinion about lunch.
     */
    const val MIN_SERVING_G: Double = 4.0
    const val MAX_SERVING_G: Double = 2000.0

    /** True when a declared serving weight is too odd to offer without saying so. */
    fun servingLooksWrong(servingGrams: Double?): Boolean {
        val g = servingGrams ?: return false
        if (!g.isFinite() || g <= 0.0) return true
        return g < MIN_SERVING_G || g > MAX_SERVING_G
    }

    /**
     * Whether a food record carries enough to be logged at all.
     *
     * ⚠️ Energy is the only field that is genuinely required, and that is a measurement rather than a
     * preference: of eight results for an ordinary search term, one came back with no energy and no
     * macros whatsoever. A row that cannot contribute a calorie is not a food the app can log, and
     * listing it teaches people that tapping a result sometimes does nothing.
     */
    fun isLoggable(per100g: NutritionDay.Nutrients): Boolean =
        per100g.kcal.isFinite() && per100g.kcal > 0.0

    /**
     * Milligrams of sodium from Open Food Facts' grams.
     *
     * ⚠️ **`sodium_100g` is in GRAMS.** The field sits beside `sodium_unit: "g"` and reads 0.043 for
     * a spread that contains 43 mg — so a value passed through unchanged is wrong by a thousand, and
     * wrong in the direction that makes every food look sodium-free. Named rather than inlined
     * because it is the kind of conversion that gets quietly dropped in a refactor.
     */
    fun sodiumMgFromGrams(grams: Double?): Double {
        val g = grams ?: return 0.0
        return if (g.isFinite() && g >= 0.0) g * 1000.0 else 0.0
    }

    // ----------------------------------------------------------------------------------- wording

    /** "30 g" · "1 serving (28 g)" · "2 servings (56 g)" — what the log row says it was. */
    fun describe(portion: Portion, sizes: Sizes): String {
        val n = trim(portion.amount)
        return when (portion.unit) {
            Unit.GRAM -> "$n g"
            Unit.MILLILITRE -> "$n ml"
            Unit.SERVING -> {
                val g = gramsFor(portion, sizes)
                val word = if (portion.amount == 1.0) "serving" else "servings"
                val label = sizes.servingLabel.takeIf { it.isNotBlank() }
                // ⚠️ The source's own label is used ONLY for a single serving, and that is because of
                // what those labels actually say. Probed: they read "1 serving (28 g)" and "1 portion
                // (330 ml)" — they already carry both the count and the weight. Appending the grams
                // gives "1 × 1 serving (28 g) (28 g)", and multiplying it gives "2 × 1 serving (28 g)",
                // which states two different counts in one line. Past one, the label is dropped and the
                // portion describes itself.
                when {
                    portion.amount == 1.0 && label != null -> label
                    g != null -> "$n $word (${trim(g)} g)"
                    else -> "$n $word"
                }
            }
            Unit.PACKAGE -> {
                val g = gramsFor(portion, sizes)
                val word = if (portion.amount == 1.0) "package" else "packages"
                if (g != null) "$n $word (${trim(g)} g)" else "$n $word"
            }
        }
    }

    /**
     * A number with no more decimals than it needs.
     *
     * ⚠️ `Locale.US` throughout the food half. A comma decimal separator turns 1.5 servings into
     * "1,5", which reads as two values in a list and is exactly the confusion a portion label cannot
     * afford.
     */
    internal fun trim(v: Double): String {
        if (!v.isFinite()) return "—"
        val whole = v.roundToInt()
        return if (abs(v - whole) < 0.05) whole.toString()
        else String.format(java.util.Locale.US, "%.1f", v)
    }
}
