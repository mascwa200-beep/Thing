package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How much to eat, and the limits on what this is allowed to tell somebody.
 *
 * The arithmetic is two lines. A calorie target is expenditure plus the energy the goal says to bank or
 * spend each day; the macros split that target up. What takes the rest of this file is the part that
 * matters more: **this is the one piece of the app with direct physical consequence**, so every limit is
 * enforced here, in a tested core, rather than in a screen where it can be forgotten.
 *
 * ```
 * kcal      = expenditure + rate_kg_per_week × KCAL_PER_KG / 7      // rate is SIGNED, negative to lose
 * protein_g = g_per_kg × reference_mass, clamped
 * fat_g     = the mode's share, never below the essential-fat floor
 * carb_g    = whatever the calories have left, never below zero
 * ```
 *
 * ⚠️ The rate is signed and the operation is **addition**, which reads wrong at a glance and is right.
 * Losing half a kilogram a week is `rate = −0.5`, so the second term is −550 and the target is 550 under
 * expenditure. Writing it as a subtraction of a positive "loss rate" is the same arithmetic and one
 * refactor away from a sign error that tells somebody in a deficit to eat more.
 *
 * ## The guardrails, and why each one is a refusal rather than a clamp
 *
 * A silently clamped number is indistinguishable from a number the app meant. Every limit below either
 * returns [Plan.Refused] or records an [Adjustment] that the surface is expected to print, so a person
 * who asks for something unsafe is told what happened instead of being handed a quietly different plan.
 *
 * | limit | why |
 * |---|---|
 * | never below the resting rate ([Body.bmr]) | eating under your own resting requirement is the line clinical guidance draws |
 * | never below [ABSOLUTE_FLOOR_KCAL] | a floor that survives a bad height or a bad age |
 * | loss capped at [MAX_LOSS_FRACTION_PER_WEEK] of bodyweight | faster costs lean mass and is not kept off |
 * | gain capped at [MAX_GAIN_FRACTION_PER_WEEK] | faster is mostly fat |
 * | refuse a goal in the underweight band | the app must not help somebody get there |
 * | refuse under [Body.MIN_AGE_YEARS] | none of these formulas describes a growing body |
 * | carbohydrate floored at zero | the subtraction above genuinely can go negative, and a negative gram target would render |
 *
 * And the invariant that keeps the surface truthful: **the returned calories are recomputed from the
 * returned grams**, so `kcal == 4·protein + 9·fat + 4·carb` exactly. A calorie ring and three macro
 * rings that disagree with each other are worse than either alone.
 *
 * ⚠️ [DISCLAIMER] is not decoration and is expected on screen wherever a target is.
 */
object MacroTargets {

    const val DISCLAIMER: String =
        "General information, not medical advice. Talk to a doctor or a dietitian before changing how " +
            "you eat, especially with any medical condition, a history of disordered eating, or if you " +
            "are pregnant or breastfeeding."

    // ------------------------------------------------------------------------------------ tuning

    /**
     * The lowest calorie target this will ever produce, whatever the formulas say.
     *
     * ⚠️ A second floor underneath the resting-rate one, and it exists because [Body.bmr] is only as
     * good as the height, age and mass it was given. A mistyped height of 100 cm produces a resting rate
     * low enough to be dangerous as a floor; this catches it.
     */
    const val ABSOLUTE_FLOOR_KCAL: Double = 1200.0

    /** Weekly loss ceiling as a fraction of bodyweight — the common evidence-based limit. */
    const val MAX_LOSS_FRACTION_PER_WEEK: Double = 0.01

    /** And an absolute one on top, so a very heavy person is not handed a three-kilogram-a-week plan. */
    const val MAX_LOSS_KG_PER_WEEK: Double = 1.5

    /** Weekly gain ceiling. Half the loss limit: surplus above this is mostly fat. */
    const val MAX_GAIN_FRACTION_PER_WEEK: Double = 0.005
    const val MAX_GAIN_KG_PER_WEEK: Double = 0.5

    const val PROTEIN_MIN_G_PER_KG: Double = 1.2
    const val PROTEIN_MAX_G_PER_KG: Double = 3.0

    /** Protein above this share of the day is hard to eat and buys nothing. */
    const val PROTEIN_MAX_KCAL_FRACTION: Double = 0.40

    /** Essential fatty acids and fat-soluble vitamins. Both floors apply; the higher wins. */
    const val FAT_MIN_G_PER_KG: Double = 0.5
    const val FAT_MIN_KCAL_FRACTION: Double = 0.15

    /** What a ketogenic plan pins carbohydrate at, in grams. */
    const val KETO_CARB_G: Double = 30.0

    const val KCAL_PER_G_PROTEIN: Int = 4
    const val KCAL_PER_G_FAT: Int = 9
    const val KCAL_PER_G_CARB: Int = 4

    // ------------------------------------------------------------------------------------- types

    /**
     * How the calories are divided. Every mode sets the same protein-first, floors-respected split; they
     * differ only in what they do with what is left.
     */
    enum class DietMode(
        val label: String,
        val proteinGPerKg: Double,
        val fatKcalFraction: Double?,
        val carbKcalFraction: Double?,
        val fixedCarbG: Double?,
    ) {
        BALANCED("Balanced", 1.8, 0.28, null, null),
        LOWER_FAT("Lower fat", 1.8, 0.20, null, null),
        LOWER_CARB("Lower carb", 2.0, null, 0.20, null),
        KETO("Ketogenic", 1.6, null, null, KETO_CARB_G),
        HIGH_PROTEIN("High protein", 2.4, 0.28, null, null),
    }

    data class Request(
        val person: Body.Person,
        val expenditure: Expenditure.Estimate,
        /** Signed, kilograms per week. Negative loses, zero maintains, positive gains. */
        val ratePerWeekKg: Double,
        val mode: DietMode = DietMode.BALANCED,
        /** Overrides the mode's protein, in grams per kilogram of reference mass. */
        val proteinGPerKgOverride: Double? = null,
        /** Where the person is heading, if anywhere. Only used for the underweight refusal and the estimate. */
        val goalKg: Double? = null,
    )

    /**
     * Whether exceeding one of the four numbers is a problem.
     *
     * ⚠️ **Not a matter of taste, and the planner's own behaviour is the evidence.** [plan] raises
     * protein when it falls below [PROTEIN_MIN_G_PER_KG] and raises fat when it falls below the fat
     * floor — that is what [AdjustmentKind.PROTEIN_RAISED] and [AdjustmentKind.FAT_RAISED] record.
     * A number the planner pushes a person UP to is a floor, and eating past a floor is the point of
     * it rather than a failure. Calories are the one genuinely binding budget; carbohydrate is the
     * remainder, so going past it means the calories went somewhere, and calories already say that.
     *
     * ⚠️ This exists because both surfaces had the same root defect in opposite directions, neither
     * of which is visible without asking this question. The LCARS macro tiles painted exceeding the
     * protein target in the palette's `negative` — the colour that file otherwise uses only for
     * REMOVE, ✕ and DELETE — so hitting more than your protein floor was rendered as a fault. The
     * standalone progress bar clamps its fill at one, so six hundred calories over drew exactly the
     * same full bar as landing on the target.
     *
     * ⚠️ And it matters beyond looks, which is why this is not cosmetic. This app measures
     * expenditure FROM what you log. A day somebody eats over and does not log is a hole in the
     * twenty-eight-day window that degrades the estimate for four weeks. A surface that treats an
     * honest over-target day as an error is working against the app's own measurement.
     */
    enum class Bound {
        /** A floor to reach. Going past it is not a fault, and is usually the intention. */
        FLOOR,

        /** A budget. Going past it is worth noticing — which is not the same as failing. */
        BUDGET,
    }

    /** The four numbers a day is read against, and which way each one binds. */
    enum class Macro(val bound: Bound) {
        CALORIES(Bound.BUDGET),
        PROTEIN(Bound.FLOOR),
        FAT(Bound.FLOOR),
        CARBS(Bound.BUDGET),
    }

    data class Targets(
        val kcal: Int,
        val proteinG: Int,
        val fatG: Int,
        val carbG: Int,
    ) {
        val proteinKcal: Int get() = proteinG * KCAL_PER_G_PROTEIN
        val fatKcal: Int get() = fatG * KCAL_PER_G_FAT
        val carbKcal: Int get() = carbG * KCAL_PER_G_CARB
        fun share(part: Int): Double = if (kcal <= 0) 0.0 else part.toDouble() / kcal
    }

    enum class AdjustmentKind {
        RATE_CAPPED,
        KCAL_RAISED_TO_RESTING_RATE,
        KCAL_RAISED_TO_FLOOR,
        PROTEIN_CAPPED,
        PROTEIN_RAISED,
        FAT_RAISED,
        CARBS_FLOORED,
        EXPENDITURE_PROVISIONAL,
    }

    /** A limit that bit. Both halves matter: [kind] so a test can assert it, [sentence] so a person is told. */
    data class Adjustment(val kind: AdjustmentKind, val sentence: String)

    enum class RefusalKind {
        UNDER_EIGHTEEN,
        IMPLAUSIBLE_BODY,
        NO_EXPENDITURE,
        GOAL_UNDERWEIGHT,
        ALREADY_UNDERWEIGHT,
    }

    sealed interface Plan {
        data class Set(
            val targets: Targets,
            val adjustments: List<Adjustment>,
            /** What the returned calories will actually do, recomputed from them. Never the requested rate. */
            val effectiveRatePerWeekKg: Double,
            val expenditureKcal: Double,
        ) : Plan {
            val capped: Boolean get() = adjustments.isNotEmpty()
        }

        data class Refused(val kind: RefusalKind, val sentence: String) : Plan
    }

    // -------------------------------------------------------------------------------------- caps

    /** The fastest this person may be told to lose, per week, as a positive number of kilograms. */
    fun maxLossPerWeekKg(kg: Double): Double = min(kg * MAX_LOSS_FRACTION_PER_WEEK, MAX_LOSS_KG_PER_WEEK)

    /** The fastest this person may be told to gain, per week. */
    fun maxGainPerWeekKg(kg: Double): Double = min(kg * MAX_GAIN_FRACTION_PER_WEEK, MAX_GAIN_KG_PER_WEEK)

    // -------------------------------------------------------------------------------------- plan

    fun plan(request: Request): Plan {
        val p = request.person
        if (p.ageYears < Body.MIN_AGE_YEARS) {
            return Plan.Refused(
                RefusalKind.UNDER_EIGHTEEN,
                "This only works for adults — the formulas behind it are fitted on grown bodies and a " +
                    "growing one needs a professional, not an app.",
            )
        }
        if (!Body.isPlausible(p)) {
            return Plan.Refused(
                RefusalKind.IMPLAUSIBLE_BODY,
                "Check the height, weight and age — one of them is outside what these formulas can describe.",
            )
        }

        val expenditure = request.expenditure as? Expenditure.Estimate.Known
            ?: return Plan.Refused(
                RefusalKind.NO_EXPENDITURE,
                Expenditure.sentence(request.expenditure),
            )

        val bmiNow = Body.bmi(p.kg, p.heightCm)
        val goal = request.goalKg
        if (goal != null && goal.isFinite() && Body.bmi(goal, p.heightCm) < Body.BMI_UNDERWEIGHT) {
            return Plan.Refused(
                RefusalKind.GOAL_UNDERWEIGHT,
                "That goal weight is in the underweight range for your height. This will not plan a way " +
                    "there. If it feels like the right target, that is worth talking to a doctor about.",
            )
        }
        if (bmiNow < Body.BMI_UNDERWEIGHT && request.ratePerWeekKg < 0.0) {
            return Plan.Refused(
                RefusalKind.ALREADY_UNDERWEIGHT,
                "You are already in the underweight range for your height, so this will not plan a loss.",
            )
        }

        val adjustments = mutableListOf<Adjustment>()
        if (expenditure.source != Expenditure.Source.MEASURED) {
            adjustments += Adjustment(
                AdjustmentKind.EXPENDITURE_PROVISIONAL,
                "These targets still lean on the formula estimate. Keep logging and weighing — they will " +
                    "settle onto what you actually burn.",
            )
        }

        // ---- rate
        var rate = if (request.ratePerWeekKg.isFinite()) request.ratePerWeekKg else 0.0
        val maxLoss = maxLossPerWeekKg(p.kg)
        val maxGain = maxGainPerWeekKg(p.kg)
        if (rate < -maxLoss) {
            rate = -maxLoss
            adjustments += Adjustment(
                AdjustmentKind.RATE_CAPPED,
                "Held to ${fmt2(maxLoss)} kg a week. Faster than about one per cent of bodyweight costs " +
                    "muscle rather than fat, and it does not stay off.",
            )
        } else if (rate > maxGain) {
            rate = maxGain
            adjustments += Adjustment(
                AdjustmentKind.RATE_CAPPED,
                "Held to ${fmt2(maxGain)} kg a week. Gaining faster is mostly fat.",
            )
        }

        // ---- calories
        val bmr = Body.bmr(p)
        var kcal = expenditure.kcal + rate * Expenditure.KCAL_PER_KG / 7.0
        if (bmr.isFinite() && kcal < bmr) {
            kcal = bmr
            adjustments += Adjustment(
                AdjustmentKind.KCAL_RAISED_TO_RESTING_RATE,
                "Raised to your resting requirement of ${Expenditure.round50(bmr)}. Eating under what your " +
                    "body needs at rest is where this stops being a diet.",
            )
        }
        if (kcal < ABSOLUTE_FLOOR_KCAL) {
            kcal = ABSOLUTE_FLOOR_KCAL
            adjustments += Adjustment(
                AdjustmentKind.KCAL_RAISED_TO_FLOOR,
                "Raised to the ${Expenditure.round50(ABSOLUTE_FLOOR_KCAL)} floor this will not go below.",
            )
        }

        // ---- protein, against a reference mass rather than the scale
        //
        // ⚠️ Grams per kilogram of *current* weight over-prescribes badly for anyone carrying a lot of
        // fat: protein requirement tracks lean mass, and fat mass needs none. Capping the reference at
        // the top of the healthy BMI range is the standard practical stand-in for lean mass when no body
        // composition measurement exists.
        val referenceKg = min(p.kg, Body.kgAtBmi(Body.BMI_HEALTHY_MAX, p.heightCm))
        val askedGPerKg = request.proteinGPerKgOverride ?: request.mode.proteinGPerKg
        var gPerKg = askedGPerKg
        if (gPerKg < PROTEIN_MIN_G_PER_KG) {
            gPerKg = PROTEIN_MIN_G_PER_KG
            adjustments += Adjustment(
                AdjustmentKind.PROTEIN_RAISED,
                "Protein raised to ${fmt1(PROTEIN_MIN_G_PER_KG)} g per kg — below that you lose muscle in a deficit.",
            )
        } else if (gPerKg > PROTEIN_MAX_G_PER_KG) {
            gPerKg = PROTEIN_MAX_G_PER_KG
            adjustments += Adjustment(
                AdjustmentKind.PROTEIN_CAPPED,
                "Protein held at ${fmt1(PROTEIN_MAX_G_PER_KG)} g per kg. More has no measured benefit.",
            )
        }
        var protein = gPerKg * referenceKg
        val proteinCeiling = kcal * PROTEIN_MAX_KCAL_FRACTION / KCAL_PER_G_PROTEIN
        if (protein > proteinCeiling) {
            protein = proteinCeiling
            adjustments += Adjustment(
                AdjustmentKind.PROTEIN_CAPPED,
                "Protein held to ${(PROTEIN_MAX_KCAL_FRACTION * 100).roundToInt()}% of the day — any more " +
                    "leaves too little room for everything else.",
            )
        }

        // ---- fat, per the mode, then the essential floor
        val mode = request.mode
        var fat = when {
            mode.fatKcalFraction != null -> kcal * mode.fatKcalFraction / KCAL_PER_G_FAT
            mode.fixedCarbG != null ->
                (kcal - protein * KCAL_PER_G_PROTEIN - mode.fixedCarbG * KCAL_PER_G_CARB) / KCAL_PER_G_FAT
            mode.carbKcalFraction != null ->
                (kcal * (1.0 - mode.carbKcalFraction) - protein * KCAL_PER_G_PROTEIN) / KCAL_PER_G_FAT
            else -> kcal * 0.28 / KCAL_PER_G_FAT
        }
        val fatFloor = maxOf(FAT_MIN_G_PER_KG * referenceKg, kcal * FAT_MIN_KCAL_FRACTION / KCAL_PER_G_FAT)
        if (fat < fatFloor) {
            fat = fatFloor
            adjustments += Adjustment(
                AdjustmentKind.FAT_RAISED,
                "Fat raised to ${fat.roundToInt()} g. Below roughly that you start missing essential fats " +
                    "and the vitamins that travel with them.",
            )
        }

        // ---- carbohydrate is what is left, and it can genuinely be negative
        //
        // ⚠️ Computed from the ROUNDED protein and fat, and rounded UP. Both halves are guardrails
        // rather than rounding preferences, because the calories below are recomputed from the grams:
        // take the remainder from the unrounded macros, or round it to nearest, and the total can land
        // *under* the target — and when the target is the floor this file promises never to go below,
        // "1,197 calories" is a broken promise. Measured before this: a 50 kg person at the 1,200 floor
        // came out at 1,197. Deriving the remainder from what will actually be shown, and rounding it
        // up, can only overshoot, and only ever by three calories.
        val pG = protein.roundToInt().coerceAtLeast(0)
        val fG = fat.roundToInt().coerceAtLeast(0)
        val remainder = kcal - pG * KCAL_PER_G_PROTEIN - fG * KCAL_PER_G_FAT
        var cG = ceil(remainder / KCAL_PER_G_CARB - 1e-9).toInt()
        if (cG < 0) {
            cG = 0
            adjustments += Adjustment(
                AdjustmentKind.CARBS_FLOORED,
                "The protein and fat floors already use the whole day, so carbohydrate is zero and the " +
                    "calorie target has moved up to match them.",
            )
        }
        // ⚠️ The invariant: the calories are the grams, not the other way round. Four rings that add up.
        val finalKcal = pG * KCAL_PER_G_PROTEIN + fG * KCAL_PER_G_FAT + cG * KCAL_PER_G_CARB

        val effective = (finalKcal - expenditure.kcal) * 7.0 / Expenditure.KCAL_PER_KG
        return Plan.Set(
            targets = Targets(finalKcal, pG, fG, cG),
            adjustments = adjustments,
            effectiveRatePerWeekKg = effective,
            expenditureKcal = expenditure.kcal,
        )
    }

    // ----------------------------------------------------------------------------------- reading

    /**
     * Weeks from here to the goal at a given weekly rate, or null when the rate does not go there.
     *
     * ⚠️ Null rather than a negative or infinite number: "−14 weeks" would render, and a caller that
     * forgot to check would print it.
     *
     * ⚠️ **Not the same question as [GoalProjection.project], and the difference is which rate goes
     * in.** This takes the rate the PLAN asks for, so it answers "if this plan works, how long is
     * it" — a property of the plan, and the reason it belongs beside one. [GoalProjection] takes the
     * rate the scale is actually showing, with its interval, so it answers "at the pace you are
     * really moving, when do you arrive" — a property of the measurement. Both are worth having and
     * the gap between them is the interesting part; what would be a defect is a surface quoting one
     * without saying which, so every caller of either has to name its input.
     */
    fun weeksToGoal(currentKg: Double, goalKg: Double, ratePerWeekKg: Double): Double? {
        if (!currentKg.isFinite() || !goalKg.isFinite() || !ratePerWeekKg.isFinite()) return null
        val gap = goalKg - currentKg
        if (abs(gap) < 1e-6) return 0.0
        if (abs(ratePerWeekKg) < 1e-6) return null
        val weeks = gap / ratePerWeekKg
        return if (weeks > 0.0) weeks else null
    }

    fun sentence(plan: Plan): String = when (plan) {
        is Plan.Refused -> plan.sentence
        is Plan.Set -> {
            val t = plan.targets
            val rate = plan.effectiveRatePerWeekKg
            val pace = when {
                abs(rate) < 0.02 -> "holding steady"
                rate < 0.0 -> "about ${fmt2(-rate)} kg a week down"
                else -> "about ${fmt2(rate)} kg a week up"
            }
            "${String.format(java.util.Locale.US, "%,d", t.kcal)} calories — ${t.proteinG} g protein, " +
                "${t.fatG} g fat, ${t.carbG} g carbs. That is $pace."
        }
    }

    private fun fmt1(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
    private fun fmt2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
