package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ Every expected value is computed from the shipped formula, with the arithmetic in the comment.
 */
class MaintenanceTest {

    private val day = 86_400_000L
    private val now = 1_700_000_000_000L

    private fun reading(daysAgo: Int, kcal: Double, window: Double = 28.0) =
        Maintenance.Reading(now - daysAgo * day, kcal, window)

    // ---------------------------------------------------------------------------------- recovery

    @Test
    fun `nothing recorded is a refusal that says so, not a zero`() {
        val r = Maintenance.recovery(emptyList(), now)
        assertTrue(r is Maintenance.Recovery.TooSoon)
        assertTrue((r as Maintenance.Recovery.TooSoon).sentence.contains("nothing to compare"))
    }

    @Test
    fun `readings inside one window are refused, because they share their data`() {
        // A 28-day window, readings 0 and 20 days ago: 20 < 28, so most of both rest on the same
        // days and any difference is the window turning over.
        val readings = listOf(reading(20, 2_100.0), reading(0, 2_400.0))
        val r = Maintenance.recovery(readings, now)
        assertTrue("got $r", r is Maintenance.Recovery.TooSoon)
        val soon = r as Maintenance.Recovery.TooSoon
        assertEquals(28.0, soon.needDays, 1e-9)
        assertEquals(20.0, soon.haveDays, 1e-9)
        assertTrue(soon.sentence, soon.sentence.contains("8 days"))
    }

    @Test
    fun `a rise across independent readings is measured and named`() {
        // 30 days apart, which clears the 28-day window. 2100 -> 2400 = +300, well past the 75 floor.
        val readings = listOf(reading(30, 2_100.0), reading(0, 2_400.0))
        val r = Maintenance.recovery(readings, now) as Maintenance.Recovery.Measured
        assertEquals(300.0, r.deltaKcal, 1e-9)
        assertEquals(30.0, r.spanDays, 1e-9)
        assertTrue(r.moved)
        assertTrue(r.sentence, r.sentence.contains("risen"))
    }

    @Test
    fun `a fall is reported as a fall rather than as an absolute`() {
        val readings = listOf(reading(30, 2_500.0), reading(0, 2_200.0))
        val r = Maintenance.recovery(readings, now) as Maintenance.Recovery.Measured
        assertEquals(-300.0, r.deltaKcal, 1e-9)
        assertTrue(r.sentence, r.sentence.contains("fallen"))
    }

    @Test
    fun `a change inside the noise is flat, which is an answer`() {
        // 2400 -> 2450 = +50, under the 75 floor.
        val readings = listOf(reading(35, 2_400.0), reading(0, 2_450.0))
        val r = Maintenance.recovery(readings, now) as Maintenance.Recovery.Measured
        assertFalse(r.moved)
        assertTrue(r.sentence, r.sentence.contains("about where it was"))
    }

    @Test
    fun `the comparison is against the CLOSEST independent reading, not the oldest`() {
        // ⚠️ The oldest describes a person further away and would exaggerate every change. With a
        // 28-day window and readings at 90, 40 and 0 days ago, the fair partner for "now" is the
        // 40-day one — the closest that is still at least 28 days back.
        val readings = listOf(
            reading(90, 1_800.0),
            reading(40, 2_200.0),
            reading(0, 2_400.0),
        )
        val r = Maintenance.recovery(readings, now) as Maintenance.Recovery.Measured
        assertEquals(2_200.0, r.fromKcal, 1e-9)
        assertEquals(200.0, r.deltaKcal, 1e-9)
        assertEquals(40.0, r.spanDays, 1e-9)
    }

    @Test
    fun `a nonsense reading is dropped rather than compared`() {
        val readings = listOf(
            reading(40, Double.NaN),
            reading(35, 2_200.0),
            reading(0, 2_400.0),
        )
        val r = Maintenance.recovery(readings, now) as Maintenance.Recovery.Measured
        assertEquals(2_200.0, r.fromKcal, 1e-9)
    }

    // ----------------------------------------------------------------------------------- step up

    @Test
    fun `stepping up goes straight to what was measured, with the difference named`() {
        val up = Maintenance.stepUp(currentTargetKcal = 1_900, measuredKcal = 2_450.0)
        assertEquals(2_450, up.toKcal)
        assertEquals(550, up.deltaKcal)
        assertTrue(up.sentence, up.sentence.contains("550 more"))
        assertTrue(up.sentence, up.sentence.contains("No slow ramp"))
    }

    @Test
    fun `a target already above the measurement is named as that, not as a step up`() {
        // ⚠️ The case somebody in this situation most needs told plainly: the target is not a deficit
        // waiting to be ended, it is running ahead of what has actually been measured.
        val up = Maintenance.stepUp(currentTargetKcal = 2_600, measuredKcal = 2_400.0)
        assertEquals(-200, up.deltaKcal)
        assertTrue(up.sentence, up.sentence.contains("LESS"))
    }

    @Test
    fun `already there says so`() {
        val up = Maintenance.stepUp(currentTargetKcal = 2_400, measuredKcal = 2_400.0)
        assertEquals(0, up.deltaKcal)
        assertTrue(up.sentence, up.sentence.contains("already"))
    }

    @Test
    fun `an unusable measurement leaves the target where it is`() {
        val up = Maintenance.stepUp(currentTargetKcal = 2_000, measuredKcal = Double.NaN)
        assertEquals(2_000, up.toKcal)
    }

    // ------------------------------------------------------------------------------- confirmation

    @Test
    fun `a real rate gets a real wait, computed from the trend's own margin`() {
        // rate 0.5 kg a week -> 0.0714286 kg a day.  2 * 0.3 / 0.0714286 = 8.4 days -> 8.
        val c = Maintenance.confirmIn(ratePerWeekKg = -0.5, trendSdKg = 0.3) as Maintenance.Confirmation.InDays
        assertEquals(8, c.days)
        // ⚠️ The sentence must say it is not a forecast of the result. That distinction is the whole
        // reason this returns a wait rather than an outcome.
        assertTrue(c.sentence, c.sentence.contains("not a forecast"))
    }

    @Test
    fun `the direction of the rate does not change how long it takes to see`() {
        val losing = Maintenance.confirmIn(-0.5, 0.3) as Maintenance.Confirmation.InDays
        val gaining = Maintenance.confirmIn(0.5, 0.3) as Maintenance.Confirmation.InDays
        assertEquals(losing.days, gaining.days)
    }

    @Test
    fun `maintaining is never confirmed by a change, and says why`() {
        val c = Maintenance.confirmIn(0.0, 0.3)
        assertTrue(c is Maintenance.Confirmation.Never)
        assertTrue((c as Maintenance.Confirmation.Never).sentence.contains("hold steady"))
    }

    @Test
    fun `a rate too slow to separate from noise is never confirmed rather than given a long wait`() {
        // rate 0.05 kg a week -> 0.00714286 a day.  2 * 0.5 / 0.00714286 = 140 days > the 120 cap.
        val c = Maintenance.confirmIn(-0.05, 0.5)
        assertTrue("got $c", c is Maintenance.Confirmation.Never)
        assertTrue((c as Maintenance.Confirmation.Never).sentence.contains("slower than the scale"))
    }

    @Test
    fun `no trend margin yet is its own refusal`() {
        val c = Maintenance.confirmIn(-0.5, Double.NaN)
        assertTrue(c is Maintenance.Confirmation.Never)
        assertTrue((c as Maintenance.Confirmation.Never).sentence.contains("weigh-ins"))
    }

    @Test
    fun `a perfectly certain trend still needs a day rather than none`() {
        // 2 * 0.0 / rate = 0 days, and "we can tell you now" is not something a wait should say.
        val c = Maintenance.confirmIn(-0.5, 0.0) as Maintenance.Confirmation.InDays
        assertEquals(1, c.days)
    }
}
