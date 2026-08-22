package dev.mascwa.pulse.core.telemetry

import kotlin.math.roundToInt

/**
 * What the four nutrients beyond the macros are measured against.
 *
 * Fibre, saturated fat, sugar and sodium have been parsed from both food sources, summed by
 * [NutritionDay.Nutrients.plus] and written to the log since the day the store was built — and drawn
 * by nothing. This is the half that makes them worth drawing: a bare "18 g of fibre" is a number
 * without a meaning, and this app has corrected that same shape three times already (an economic
 * figure with no vintage, a safety feed with no coverage, a market with no session).
 *
 * ⚠️ **Every figure here is POPULATION guidance and this file says so in every sentence it produces.**
 * These are the reference intakes public-health bodies publish for adults in general. They are not a
 * prescription, they do not know about anyone's kidneys or blood pressure, and this tab already tells
 * a real person how much to eat — so the register throughout is "the usual reference is", never "you
 * should".
 *
 * ⚠️ **And where the app cannot honestly compare, it refuses rather than guesses.** Three of the four
 * refuse under real conditions:
 *
 *  - fibre and saturated fat are stated as a share of energy, so with no calorie target there is
 *    nothing to take a share OF;
 *  - sodium's limit is written for adults, so it is withheld when the profile says the reader is a
 *    child or does not say at all;
 *  - **sugar has no limit here at all**, and that is the most important refusal in the file. See
 *    [sugarNote].
 */
object NutrientGuides {

    /** What kind of number a guide is, because "aim for" and "stay under" are opposite instructions. */
    enum class Kind {
        /** A floor to reach. More is not a failure. */
        TARGET,

        /** A ceiling. Going past it is the thing the figure exists to flag. */
        LIMIT,
    }

    /**
     * One reference intake, with the basis it rests on.
     *
     * [basis] is not decoration. Two of these scale with the calorie target and one does not, so a
     * reader who changes their target will watch two of the three numbers move — and a figure that
     * moves for reasons the screen has not explained reads as a bug.
     */
    data class Guide(
        val label: String,
        val amount: Double,
        val unit: String,
        val kind: Kind,
        val basis: String,
        val source: String,
    ) {
        /** How far along the guide a day's intake is. Not clamped: past a LIMIT is exactly the point. */
        fun fractionOf(eaten: Double): Double = if (amount <= 0.0) 0.0 else eaten / amount

        /** "18 of 34 g" — the whole comparison in the width of a row. */
        fun readout(eaten: Double, unit: String = this.unit): String =
            "${eaten.roundToInt()} of ${amount.roundToInt()} $unit"
    }

    // ------------------------------------------------------------------------------ the numbers

    /**
     * Fibre: **14 g per 1000 kcal**, the Adequate Intake in the US Dietary Reference Intakes.
     *
     * Energy-based rather than a flat number, which is why it needs the target. That is also why it is
     * the fairest of the four to show: somebody eating 1,600 kcal and somebody eating 3,000 are not
     * failing the same test.
     */
    const val FIBRE_G_PER_1000_KCAL = 14.0

    /** Saturated fat: **under 10% of energy**, the WHO population guideline. 9 kcal per gram. */
    const val SATFAT_MAX_ENERGY_SHARE = 0.10
    const val KCAL_PER_GRAM_FAT = 9.0

    /** Sodium: **under 2,000 mg a day** for adults, the WHO guideline. Not scaled to energy. */
    const val SODIUM_MAX_MG = 2000.0

    /** Below this the sodium guideline is not the adult one, so it is withheld rather than misapplied. */
    const val ADULT_FROM_AGE = 18

    /**
     * Fibre for a given calorie target, or null when there is no target to take a share of.
     *
     * ⚠️ Null rather than a default. A plan the app has refused to make is not the same as a plan of
     * 2,000 kcal, and quietly substituting one produces a fibre figure that looks measured.
     */
    fun fibre(targetKcal: Int?): Guide? {
        val kcal = targetKcal?.takeIf { it > 0 } ?: return null
        return Guide(
            label = "Fibre",
            amount = kcal / 1000.0 * FIBRE_G_PER_1000_KCAL,
            unit = "g",
            kind = Kind.TARGET,
            basis = "14 g per 1,000 kcal, so it moves with your target",
            source = "US Dietary Reference Intakes, adequate intake",
        )
    }

    /** Saturated fat for a given calorie target, or null when there is no target. */
    fun saturatedFat(targetKcal: Int?): Guide? {
        val kcal = targetKcal?.takeIf { it > 0 } ?: return null
        return Guide(
            label = "Saturated fat",
            amount = kcal * SATFAT_MAX_ENERGY_SHARE / KCAL_PER_GRAM_FAT,
            unit = "g",
            kind = Kind.LIMIT,
            basis = "under 10% of your calories, so it moves with your target",
            source = "World Health Organization, population guideline",
        )
    }

    /**
     * Sodium, or null when the profile does not establish an adult.
     *
     * ⚠️ The age gate is not pedantry. The adult figure is materially higher than the one published
     * for children, and a limit that is too generous is the direction that does harm here — it reads
     * as permission. An unstated birth year is treated exactly like a child's: withheld.
     */
    fun sodium(birthYear: Int, thisYear: Int): Guide? {
        if (birthYear <= 0) return null
        val age = thisYear - birthYear
        if (age < ADULT_FROM_AGE) return null
        return Guide(
            label = "Sodium",
            amount = SODIUM_MAX_MG,
            unit = "mg",
            kind = Kind.LIMIT,
            basis = "a flat daily figure — it does not move with your target",
            source = "World Health Organization, adults",
        )
    }

    /**
     * Why sugar is shown as a plain total and never as a share of anything.
     *
     * ⚠️ **This is the load-bearing refusal in the file.** The WHO guideline everyone quotes — under
     * 10% of energy, and conditionally under 5% — is about **free sugars**: what a manufacturer or a
     * cook added, plus what is in honey, syrup and fruit juice. Both food sources publish **total
     * sugars**, which also counts the sugar in an apple and the lactose in a glass of milk. Nothing in
     * the data separates them.
     *
     * Rendering total sugars against the free-sugars limit would tell somebody eating fruit and yogurt
     * that they had breached a public-health guideline they had not gone near. So the number is shown
     * with no line drawn under it, and this sentence is shown with it. A figure the app cannot judge
     * honestly is worth more with its limitation attached than with a confident bar beside it.
     */
    const val sugarNote: String =
        "Total sugars, including what is naturally in fruit and milk. The usual guideline is about " +
            "added sugars only, which the data cannot separate out — so no limit is drawn here."

    // ------------------------------------------------------------------------------- the sentence

    /**
     * How a day reads against one guide, in the register this feature uses everywhere.
     *
     * ⚠️ No praise and no scolding, in either direction. "Well done" on a day somebody has not
     * finished eating is a judgement the data cannot support, and it is the tone that makes a
     * tracker into something people delete.
     */
    fun sentence(g: Guide, eaten: Double): String {
        val f = g.fractionOf(eaten)
        return when (g.kind) {
            Kind.TARGET -> when {
                f >= 1.0 -> "Past the usual reference of ${g.amount.roundToInt()} ${g.unit}."
                f >= 0.7 -> "Most of the way to the usual ${g.amount.roundToInt()} ${g.unit}."
                else -> "The usual reference is ${g.amount.roundToInt()} ${g.unit} a day."
            }
            Kind.LIMIT -> when {
                f > 1.0 -> "Past the usual ceiling of ${g.amount.roundToInt()} ${g.unit}."
                f >= 0.85 -> "Close to the usual ceiling of ${g.amount.roundToInt()} ${g.unit}."
                else -> "The usual ceiling is ${g.amount.roundToInt()} ${g.unit} a day."
            }
        }
    }

    /**
     * Every guide the app can honestly state for this reader, paired with what they have eaten.
     *
     * Ordered fibre → saturated fat → sodium, which is one to reach followed by two to stay under.
     * Sugar is deliberately absent: it has no guide, and a screen wanting to show it reads the total
     * and [sugarNote] directly.
     */
    fun forDay(
        eaten: NutritionDay.Nutrients,
        targetKcal: Int?,
        birthYear: Int,
        thisYear: Int,
    ): List<Pair<Guide, Double>> = listOfNotNull(
        fibre(targetKcal)?.let { it to eaten.fibreG },
        saturatedFat(targetKcal)?.let { it to eaten.satFatG },
        sodium(birthYear, thisYear)?.let { it to eaten.sodiumMg },
    )
}
