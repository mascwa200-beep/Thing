package dev.mascwa.pulse.core.telemetry

import kotlin.math.pow

/**
 * Resting metabolic rate by non-linear allometric scaling, and the adaptation discount that applies to
 * somebody who has been dieting.
 *
 * ### Why this exists beside [Body.bmr]
 *
 * Mifflin–St Jeor is linear in mass: it charges every kilogram the same metabolic rent. Biology does
 * not work that way. The organs that dominate resting expenditure — brain, heart, liver, kidney — burn
 * on the order of 200–440 kcal per kilogram per day, while skeletal muscle burns roughly 13. As a body
 * gets bigger the extra mass is overwhelmingly the cheap kind, so resting rate per kilogram *falls* as
 * size rises. A power law captures that; a straight line cannot.
 *
 * This matters for a bounded but real window. [Expenditure] blends a formula estimate with measured
 * energy balance and leans on the formula hardest in the first weeks, before enough weigh-ins and
 * logged days exist to measure anything. So the equation chosen here decides what somebody eats while
 * the app is still learning, and stops mattering much once it has learned.
 *
 * ### The three equations
 *
 * From the published derivation. Mass in kilograms, height in centimetres, age in whole years.
 *
 * ```
 * 1  anthropometric   129.6·kg^0.55 + 0.011·cm^2 − ageTerm(1.96, 4.9) − 213.8·sex
 * 2  composition       50.2·FFM^0.7 + 40.5·(FFM^0.7 · FM^0.066) − ageTerm(1.1, 2.75)
 * 3  athlete           40.4·FFM^0.932
 * ```
 *
 * ⚠️ **These are three separate equations and their exponents do not mix.** The 0.55 belongs to body
 * *weight*, and only in the equation that has no body-composition term at all; the 0.7 and 0.066 belong
 * to fat-free and fat mass in a different equation. A summary that hands you "weight^0.55 … fat-free
 * mass^0.7" as one formula has merged two of them, and the result is not an equation anybody fitted.
 *
 * ⚠️ **The age term is a slope, not a multiplier, and reading it the other way puts a cliff on a
 * birthday.** The source says the estimate "reduces by 1.96 Calories/year up to age 60; 4.9 Calories/
 * year after age 60". Applied as `4.9 × age` for an over-60, a sixty-year-old is charged 117.6 kcal and
 * a sixty-one-year-old 298.9 — a 181 kcal step for having one more birthday. Charged as a piecewise
 * slope it is 117.6 then 122.5, which is what "per year" means. See [ageTerm].
 *
 * ### Equation 3 is not simply "the better one for athletes"
 *
 * Its exponent, 0.932, is close to linear — far above the 0.5–0.7 seen across the general population.
 * That is the point: in a population that is already lean and heavily muscled, the mix of tissue types
 * changes much less from one person to the next, so the curvature that a general-population fit needs
 * is absent. Applying it to an untrained body would badly over-estimate, which is why it is gated on an
 * explicit self-report rather than inferred from body fat alone.
 *
 * Every function returns [Double.NaN] for a body outside the range these were fitted on, matching
 * [Body.bmr] — an obviously broken number rather than a plausible wrong one.
 */
object BmrEquations {

    // -------------------------------------------------------------------------------- equation 1

    const val ANTHRO_MASS_COEFF: Double = 129.6
    const val ANTHRO_MASS_EXPONENT: Double = 0.55
    const val ANTHRO_HEIGHT_COEFF: Double = 0.011
    const val ANTHRO_HEIGHT_EXPONENT: Double = 2.0
    const val ANTHRO_AGE_PER_YEAR: Double = 1.96
    const val ANTHRO_AGE_PER_YEAR_OVER_60: Double = 4.9
    const val ANTHRO_FEMALE_OFFSET: Double = 213.8

    // -------------------------------------------------------------------------------- equation 2

    const val COMP_FFM_COEFF: Double = 50.2
    const val COMP_FFM_EXPONENT: Double = 0.7
    const val COMP_INTERACTION_COEFF: Double = 40.5
    const val COMP_FM_EXPONENT: Double = 0.066
    const val COMP_AGE_PER_YEAR: Double = 1.1
    const val COMP_AGE_PER_YEAR_OVER_60: Double = 2.75

    // -------------------------------------------------------------------------------- equation 3

    const val ATHLETE_FFM_COEFF: Double = 40.4
    const val ATHLETE_FFM_EXPONENT: Double = 0.932

    /** The age at which both published age slopes steepen. */
    const val AGE_BREAKPOINT: Int = 60

    // ------------------------------------------------------------------------------- adaptation

    /**
     * Adaptive thermogenesis: a body in an energy deficit down-regulates, and standard formulas — fitted
     * on people who are not dieting — over-estimate it. Applied as multipliers on the finished estimate.
     *
     * ⚠️ [BOTH] is **0.92 as published, not the product** of the two (0.95 × 0.97 = 0.9215). They round to
     * the same two figures, which is presumably why the source states one number, but stating the product
     * here would be inventing a third significant figure the source does not claim.
     */
    const val DEFICIT_FACTOR: Double = 0.95
    const val BELOW_PEAK_FACTOR: Double = 0.97
    const val BOTH_FACTOR: Double = 0.92

    /** How far below peak weight counts as "reduced" for [BELOW_PEAK_FACTOR]. */
    const val BELOW_PEAK_FRACTION: Double = 0.10

    // ------------------------------------------------------------------------------- body fat

    /**
     * Essential body fat is roughly 3% in men and 10–13% in women, and nothing survives at zero. The
     * upper bound is past the highest figures in the literature rather than at them.
     *
     * ⚠️ The lower bound is load-bearing arithmetic as well as physiology: fat mass appears as
     * `FM^0.066`, and at exactly zero that whole interaction term collapses to zero, silently removing a
     * large positive contribution instead of failing.
     */
    const val MIN_BODY_FAT_PCT: Double = 3.0
    const val MAX_BODY_FAT_PCT: Double = 70.0

    // ------------------------------------------------------------------------------------ types

    /** Which formula produced a number. Carried out of [estimate] so a surface can say, not imply. */
    enum class Equation(val label: String) {
        ANTHROPOMETRIC("height, weight, age and sex"),
        COMPOSITION("body composition"),
        ATHLETE("athlete body composition"),
    }

    /**
     * What the caller knows about the dieting history. Both default to false, so a caller that knows
     * nothing gets the undiscounted estimate rather than a guessed discount.
     */
    data class Adaptation(
        val inDeficit: Boolean = false,
        val belowPeak: Boolean = false,
    ) {
        val factor: Double
            get() = when {
                inDeficit && belowPeak -> BOTH_FACTOR
                inDeficit -> DEFICIT_FACTOR
                belowPeak -> BELOW_PEAK_FACTOR
                else -> 1.0
            }
    }

    /**
     * A resting rate and the provenance of it. [beforeAdaptation] is kept so a surface can show the
     * discount as a discount rather than folding it invisibly into one figure.
     */
    data class Estimate(
        val kcal: Double,
        val equation: Equation,
        val beforeAdaptation: Double,
        val adaptationFactor: Double,
    ) {
        val adapted: Boolean get() = adaptationFactor < 1.0
    }

    // -------------------------------------------------------------------------------- the maths

    /**
     * The published age penalty, as a piecewise-linear slope.
     *
     * `perYear` applies to every year up to [AGE_BREAKPOINT] and `perYearOver` to each year beyond it,
     * so the function is continuous at the breakpoint. See the class KDoc for why the alternative
     * reading — swapping the whole multiplier at 60 — cannot be what was meant.
     */
    fun ageTerm(ageYears: Int, perYear: Double, perYearOver: Double): Double {
        val under = minOf(ageYears, AGE_BREAKPOINT)
        val over = maxOf(0, ageYears - AGE_BREAKPOINT)
        return perYear * under + perYearOver * over
    }

    /** True when [bodyFatPct] is a figure these equations are defined over. */
    fun isPlausibleBodyFat(bodyFatPct: Double): Boolean =
        bodyFatPct.isFinite() && bodyFatPct in MIN_BODY_FAT_PCT..MAX_BODY_FAT_PCT

    /** Fat-free mass in kilograms, or NaN if either input is out of range. */
    fun fatFreeMassKg(kg: Double, bodyFatPct: Double): Double =
        if (!isPlausibleBodyFat(bodyFatPct) || !kg.isFinite() || kg <= 0.0) Double.NaN
        else kg * (1.0 - bodyFatPct / 100.0)

    /** Fat mass in kilograms, or NaN if either input is out of range. */
    fun fatMassKg(kg: Double, bodyFatPct: Double): Double =
        if (!isPlausibleBodyFat(bodyFatPct) || !kg.isFinite() || kg <= 0.0) Double.NaN
        else kg * (bodyFatPct / 100.0)

    /**
     * Equation 1 — height, weight, age and sex.
     *
     * ⚠️ [Body.Sex.UNSPECIFIED] takes the **male** term, which here means no offset at all and therefore
     * the higher of the two estimates. That is the same safe direction [Body.bmr] documents: this number
     * ends up under a calorie floor, and over-estimating raises the floor.
     */
    fun anthropometric(p: Body.Person): Double {
        if (!Body.isPlausible(p)) return Double.NaN
        val mass = ANTHRO_MASS_COEFF * p.kg.pow(ANTHRO_MASS_EXPONENT)
        val height = ANTHRO_HEIGHT_COEFF * p.heightCm.pow(ANTHRO_HEIGHT_EXPONENT)
        val age = ageTerm(p.ageYears, ANTHRO_AGE_PER_YEAR, ANTHRO_AGE_PER_YEAR_OVER_60)
        val sex = if (p.sex == Body.Sex.FEMALE) ANTHRO_FEMALE_OFFSET else 0.0
        return mass + height - age - sex
    }

    /**
     * Equation 2 — fat-free and fat mass.
     *
     * The second term is an interaction, `FFM^0.7 · FM^0.066`, not a separate fat-mass term: fat tissue's
     * own contribution is small, but carrying more of it raises what the lean tissue costs to maintain.
     */
    fun composition(p: Body.Person, bodyFatPct: Double): Double {
        if (!Body.isPlausible(p) || !isPlausibleBodyFat(bodyFatPct)) return Double.NaN
        val ffm = fatFreeMassKg(p.kg, bodyFatPct).pow(COMP_FFM_EXPONENT)
        val fm = fatMassKg(p.kg, bodyFatPct).pow(COMP_FM_EXPONENT)
        val age = ageTerm(p.ageYears, COMP_AGE_PER_YEAR, COMP_AGE_PER_YEAR_OVER_60)
        return COMP_FFM_COEFF * ffm + COMP_INTERACTION_COEFF * (ffm * fm) - age
    }

    /**
     * Equation 3 — athletes, defined by the source as seven or more hours a week of intense exercise.
     *
     * ⚠️ Carries **no age term at all**, which is the fitted form rather than an omission here.
     */
    fun athlete(p: Body.Person, bodyFatPct: Double): Double {
        if (!Body.isPlausible(p) || !isPlausibleBodyFat(bodyFatPct)) return Double.NaN
        return ATHLETE_FFM_COEFF * fatFreeMassKg(p.kg, bodyFatPct).pow(ATHLETE_FFM_EXPONENT)
    }

    /** True when [currentKg] sits more than [BELOW_PEAK_FRACTION] below [peakKg]. */
    fun isBelowPeak(currentKg: Double, peakKg: Double): Boolean =
        currentKg.isFinite() && peakKg.isFinite() && peakKg > 0.0 &&
            currentKg < peakKg * (1.0 - BELOW_PEAK_FRACTION)

    /**
     * The best estimate the available data supports, with the equation that produced it.
     *
     * Selection is by what is *known*, not by what would flatter: body fat plus an athlete self-report
     * chooses equation 3, body fat alone chooses 2, and neither falls back to 1. A body fat figure that
     * is out of range is treated as absent rather than clamped into range — a clamp would turn a typo
     * into a confident number.
     *
     * Returns null for a body outside [Body.isPlausible], rather than an [Estimate] wrapping NaN, so a
     * caller cannot accidentally render "NaN kcal".
     */
    fun estimate(
        p: Body.Person,
        bodyFatPct: Double? = null,
        athlete: Boolean = false,
        adaptation: Adaptation = Adaptation(),
    ): Estimate? {
        if (!Body.isPlausible(p)) return null
        val bf = bodyFatPct?.takeIf { isPlausibleBodyFat(it) }
        val equation = when {
            bf != null && athlete -> Equation.ATHLETE
            bf != null -> Equation.COMPOSITION
            else -> Equation.ANTHROPOMETRIC
        }
        val raw = when (equation) {
            Equation.ATHLETE -> athlete(p, bf!!)
            Equation.COMPOSITION -> composition(p, bf!!)
            Equation.ANTHROPOMETRIC -> anthropometric(p)
        }
        if (!raw.isFinite() || raw <= 0.0) return null
        val factor = adaptation.factor
        return Estimate(
            kcal = raw * factor,
            equation = equation,
            beforeAdaptation = raw,
            adaptationFactor = factor,
        )
    }

    /**
     * One sentence naming the equation used and any discount applied, for a surface that should say
     * where a number came from rather than presenting it bare.
     */
    fun describe(e: Estimate): String {
        val base = "Estimated from ${e.equation.label}"
        if (!e.adapted) return "$base."
        val pct = ((1.0 - e.adaptationFactor) * 100.0).let { kotlin.math.round(it).toInt() }
        return "$base, reduced $pct% for the metabolic slowdown that comes with dieting."
    }
}
