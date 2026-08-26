package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ Every threshold below is worked from the shipped constants with the arithmetic in the comment:
 * `INTAKE_SHIFT_FRACTION` 0.15, `INTAKE_SHIFT_MIN_ABSOLUTE` 250, `MIN_INTAKE_DAYS_EACH_SIDE` 5,
 * `INTAKE_SHIFT_RECENT_DAYS` 7. A shift must clear BOTH, which is what the two near-miss cases pin.
 */
class ExpenditureIntakeShiftTest {

    private val day = 86_400_000L
    private val now = 1_700_000_000_000L

    /** [n] days of intake ending yesterday, newest last, all at [kcal]. */
    private fun days(n: Int, kcal: Double, from: Int = 0): List<Expenditure.IntakeDay> =
        (0 until n).map { i ->
            Expenditure.IntakeDay(dayStartMs = now - (from + n - i) * day, kcal = kcal)
        }

    /** [earlier] days at [was] before the recent window, then [recent] days at [isNow] inside it. */
    private fun split(
        earlier: Int,
        was: Double,
        recent: Int,
        isNow: Double,
    ): List<Expenditure.IntakeDay> =
        days(earlier, was, from = Expenditure.INTAKE_SHIFT_RECENT_DAYS) + days(recent, isNow)

    @Test
    fun `a real cut is a shift`() {
        // 2,600 down to 2,000: diff 600. Clears 250 absolute, and 600 ≥ 2600 × 0.15 = 390. ✓
        val s = Expenditure.intakeShift(split(14, 2600.0, 6, 2000.0), now)
        assertTrue(s.changed)
        assertEquals(2600.0, s.earlierMean, 1e-9)
        assertEquals(2000.0, s.recentMean, 1e-9)
        assertEquals(-600.0, s.delta, 1e-9)
        assertTrue(s.sentence.contains("a good deal less"))
        assertTrue(s.sentence.contains("2000"))
        assertTrue(s.sentence.contains("2600"))
    }

    @Test
    fun `a real increase is a shift and says so the other way round`() {
        // 1,800 up to 2,400: diff 600 ≥ 250, and 600 ≥ 1800 × 0.15 = 270. ✓
        val s = Expenditure.intakeShift(split(14, 1800.0, 6, 2400.0), now)
        assertTrue(s.changed)
        assertEquals(600.0, s.delta, 1e-9)
        assertTrue(s.sentence.contains("a good deal more"))
    }

    @Test
    fun `a change that clears the fraction but not the floor is not a shift`() {
        // ⚠️ The reason the absolute floor exists. 1,100 to 900 is a diff of 200, which IS 18% of
        // 1,100 and so clears 0.15 — but 200 calories a day is a snack, not a decision. Below the
        // 250 floor, so no shift.
        val s = Expenditure.intakeShift(split(14, 1100.0, 6, 900.0), now)
        assertFalse(s.changed)
        assertTrue(200.0 >= 1100.0 * Expenditure.INTAKE_SHIFT_FRACTION)
        assertTrue(200.0 < Expenditure.INTAKE_SHIFT_MIN_ABSOLUTE)
    }

    @Test
    fun `a change that clears the floor but not the fraction is not a shift`() {
        // ⚠️ The reason the fraction exists beside the floor. 4,000 to 3,700 is 300 — over the 250
        // floor — but only 7.5% of a four-thousand-calorie day, which is one ordinary meal moving
        // about. 300 < 4000 × 0.15 = 600, so no shift.
        val s = Expenditure.intakeShift(split(14, 4000.0, 6, 3700.0), now)
        assertFalse(s.changed)
        assertTrue(300.0 >= Expenditure.INTAKE_SHIFT_MIN_ABSOLUTE)
        assertTrue(300.0 < 4000.0 * Expenditure.INTAKE_SHIFT_FRACTION)
    }

    @Test
    fun `too few days on either side is not a shift and says which`() {
        // Four recent days against fourteen earlier: below MIN_INTAKE_DAYS_EACH_SIDE = 5.
        val thin = Expenditure.intakeShift(split(14, 2600.0, 4, 1500.0), now)
        assertFalse(thin.changed)
        assertTrue(thin.sentence.contains("Not enough logged days"))
        assertEquals(4, thin.recentDays)

        // And the same the other way: four earlier days against six recent.
        val young = Expenditure.intakeShift(split(4, 2600.0, 6, 1500.0), now)
        assertFalse(young.changed)
        assertTrue(young.sentence.contains("Not enough logged days"))
    }

    @Test
    fun `a gap is not a zero-calorie day and a marked fast is`() {
        // ⚠️ `counted` is the single definition of "logged" and this rides on it. An unlogged day is
        // simply absent from the list; a day explicitly marked fasted has kcal 0 and must be counted,
        // or somebody who fasts on Mondays would have every Monday quietly dropped from the mean.
        val fasting = days(14, 2600.0, from = Expenditure.INTAKE_SHIFT_RECENT_DAYS) +
            (0 until 6).map { i ->
                Expenditure.IntakeDay(now - (6 - i) * day, kcal = 0.0, fasted = true)
            }
        val s = Expenditure.intakeShift(fasting, now)
        assertEquals(6, s.recentDays)
        assertEquals(0.0, s.recentMean, 1e-9)
        assertTrue(s.changed)

        // Six days that were never logged at all are not in the list, so the recent side is empty
        // and the comparison refuses rather than reading the gap as a fast.
        val gap = Expenditure.intakeShift(days(14, 2600.0, from = Expenditure.INTAKE_SHIFT_RECENT_DAYS), now)
        assertFalse(gap.changed)
        assertEquals(0, gap.recentDays)
        assertTrue(gap.sentence.contains("Not enough logged days"))
    }

    // ------------------------------------------------------------------------------- the widening

    private fun measured(sd: Double) = Expenditure.Estimate.Known(
        kcal = 2400.0,
        sdKcal = sd,
        source = Expenditure.Source.MEASURED,
        windowDays = 28.0,
        loggedDays = 20,
        completeness = 0.7,
    )

    private fun steadySteps() = Expenditure.stepShift(
        (0 until 20).map { Expenditure.StepDay(now - (20 - it) * day, 8_000) },
        now,
    )

    private fun shiftedSteps() = Expenditure.stepShift(
        (0 until 14).map { Expenditure.StepDay(now - (7 + 14 - it) * day, 3_000) } +
            (0 until 6).map { Expenditure.StepDay(now - (6 - it) * day, 12_000) },
        now,
    )

    @Test
    fun `nothing shifted leaves the estimate exactly as it was`() {
        val e = measured(150.0)
        val same = Expenditure.widenForShifts(
            e,
            steadySteps(),
            Expenditure.intakeShift(days(20, 2400.0), now),
        )
        assertEquals(e, same)
    }

    @Test
    fun `one shift widens by its own inflation`() {
        // 150 × 1.5 = 225.
        val widened = Expenditure.widenForShifts(
            measured(150.0),
            steadySteps(),
            Expenditure.intakeShift(split(14, 2600.0, 6, 2000.0), now),
        )
        assertEquals(225.0, widened.sdKcal, 1e-9)
        // Only the interval moves. The figure itself is what it measured.
        assertEquals(2400.0, widened.kcal, 1e-9)
    }

    @Test
    fun `two shifts widen by the LARGER of them and not by their product`() {
        // ⚠️ The rule that matters. Both inflations are 1.5, so the product would be 2.25 and give
        // 337.5 — a claim about uncertainty that neither fact supports on its own. The max is 1.5,
        // so 150 × 1.5 = 225, the same as one shift.
        val bothMoved = Expenditure.widenForShifts(
            measured(150.0),
            shiftedSteps(),
            Expenditure.intakeShift(split(14, 2600.0, 6, 2000.0), now),
        )
        assertTrue("the step fixture must actually have shifted", shiftedSteps().changed)
        assertEquals(225.0, bothMoved.sdKcal, 1e-9)
        assertTrue(bothMoved.sdKcal < 150.0 * 2.25)
    }

    @Test
    fun `the one-shift overload still behaves exactly as it did`() {
        // ⚠️ It has a caller and its own tests; this pins that the new plural form did not change it.
        val e = measured(150.0)
        assertEquals(e, Expenditure.widenForShift(e, steadySteps()))
        assertEquals(225.0, Expenditure.widenForShift(e, shiftedSteps()).sdKcal, 1e-9)
    }
}
