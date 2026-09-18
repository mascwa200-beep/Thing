package dev.mascwa.pulse.core.telemetry

import kotlin.math.roundToInt

/**
 * The vitamins and minerals a food record may carry, and how far a day's eating got through them.
 *
 * ## ⚠️ Absent is not zero, and that is the whole reason this is a separate type
 *
 * [NutritionDay.Nutrients] holds eight non-nullable doubles, which is right for the macros: a food
 * with no fat really does have no fat, and every source publishes all four. Micronutrients are the
 * opposite. Measured over the whole bundled corpus of 4,524,449 products — 2,582,583 of which carry
 * any nutrition at all — this is how often each figure is actually recorded:
 *
 * | | share of products carrying nutrition |
 * |---|---|
 * | calcium | 25.9% |
 * | iron | 26.1% |
 * | cholesterol | 26.0% |
 * | potassium | 19.8% |
 * | vitamin C | 18.1% |
 * | trans fat | 16.7% |
 * | vitamin D | 12.6% |
 * | vitamin A | 11.5% |
 *
 * So roughly three records in four say nothing about calcium. Stored as a double defaulting to 0.0,
 * a glass of milk from such a record would read **"0 mg calcium"** — a false statement about a food,
 * printed with the same confidence as a measured one. Worse at the day level: a total summed from
 * six foods of which one reported calcium would be displayed as *the day's calcium*, silently an
 * undercount presented as a fact. That is the shape this app has corrected four times already — an
 * economic figure with no vintage, a safety feed with no coverage, a market with no session, a
 * widget that could not say why it was empty.
 *
 * Hence: an [Amounts] is a **map**, and a missing key is a missing measurement. There is no way to
 * spell "zero" by accident, because absence is the absence of an entry rather than a value that
 * happens to be zero.
 *
 * ## And a day says how much of itself it actually measured
 *
 * [NutrientTally] carries `reported` alongside `total`, so the surface can say "310 mg, from 2 of
 * your 6 foods" instead of implying the day is fully accounted for. [Day.coverage] is that fraction.
 *
 * ## The rules live in [SparseNutrition] now
 *
 * They were worked out here and they are argued here, but [NutrientSet] needs every one of them
 * again for twenty-nine further nutrients — so scaling, the union add, and the tally fold moved to
 * one generic place and both callers are thin. Nothing about the behaviour changed; what changed is
 * that there is no longer a second copy of it to drift.
 */
object Micronutrients {

    /**
     * The eight figures both bundled sources publish.
     *
     * ⚠️ The list is exactly what the corpus carries — no more. A ninth entry with nothing behind it
     * would be a row on a screen that is empty for every food anybody ever scans, which is the
     * "declared and never populated" defect this project keeps correcting.
     */
    enum class Micro(val label: String, val unit: String) {
        CALCIUM("Calcium", "mg"),
        IRON("Iron", "mg"),
        POTASSIUM("Potassium", "mg"),
        VITAMIN_A("Vitamin A", "µg"),
        VITAMIN_C("Vitamin C", "mg"),
        VITAMIN_D("Vitamin D", "µg"),
        CHOLESTEROL("Cholesterol", "mg"),
        TRANS_FAT("Trans fat", "g"),
    }

    /**
     * How many of [m]'s own units are in one gram, or null if its unit is not one this understands.
     *
     * ⚠️ **The single derivation two separate rules rest on**, so neither can be right while the
     * other is wrong: `FoodPortion.maxPer100g` bounds a figure by the weight of the food, and
     * [fromGrams] converts a source that publishes in grams. Written as a table of eight entries
     * instead, it would be two tables — and the one that gets forgotten is always the second.
     *
     * ⚠️ Null rather than a default, and the null is load-bearing: silently assuming grams for an
     * unrecognised unit would admit a figure a thousand times too small AND bound it a thousand times
     * too loosely, in one stroke. `FoodPortionTest` fails the build if any declared micronutrient
     * lands here, so a ninth entry cannot introduce one quietly.
     */
    fun perGram(m: Micro): Double? = when (m.unit) {
        "g" -> 1.0
        "mg" -> 1_000.0
        "µg" -> 1_000_000.0
        else -> null
    }

    /**
     * A figure a source published in GRAMS, in the unit this app stores [m] in.
     *
     * ⚠️ Named rather than inlined at the call site, for exactly the reason
     * `FoodPortion.sodiumMgFromGrams` gives about itself: it is the kind of conversion that gets
     * quietly dropped in a refactor, and a dropped thousandfold makes every food look free of
     * something. Open Food Facts publishes all eight of these in grams — probed against the live API,
     * where the `_unit` field beside each reads "g" — so this is the whole of what that path needs.
     *
     * ⚠️ Null in, null out. Zero is a claim that a food contains none of something, which is a
     * different fact from nobody having measured it.
     */
    fun fromGrams(m: Micro, grams: Double?): Double? {
        val g = grams ?: return null
        if (!g.isFinite() || g < 0.0) return null
        return perGram(m)?.let { g * it }
    }

    /**
     * What one food records, per whatever quantity the caller is talking about.
     *
     * ⚠️ A key that is not here was never measured. Callers must render that as "not recorded" and
     * never as a number.
     */
    @JvmInline
    value class Amounts(val values: Map<Micro, Double> = emptyMap()) {

        operator fun get(m: Micro): Double? = values[m]

        val isEmpty: Boolean get() = values.isEmpty()

        /** Scale a per-100-gram record to the portion eaten. Absences stay absent. */
        fun scaled(factor: Double): Amounts = Amounts(SparseNutrition.scale(values, factor))

        /**
         * Two records added — an ingredient onto a running total.
         *
         * ⚠️ **The union, not the intersection, and absent stays absent on both sides.** If one
         * ingredient records calcium and another does not, the sum is the one figure there is, and
         * [Day.coverage] is what says how much of the dish that figure was drawn from. Treating the
         * silent ingredient as zero would understate the total; refusing to add at all would report
         * nothing for a dish that partly knows.
         */
        operator fun plus(other: Amounts): Amounts =
            Amounts(SparseNutrition.merge(values, other.values))
    }

    /**
     * A day's micronutrients, and how much of the day they were drawn from.
     *
     * [entries] counts every logged food, whether or not it reported anything — that is the
     * denominator the coverage fraction needs.
     */
    data class Day(val tallies: Map<Micro, NutrientTally> = emptyMap(), val entries: Int = 0) {

        operator fun get(m: Micro): NutrientTally? = tallies[m]

        /**
         * The share of today's foods that recorded [m], 0.0..1.0, or null if nothing was logged.
         *
         * ⚠️ Null and 0.0 differ: nothing logged is not the same as nothing reported.
         */
        fun coverage(m: Micro): Double? {
            if (entries <= 0) return null
            return (tallies[m]?.reported ?: 0).toDouble() / entries
        }

        /**
         * Plain English for how well founded a figure is, or null when it needs no caveat.
         *
         * ⚠️ Silent above [WELL_COVERED] on purpose. A caveat printed on every row is a caveat
         * nobody reads, and the honest reading of "6 of 6 foods reported it" is simply the number.
         */
        fun caveat(m: Micro): String? {
            if (entries <= 0) return null
            val reported = tallies[m]?.reported ?: 0
            if (reported == 0) return "None of today's food records this."
            val share = reported.toDouble() / entries
            if (share >= WELL_COVERED) return null
            return "From $reported of ${entries} foods — the rest do not record it."
        }
    }

    /** Above this share of a day's foods reporting a figure, the figure stands without a caveat. */
    const val WELL_COVERED = 0.8

    /**
     * Add one eaten portion to a day.
     *
     * ⚠️ [entries] rises whatever [amounts] holds, **including nothing at all**. A food that
     * reported no micronutrients is still a food that was eaten, and leaving it out of the
     * denominator would report perfect coverage for a day mostly made of records that say nothing.
     */
    fun add(day: Day, amounts: Amounts): Day =
        Day(SparseNutrition.tally(day.tallies, amounts.values), day.entries + 1)

    /** A whole day from its portions, in one call. */
    fun of(portions: List<Amounts>): Day = portions.fold(Day()) { d, a -> add(d, a) }

    // ------------------------------------------------------------------- reference intakes

    /**
     * Whether a reference intake can be stated at all, and if not, why not.
     *
     * ⚠️ Two of the eight have **no number in current guidance**, and that is a finding rather than
     * a gap in this file — see [note].
     */
    sealed interface Reference {
        /** A figure that can honestly be compared against. */
        data class Amount(val guide: NutrientGuides.Guide) : Reference

        /** No number exists or applies. [why] is shown in its place. */
        data class None(val why: String) : Reference
    }

    /** Below this the adult reference intakes do not apply. */
    const val ADULT_FROM = 19

    /**
     * The usual reference intake for [m], for an adult of this [sex] and [ageYears].
     *
     * ⚠️ **Population guidance, in the register this app already uses for the macros**: what
     * public-health bodies publish for adults in general, never a prescription, and it knows nothing
     * about anyone's medicine, pregnancy or kidneys.
     *
     * ⚠️ **An unstated sex takes the HIGHER of the two adult figures, and that is safe here only
     * because every figure this returns is a TARGET rather than a limit.** Aiming at 18 mg of iron
     * when 8 would do costs nothing when the source of it is food; the reverse — quietly halving
     * somebody's target — would be a real disservice. The basis line says which figure was used and
     * why, so a reader who states their sex will see the number move for a reason.
     *
     * ⚠️ Age is a real gate, not decoration: every figure here is written for adults, and applying
     * an adult's calcium target to a twelve-year-old is misapplying it. An unstated birth year is
     * treated exactly as a child's, the same rule [NutrientGuides] already uses for sodium.
     */
    fun reference(m: Micro, sex: Body.Sex, ageYears: Int): Reference {
        when (m) {
            Micro.CHOLESTEROL -> return Reference.None(CHOLESTEROL_NOTE)
            Micro.TRANS_FAT -> return Reference.None(TRANS_FAT_NOTE)
            else -> Unit
        }
        if (ageYears < ADULT_FROM) {
            return Reference.None(
                "These reference intakes are the ones published for adults. Add a birth year in " +
                    "the profile to see them."
            )
        }
        val stated = sex != Body.Sex.UNSPECIFIED
        val older = ageYears >= 51

        // Each figure as (for men, for women). ⚠️ Kept as a pair even where the two are equal, so
        // the basis line below can tell "there was a choice and it was made" from "there was none".
        val (forMen, forWomen, source) = when (m) {
            // 1,000 mg for adults; 1,200 from 51 for women and from 71 for men.
            Micro.CALCIUM -> Triple(
                if (ageYears >= 71) 1200.0 else 1000.0,
                if (older) 1200.0 else 1000.0,
                "Dietary Reference Intakes, adults",
            )
            // ⚠️ The widest gap of the eight — more than double before 51 — which is exactly why an
            // unstated profile has to say which figure it used rather than quietly pick one.
            Micro.IRON -> Triple(8.0, if (older) 8.0 else 18.0, "Dietary Reference Intakes, adults")
            Micro.POTASSIUM -> Triple(3400.0, 2600.0, "Adequate Intake, adults")
            Micro.VITAMIN_A -> Triple(
                900.0, 700.0,
                "Dietary Reference Intakes, adults (retinol activity equivalents)",
            )
            Micro.VITAMIN_C -> Triple(90.0, 75.0, "Dietary Reference Intakes, adults")
            Micro.VITAMIN_D -> {
                val v = if (ageYears >= 71) 20.0 else 15.0
                Triple(v, v, "Dietary Reference Intakes, adults")
            }
            else -> return Reference.None("No reference intake is published for this.")
        }
        val amount = when {
            sex == Body.Sex.FEMALE -> forWomen
            sex == Body.Sex.MALE -> forMen
            else -> maxOf(forMen, forWomen)
        }
        // ⚠️ Only claims to have chosen when the two figures actually differ. Saying "the higher of
        // the two" where both are 15 µg describes a decision that was never made, which reads as a
        // fault in the app rather than as care.
        val basis = if (stated || forMen == forWomen) {
            "The usual reference for an adult."
        } else {
            "The higher of the two adult references, because the profile does not say which applies."
        }
        return Reference.Amount(
            NutrientGuides.Guide(
                label = m.label,
                amount = amount,
                unit = m.unit,
                kind = NutrientGuides.Kind.TARGET,
                basis = basis,
                source = source,
            )
        )
    }

    /**
     * ⚠️ **There is deliberately no cholesterol figure, and it is not an omission.**
     *
     * The 300 mg-a-day ceiling most people remember was **removed** from the US Dietary Guidelines
     * in 2015, on the finding that dietary cholesterol is a poor predictor of blood cholesterol for
     * most people. What replaced it is advice with no number in it. Printing 300 mg here would be
     * quoting a guideline that no longer exists, which is worse than printing nothing.
     */
    const val CHOLESTEROL_NOTE =
        "There is no daily figure to compare against: the 300 mg ceiling was withdrawn in 2015. " +
            "Current advice is to keep it low as part of an overall pattern of eating, without a number."

    /**
     * ⚠️ **No trans-fat figure either, and for the opposite reason: the guidance is stricter than a
     * number.**
     *
     * The WHO position is elimination of industrially-produced trans fat, not a daily allowance.
     * A budget on screen reads as permission to spend it.
     */
    const val TRANS_FAT_NOTE =
        "There is no daily allowance for this. The guidance is to keep industrially-produced " +
            "trans fat out of food altogether rather than to stay under a figure."

    /** "310 of 1,000 mg" — a whole comparison in the width of a row. */
    fun readout(m: Micro, eaten: Double, guide: NutrientGuides.Guide?): String {
        val e = if (eaten >= 100.0) eaten.roundToInt().toString() else trim(eaten)
        return if (guide == null) "$e ${m.unit}"
        else "$e of ${if (guide.amount >= 100.0) guide.amount.roundToInt() else guide.amount} ${m.unit}"
    }

    private fun trim(v: Double): String {
        val r = (v * 10.0).roundToInt() / 10.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }
}
