package dev.mascwa.pulse.core.telemetry

import kotlin.math.min

/**
 * Every other nutrient a food record carries, beyond the eight macros and the eight micronutrients.
 *
 * ## What is here, and how the list was arrived at
 *
 * Open Food Facts publishes **123 per-100-gram nutrient columns**, counted from the real export
 * header. [NutritionDay.Nutrients] stores eight of them and [Micronutrients.Micro] another eight,
 * and — measured over a 219,989-product sample — those sixteen are almost exactly the sixteen best
 * covered. This file is the next twenty-nine, and it exists because the sixteen are the ones a
 * *label* is required to print rather than the ones a food *has*.
 *
 * ⚠️ **The first coverage pass was wrong and the correction changed the list.** Counting non-empty
 * cells is not counting figures: `vitamin-k` has 3,081 non-empty cells and **143 non-zero** ones,
 * `caffeine` 68, `choline` 10, `alcohol` 679. All four were on the list and all four are off it —
 * a row that is empty for essentially every food anybody scans is the "declared and never
 * populated" defect, and four of them would have been shipped on the strength of a bad count. The
 * list below is non-zero coverage over that sample, and [typicalPer100g] is the non-zero median.
 *
 * ⚠️ **`salt` is deliberately absent.** It and `sodium` have identical non-null counts (35,084) and,
 * probed value by value, agree on every product that carries both — 35,084 agreeing, none
 * disagreeing, none one-sided. Open Food Facts derives one from the other. They are one figure in
 * two units, and storing both would be the same number twice under two names.
 *
 * ⚠️ **`phylloquinone` and `vitamin-k` are NOT the same column in two spellings, however much they
 * look it.** Both name vitamin K1. Of the fourteen products carrying both, only 36% agree within
 * one per cent and their medians differ by **thirty times**: they are different data of unknown
 * provenance wearing similar names. `vitamin-k` is off the list anyway on the count above; the
 * finding is recorded because the reasoning that would merge them is obvious and wrong.
 *
 * ## Sparse, like the micronutrients and for the same reason
 *
 * The densest nutrient here is on 5.7% of products and most are near 2%, so an [Amounts] is a
 * **map** and a missing key is a missing measurement. There is no way to spell "zero" by accident.
 * [Micronutrients] argues this at length and this file inherits the argument; the shared operations
 * live in [SparseNutrition] so the two cannot drift apart.
 *
 * ## Not folded into [Micronutrients.Micro], and that is deliberate
 *
 * Those eight exist because each has a **reference guideline** to compare a day against —
 * `Micronutrients.reference()` and its refusals are the whole point of that file. These
 * twenty-nine have none this app can honestly state. Merging the enums would mean either inventing
 * guidelines for twenty-nine nutrients or discarding the eight real ones, so a surface that offers
 * both assembles one list from two enums and shows a "% of guideline" bar only where there is one.
 */
object NutrientSet {

    /**
     * The unit a nutrient is stored in, and the arithmetic that follows from it.
     *
     * ⚠️ [maxPer100g] is **derived** from [perGram] rather than restated. A hundred grams of food
     * cannot hold more than a hundred grams of anything, whatever unit that anything is measured in
     * — writing the bound out per unit would be the same fact three times, and the third one is
     * always the one that is wrong.
     */
    enum class Unit(
        val symbol: String,
        /** How many of this unit are in one gram. */
        val perGram: Double,
        /**
         * How many of this unit one stored integer step is worth, inverted: value x [scale] is stored.
         *
         * ⚠️ **This is the N2 trap made structural.** Vitamin D shipped for weeks stored as an
         * integer number of milligrams against a 15 µg guideline, so a fortified yogurt at 0.4 µg
         * was stored as **0** — not a rounding error, the total loss of the figure. The rule that
         * prevents it is that one stored step must be small against what the nutrient actually
         * measures, and [NutrientSetTest] asserts exactly that against [typicalPer100g] for all
         * twenty-nine, so a thirtieth cannot be added with a scale that erases it.
         */
        val scale: Int,
    ) {
        /** Grams. One step is 0.01 mg, which is a tenth of a per cent of the smallest median here. */
        GRAM("g", 1.0, 100_000),

        /** Milligrams. One step is 0.1 ng. */
        MILLIGRAM("mg", 1_000.0, 10_000),

        /** Micrograms. One step is 0.1 pg; the ceiling below is what INT_MAX allows, not physics. */
        MICROGRAM("µg", 1_000_000.0, 10_000),
        ;

        /** The most of this a hundred grams of food can hold, in this unit. */
        val maxPer100g: Double get() = 100.0 * perGram

        /**
         * The largest value the database column can hold, in stored steps.
         *
         * ⚠️ **Capped at [Int.MAX_VALUE], and the cap is not nutritional.** Room reads these columns
         * as 32-bit `Int`: SQLite will store 1.5e11 happily in a 64-bit integer and `getInt` will
         * then hand Kotlin a truncated value — garbage arriving on the phone as a small, plausible
         * figure. `build_food_db.py` says the same thing about the macro columns and this is the
         * same rule. For micrograms the cap binds first, at about 0.2 g per 100 g, which is still
         * orders of magnitude above anything edible.
         *
         * ⚠️ **The [min] is explicit rather than load-bearing, and the negative test is what
         * established that** — it reported the guard asleep, which was correct. `Double.toInt()`
         * compiles to the JVM's `d2i`, which SATURATES: run and checked, `(int) 1e12` is
         * `Integer.MAX_VALUE`, not a wrapped value. So deleting the [min] changes nothing. It stays
         * because the number this yields is load-bearing — [store] refuses against it — and a
         * reader should not have to know `d2i` semantics to see why the ceiling is what it is.
         */
        val storedCeiling: Int get() = min(maxPer100g * scale, Int.MAX_VALUE.toDouble()).toInt()
    }

    /** How the picker groups them. Ordering is the order a list should offer them in. */
    enum class Group(val label: String) {
        CARBOHYDRATE("Carbohydrates"),
        FAT("Fats"),
        MINERAL("Minerals"),
        VITAMIN("Vitamins"),
        OTHER("Other"),
    }

    /**
     * One nutrient.
     *
     * ⚠️ **[id] is explicit and permanent.** It is written into a prebuilt database asset of several
     * million rows, so it is not the ordinal: reordering this enum, or inserting a nutrient in the
     * middle, would silently re-label every value already shipped — magnesium read as phosphorus,
     * with no error anywhere. [NutrientSetTest] pins the whole name-to-id mapping so a reorder fails
     * the build. **A retired nutrient's id is never reused.**
     *
     * ⚠️ **[offField] is the source of truth for the builder too.** `tools/food/build_food_db.py`
     * reads these declarations out of this file rather than keeping its own table, which is the
     * pattern `tools/kb/ci_parity_lint.py` already uses for the guide-category allowlist. Two
     * tables of the same fact drift, and the one that drifts is always the one nobody is reading.
     * The declarations are therefore one per line with a fixed argument order — keep them that way.
     *
     * @param typicalPer100g the non-zero median over the measured sample, in [unit]. Documentation
     *   that the resolution test can act on, rather than a comment nobody can check.
     */
    enum class Nutrient(
        val id: Int,
        val label: String,
        val unit: Unit,
        val group: Group,
        val offField: String,
        val typicalPer100g: Double,
    ) {
        ADDED_SUGARS(1, "Added sugars", Unit.GRAM, Group.CARBOHYDRATE, "added-sugars", 7.89),
        STARCH(2, "Starch", Unit.GRAM, Group.CARBOHYDRATE, "starch", 14.7),
        SUCROSE(3, "Sucrose", Unit.GRAM, Group.CARBOHYDRATE, "sucrose", 1.47),
        GLUCOSE(4, "Glucose", Unit.GRAM, Group.CARBOHYDRATE, "glucose", 0.182),
        FRUCTOSE(5, "Fructose", Unit.GRAM, Group.CARBOHYDRATE, "fructose", 0.184),
        MALTOSE(6, "Maltose", Unit.GRAM, Group.CARBOHYDRATE, "maltose", 0.113),
        LACTOSE(7, "Lactose", Unit.GRAM, Group.CARBOHYDRATE, "lactose", 0.0685),
        GALACTOSE(8, "Galactose", Unit.GRAM, Group.CARBOHYDRATE, "galactose", 0.0104),
        POLYOLS(9, "Polyols", Unit.GRAM, Group.CARBOHYDRATE, "polyols", 0.112),
        MONOUNSATURATED_FAT(10, "Monounsaturated fat", Unit.GRAM, Group.FAT, "monounsaturated-fat", 3.6),
        POLYUNSATURATED_FAT(11, "Polyunsaturated fat", Unit.GRAM, Group.FAT, "polyunsaturated-fat", 2.38),
        MAGNESIUM(12, "Magnesium", Unit.MILLIGRAM, Group.MINERAL, "magnesium", 41.2),
        PHOSPHORUS(13, "Phosphorus", Unit.MILLIGRAM, Group.MINERAL, "phosphorus", 147.0),
        ZINC(14, "Zinc", Unit.MILLIGRAM, Group.MINERAL, "zinc", 1.15),
        MANGANESE(15, "Manganese", Unit.MILLIGRAM, Group.MINERAL, "manganese", 0.766),
        COPPER(16, "Copper", Unit.MILLIGRAM, Group.MINERAL, "copper", 0.176),
        IODINE(17, "Iodine", Unit.MICROGRAM, Group.MINERAL, "iodine", 9.43),
        SELENIUM(18, "Selenium", Unit.MICROGRAM, Group.MINERAL, "selenium", 6.12),
        VITAMIN_B1(19, "Thiamin (B1)", Unit.MILLIGRAM, Group.VITAMIN, "vitamin-b1", 0.12),
        VITAMIN_B2(20, "Riboflavin (B2)", Unit.MICROGRAM, Group.VITAMIN, "vitamin-b2", 51.3),
        NIACIN(21, "Niacin (B3)", Unit.MILLIGRAM, Group.VITAMIN, "vitamin-pp", 0.96),
        PANTOTHENIC_ACID(22, "Pantothenic acid (B5)", Unit.MILLIGRAM, Group.VITAMIN, "pantothenic-acid", 0.341),
        VITAMIN_B6(23, "Vitamin B6", Unit.MILLIGRAM, Group.VITAMIN, "vitamin-b6", 0.11),
        FOLATE(24, "Folate (B9)", Unit.MICROGRAM, Group.VITAMIN, "vitamin-b9", 23.8),
        VITAMIN_B12(25, "Vitamin B12", Unit.MICROGRAM, Group.VITAMIN, "vitamin-b12", 0.0861),
        VITAMIN_E(26, "Vitamin E", Unit.MILLIGRAM, Group.VITAMIN, "vitamin-e", 0.814),
        VITAMIN_K1(27, "Vitamin K1", Unit.MICROGRAM, Group.VITAMIN, "phylloquinone", 2.0),
        BETA_CAROTENE(28, "Beta-carotene", Unit.MICROGRAM, Group.VITAMIN, "beta-carotene", 7.36),
        WATER(29, "Water", Unit.GRAM, Group.OTHER, "water", 31.3),
        ;

        /** The most of this a hundred grams of food can hold, in [unit]. */
        val maxPer100g: Double get() = unit.maxPer100g
    }

    /** By stored id, or null. Null rather than a throw: an unknown id is data, not a programming error. */
    fun byId(id: Int): Nutrient? = BY_ID[id]

    /** By the Open Food Facts column stem, or null. */
    fun byOffField(field: String): Nutrient? = BY_OFF[field]

    private val BY_ID: Map<Int, Nutrient> = Nutrient.entries.associateBy { it.id }
    private val BY_OFF: Map<String, Nutrient> = Nutrient.entries.associateBy { it.offField }

    // --------------------------------------------------------------------------------- conversion

    /**
     * A figure a source published in GRAMS, in the unit this app stores [n] in.
     *
     * ⚠️ Named rather than inlined, for the reason `Micronutrients.fromGrams` gives about itself: it
     * is the kind of conversion quietly dropped in a refactor, and a dropped thousandfold makes
     * every food look free of something. Open Food Facts publishes every one of these in grams.
     *
     * ⚠️ Null in, null out. Zero is a claim that a food contains none of something, which is a
     * different fact from nobody having measured it.
     */
    fun fromGrams(n: Nutrient, grams: Double?): Double? {
        val g = grams ?: return null
        if (!g.isFinite() || g < 0.0) return null
        return g * n.unit.perGram
    }

    /**
     * A figure in [n]'s own unit, or null if it cannot be believed.
     *
     * ⚠️ Bounded by **physics, not nutrition**, exactly as `FoodPortion.MAX_MASS_G_PER_100G` and the
     * builder's own ceilings are. A tighter bound is an opinion that can be wrong about a real food;
     * what is unarguable is that a constituent cannot outweigh the food it is in, and that alone
     * catches the whole thousandfold unit-error family — which is where the wrong numbers come from.
     * The sample's own maxima say why it is needed: added sugars reaching **3,000 g per 100 g**,
     * starch 4,000, monounsaturated fat 510.
     */
    fun sane(n: Nutrient, value: Double?): Double? {
        val v = value ?: return null
        if (!v.isFinite() || v < 0.0) return null
        if (v > n.maxPer100g) return null
        return v
    }

    /**
     * A whole record with the unbelievable figures dropped.
     *
     * ⚠️ **Dropped, not clamped, and the parser boundary is where this belongs.** A record claiming
     * three kilograms of added sugar in a hundred grams is not a record that needs the number
     * shaving down to a hundred — nothing about it can be believed, so the honest thing is that the
     * food has no added-sugar figure at all, which the map expresses and a clamped number cannot.
     *
     * The sibling for the eight micronutrients is `FoodPortion.saneMicros`, and the two exist for
     * the same reason: every path a food takes into this app converts through one boundary
     * function, so a source that publishes nonsense cannot reach the log however it arrives.
     */
    fun sane(amounts: Amounts): Amounts {
        if (amounts.isEmpty) return amounts
        val kept = amounts.values.mapNotNull { (n, v) -> sane(n, v)?.let { n to it } }
        return if (kept.size == amounts.values.size) amounts else Amounts(kept.toMap())
    }

    /** A believable figure as the integer the database stores, or null. */
    fun store(n: Nutrient, value: Double?): Int? {
        val v = sane(n, value) ?: return null
        val scaled = Math.round(v * n.unit.scale)
        if (scaled > n.unit.storedCeiling) return null
        return scaled.toInt()
    }

    /** A stored integer back in [n]'s own unit. */
    fun read(n: Nutrient, stored: Int): Double = stored.toDouble() / n.unit.scale

    // ------------------------------------------------------------------------------- what a food has

    /**
     * What one food records, per whatever quantity the caller is talking about.
     *
     * ⚠️ A key that is not here was never measured. Callers must render that as "not recorded" and
     * never as a number.
     */
    @JvmInline
    value class Amounts(val values: Map<Nutrient, Double> = emptyMap()) {

        operator fun get(n: Nutrient): Double? = values[n]

        val isEmpty: Boolean get() = values.isEmpty()

        /** Scale a per-100-gram record to the portion eaten. Absences stay absent. */
        fun scaled(factor: Double): Amounts = Amounts(SparseNutrition.scale(values, factor))

        /** Two records added — an ingredient onto a running total. The union, never the intersection. */
        operator fun plus(other: Amounts): Amounts =
            Amounts(SparseNutrition.merge(values, other.values))
    }

    /**
     * A day's extra nutrients, and how much of the day they were drawn from.
     *
     * [entries] counts every logged food, whether or not it reported anything — that is the
     * denominator the coverage fraction needs.
     */
    data class Day(
        val tallies: Map<Nutrient, NutrientTally> = emptyMap(),
        val entries: Int = 0,
    ) {
        operator fun get(n: Nutrient): NutrientTally? = tallies[n]

        /**
         * The share of today's foods that recorded [n], or null if nothing was logged.
         *
         * ⚠️ Null and 0.0 differ: nothing logged is not the same as nothing reported.
         */
        fun coverage(n: Nutrient): Double? {
            if (entries <= 0) return null
            return (tallies[n]?.reported ?: 0).toDouble() / entries
        }

        /**
         * Plain English for how well founded a figure is, or null when it needs no caveat.
         *
         * ⚠️ The threshold is [Micronutrients.WELL_COVERED], shared rather than restated — two
         * screens in one app disagreeing about when a total is trustworthy would be worse than
         * either rule alone.
         */
        fun caveat(n: Nutrient): String? {
            if (entries <= 0) return null
            val reported = tallies[n]?.reported ?: 0
            if (reported == 0) return "None of today's food records this."
            if (reported.toDouble() / entries >= Micronutrients.WELL_COVERED) return null
            return "From $reported of $entries foods — the rest do not record it."
        }
    }

    /**
     * Add one eaten portion to a day.
     *
     * ⚠️ [Day.entries] rises whatever [amounts] holds, **including nothing at all**. A food that
     * reported none of these is still a food that was eaten, and leaving it out of the denominator
     * would report perfect coverage for a day mostly made of records that say nothing.
     */
    fun add(day: Day, amounts: Amounts): Day =
        Day(SparseNutrition.tally(day.tallies, amounts.values), day.entries + 1)

    /** A whole day, folded from what each portion recorded. */
    fun of(portions: List<Amounts>): Day = portions.fold(Day()) { d, a -> add(d, a) }
}
