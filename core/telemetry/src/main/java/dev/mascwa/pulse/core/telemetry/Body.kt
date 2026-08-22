package dev.mascwa.pulse.core.telemetry

/**
 * The small pieces of body arithmetic that more than one health core needs.
 *
 * Kept apart from [MacroTargets] and [Expenditure] deliberately: both need a resting metabolic rate and
 * neither should have to depend on the other to get one. Pure, and every input is validated rather than
 * trusted, because everything downstream of here ends up as a number of calories a real person eats.
 */
object Body {

    enum class Sex { MALE, FEMALE, UNSPECIFIED }

    /** Height in centimetres, mass in kilograms, age in whole years. */
    data class Person(
        val kg: Double,
        val heightCm: Double,
        val ageYears: Int,
        val sex: Sex = Sex.UNSPECIFIED,
    )

    // ------------------------------------------------------------------------------------- bands

    const val BMI_UNDERWEIGHT: Double = 18.5
    const val BMI_HEALTHY_MAX: Double = 25.0
    const val BMI_OVERWEIGHT_MAX: Double = 30.0

    enum class BmiBand(val label: String) {
        UNDERWEIGHT("Underweight"),
        HEALTHY("Healthy range"),
        OVERWEIGHT("Overweight"),
        OBESE("Obese"),
    }

    /**
     * ⚠️ BMI is a population screening tool, not a measure of an individual's body composition. A
     * muscular person reads "overweight" on it and is not. It appears here for exactly one purpose — the
     * underweight refusal in [MacroTargets] — where being crude in the safe direction is acceptable and
     * having no check at all is not.
     */
    fun bmi(kg: Double, heightCm: Double): Double {
        if (!kg.isFinite() || !heightCm.isFinite() || heightCm <= 0.0) return Double.NaN
        val m = heightCm / 100.0
        return kg / (m * m)
    }

    fun bmiBand(value: Double): BmiBand? = when {
        !value.isFinite() -> null
        value < BMI_UNDERWEIGHT -> BmiBand.UNDERWEIGHT
        value < BMI_HEALTHY_MAX -> BmiBand.HEALTHY
        value < BMI_OVERWEIGHT_MAX -> BmiBand.OVERWEIGHT
        else -> BmiBand.OBESE
    }

    /** The mass that would put this height at a given BMI. */
    fun kgAtBmi(value: Double, heightCm: Double): Double {
        val m = heightCm / 100.0
        return value * m * m
    }

    // --------------------------------------------------------------------------------------- bmr

    /** Adults only — every formula here is fitted on adults and none of them describes a growing body. */
    const val MIN_AGE_YEARS: Int = 18
    const val MAX_AGE_YEARS: Int = 120
    const val MIN_HEIGHT_CM: Double = 100.0
    const val MAX_HEIGHT_CM: Double = 250.0
    const val MIN_KG: Double = 25.0
    const val MAX_KG: Double = 400.0

    /** True when every field is inside the range the formulas below are defined over. */
    fun isPlausible(p: Person): Boolean =
        p.kg.isFinite() && p.kg in MIN_KG..MAX_KG &&
            p.heightCm.isFinite() && p.heightCm in MIN_HEIGHT_CM..MAX_HEIGHT_CM &&
            p.ageYears in MIN_AGE_YEARS..MAX_AGE_YEARS

    /**
     * Resting metabolic rate, Mifflin–St Jeor (1990) — the formula that validates best against measured
     * rates in ordinary adults.
     *
     * ```
     * 10·kg + 6.25·cm − 5·years + 5     (male)
     * 10·kg + 6.25·cm − 5·years − 161   (female)
     * ```
     *
     * ⚠️ [Sex.UNSPECIFIED] takes the **male** constant, and that is the safe direction rather than a
     * default. This value's only load-bearing use is as a *floor* under a calorie target, so
     * over-estimating it makes the floor higher and the plan more conservative. Taking the lower
     * constant would quietly permit a target a hundred and sixty calories below where the guardrail
     * intends to stop, for exactly the people who did not say.
     *
     * Returns NaN for a body outside [isPlausible], so a caller that skips the check gets an obviously
     * broken number rather than a plausible wrong one.
     */
    fun bmr(p: Person): Double {
        if (!isPlausible(p)) return Double.NaN
        val base = 10.0 * p.kg + 6.25 * p.heightCm - 5.0 * p.ageYears
        return base + if (p.sex == Sex.FEMALE) -161.0 else 5.0
    }
}
