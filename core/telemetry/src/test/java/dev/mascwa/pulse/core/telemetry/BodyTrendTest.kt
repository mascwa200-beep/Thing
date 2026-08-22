package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The weight trend, its rate, and the uncertainty on both.
 *
 * Every fixture here is either exactly derivable by hand (a flat series, a straight ramp, the pure
 * [BodyTrend.suspectNoiseFactor]) or is asserted against the **true** value that built it with a stated
 * tolerance — never against whatever the current implementation happens to emit. A test that freezes the
 * output guards nothing; a test that names the truth guards the estimator.
 */
class BodyTrendTest {

    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L

    private fun daily(vararg kg: Double): List<BodyTrend.Weighin> =
        kg.mapIndexed { i, v -> BodyTrend.Weighin(t0 + i * day, v) }

    private fun ramp(n: Int, from: Double, perDay: Double): List<BodyTrend.Weighin> =
        (0 until n).map { BodyTrend.Weighin(t0 + it * day, from + perDay * it) }

    private fun est(w: List<BodyTrend.Weighin>) = BodyTrend.estimate(w) as BodyTrend.Trend.Estimated

    // ------------------------------------------------------------------------------- nothing to say

    @Test
    fun noWeighinsIsNotATrend() {
        val t = BodyTrend.estimate(emptyList())
        assertTrue(t is BodyTrend.Trend.TooLittle)
        assertEquals(0, (t as BodyTrend.Trend.TooLittle).have)
        assertTrue(t.sentence.isNotBlank())
    }

    /** A scale that reports zero is reporting a failure, not a weight. So is one reporting NaN. */
    @Test
    fun impossibleReadingsAreDropped() {
        assertTrue(BodyTrend.estimate(daily(0.0, -3.0, Double.NaN)) is BodyTrend.Trend.TooLittle)
        val t = est(daily(0.0, 80.0, Double.NaN, 80.2))
        assertEquals("only the two real readings survive", 2, t.points.size)
    }

    @Test
    fun oneWeighinIsATrendWithNoRate() {
        val t = est(daily(80.0))
        assertEquals(80.0, t.latest.trendKg, 1e-9)
        assertFalse("one point cannot describe a rate", t.hasRate)
        assertEquals(0.0, t.latest.ratePerDayKg, 1e-9)
    }

    @Test
    fun theInputDoesNotHaveToArriveInOrder() {
        val shuffled = listOf(
            BodyTrend.Weighin(t0 + 2 * day, 79.0),
            BodyTrend.Weighin(t0, 80.0),
            BodyTrend.Weighin(t0 + 1 * day, 79.5),
        )
        val t = est(shuffled)
        assertEquals(listOf(t0, t0 + day, t0 + 2 * day), t.points.map { it.atMs })
        assertEquals(80.0, t.points.first().observedKg, 1e-9)
    }

    /**
     * Two readings at the same instant are two independent measurements of one weight, so both count and
     * both resolve to their mean.
     *
     * ⚠️ Derivable by hand, which is why it is worth asserting exactly. The first posterior is 80.0 with
     * variance R. The second update has no time to advance, so the prior is that posterior: the
     * innovation is 0.4, `S = R + R`, the gain is `R / 2R = ½`, and the level lands on 80.2. The backward
     * pass then has `C = P·P⁻¹ = I` across a zero-length step, so the earlier point is dragged onto the
     * later one exactly — which is right: there is only one instant here, so there can only be one
     * estimate of it. My first version of this test asserted the two would *differ*, which would have
     * meant the filter believed a person weighed two things at once.
     */
    @Test
    fun twoReadingsAtTheSameInstantResolveToTheirMean() {
        val t = est(listOf(BodyTrend.Weighin(t0, 80.0), BodyTrend.Weighin(t0, 80.4)))
        assertEquals(2, t.points.size)
        assertEquals(80.2, t.points[1].trendKg, 1e-9)
        assertEquals("one instant, one estimate of it", t.points[0].trendKg, t.points[1].trendKg, 1e-12)
        assertEquals("and each keeps its own reading", 80.0, t.points[0].observedKg, 1e-9)
        assertEquals(80.4, t.points[1].observedKg, 1e-9)
    }

    // ------------------------------------------------------------------------------------ the trend

    /** A perfectly flat record has a trend equal to the readings and a rate of exactly zero. */
    @Test
    fun aFlatRecordHasNoRate() {
        val t = est(ramp(29, 80.0, 0.0))
        assertEquals(80.0, t.latest.trendKg, 1e-9)
        assertEquals(0.0, t.latest.ratePerWeekKg, 1e-9)
        assertFalse("and nothing to declare about a direction", t.latest.rateIsClear)
    }

    /**
     * ⚠️ THE POINT OF THE WHOLE FILTER. A person weighing 1 kg either side of 80 on alternate mornings
     * has an unreadable scale and a perfectly steady weight, and the trend must say 80 rather than
     * whichever morning it is.
     */
    @Test
    fun alternatingNoiseIsSmoothedAway() {
        val t = est((0..40).map { BodyTrend.Weighin(t0 + it * day, 80.0 + if (it % 2 == 0) 1.0 else -1.0) })
        assertEquals("the newest reading is a whole kilogram out", 81.0, t.latest.observedKg, 1e-9)
        assertEquals("the trend is not", 80.0, t.latest.trendKg, 0.1)
        assertFalse("and a ±1 kg wobble is not a direction", t.latest.rateIsClear)
    }

    /**
     * A straight descent of 0.05 kg a day is −0.35 kg a week, and the estimator recovers it.
     *
     * ⚠️ The tolerance is not slack — it is a measured property. The smoother shrinks a noiseless
     * straight line very slightly, because it is told the readings carry 0.7 kg of noise and shrinks
     * toward its own model accordingly: measured, −0.3448 against a true −0.35, about 1.5% short. Two per
     * cent is therefore the bar, and a change that made it worse would fail here.
     */
    @Test
    fun aSteadyDescentRecoversItsRate() {
        val t = est(ramp(29, 85.0, -0.05))
        assertEquals(-0.35, t.latest.ratePerWeekKg, 0.35 * 0.02)
        assertTrue("half a kilo a month over a month is not noise", t.latest.rateIsClear)
        assertEquals("and the weight change with it", -1.4, t.points.last().trendKg - t.points.first().trendKg, 1.4 * 0.03)
    }

    @Test
    fun aSteadyClimbRecoversItsRate() {
        val t = est(ramp(29, 70.0, 0.05))
        assertEquals(0.35, t.latest.ratePerWeekKg, 0.35 * 0.02)
        assertTrue(t.latest.rateIsClear)
    }

    /**
     * ⚠️ THE HONEST LIMIT, and it is a *feature* rather than a shortfall.
     *
     * A real climb of 0.21 kg a week over four weeks of daily weigh-ins is genuinely not distinguishable
     * from noise at 95% — the interval on the rate is ±0.15, so the bar is ±0.30. The estimator recovers
     * the number to within 1.5% and still declines to state a direction, and that is the correct answer:
     * the *slope* is well estimated, the *sign* is not yet certain, and only one of those is safe to act
     * on. Give it another fortnight and it clears.
     *
     * I originally wrote this case expecting a direction and was wrong; the code was right.
     */
    @Test
    fun aGentleClimbIsRecoveredButNotYetDeclared() {
        val short = est(ramp(29, 70.0, 0.03))
        assertEquals("the number is right", 0.21, short.latest.ratePerWeekKg, 0.21 * 0.02)
        assertFalse("but four weeks cannot tell its sign from zero", short.latest.rateIsClear)

        val longer = est(ramp(57, 70.0, 0.03))
        assertEquals(0.21, longer.latest.ratePerWeekKg, 0.21 * 0.02)
        assertTrue("a tighter interval is what earns the direction",
            longer.latest.rateSdPerWeekKg < short.latest.rateSdPerWeekKg)
    }

    /**
     * ⚠️ THE HONESTY BAR. A direction is stated only when its interval excludes zero. Deleting the
     * [BodyTrend.RATE_CLEAR_SDS] test would let a wobble be reported as a diet.
     */
    @Test
    fun aDirectionIsOnlyDeclaredWhenTheIntervalExcludesZero() {
        // Built so the two sit either side of the bar rather than at the extremes, or the guard is
        // never reached: a flat series scores zero and a steep one is obvious.
        val quiet = est(ramp(29, 80.0, 0.0))
        assertFalse(quiet.latest.rateIsClear)
        assertTrue(
            "the quiet case must be inside the bar, not merely small",
            abs(quiet.latest.ratePerWeekKg) < BodyTrend.RATE_CLEAR_SDS * quiet.latest.rateSdPerWeekKg,
        )

        val real = est(ramp(29, 85.0, -0.05))
        assertTrue(real.latest.rateIsClear)
        assertTrue(
            abs(real.latest.ratePerWeekKg) > BodyTrend.RATE_CLEAR_SDS * real.latest.rateSdPerWeekKg,
        )
    }

    /**
     * ⚠️ THE BACKWARD SMOOTHER, guarded by the one property that is deterministic.
     *
     * A forward filter leaves the earliest reading exactly where the scale put it and has to walk in from
     * there, so the early part of a record sags off a straight line by around 0.09 kg. The smoother
     * re-estimates those points using the whole record and every one lands on the line: measured, a worst
     * deviation of 0.021 kg with it against 0.095 without.
     *
     * ⚠️ This is a *proxy*. What the smoother is really worth is variance on noisy data — the first
     * point's spread falls from ±0.73 kg to ±0.23, which halves the interval on the measured expenditure
     * — and that cannot be asserted from a deterministic fixture. It also cannot be asserted from the
     * newest point, which is identical either way because there is no future to smooth it with; my first
     * attempt at this guard did exactly that and slept through the smoother being deleted.
     */
    @Test
    fun theSmootherPutsEveryPastPointBackOnTheLine() {
        val t = est(ramp(29, 85.0, -0.05))
        var worst = 0.0
        for (k in t.points.indices) {
            val off = t.points[k].trendKg - (85.0 - 0.05 * k)
            if (abs(off) > abs(worst)) worst = off
        }
        assertTrue("worst deviation from the line was $worst kg", abs(worst) < 0.05)
    }

    /** Fewer readings, further apart, must widen the interval rather than break. */
    @Test
    fun aGapInTheRecordWidensTheIntervalRatherThanBreaking() {
        val dense = est(ramp(15, 80.0, -0.05))
        val sparse = est(listOf(0, 7, 14).map { BodyTrend.Weighin(t0 + it * day, 80.0 - 0.05 * it) })
        assertTrue("three readings a week apart know less than fifteen daily ones",
            sparse.latest.rateSdPerWeekKg > dense.latest.rateSdPerWeekKg)
        assertTrue(sparse.latest.rateSdPerWeekKg.isFinite())
        assertTrue(sparse.latest.trendSdKg.isFinite())
    }

    // --------------------------------------------------------------------------- suspect readings

    /**
     * The suppression is continuous: a reading exactly on the gate is untouched, and past it the widening
     * grows as the square of how far out it fell.
     */
    @Test
    fun theSuspectFactorIsContinuousAtTheGateAndSquaredBeyondIt() {
        // gate = OUTLIER_GATE_SDS × predictionSd = 4 × 1 = 4
        assertEquals(1.0, BodyTrend.suspectNoiseFactor(1.0, 1.0), 1e-12)
        assertEquals("exactly on the gate is not yet suspect", 1.0, BodyTrend.suspectNoiseFactor(4.0, 1.0), 1e-12)
        assertEquals("twice the gate widens by four", 4.0, BodyTrend.suspectNoiseFactor(8.0, 1.0), 1e-12)
        assertEquals("ten times the gate widens by a hundred", 100.0, BodyTrend.suspectNoiseFactor(40.0, 1.0), 1e-12)
        // gate = 4 × 0.5 = 2, so an innovation of 2 sits exactly on it
        assertEquals(1.0, BodyTrend.suspectNoiseFactor(2.0, 0.5), 1e-12)
        assertEquals(4.0, BodyTrend.suspectNoiseFactor(-4.0, 0.5), 1e-12)
        // Direction is irrelevant.
        assertEquals(
            BodyTrend.suspectNoiseFactor(8.0, 1.0),
            BodyTrend.suspectNoiseFactor(-8.0, 1.0),
            1e-12,
        )
        // A degenerate prediction cannot be judged against.
        assertEquals(1.0, BodyTrend.suspectNoiseFactor(8.0, 0.0), 1e-12)
        assertEquals(1.0, BodyTrend.suspectNoiseFactor(8.0, Double.NaN), 1e-12)
    }

    /**
     * ⚠️ THE GUARD THE GRADUATED SUPPRESSION EXISTS FOR. A dropped decimal point puts 800 kg into an
     * otherwise flat record, and it must be almost powerless — the earlier fixed twenty-five-fold
     * widening let a reading like this drag the trend by kilograms, which is hundreds of calories on the
     * target derived from it.
     */
    @Test
    fun oneAbsurdReadingCannotMoveTheTrend() {
        val clean = ramp(29, 80.0, 0.0).toMutableList()
        clean[14] = clean[14].copy(kg = 800.0)
        val t = est(clean)
        assertTrue("and it is flagged", t.points[14].suspect)
        assertEquals("the point itself barely moves", 80.0, t.points[14].trendKg, 0.05)
        assertEquals("and the answer the coach reads does not", 80.0, t.latest.trendKg, 0.05)
    }

    /**
     * ⚠️ AND THE GUARD THAT IT IS SUPPRESSION, NOT DELETION. Somebody who genuinely comes back three
     * kilograms heavier must be followed, or the trend would sit at the old weight forever and every
     * later reading would look like an outlier against it.
     */
    @Test
    fun aSustainedStepIsFollowed() {
        val step = (0..60).map { BodyTrend.Weighin(t0 + it * day, if (it < 20) 80.0 else 83.0) }
        val t = est(step)
        assertTrue("a fortnight later it must be most of the way there", t.points[34].trendKg > 82.0)
        assertTrue("and it must not overshoot wildly on the way", t.points[34].trendKg < 84.0)
        assertEquals("by two months it has arrived", 83.0, t.points[60].trendKg, 0.5)
    }

    /** A single reading three kilograms out is odd but plausible, so it is suppressed rather than ignored. */
    @Test
    fun anOddButPlausibleReadingStillCounts() {
        val clean = ramp(29, 80.0, 0.0).toMutableList()
        clean[14] = clean[14].copy(kg = 82.0)
        val t = est(clean)
        assertTrue("it moves the trend a little", t.points[14].trendKg > 80.0)
        assertTrue("but nowhere near all the way", t.points[14].trendKg < 81.0)
    }

    // ------------------------------------------------------------------------------------- reading

    @Test
    fun nearestHonoursItsTolerance() {
        val t = est(ramp(29, 80.0, -0.05))
        assertEquals(t0 + 10 * day, BodyTrend.nearest(t, t0 + 10 * day, 1.0)?.atMs)
        // Half a day off, with a one-day tolerance.
        assertNotNull(BodyTrend.nearest(t, t0 + 10 * day + day / 2, 1.0))
        // Ten days before the record starts, with a two-day tolerance.
        assertNull(BodyTrend.nearest(t, t0 - 10 * day, 2.0))
        // A tolerance of zero only matches an exact instant.
        assertNotNull(BodyTrend.nearest(t, t0 + 3 * day, 0.0))
        assertNull(BodyTrend.nearest(t, t0 + 3 * day + 1, 0.0))
    }

    @Test
    fun spanIsMeasuredBetweenTheOldestAndNewestReading() {
        assertEquals(28.0, BodyTrend.spanDays(est(ramp(29, 80.0, 0.0))), 1e-9)
        assertEquals(0.0, BodyTrend.spanDays(est(daily(80.0))), 1e-9)
    }

    // ------------------------------------------------------------------------------------- wording

    @Test
    fun aRateInsideTheNoiseIsSaidToBeSteady() {
        val t = est(ramp(29, 80.0, 0.0))
        val s = BodyTrend.rateSentence(t.latest, BodyTrend.MassUnit.KG)
        assertTrue(s, s.contains("steady", ignoreCase = true))
        assertFalse("and it must not name a direction", s.contains("Down") || s.contains("Up"))
    }

    @Test
    fun oneWeighinSaysSoRatherThanClaimingToBeSteady() {
        val t = est(daily(80.0))
        val s = BodyTrend.rateSentence(t.latest, BodyTrend.MassUnit.KG, hasRate = t.hasRate)
        assertTrue(s, s.contains("second", ignoreCase = true))
        assertFalse(s.contains("steady", ignoreCase = true))
    }

    @Test
    fun aClearRateIsSaidInTheReadersOwnUnit() {
        val t = est(ramp(29, 85.0, -0.05))
        val kg = BodyTrend.rateSentence(t.latest, BodyTrend.MassUnit.KG)
        assertTrue(kg, kg.startsWith("Down") && kg.contains("kg a week"))
        assertTrue(kg, kg.contains("0.3"))            // −0.35 kg/week

        val lb = BodyTrend.rateSentence(t.latest, BodyTrend.MassUnit.LB)
        assertTrue(lb, lb.startsWith("Down") && lb.contains("lb a week"))
        assertTrue("0.35 kg is 0.77 lb", lb.contains("0.7") || lb.contains("0.8"))
        assertFalse("and never both units at once", lb.contains("kg"))
    }

    @Test
    fun aClimbIsSaidAsAClimb() {
        val t = est(ramp(29, 70.0, 0.05))
        assertTrue(BodyTrend.rateSentence(t.latest, BodyTrend.MassUnit.KG).startsWith("Up"))
    }

    @Test
    fun theTrendSentenceCarriesBothNumbers() {
        val t = est((0..40).map { BodyTrend.Weighin(t0 + it * day, 80.0 + if (it % 2 == 0) 1.0 else -1.0) })
        val s = BodyTrend.trendSentence(t.latest, BodyTrend.MassUnit.KG)
        assertTrue(s, s.contains("80.0"))
        assertTrue("and what the scale actually said", s.contains("81.0"))
    }
}
