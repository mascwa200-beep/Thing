package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ Every expected number below is computed from the shipped formula with the arithmetic left in
 * the comment. That habit is recorded in this repository roughly seventeen times over, and it earned
 * itself again here: working the open-ended case by hand is what showed that the *likely* arrival
 * also has to be capped, not only the far end.
 *
 * `INTERVAL_SDS` is [BodyTrend.RATE_CLEAR_SDS] = 1.96 throughout.
 */
class GoalProjectionTest {

    /** A trend point with only the fields this core reads; the rest are inert here. */
    private fun point(
        trendKg: Double,
        trendSdKg: Double,
        ratePerDayKg: Double,
        rateSdPerDayKg: Double,
    ) = BodyTrend.Point(
        atMs = 0L,
        observedKg = trendKg,
        trendKg = trendKg,
        trendSdKg = trendSdKg,
        ratePerDayKg = ratePerDayKg,
        rateSdPerDayKg = rateSdPerDayKg,
        suspect = false,
    )

    // Half a kilogram a week, in kilograms per day: 0.5 / 7 = 0.0714285714…
    private val halfAKiloAWeek = 0.5 / 7.0

    @Test
    fun `a clear loss toward a goal gives a real range`() {
        // trend 85.0 ± 0.2, losing 0.5 kg/week ± 0.07 kg/week, goal 80.0.
        //   rate is clear: 0.0714286 > 1.96 × 0.01 = 0.0196            ✓
        //   distance = 80 − 85 = −5.0, reach = 1.96 × 0.2 = 0.392, so not Arrived
        //   fastSpeed = 0.0714286 + 0.0196 = 0.0910286
        //   slowSpeed = 0.0714286 − 0.0196 = 0.0518286
        //   nearGap   = 5.0 − 0.392 = 4.608 ; farGap = 5.0 + 0.392 = 5.392
        //   soonest = 4.608 / 0.0910286 = 50.6215 days
        //   likely  = 5.0    / 0.0714286 = 70.0    days   (10 weeks at half a kilo)
        //   latest  = 5.392 / 0.0518286 = 104.035 days
        val p = point(85.0, 0.2, -halfAKiloAWeek, 0.01)
        val r = GoalProjection.project(p, hasRate = true, goalKg = 80.0)

        val projected = r as GoalProjection.Projection.Projected
        assertEquals(-5.0, projected.distanceKg, 1e-9)
        assertEquals(50.6215, projected.soonestDays, 1e-3)
        assertEquals(70.0, projected.likelyDays, 1e-9)
        assertNotNull(projected.latestDays)
        assertEquals(104.035, projected.latestDays!!, 1e-3)

        // span(50.6215) → 14 ≤ d < 100 → round(50.6215 / 7) = round(7.2316) = 7 weeks
        // span(70.0)    → round(10.0) = 10 weeks
        // span(104.035) → ≥ 100 → round(104.035 / 30.44) = round(3.4177) = 3 months
        assertEquals(
            "If the last few weeks carry on, 5.0 kg to go — somewhere between 7 weeks and 3 months, " +
                "most likely 10 weeks.",
            projected.sentence,
        )
    }

    @Test
    fun `a rate whose interval spans zero is refused rather than projected`() {
        // 0.01 kg/day down with an SD of 0.01: 0.01 > 1.96 × 0.01 = 0.0196 is FALSE, so the rate is
        // not clear and the reciprocal has no bounded quantiles. See the class note.
        val p = point(85.0, 0.2, -0.01, 0.01)
        val r = GoalProjection.project(p, hasRate = true, goalKg = 80.0)
        val notYet = r as GoalProjection.Projection.NotYet
        assertTrue(notYet.sentence.contains("holding steady"))
    }

    @Test
    fun `being at the goal is its own answer and does not need a rate`() {
        // distance = 80.0 − 80.1 = −0.1; reach = 1.96 × 0.2 = 0.392; |−0.1| ≤ 0.392 → Arrived.
        // ⚠️ The rate here is deliberately NOT clear (0.01 against 1.96 × 0.01). Arriving is checked
        // first on purpose: standing at your goal is true whether or not the scale can name a
        // direction, and reporting "holding steady within the noise" to somebody who has finished
        // would be answering a question they did not ask.
        val p = point(80.1, 0.2, -0.01, 0.01)
        val r = GoalProjection.project(p, hasRate = true, goalKg = 80.0)
        val arrived = r as GoalProjection.Projection.Arrived
        assertEquals(80.1, arrived.trendKg, 1e-9)
        assertTrue(arrived.sentence.contains("as near as the scale can tell"))
    }

    @Test
    fun `a clear rate pointing the wrong way says so rather than quoting a negative date`() {
        // Goal is below (distance −5.0) and the trend is rising, so `towardGoal` is false.
        // direction(+0.5 kg/week) → fmt(0.5) is below one, so two decimals: "up 0.50 kg a week".
        // fmt(5.0) is at or above one, so one decimal: "5.0 kg".
        val p = point(85.0, 0.2, halfAKiloAWeek, 0.01)
        val r = GoalProjection.project(p, hasRate = true, goalKg = 80.0)
        val away = r as GoalProjection.Projection.MovingAway
        assertEquals(-5.0, away.distanceKg, 1e-9)
        assertEquals(
            "Carrying on as you are moves you away from your goal, not toward it — up 0.50 kg a week " +
                "against a goal 5.0 kg the other way.",
            away.sentence,
        )
    }

    @Test
    fun `past the horizon neither end of the range is quoted`() {
        // 2 g/day with an SD of 1 g/day: clear, but barely — 0.002 > 1.96 × 0.001 = 0.00196.
        //   slowSpeed = 0.002 − 0.00196 = 0.00004
        //   farGap    = 5.0 + 0.392     = 5.392
        //   latestRaw = 5.392 / 0.00004 = 134,800 days → past 730 → null
        //   likely    = 5.0   / 0.002   = 2,500 days   → ALSO past 730
        // ⚠️ This is the case that found the defect: `span(2500)` is "82 months", which is exactly the
        // unusable far date the horizon exists to suppress. Neither figure is quoted.
        val p = point(85.0, 0.2, -0.002, 0.001)
        val r = GoalProjection.project(p, hasRate = true, goalKg = 80.0)
        val projected = r as GoalProjection.Projection.Projected
        assertNull(projected.latestDays)
        assertTrue(projected.openEnded)
        assertEquals(2500.0, projected.likelyDays, 1e-6)
        assertEquals(
            "If the last few weeks carry on, 5.0 kg to go — but at the rate you are actually moving " +
                "that is further off than this is worth putting a date on.",
            projected.sentence,
        )
        assertTrue("82 months" !in projected.sentence)
    }

    @Test
    fun `an open far end still quotes the likely arrival when that is inside the horizon`() {
        // Chosen so the far end runs past 730 while the middle does not.
        //   speed 0.0102 kg/day, SD 0.005 → clear: 0.0102 > 1.96 × 0.005 = 0.0098
        //   slowSpeed = 0.0102 − 0.0098 = 0.0004
        //   farGap    = 5.392 ; latestRaw = 5.392 / 0.0004 = 13,480 days → null
        //   likely    = 5.0 / 0.0102 = 490.196 days, inside 730, so it IS quoted
        //   span(490.196) → round(490.196 / 30.44) = round(16.10) = 16 months
        val p = point(85.0, 0.2, -0.0102, 0.005)
        val r = GoalProjection.project(p, hasRate = true, goalKg = 80.0)
        val projected = r as GoalProjection.Projection.Projected
        assertNull(projected.latestDays)
        assertEquals(490.196, projected.likelyDays, 1e-3)
        assertEquals(
            "If the last few weeks carry on, 5.0 kg to go — about 16 months, though at the slow end " +
                "of your current rate it could be a good deal longer than this is worth guessing at.",
            projected.sentence,
        )
    }

    @Test
    fun `no goal and no trend are refused separately`() {
        val p = point(85.0, 0.2, -halfAKiloAWeek, 0.01)
        assertTrue(
            (GoalProjection.project(p, true, goalKg = 0.0) as GoalProjection.Projection.NotYet)
                .sentence.contains("No goal weight set"),
        )
        val noTrend = point(0.0, 0.2, -halfAKiloAWeek, 0.01)
        assertTrue(
            (GoalProjection.project(noTrend, true, goalKg = 80.0) as GoalProjection.Projection.NotYet)
                .sentence.contains("No trend weight yet"),
        )
    }

    @Test
    fun `one weigh-in has no rate to count down with`() {
        val p = point(85.0, 0.2, -halfAKiloAWeek, 0.01)
        val r = GoalProjection.project(p, hasRate = false, goalKg = 80.0)
        assertTrue((r as GoalProjection.Projection.NotYet).sentence.contains("One weigh-in so far"))
    }

    @Test
    fun `pounds change the wording and not the arithmetic`() {
        // Identical to the first case; only the unit differs.
        //   gap 5.0 kg × 2.2046226 = 11.023 lb → fmt is at or above one → "11.0 lb"
        val p = point(85.0, 0.2, -halfAKiloAWeek, 0.01)
        val kg = GoalProjection.project(p, true, 80.0, BodyTrend.MassUnit.KG)
            as GoalProjection.Projection.Projected
        val lb = GoalProjection.project(p, true, 80.0, BodyTrend.MassUnit.LB)
            as GoalProjection.Projection.Projected
        assertEquals(kg.likelyDays, lb.likelyDays, 1e-9)
        assertEquals(kg.soonestDays, lb.soonestDays, 1e-9)
        assertTrue(lb.sentence.contains("11.0 lb to go"))
    }

    @Test
    fun `span reads in the unit somebody would use`() {
        assertEquals("a day", GoalProjection.span(0.4))
        assertEquals("a day", GoalProjection.span(1.0))
        // round(9.0) = 9, and 9 < 14 so it stays in days.
        assertEquals("9 days", GoalProjection.span(9.0))
        // ⚠️ The boundary, pinned in both directions: 13.9 is still days, 14.0 is weeks.
        assertEquals("14 days", GoalProjection.span(13.9))
        assertEquals("2 weeks", GoalProjection.span(14.0))
        // 99 / 7 = 14.14 → 14 weeks; 100 / 30.44 = 3.285 → 3 months.
        assertEquals("14 weeks", GoalProjection.span(99.0))
        assertEquals("3 months", GoalProjection.span(100.0))
        // ⚠️ Seven days is SEVEN DAYS, not "a week" — the weeks branch does not begin until
        // fourteen. My first assertion here said "a week" while the comment beside it said "days
        // branch", which is the same recurring carelessness this file opens by warning about: the
        // expected value has to be computed from the shipped function, not recalled.
        assertEquals("7 days", GoalProjection.span(7.0))
        assertEquals("an unknown time", GoalProjection.span(Double.NaN))
    }

    @Test
    fun spanNeverSaysOne() {
        // ⚠️ The guard behind `span`'s missing singulars. The weeks branch starts at fourteen days
        // (two weeks) and the months branch at a hundred (three months and a fortnight), so neither
        // can ever render a "1". Swept across the whole domain at a tenth of a day rather than
        // argued: if a boundary is lowered later this fails and asks for "a week" back.
        var d = 0.0
        while (d <= 800.0) {
            val s = GoalProjection.span(d)
            assertTrue("span($d) = $s", s != "1 weeks" && s != "1 months")
            d += 0.1
        }
        // And the two boundaries themselves, pinned exactly.
        assertEquals("2 weeks", GoalProjection.span(14.0))
        assertEquals("3 months", GoalProjection.span(100.0))
    }

    @Test
    fun `the projected instant is the day count off the given clock`() {
        val now = 1_700_000_000_000L
        // 70 days × 86,400,000 ms = 6,048,000,000 ms
        assertEquals(now + 6_048_000_000L, GoalProjection.atMs(now, 70.0))
    }
}
