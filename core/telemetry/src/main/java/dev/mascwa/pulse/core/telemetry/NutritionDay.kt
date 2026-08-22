package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What was eaten, and what it adds up to.
 *
 * The model every other part of the food half is built on, plus the arithmetic for rolling a day of
 * entries into one set of numbers. Pure and clock-free — the *day* an entry belongs to is decided by the
 * caller and carried on the entry, because this core has no timezone and a day boundary taken in UTC is
 * a day out for most of the world.
 *
 * ⚠️ [Entry.nutrients] is what that portion actually contains, **not** a per-100-gram figure. Food data
 * arrives per 100 g and per serving in roughly equal measure, and carrying both interpretations in one
 * field is how a 30 g biscuit ends up logged as 500 calories. The conversion happens once, where the
 * portion is chosen, and everything downstream adds numbers that are already about the thing on the plate.
 */
object NutritionDay {

    const val KCAL_PER_G_PROTEIN: Double = 4.0
    const val KCAL_PER_G_FAT: Double = 9.0
    const val KCAL_PER_G_CARB: Double = 4.0

    /** Everything worth tracking about a portion. All absolute, all for the quantity actually eaten. */
    data class Nutrients(
        val kcal: Double = 0.0,
        val proteinG: Double = 0.0,
        val fatG: Double = 0.0,
        val carbG: Double = 0.0,
        val fibreG: Double = 0.0,
        val sugarG: Double = 0.0,
        val satFatG: Double = 0.0,
        val sodiumMg: Double = 0.0,
    ) {
        operator fun plus(other: Nutrients) = Nutrients(
            kcal + other.kcal,
            proteinG + other.proteinG,
            fatG + other.fatG,
            carbG + other.carbG,
            fibreG + other.fibreG,
            sugarG + other.sugarG,
            satFatG + other.satFatG,
            sodiumMg + other.sodiumMg,
        )

        /** Scale a per-100-gram (or per-serving) record to the portion actually eaten. */
        fun scaled(factor: Double): Nutrients {
            if (!factor.isFinite() || factor < 0.0) return Nutrients()
            return Nutrients(
                kcal * factor, proteinG * factor, fatG * factor, carbG * factor,
                fibreG * factor, sugarG * factor, satFatG * factor, sodiumMg * factor,
            )
        }

        val isEmpty: Boolean get() = kcal == 0.0 && proteinG == 0.0 && fatG == 0.0 && carbG == 0.0
    }

    /** Which sitting an entry belongs to. Ordering is the order of a day, which the UI relies on. */
    enum class Meal(val label: String) {
        BREAKFAST("Breakfast"),
        LUNCH("Lunch"),
        DINNER("Dinner"),
        SNACK("Snacks"),
    }

    /** Where a food record came from, so the surface can say how much to trust it. */
    enum class Source(val label: String) {
        /** A crowd-sourced packaged-food record. Accurate about the label, and the label is what it is. */
        OPEN_FOOD_FACTS("Open Food Facts"),

        /** A laboratory analysis of a generic food. The most reliable there is. */
        USDA("USDA"),

        /** Bundled with the app, from the public-domain USDA data. */
        OFFLINE("Bundled"),

        /** The person typed the numbers in. */
        CUSTOM("Yours"),

        /** Something they built out of other entries. */
        RECIPE("Recipe"),
    }

    /**
     * One thing eaten.
     *
     * [dayStartMs] is the start of the day it counts toward **in the eater's own zone**, decided by the
     * caller. [atMs] is when it was actually logged, which is a different question and is what the UI
     * orders a day by.
     */
    data class Entry(
        val id: String,
        val dayStartMs: Long,
        val atMs: Long,
        val name: String,
        val grams: Double,
        val nutrients: Nutrients,
        val brand: String = "",
        val servingLabel: String = "",
        val meal: Meal = Meal.SNACK,
        val source: Source = Source.CUSTOM,
        val foodId: String = "",
    )

    // ------------------------------------------------------------------------------------- totals

    fun total(entries: List<Entry>): Nutrients =
        entries.fold(Nutrients()) { acc, e -> acc + e.nutrients }

    /** Every meal present, in the order a day happens. Absent meals are omitted rather than zeroed. */
    fun byMeal(entries: List<Entry>): Map<Meal, Nutrients> {
        val out = linkedMapOf<Meal, Nutrients>()
        for (meal in Meal.entries) {
            val slice = entries.filter { it.meal == meal }
            if (slice.isNotEmpty()) out[meal] = total(slice)
        }
        return out
    }

    /** What is left of each target. Negative means over, which the surface is expected to show. */
    data class Remaining(val kcal: Int, val proteinG: Int, val fatG: Int, val carbG: Int) {
        val overKcal: Boolean get() = kcal < 0
    }

    fun remaining(eaten: Nutrients, targets: MacroTargets.Targets) = Remaining(
        kcal = targets.kcal - eaten.kcal.roundToInt(),
        proteinG = targets.proteinG - eaten.proteinG.roundToInt(),
        fatG = targets.fatG - eaten.fatG.roundToInt(),
        carbG = targets.carbG - eaten.carbG.roundToInt(),
    )

    // ------------------------------------------------------------------------------- self-checking

    /** The calories the macros imply, which is not always the calories the record claims. */
    fun energyFromMacros(n: Nutrients): Double =
        n.proteinG * KCAL_PER_G_PROTEIN + n.fatG * KCAL_PER_G_FAT + n.carbG * KCAL_PER_G_CARB

    /**
     * How far outside its own macros a record's stated energy may fall before it is worth flagging.
     *
     * ⚠️ Loose on purpose, and both halves matter. Crowd-sourced records are typed off a label by
     * volunteers, and labels themselves round: a 250 kcal snack whose macros come to 230 is ordinary
     * transcription, not an error worth interrupting somebody over. What this exists to catch is the
     * order-of-magnitude kind — a per-100-gram figure entered as a per-serving one, or a decimal point in
     * the wrong place — which lands far outside 25%. The absolute floor stops a 12 kcal cup of tea being
     * flagged for a 4 kcal discrepancy that is arithmetically large and means nothing.
     */
    const val ENERGY_MISMATCH_FRACTION: Double = 0.25
    const val ENERGY_MISMATCH_FLOOR_KCAL: Double = 30.0

    /** True when a record's stated calories and its own macros cannot both be right. */
    fun energyLooksWrong(n: Nutrients): Boolean {
        if (n.kcal <= 0.0 || !n.kcal.isFinite()) return false
        val implied = energyFromMacros(n)
        if (implied <= 0.0) return false
        val gap = abs(n.kcal - implied)
        return gap > ENERGY_MISMATCH_FLOOR_KCAL && gap > n.kcal * ENERGY_MISMATCH_FRACTION
    }

    // ----------------------------------------------------------------------------------- wording

    /** "1,847 kcal · 132 P / 61 F / 189 C" — the one-line summary of a day. */
    fun summarise(n: Nutrients): String = String.format(
        java.util.Locale.US,
        "%,d kcal · %d P / %d F / %d C",
        n.kcal.roundToInt(), n.proteinG.roundToInt(), n.fatG.roundToInt(), n.carbG.roundToInt(),
    )
}
