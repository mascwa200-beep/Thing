package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every expected value here was computed from the published formula before the assertion was written,
 * and the arithmetic is left in the comment beside it. This repository has a long record of assertions
 * that encoded a recollection rather than the formula, so the working is part of the test.
 *
 * Reference body throughout: 80 kg, 180 cm, 30 years.
 */
class BmrEquationsTest {

    private val male = Body.Person(kg = 80.0, heightCm = 180.0, ageYears = 30, sex = Body.Sex.MALE)
    private val female = male.copy(sex = Body.Sex.FEMALE)

    // 129.6·80^0.55 + 0.011·180² − 1.96·30
    //   80^0.55      = 11.135560
    //   129.6·…      = 1443.1286
    //   0.011·32400  =  356.4
    //   1.96·30      =   58.8
    //                = 1740.7268
    @Test
    fun `equation one matches the published formula`() {
        assertEquals(1740.7268, BmrEquations.anthropometric(male), 1e-3)
    }

    // The female term is a flat −213.8 offset: 1740.7268 − 213.8 = 1526.9268
    @Test
    fun `the female term is a flat offset of two hundred and thirteen point eight`() {
        assertEquals(1526.9268, BmrEquations.anthropometric(female), 1e-3)
        assertEquals(
            BmrEquations.ANTHRO_FEMALE_OFFSET,
            BmrEquations.anthropometric(male) - BmrEquations.anthropometric(female),
            1e-6,
        )
    }

    /**
     * ⚠️ Unspecified sex takes the MALE term, which is the higher estimate. Same safe direction
     * [Body.bmr] documents: this figure ends up beneath a calorie floor, so over-estimating raises the
     * floor. Taking the female term would quietly permit a target 214 kcal lower for the people who did
     * not say.
     */
    @Test
    fun `unspecified sex takes the higher estimate rather than the lower`() {
        val unspecified = male.copy(sex = Body.Sex.UNSPECIFIED)
        assertEquals(
            BmrEquations.anthropometric(male),
            BmrEquations.anthropometric(unspecified),
            1e-9,
        )
        assertTrue(
            BmrEquations.anthropometric(unspecified) > BmrEquations.anthropometric(female),
        )
    }

    // 80 kg at 20% fat → FFM 64, FM 16
    //   64^0.7   = 18.379170
    //   16^0.066 =  1.200799
    //   50.2·18.379170             =  922.6344
    //   40.5·(18.379170·1.200799)  =  893.8260
    //   1.1·30                     =   33.0
    //                              = 1783.4604
    @Test
    fun `equation two matches the published formula`() {
        assertEquals(1783.4604, BmrEquations.composition(male, 20.0), 1e-3)
    }

    // 40.4·64^0.932 = 40.4·48.23472 = 1948.6826
    @Test
    fun `equation three matches the published formula`() {
        assertEquals(1948.6826, BmrEquations.athlete(male, 20.0), 1e-3)
    }

    /**
     * ⚠️ THE READING THAT MATTERS. "Reduces by 1.96 Cal/year up to 60; 4.9 Cal/year after 60" is a
     * slope. Applied instead as a multiplier that swaps at the breakpoint, the penalty jumps from
     * 1.96·60 = 117.60 to 4.9·61 = 298.90 — a **181.30 kcal step for having a birthday**.
     *
     * Charged as a slope: 1.96·60 + 4.9·1 = 122.50, a step of exactly one year's worth.
     */
    @Test
    fun `the age term is a slope so one birthday cannot cost a hundred and eighty calories`() {
        val at60 = BmrEquations.ageTerm(60, 1.96, 4.9)
        val at61 = BmrEquations.ageTerm(61, 1.96, 4.9)
        assertEquals(117.60, at60, 1e-9)
        assertEquals(122.50, at61, 1e-9)
        assertEquals(4.9, at61 - at60, 1e-9)

        // and it is continuous, not merely small, across the breakpoint
        assertEquals(115.64, BmrEquations.ageTerm(59, 1.96, 4.9), 1e-9)
        assertEquals(166.60, BmrEquations.ageTerm(70, 1.96, 4.9), 1e-9)
    }

    @Test
    fun `below the breakpoint only the first slope applies`() {
        assertEquals(1.96 * 30, BmrEquations.ageTerm(30, 1.96, 4.9), 1e-9)
        assertEquals(1.1 * 30, BmrEquations.ageTerm(30, 1.1, 2.75), 1e-9)
    }

    /**
     * ⚠️ The published combined factor is 0.92. The product of the two individual factors is 0.9215.
     * They agree to two figures, which is presumably why one number is published — but asserting the
     * product would be claiming a third figure the source does not.
     */
    @Test
    fun `both adaptations use the published combined factor not the product`() {
        assertEquals(0.92, BmrEquations.Adaptation(inDeficit = true, belowPeak = true).factor, 1e-9)
        assertEquals(0.95, BmrEquations.Adaptation(inDeficit = true).factor, 1e-9)
        assertEquals(0.97, BmrEquations.Adaptation(belowPeak = true).factor, 1e-9)
        assertEquals(1.0, BmrEquations.Adaptation().factor, 1e-9)
        // and it is deliberately not 0.95 × 0.97
        assertTrue(BmrEquations.BOTH_FACTOR != BmrEquations.DEFICIT_FACTOR * BmrEquations.BELOW_PEAK_FACTOR)
    }

    @Test
    fun `knowing nothing about dieting applies no discount at all`() {
        val e = BmrEquations.estimate(male)
        assertNotNull(e)
        assertEquals(1.0, e!!.adaptationFactor, 1e-9)
        assertFalse(e.adapted)
        assertEquals(e.beforeAdaptation, e.kcal, 1e-9)
    }

    // 1740.7268 × 0.92 = 1601.4687
    @Test
    fun `the discount multiplies the finished estimate`() {
        val e = BmrEquations.estimate(
            male,
            adaptation = BmrEquations.Adaptation(inDeficit = true, belowPeak = true),
        )
        assertNotNull(e)
        assertEquals(1601.4687, e!!.kcal, 1e-3)
        assertEquals(1740.7268, e.beforeAdaptation, 1e-3)
        assertTrue(e.adapted)
    }

    // ------------------------------------------------------------------------------- selection

    @Test
    fun `equation is chosen by what is known not by what flatters`() {
        assertEquals(
            BmrEquations.Equation.ANTHROPOMETRIC,
            BmrEquations.estimate(male)!!.equation,
        )
        assertEquals(
            BmrEquations.Equation.COMPOSITION,
            BmrEquations.estimate(male, bodyFatPct = 20.0)!!.equation,
        )
        assertEquals(
            BmrEquations.Equation.ATHLETE,
            BmrEquations.estimate(male, bodyFatPct = 20.0, athlete = true)!!.equation,
        )
    }

    /**
     * ⚠️ An athlete flag with no body-fat figure cannot reach equation 3 — it needs fat-free mass and
     * there is none. It falls to equation 1 rather than guessing a body fat for a lean person, which
     * would be the app inventing the very input it is missing.
     */
    @Test
    fun `an athlete flag without body fat falls back rather than guessing`() {
        assertEquals(
            BmrEquations.Equation.ANTHROPOMETRIC,
            BmrEquations.estimate(male, athlete = true)!!.equation,
        )
    }

    /**
     * ⚠️ Out of range is treated as ABSENT, never clamped into range. Clamping turns a typo — 200%
     * body fat, or 0 — into a confident number computed from a figure the person never gave.
     */
    @Test
    fun `an implausible body fat is treated as absent rather than clamped`() {
        for (bad in listOf(0.0, -5.0, 2.0, 85.0, 200.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val e = BmrEquations.estimate(male, bodyFatPct = bad)
            assertNotNull("body fat $bad should fall back, not fail", e)
            assertEquals(
                "body fat $bad must not reach a composition equation",
                BmrEquations.Equation.ANTHROPOMETRIC,
                e!!.equation,
            )
            // and specifically: it produced equation 1's number, not a clamped equation 2
            assertEquals(1740.7268, e.kcal, 1e-3)
        }
    }

    /**
     * ⚠️ Zero fat mass is refused rather than computed. `FM^0.066` at exactly zero is zero, which
     * silently deletes the entire interaction term — 894 of this person's 1783 kcal — and returns a
     * number that looks like an answer.
     */
    @Test
    fun `zero body fat cannot silently delete the interaction term`() {
        assertTrue(BmrEquations.composition(male, 0.0).isNaN())
        assertFalse(BmrEquations.isPlausibleBodyFat(0.0))

        // Quantifying what the guard is worth: at a normal body fat the interaction term is
        // 40.5·(FFM^0.7·FM^0.066) = 893.83 of this person's 1783.46 kcal — half the estimate. At
        // FM = 0 exactly, FM^0.066 is 0 and all of it disappears without any error.
        val total = BmrEquations.composition(male, 20.0)
        val withoutInteraction =
            BmrEquations.COMP_FFM_COEFF * Math.pow(64.0, BmrEquations.COMP_FFM_EXPONENT) -
                BmrEquations.ageTerm(30, BmrEquations.COMP_AGE_PER_YEAR, BmrEquations.COMP_AGE_PER_YEAR_OVER_60)
        assertEquals(889.6344, withoutInteraction, 1e-3)
        assertTrue(
            "the interaction term is worth ${total - withoutInteraction} kcal",
            total - withoutInteraction > 800.0,
        )
    }

    // ------------------------------------------------------------------------------ plausibility

    @Test
    fun `an implausible body returns null rather than an estimate wrapping NaN`() {
        val tooYoung = male.copy(ageYears = 12)
        val tooLight = male.copy(kg = 10.0)
        assertNull(BmrEquations.estimate(tooYoung))
        assertNull(BmrEquations.estimate(tooLight))
        assertNull(BmrEquations.estimate(tooYoung, bodyFatPct = 20.0))
        assertTrue(BmrEquations.anthropometric(tooYoung).isNaN())
    }

    @Test
    fun `fat free and fat mass split the body between them`() {
        assertEquals(64.0, BmrEquations.fatFreeMassKg(80.0, 20.0), 1e-9)
        assertEquals(16.0, BmrEquations.fatMassKg(80.0, 20.0), 1e-9)
        assertEquals(
            80.0,
            BmrEquations.fatFreeMassKg(80.0, 20.0) + BmrEquations.fatMassKg(80.0, 20.0),
            1e-9,
        )
    }

    // --------------------------------------------------------------------------------- peak rule

    @Test
    fun `below peak is strictly more than a tenth below`() {
        assertTrue(BmrEquations.isBelowPeak(currentKg = 89.0, peakKg = 100.0))
        assertFalse(BmrEquations.isBelowPeak(currentKg = 90.0, peakKg = 100.0))
        assertFalse(BmrEquations.isBelowPeak(currentKg = 100.0, peakKg = 100.0))
        assertFalse(BmrEquations.isBelowPeak(currentKg = 110.0, peakKg = 100.0))
        // a missing or nonsense peak is not a reduction
        assertFalse(BmrEquations.isBelowPeak(currentKg = 80.0, peakKg = 0.0))
        assertFalse(BmrEquations.isBelowPeak(currentKg = 80.0, peakKg = Double.NaN))
    }

    // ------------------------------------------------------------------------------- provenance

    @Test
    fun `describe names the equation and any discount`() {
        val plain = BmrEquations.describe(BmrEquations.estimate(male)!!)
        assertTrue(plain, plain.contains("height, weight, age and sex"))
        assertFalse(plain, plain.contains("%"))

        val dieting = BmrEquations.describe(
            BmrEquations.estimate(
                male,
                bodyFatPct = 20.0,
                adaptation = BmrEquations.Adaptation(inDeficit = true),
            )!!,
        )
        assertTrue(dieting, dieting.contains("body composition"))
        assertTrue(dieting, dieting.contains("5%"))
    }

    /**
     * A sanity check rather than a fitted expectation: for an ordinary adult the new equation and
     * Mifflin–St Jeor should land close together. 1740.73 against 1780.00 is a 39 kcal difference,
     * which is the right order. A large gap here would mean an exponent or coefficient is wrong.
     */
    @Test
    fun `equation one lands near Mifflin St Jeor for an ordinary adult`() {
        val mine = BmrEquations.anthropometric(male)
        val msj = Body.bmr(male)
        assertEquals(1780.0, msj, 1e-9)
        assertTrue("expected within 100 kcal, got ${msj - mine}", kotlin.math.abs(msj - mine) < 100.0)
    }
}
