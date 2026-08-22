package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The body arithmetic under the calorie targets.
 *
 * Every expected value below is computed from the published formula in the comment beside it, not from
 * running the code and copying what came out.
 */
class BodyTest {

    // ------------------------------------------------------------------------------------- resting rate

    /** Mifflin–St Jeor, male: 10·85 + 6.25·178 − 5·34 + 5 = 850 + 1112.5 − 170 + 5 = 1797.5 */
    @Test
    fun theMaleRestingRateMatchesMifflinStJeor() {
        assertEquals(1797.5, Body.bmr(Body.Person(85.0, 178.0, 34, Body.Sex.MALE)), 1e-9)
    }

    /** Female: the same base with −161 instead of +5 → 850 + 1112.5 − 170 − 161 = 1631.5 */
    @Test
    fun theFemaleRestingRateMatchesMifflinStJeor() {
        assertEquals(1631.5, Body.bmr(Body.Person(85.0, 178.0, 34, Body.Sex.FEMALE)), 1e-9)
    }

    /**
     * ⚠️ THE SAFE DIRECTION, and the reason it is a test rather than a comment.
     *
     * An unstated sex takes the **male** constant, which is 166 calories higher. This value's only
     * load-bearing use is as a floor under a calorie target, so over-estimating makes the floor higher
     * and the plan more conservative. Taking the lower constant would quietly permit a target 166
     * calories below where the guardrail intends to stop, for exactly the people who did not say.
     */
    @Test
    fun anUnstatedSexTakesTheHigherConstant() {
        val p = Body.Person(85.0, 178.0, 34, Body.Sex.UNSPECIFIED)
        val male = Body.bmr(p.copy(sex = Body.Sex.MALE))
        val female = Body.bmr(p.copy(sex = Body.Sex.FEMALE))
        assertTrue("the two constants must differ or this test proves nothing", male > female + 100.0)
        assertEquals("unstated must take the higher one", male, Body.bmr(p), 1e-9)
    }

    /**
     * ⚠️ A body outside what the formula describes returns NaN rather than a plausible wrong number, so a
     * caller that skips [Body.isPlausible] gets something obviously broken instead of a floor built on a
     * typo.
     */
    @Test
    fun anImpossibleBodyHasNoRestingRate() {
        assertTrue(Body.bmr(Body.Person(85.0, 40.0, 34, Body.Sex.MALE)).isNaN())   // 40 cm tall
        assertTrue(Body.bmr(Body.Person(5.0, 178.0, 34, Body.Sex.MALE)).isNaN())   // 5 kg
        assertTrue(Body.bmr(Body.Person(85.0, 178.0, 9, Body.Sex.MALE)).isNaN())   // a child
        assertTrue(Body.bmr(Body.Person(Double.NaN, 178.0, 34)).isNaN())
    }

    @Test
    fun plausibilityCoversEveryBound() {
        assertTrue(Body.isPlausible(Body.Person(70.0, 175.0, 30)))
        // Each bound, one step outside.
        assertFalse(Body.isPlausible(Body.Person(Body.MIN_KG - 0.1, 175.0, 30)))
        assertFalse(Body.isPlausible(Body.Person(Body.MAX_KG + 0.1, 175.0, 30)))
        assertFalse(Body.isPlausible(Body.Person(70.0, Body.MIN_HEIGHT_CM - 0.1, 30)))
        assertFalse(Body.isPlausible(Body.Person(70.0, Body.MAX_HEIGHT_CM + 0.1, 30)))
        assertFalse(Body.isPlausible(Body.Person(70.0, 175.0, Body.MIN_AGE_YEARS - 1)))
        assertFalse(Body.isPlausible(Body.Person(70.0, 175.0, Body.MAX_AGE_YEARS + 1)))
        // And each bound, exactly on it.
        assertTrue(Body.isPlausible(Body.Person(Body.MIN_KG, Body.MIN_HEIGHT_CM, Body.MIN_AGE_YEARS)))
        assertTrue(Body.isPlausible(Body.Person(Body.MAX_KG, Body.MAX_HEIGHT_CM, Body.MAX_AGE_YEARS)))
    }

    // --------------------------------------------------------------------------------------------- bmi

    /** 85 / 1.78² = 85 / 3.1684 = 26.8274... */
    @Test
    fun bodyMassIndexIsMassOverHeightSquared() {
        assertEquals(85.0 / (1.78 * 1.78), Body.bmi(85.0, 178.0), 1e-9)
        assertEquals(26.8274, Body.bmi(85.0, 178.0), 1e-4)
    }

    @Test
    fun aHeightOfZeroHasNoBodyMassIndex() {
        assertTrue(Body.bmi(85.0, 0.0).isNaN())
        assertTrue(Body.bmi(Double.NaN, 178.0).isNaN())
        assertNull(Body.bmiBand(Double.NaN))
    }

    /** The bands are half-open upward, so a value exactly on a boundary belongs to the band above. */
    @Test
    fun theBandsMeetExactlyOnTheirBoundaries() {
        assertEquals(Body.BmiBand.UNDERWEIGHT, Body.bmiBand(Body.BMI_UNDERWEIGHT - 0.01))
        assertEquals(Body.BmiBand.HEALTHY, Body.bmiBand(Body.BMI_UNDERWEIGHT))
        assertEquals(Body.BmiBand.HEALTHY, Body.bmiBand(Body.BMI_HEALTHY_MAX - 0.01))
        assertEquals(Body.BmiBand.OVERWEIGHT, Body.bmiBand(Body.BMI_HEALTHY_MAX))
        assertEquals(Body.BmiBand.OVERWEIGHT, Body.bmiBand(Body.BMI_OVERWEIGHT_MAX - 0.01))
        assertEquals(Body.BmiBand.OBESE, Body.bmiBand(Body.BMI_OVERWEIGHT_MAX))
    }

    /** [Body.kgAtBmi] is the inverse of [Body.bmi], which is the only property the callers rely on. */
    @Test
    fun theMassAtABodyMassIndexInvertsIt() {
        for (cm in listOf(150.0, 165.0, 178.0, 195.0)) {
            for (target in listOf(18.5, 22.0, 25.0, 30.0)) {
                val kg = Body.kgAtBmi(target, cm)
                assertEquals("round trip at ${cm}cm/$target", target, Body.bmi(kg, cm), 1e-9)
            }
        }
        // 25 × 1.78² = 79.21
        assertEquals(79.21, Body.kgAtBmi(25.0, 178.0), 1e-9)
    }
}
