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
     * The same conversion for the vitamins and minerals, so there is one scaling rule rather than
     * two that can drift.
     *
     * ⚠️ It sits here, beside [eaten], deliberately. Writing `grams / 100.0` at the call site would
     * be a second copy of a rule this file's own note says has exactly one home — and the day the
     * two disagree, the macros and the micronutrients on one entry would describe different
     * portions of the same food.
     */
    fun eatenMicros(per100g: Micronutrients.Amounts, grams: Double): Micronutrients.Amounts {
        if (!grams.isFinite() || grams <= 0.0) return Micronutrients.Amounts()
        return per100g.scaled(grams / PER)
    }

    /**
     * The same, for the twenty-nine further nutrients.
     *
     * ⚠️ A twin of [eatenMicros] rather than a generalisation of it, because the two are keyed by
     * different enums and Kotlin value classes do not share a supertype worth abstracting over. The
     * rules they enforce are shared where it matters — [SparseNutrition] owns the scaling — so the
     * duplication here is a signature, not a behaviour.
     */
    fun eatenExtras(per100g: NutrientSet.Amounts, grams: Double): NutrientSet.Amounts {
        if (!grams.isFinite() || grams <= 0.0) return NutrientSet.Amounts()
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

    /**
     * The same direction for the vitamins and minerals somebody typed off a label.
     *
     * ⚠️ **Empty rather than null when the weight is unknown, unlike [per100gFrom], and the
     * difference is deliberate rather than an inconsistency.** That one's null is the refusal that
     * gates the whole save — a food without a density is not a food — and these ride along with it
     * on the same weight. A second refusal here would only be a second spelling of "no weight", and
     * a second thing every caller has to remember to check for no gain: anything reaching this
     * without a weight already has nothing to save.
     *
     * ⚠️ Absent stays absent through the conversion, because the scaling maps over the keys that
     * exist rather than over the enum. A nutrient nobody typed does not acquire a zero density.
     */
    fun per100gMicrosFrom(eaten: Micronutrients.Amounts, grams: Double): Micronutrients.Amounts {
        if (!grams.isFinite() || grams <= 0.0) return Micronutrients.Amounts()
        return eaten.scaled(PER / grams)
    }

    /** The twin of [per100gMicrosFrom] for [NutrientSet], for the reason [eatenExtras] gives. */
    fun per100gExtrasFrom(eaten: NutrientSet.Amounts, grams: Double): NutrientSet.Amounts {
        if (!grams.isFinite() || grams <= 0.0) return NutrientSet.Amounts()
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
     * A hundred grams of food cannot hold more than a hundred grams of anything.
     *
     * ⚠️ **This is the bound nothing in the app had, and a crowd-entered corpus of 4.4 million rows
     * guarantees it is needed.** The builder's own value ceiling was a single number applied to the
     * *raw* figure, so `proteins_100g: 5000` — five kilograms of protein in a hundred grams — was
     * stored as fact, and a vitamin A figure somebody typed in international units into a field
     * documented as grams became one and a half billion micrograms. Neither reads as an error on a
     * card. They read as a food.
     *
     * ⚠️ **Deliberately physics rather than nutrition.** A tighter ceiling would catch more, and
     * every tighter ceiling is an opinion that can be wrong about a real food — a protein isolate
     * really is eighty grams of protein per hundred, and pure oil really is a hundred grams of fat.
     * What is unarguable is that a constituent cannot outweigh the food, and that alone catches the
     * whole thousand-fold unit-error family, which is where the wrong numbers actually come from.
     *
     * ⚠️ What it does **not** catch is stated plainly rather than left to be discovered: a sodium
     * figure of forty grams per hundred is inside the bound and outside anything edible. Improbable
     * needs a nutritional opinion; impossible does not. This is the second kind.
     */
    const val MAX_MASS_G_PER_100G: Double = 100.0

    /**
     * The most energy a hundred grams can carry.
     *
     * Pure fat is about 900 kcal per hundred grams and ethanol about 700, so nothing edible reaches
     * a thousand. The slack above 900 is for a record that rounded rather than one that is wrong.
     */
    const val MAX_KCAL_PER_100G: Double = 1000.0

    /**
     * Protein, fat and carbohydrate are distinct constituents, so together they cannot outweigh the
     * food either — with slack, because a source may round each of them and some conventions count
     * fibre inside the carbohydrate figure while others do not.
     */
    const val MAX_MACRO_SUM_G: Double = 105.0

    /**
     * The most of [m] a hundred grams of food could contain, in that micronutrient's own unit.
     *
     * ⚠️ **Derived from the declared unit rather than tabulated per micronutrient**, so a
     * micronutrient added later is bounded the moment it exists. A table would be a second list to
     * keep in step with [Micronutrients.Micro], and the one that gets forgotten is always the second.
     * An unrecognised unit is left unbounded here and caught by a test instead — silently admitting
     * everything is exactly the failure this whole rule exists to stop, so it must not be possible to
     * introduce one quietly.
     *
     * ⚠️ The unit-to-scale step itself lives in [Micronutrients.perGram], shared with
     * [Micronutrients.fromGrams], so a bound and a conversion cannot come to disagree about what a
     * microgram is.
     */
    fun maxPer100g(m: Micronutrients.Micro): Double =
        Micronutrients.perGram(m)?.let { MAX_MASS_G_PER_100G * it } ?: Double.MAX_VALUE

    /**
     * [per100g] with anything impossible removed — and nothing, when the record contradicts itself.
     *
     * ⚠️ **A per-hundred-gram DENSITY only.** It must never be applied to an amount somebody ate:
     * two thousand kcal is an ordinary day and would be discarded by every rule here. The parsers
     * and [Food.of] deal in densities; the log deals in amounts, and the two must not share this.
     *
     * ⚠️ When protein, fat and carbohydrate together outweigh the food, **the whole nutrition block
     * goes** rather than one field, because there is no way to know which of them is wrong — and a
     * record whose macros are impossible has not earned belief about its energy either. That leaves a
     * food with a name and no numbers, which is a state the app already has a surface for
     * (`FoodLookup.NoNutrition`) and a far better answer than a plausible-looking calorie count.
     */
    fun sane(per100g: NutritionDay.Nutrients): NutritionDay.Nutrients {
        val macros = bounded(per100g.proteinG, MAX_MASS_G_PER_100G) +
            bounded(per100g.fatG, MAX_MASS_G_PER_100G) +
            bounded(per100g.carbG, MAX_MASS_G_PER_100G)
        if (macros > MAX_MACRO_SUM_G) return NutritionDay.Nutrients()
        return NutritionDay.Nutrients(
            kcal = bounded(per100g.kcal, MAX_KCAL_PER_100G),
            proteinG = bounded(per100g.proteinG, MAX_MASS_G_PER_100G),
            fatG = bounded(per100g.fatG, MAX_MASS_G_PER_100G),
            carbG = bounded(per100g.carbG, MAX_MASS_G_PER_100G),
            fibreG = bounded(per100g.fibreG, MAX_MASS_G_PER_100G),
            sugarG = bounded(per100g.sugarG, MAX_MASS_G_PER_100G),
            satFatG = bounded(per100g.satFatG, MAX_MASS_G_PER_100G),
            sodiumMg = bounded(per100g.sodiumMg, MAX_MASS_G_PER_100G * 1_000.0),
        )
    }

    /**
     * The same rule for the vitamins and minerals.
     *
     * ⚠️ An impossible figure is **dropped from the map, not zeroed** — the reason
     * [Micronutrients.Amounts] is a map at all. A zero says somebody measured none; an absent key says
     * nobody has a usable figure, and those are different things to put in front of a day's total.
     */
    fun saneMicros(micros: Micronutrients.Amounts): Micronutrients.Amounts =
        Micronutrients.Amounts(
            micros.values.filter { (m, v) -> v.isFinite() && v >= 0.0 && v <= maxPer100g(m) }
        )

    /**
     * Why a hand-entered density cannot be right, or null when it can.
     *
     * ⚠️ **The same rule as [sane], reached differently on purpose.** A parser has nobody to tell, so
     * it drops the field and carries on. A person typing their own numbers has to be told, because
     * the commonest cause is the weight beside them rather than the numbers themselves — and silently
     * emptying what they just entered would look like the app losing their food.
     */
    fun densityLooksWrong(per100g: NutritionDay.Nutrients): String? {
        if (per100g.kcal > MAX_KCAL_PER_100G) {
            return "that works out to ${per100g.kcal.roundToInt()} kcal per 100 g — nothing edible is " +
                "above about 900. Check the weight."
        }
        val macros = per100g.proteinG + per100g.fatG + per100g.carbG
        if (macros > MAX_MACRO_SUM_G) {
            return "protein, fat and carbs come to ${macros.roundToInt()} g per 100 g, which is more " +
                "than the food weighs. Check the weight."
        }
        return null
    }

    /** A figure inside its bound, or zero — NaN and negatives included, which no source should send. */
    private fun bounded(v: Double, max: Double): Double =
        if (v.isFinite() && v >= 0.0 && v <= max) v else 0.0

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
