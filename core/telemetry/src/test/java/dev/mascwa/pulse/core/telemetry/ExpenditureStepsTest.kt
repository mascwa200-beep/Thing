package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The step-informed half of [Expenditure].
 *
 * ⚠️ Kept in its own file rather than grown onto `ExpenditureTest`, which is already long and about
 * the measurement itself. These are about a proxy that INFORMS the measurement and never replaces
 * it, and the separation makes that boundary visible in the file list.
 */
class ExpenditureStepsTest {

    private val day = 86_400_000L
    private val now = 1_700_000_000_000L

    private fun days(count: Int, steps: Int, endingDaysAgo: Int = 0): List<Expenditure.StepDay> =
        (0 until count).map {
            Expenditure.StepDay(now - (endingDaysAgo + it) * day, steps)
        }

    // ------------------------------------------------------------------------------------- bands

    @Test
    fun `the published category boundaries are exactly where they are published`() {
        // Tudor-Locke and Bassett: <5000, 5000-7499, 7500-9999, 10000-12499, 12500+.
        assertEquals(Expenditure.StepBand.SEDENTARY, Expenditure.stepBand(0))
        assertEquals(Expenditure.StepBand.SEDENTARY, Expenditure.stepBand(4_999))
        assertEquals(Expenditure.StepBand.LOW_ACTIVE, Expenditure.stepBand(5_000))
        assertEquals(Expenditure.StepBand.LOW_ACTIVE, Expenditure.stepBand(7_499))
        assertEquals(Expenditure.StepBand.SOMEWHAT_ACTIVE, Expenditure.stepBand(7_500))
        assertEquals(Expenditure.StepBand.SOMEWHAT_ACTIVE, Expenditure.stepBand(9_999))
        assertEquals(Expenditure.StepBand.ACTIVE, Expenditure.stepBand(10_000))
        assertEquals(Expenditure.StepBand.ACTIVE, Expenditure.stepBand(12_499))
        assertEquals(Expenditure.StepBand.HIGHLY_ACTIVE, Expenditure.stepBand(12_500))
        assertEquals(Expenditure.StepBand.HIGHLY_ACTIVE, Expenditure.stepBand(40_000))
    }

    @Test
    fun `a nonsense count reads as sedentary rather than taking the screen down`() {
        // ⚠️ `last {}` throws when nothing matches, and a negative satisfies no floor. A broken
        // sensor reading must not be able to crash a card.
        assertEquals(Expenditure.StepBand.SEDENTARY, Expenditure.stepBand(-1))
        assertEquals(Expenditure.StepBand.SEDENTARY, Expenditure.stepBand(Int.MIN_VALUE))
    }

    // -------------------------------------------------------------------------------- suggestion

    @Test
    fun `a step count never talks somebody down from what they told us`() {
        // Somebody who trains hard and walks very little has told us something a pedometer cannot
        // contradict. And the asymmetry is in the safe direction: over-estimating expenditure raises
        // the target, which is recoverable, where under-estimating means eating too little.
        assertNull(Expenditure.suggestedActivity(Expenditure.StepBand.SEDENTARY, Expenditure.Activity.HIGH))
        assertNull(Expenditure.suggestedActivity(Expenditure.StepBand.LOW_ACTIVE, Expenditure.Activity.MODERATE))
    }

    @Test
    fun `it suggests only what the walking volume genuinely supports`() {
        assertEquals(
            Expenditure.Activity.MODERATE,
            Expenditure.suggestedActivity(Expenditure.StepBand.ACTIVE, Expenditure.Activity.SEDENTARY),
        )
        assertEquals(
            Expenditure.Activity.HIGH,
            Expenditure.suggestedActivity(Expenditure.StepBand.HIGHLY_ACTIVE, Expenditure.Activity.LIGHT),
        )
        // 7,500-9,999 is "somewhat active" walking and does not on its own support MODERATE, which
        // means active most days OR three to five sessions a week — neither of which is walking.
        assertEquals(
            Expenditure.Activity.LIGHT,
            Expenditure.suggestedActivity(Expenditure.StepBand.SOMEWHAT_ACTIVE, Expenditure.Activity.SEDENTARY),
        )
    }

    @Test
    fun `VERY_HIGH is never suggested from a step count, whatever the band`() {
        // ⚠️ That band means hard training twice a day or heavy manual work. A pedometer cannot
        // distinguish a shift on a building site from a long dog walk, so it never claims to.
        for (band in Expenditure.StepBand.entries) {
            for (current in Expenditure.Activity.entries) {
                val s = Expenditure.suggestedActivity(band, current)
                assertTrue("$band from $current gave $s", s != Expenditure.Activity.VERY_HIGH)
            }
        }
    }

    // ------------------------------------------------------------------------------------- shift

    @Test
    fun `too few days on either side is not a shift, and says which`() {
        val steps = days(count = 3, steps = 9_000) + days(count = 12, steps = 3_000, endingDaysAgo = 8)
        val shift = Expenditure.stepShift(steps, now)
        assertFalse(shift.changed)
        assertTrue(shift.sentence, shift.sentence.contains("Not enough days"))
    }

    @Test
    fun `a real rise is a shift and says which way`() {
        // earlier 4000 over 12 days, recent 9000 over 7 days.
        //   diff = 5000, absolute gate 1500 -> passes, fraction gate 4000 * 0.25 = 1000 -> passes.
        val steps = days(count = 7, steps = 9_000) + days(count = 12, steps = 4_000, endingDaysAgo = 8)
        val shift = Expenditure.stepShift(steps, now)
        assertTrue(shift.changed)
        assertEquals(4_000, shift.earlierMean)
        assertEquals(9_000, shift.recentMean)
        assertEquals(5_000, shift.delta)
        assertTrue(shift.sentence, shift.sentence.contains("more"))
    }

    @Test
    fun `a real fall is a shift the other way`() {
        // earlier 10000, recent 6000: diff 4000 >= 1500 and >= 10000 * 0.25 = 2500.
        val steps = days(count = 7, steps = 6_000) + days(count = 12, steps = 10_000, endingDaysAgo = 8)
        val shift = Expenditure.stepShift(steps, now)
        assertTrue(shift.changed)
        assertEquals(-4_000, shift.delta)
        assertTrue(shift.sentence, shift.sentence.contains("less"))
    }

    @Test
    fun `the absolute floor stops a small base making a big fraction`() {
        // earlier 2000, recent 3200: diff 1200. The FRACTION gate passes (2000 * 0.25 = 500) and the
        // ABSOLUTE one does not (1200 < 1500), so this case isolates the absolute floor. Two days
        // spent equally on a sofa are not a change in how somebody lives.
        val steps = days(count = 7, steps = 3_200) + days(count = 12, steps = 2_000, endingDaysAgo = 8)
        assertFalse(Expenditure.stepShift(steps, now).changed)
    }

    @Test
    fun `the fraction gate stops a large base making a big absolute`() {
        // earlier 20000, recent 18000: diff 2000. The ABSOLUTE gate passes (>= 1500) and the
        // FRACTION one does not (20000 * 0.25 = 5000), so this case isolates the fraction.
        val steps = days(count = 7, steps = 18_000) + days(count = 12, steps = 20_000, endingDaysAgo = 8)
        assertFalse(Expenditure.stepShift(steps, now).changed)
    }

    @Test
    fun `days outside the window are not compared`() {
        // Everything old sits 40 days back, well outside the 28-day default, so the earlier side has
        // nothing in it and no shift can be claimed however different the two figures are.
        val steps = days(count = 7, steps = 15_000) + days(count = 12, steps = 1_000, endingDaysAgo = 40)
        val shift = Expenditure.stepShift(steps, now)
        assertFalse(shift.changed)
        assertEquals(0, shift.earlierDays)
    }

    // ------------------------------------------------------------------------------------ widening

    @Test
    fun `nothing changed leaves the estimate exactly as it was`() {
        val measured = known(2_600.0, 200.0)
        val steady = Expenditure.stepShift(days(20, 8_000), now)
        assertFalse(steady.changed)
        assertEquals(measured, Expenditure.widenForShift(measured, steady))
    }

    @Test
    fun `a shift widens the interval by the stated factor and nothing else`() {
        val measured = known(2_600.0, 200.0)
        val shifted = Expenditure.stepShift(
            days(count = 7, steps = 9_000) + days(count = 12, steps = 4_000, endingDaysAgo = 8),
            now,
        )
        val widened = Expenditure.widenForShift(measured, shifted)
        // 200 * 1.5 = 300.
        assertEquals(300.0, widened.sdKcal, 1e-9)
        assertEquals(measured.kcal, widened.kcal, 1e-9)
        assertEquals(measured.loggedDays, widened.loggedDays)
        assertEquals(measured.windowDays, widened.windowDays, 1e-9)
    }

    @Test
    fun `widening hands weight to the formula through the blend, which is the whole mechanism`() {
        // formula 2400 kcal, sd = 0.15 * 2400 = 360.  measured 2600 kcal, sd 200.
        //   before: wf = 1/360^2 = 7.7160e-6, wm = 1/200^2 = 2.5e-5  -> share = 0.7642
        //   after:  wm = 1/300^2 = 1.1111e-5                          -> share = 0.5902
        val formula = Expenditure.fromFormula(2400.0 / Expenditure.Activity.SEDENTARY.multiplier, Expenditure.Activity.SEDENTARY)
        val measured = known(2_600.0, 200.0)
        val shifted = Expenditure.stepShift(
            days(count = 7, steps = 9_000) + days(count = 12, steps = 4_000, endingDaysAgo = 8),
            now,
        )
        val before = Expenditure.measuredShare(formula, measured)
        val after = Expenditure.measuredShare(formula, Expenditure.widenForShift(measured, shifted))
        assertEquals(0.7642, before, 1e-3)
        assertEquals(0.5902, after, 1e-3)
        assertTrue("before=$before after=$after", after < before)
    }

    @Test
    fun `widening can never turn a working estimate into no estimate`() {
        // ⚠️ The property that ruled out the obvious alternative. Shortening the window would drop
        // logged days, and under MIN_LOGGED_DAYS the estimate becomes NotYet — so somebody whose
        // habits changed would be left with no number at all for a fortnight, exactly when they want
        // one. Widening keeps every field that any gate reads.
        val measured = known(2_600.0, 200.0)
        val shifted = Expenditure.stepShift(
            days(count = 7, steps = 9_000) + days(count = 12, steps = 4_000, endingDaysAgo = 8),
            now,
        )
        val widened = Expenditure.widenForShift(measured, shifted)
        assertEquals(measured.loggedDays, widened.loggedDays)
        assertEquals(measured.completeness, widened.completeness, 1e-9)
        assertEquals(measured.source, widened.source)
    }

    private fun known(kcal: Double, sd: Double) = Expenditure.Estimate.Known(
        kcal = kcal,
        sdKcal = sd,
        source = Expenditure.Source.MEASURED,
        windowDays = 28.0,
        loggedDays = 22,
        completeness = 0.8,
    )
}
