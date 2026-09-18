package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Measuring what somebody actually burns, and refusing to when it cannot be measured.
 *
 * The central assertion is [theIdentityRecoversAKnownExpenditure]: a record is built from a *known*
 * expenditure and the estimator has to find it. Every other case here is about the refusals, which are
 * the more important half — an expenditure figure printed too early is a wrong number a person sets
 * their diet by.
 */
class ExpenditureTest {

    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L

    /**
     * A noiseless record: weight walks at [kgPerDay] from [startKg], intake is [kcal] every day.
     *
     * Both sides are exact, so the expenditure the estimator should find is exact too:
     * `kcal − 7700 × kgPerDay`.
     */
    private fun record(days: Int, startKg: Double, kgPerDay: Double, kcal: Double):
        Pair<List<BodyTrend.Weighin>, List<Expenditure.IntakeDay>> =
        (0 until days).map { BodyTrend.Weighin(t0 + it * day, startKg + kgPerDay * it) } to
            (0 until days).map { Expenditure.IntakeDay(t0 + it * day, kcal) }

    private fun measure(
        w: List<BodyTrend.Weighin>,
        i: List<Expenditure.IntakeDay>,
        nowOffsetDays: Int = 0,
        window: Int = Expenditure.DEFAULT_WINDOW_DAYS,
    ): Expenditure.Estimate {
        val last = w.maxOf { it.atMs }
        return Expenditure.measure(BodyTrend.estimate(w), i, last + nowOffsetDays * day, window)
    }

    // ------------------------------------------------------------------------------------ fasting

    /**
     * ⚠️ THE DEFECT THIS FIXES. The predicate used to be `kcal > 0`, so a day somebody deliberately
     * fasted was dropped from the window and counted against completeness — identical treatment to a
     * day they forgot to log. Both halves are wrong, and the second is the worse one: a long enough
     * run of fasts could push a scrupulous tracker to NotYet for tracking too well.
     */
    @Test
    fun `a marked fast is a record and an absent day is not`() {
        assertTrue(Expenditure.IntakeDay(t0, 0.0, fasted = true).counted)
        assertFalse(Expenditure.IntakeDay(t0, 0.0, fasted = false).counted)
        assertTrue(Expenditure.IntakeDay(t0, 2000.0).counted)
        // NaN is never a record however it is marked
        assertFalse(Expenditure.IntakeDay(t0, Double.NaN, fasted = true).counted)
    }

    /**
     * The behavioural half: two identical 28-day records, one where five zero-calorie days are marked
     * as fasts and one where the same five days are simply missing. The marked run must be measurable;
     * the unmarked one is a less complete window and must report fewer logged days.
     */
    @Test
    fun `marking a fast keeps the day in the window instead of dropping it`() {
        val (w, full) = record(28, 80.0, -0.05, 2200.0)
        val fastDays = setOf(3, 7, 11, 15, 19)

        val marked = full.mapIndexed { i, d ->
            if (i in fastDays) Expenditure.IntakeDay(d.dayStartMs, 0.0, fasted = true) else d
        }
        val absent = full.filterIndexed { i, _ -> i !in fastDays }

        val withFasts = measure(w, marked)
        val withGaps = measure(w, absent)

        assertTrue("a marked run should still measure, got $withFasts", withFasts is Expenditure.Estimate.Known)
        val known = withFasts as Expenditure.Estimate.Known

        // The five fasts are inside the window and counted.
        val loggedWithGaps = when (withGaps) {
            is Expenditure.Estimate.Known -> withGaps.loggedDays
            is Expenditure.Estimate.Doubtful -> withGaps.loggedDays
            is Expenditure.Estimate.NotYet -> withGaps.loggedDays
        }
        assertTrue(
            "marked run logged ${known.loggedDays}, gapped run logged $loggedWithGaps",
            known.loggedDays > loggedWithGaps,
        )
        assertTrue("completeness ${known.completeness}", known.completeness > 0.9)
    }

    /**
     * ⚠️ And the fast is worth zero, not skipped. Five zero days inside a 2200 kcal run must pull the
     * measured expenditure DOWN — if a fast were merely counted for completeness while its calories
     * were ignored, the mean would be unchanged and this would fail.
     */
    @Test
    fun `a fast contributes zero calories to the mean rather than being skipped`() {
        val (w, full) = record(28, 80.0, -0.05, 2200.0)
        val fastDays = setOf(3, 7, 11, 15, 19)
        val marked = full.mapIndexed { i, d ->
            if (i in fastDays) Expenditure.IntakeDay(d.dayStartMs, 0.0, fasted = true) else d
        }
        val plain = measure(w, full) as Expenditure.Estimate.Known
        val fasted = measure(w, marked) as Expenditure.Estimate.Known
        assertTrue(
            "plain ${plain.kcal}, with fasts ${fasted.kcal}",
            fasted.kcal < plain.kcal - 200.0,
        )
    }

    // -------------------------------------------------------------------------- it refuses, and says why

    @Test
    fun withNoWeighinsThereIsNothingToMeasureAgainst() {
        val e = Expenditure.measure(BodyTrend.Trend.TooLittle(0, "none"), emptyList(), t0)
        assertTrue(e is Expenditure.Estimate.NotYet)
        assertTrue((e as Expenditure.Estimate.NotYet).why.contains("weigh", ignoreCase = true))
    }

    @Test
    fun oneWeighinInTheWindowIsNotASpan() {
        val (w, i) = record(30, 85.0, -0.05, 2000.0)
        // A three-day window can only reach one weigh-in's worth of useful span.
        val e = Expenditure.measure(BodyTrend.estimate(w), i, w.last().atMs, windowDays = 1)
        assertTrue(e is Expenditure.Estimate.NotYet)
    }

    /**
     * ⚠️ THE THREE-WEEK FLOOR. Below it the answer's spread runs past ±200 calories and the interval
     * under-states it, so nothing is printed at all.
     */
    @Test
    fun aFortnightIsNotEnoughSpan() {
        val (w, i) = record(15, 85.0, -0.05, 2000.0)     // 14 days of span
        val e = measure(w, i)
        assertTrue("14 days must be refused", e is Expenditure.Estimate.NotYet)
        assertEquals(Expenditure.MIN_SPAN_DAYS, (e as Expenditure.Estimate.NotYet).neededSpanDays, 1e-9)

        val (w2, i2) = record(23, 85.0, -0.05, 2000.0)   // 22 days of span
        assertTrue("22 days is over the line", measure(w2, i2) is Expenditure.Estimate.Known)
    }

    /** A window whose newest weigh-in is a fortnight old describes a fortnight ago, not now. */
    @Test
    fun aStaleWeighinCannotDescribeToday() {
        val (w, i) = record(30, 85.0, -0.05, 2000.0)
        val e = measure(w, i, nowOffsetDays = 10)
        assertTrue(e is Expenditure.Estimate.NotYet)
        assertTrue((e as Expenditure.Estimate.NotYet).why.contains("scale", ignoreCase = true))
    }

    @Test
    fun tooFewLoggedDaysIsARefusal() {
        val (w, all) = record(30, 85.0, -0.05, 2000.0)
        val sparse = all.filterIndexed { idx, _ -> idx % 5 == 0 }   // 6 of 29 days
        val e = measure(w, sparse)
        assertTrue(e is Expenditure.Estimate.NotYet)
        val why = (e as Expenditure.Estimate.NotYet).why
        assertTrue(why, why.contains("logged"))
        assertTrue("and it names how many, so the gap is actionable", why.any { it.isDigit() })
    }

    /**
     * ⚠️ Enough days in absolute terms but not enough of the window: half-logged is a biased sample, not
     * a smaller one, because the days people skip are the days they eat most.
     */
    @Test
    fun aHalfLoggedWindowIsARefusalEvenWithPlentyOfDays() {
        val (w, all) = record(40, 85.0, -0.05, 2000.0)
        val half = all.filterIndexed { idx, _ -> idx % 2 == 0 }     // ~20 days of 39, above MIN_LOGGED_DAYS
        assertTrue("the absolute count is not the binding limit here", half.size > Expenditure.MIN_LOGGED_DAYS)
        val e = measure(w, half)
        assertTrue(e is Expenditure.Estimate.NotYet)
    }

    // ------------------------------------------------------------------------------- the measurement

    /**
     * ⚠️ THE WHOLE FEATURE IN ONE ASSERTION.
     *
     * Someone eating exactly 2,000 calories a day who loses exactly 0.05 kg a day is, by the energy
     * identity, burning `2000 + 7700 × 0.05 = 2385`. The estimator is given only the weigh-ins and the
     * intake and has to find that number.
     *
     * The tolerance is a measured property, not slack: on a *noiseless* straight line the smoother
     * shrinks the weight change by about two per cent (it is told the readings carry 0.7 kg of noise), so
     * the recovered figure lands about ten calories low — 2,376 measured against 2,385. One per cent is
     * the bar. On realistic *noisy* records there is no meaningful bias at all: measured at −11, −12, −4,
     * −2 and −4 calories across spans of three weeks to three months.
     */
    @Test
    fun theIdentityRecoversAKnownExpenditure() {
        val (w, i) = record(29, 85.0, -0.05, 2000.0)
        val e = measure(w, i) as Expenditure.Estimate.Known
        val truth = 2000.0 + Expenditure.KCAL_PER_KG * 0.05
        assertEquals(2385.0, truth, 1e-9)
        assertEquals(truth, e.kcal, truth * 0.01)
        assertEquals(Expenditure.Source.MEASURED, e.source)
        assertEquals(28, e.loggedDays)
        assertEquals(1.0, e.completeness, 1e-9)
        assertTrue("and it must carry a real interval", e.sdKcal > 0.0 && e.sdKcal < 300.0)
    }

    /** Weight that does not move means intake and expenditure are the same thing. */
    @Test
    fun aFlatWeightMeansYouAreEatingWhatYouBurn() {
        val (w, i) = record(29, 85.0, 0.0, 2400.0)
        val e = measure(w, i) as Expenditure.Estimate.Known
        assertEquals(2400.0, e.kcal, 5.0)
    }

    /** Gaining on a given intake means burning less than it. */
    @Test
    fun gainingMeansBurningLessThanYouEat() {
        val (w, i) = record(29, 70.0, 0.04, 3000.0)
        val e = measure(w, i) as Expenditure.Estimate.Known
        val truth = 3000.0 - Expenditure.KCAL_PER_KG * 0.04
        assertEquals(2692.0, truth, 1e-9)
        assertEquals(truth, e.kcal, truth * 0.02)
        assertTrue(e.kcal < 3000.0)
    }

    /** A body cannot burn two hundred calories a day, so the arithmetic is reported as a data problem. */
    @Test
    fun anImpossibleAnswerIsCalledOutRatherThanReturned() {
        // Half a kilogram a day gained on 2,000 calories: 2000 − 7700×0.5 = −1,850.
        val (w, i) = record(29, 85.0, 0.5, 2000.0)
        val e = measure(w, i)
        assertTrue(e is Expenditure.Estimate.Doubtful)
        val d = e as Expenditure.Estimate.Doubtful
        assertTrue(d.kcal < Expenditure.PLAUSIBLE_MIN_KCAL)
        assertTrue(d.why, d.why.contains("unlogged", ignoreCase = true) || d.why.contains("physics"))
    }

    /**
     * ⚠️ The finite-population correction, and it is not a nicety: the days in the window *are* the
     * population, so a fully logged window has no sampling error on its own mean at all. A partly logged
     * one is priced for the missingness instead, which must show up as a visibly wider answer.
     */
    @Test
    fun missingDaysWidenTheAnswerRatherThanBeingIgnored() {
        val (w, all) = record(40, 85.0, -0.05, 2000.0)
        val full = measure(w, all) as Expenditure.Estimate.Known
        // Drop one day in four — still above the completeness floor.
        val gappy = all.filterIndexed { idx, _ -> idx % 4 != 0 }
        val partial = measure(w, gappy) as Expenditure.Estimate.Known

        assertEquals(1.0, full.completeness, 1e-9)
        assertTrue(partial.completeness < 1.0 && partial.completeness >= Expenditure.MIN_COMPLETENESS)
        assertTrue(
            "a gappy log must report a wider interval (${partial.sdKcal} vs ${full.sdKcal})",
            partial.sdKcal > full.sdKcal * 1.2,
        )
    }

    // ---------------------------------------------------------------------------------- the formula

    @Test
    fun theFormulaEstimateIsRestingTimesActivity() {
        val bmr = 1797.5
        val e = Expenditure.fromFormula(bmr, Expenditure.Activity.MODERATE)
        assertEquals(bmr * 1.55, e.kcal, 1e-9)
        assertEquals(e.kcal * Expenditure.FORMULA_SD_FRACTION, e.sdKcal, 1e-9)
        assertEquals(Expenditure.Source.FORMULA, e.source)
        assertEquals("it measured nothing, and must not claim to have", 0, e.loggedDays)
    }

    @Test
    fun theActivityMultipliersRiseInOrder() {
        val m = Expenditure.Activity.entries.map { it.multiplier }
        assertEquals(m.sorted(), m)
        assertTrue(Expenditure.Activity.entries.all { it.label.isNotBlank() })
    }

    // ------------------------------------------------------------------------------------- the blend

    /**
     * Inverse-variance weighting, by hand:
     *
     * ```
     * wf = 1/360² = 1/129600 ; wm = 1/120² = 1/14400
     * kcal = (2400·wf + 2600·wm) / (wf + wm) = 2580
     * sd   = sqrt(1 / (wf + wm))            = 113.842
     * ```
     */
    @Test
    fun theBlendWeightsEachSideByItsOwnConfidence() {
        val formula = Expenditure.Estimate.Known(2400.0, 360.0, Expenditure.Source.FORMULA, 0.0, 0, 0.0)
        val measured = Expenditure.Estimate.Known(2600.0, 120.0, Expenditure.Source.MEASURED, 28.0, 28, 1.0)
        val wf = 1.0 / (360.0 * 360.0)
        val wm = 1.0 / (120.0 * 120.0)

        val b = Expenditure.blend(formula, measured)
        assertEquals((2400.0 * wf + 2600.0 * wm) / (wf + wm), b.kcal, 1e-9)
        assertEquals(2580.0, b.kcal, 1e-9)
        assertEquals(sqrt(1.0 / (wf + wm)), b.sdKcal, 1e-9)
        assertEquals(Expenditure.Source.BLENDED, b.source)
        assertTrue("the blend must be tighter than either side alone", b.sdKcal < measured.sdKcal)
        assertEquals("nine tenths of it is the measurement", wm / (wf + wm), Expenditure.measuredShare(formula, measured), 1e-9)
        assertEquals(0.9, Expenditure.measuredShare(formula, measured), 1e-9)
    }

    /**
     * ⚠️ THE REASON THERE IS NO "SWITCH TO ADAPTIVE AFTER N DAYS" SETTING. The changeover happens on its
     * own as the measurement tightens, with no threshold to pick and no day on which the number jumps.
     */
    @Test
    fun theMeasurementTakesOverAsItTightens() {
        val formula = Expenditure.Estimate.Known(2400.0, 360.0, Expenditure.Source.FORMULA, 0.0, 0, 0.0)
        var previous = 0.0
        for (sd in listOf(400.0, 300.0, 200.0, 120.0, 80.0)) {
            val measured = Expenditure.Estimate.Known(2600.0, sd, Expenditure.Source.MEASURED, 28.0, 28, 1.0)
            val share = Expenditure.measuredShare(formula, measured)
            assertTrue("share must climb as the measurement tightens", share > previous)
            previous = share
        }
        assertTrue("and it starts below half when the measurement is looser than the formula", previous > 0.9)
    }

    /**
     * ⚠️ A ZERO INTERVAL IS CERTAINTY, NOT A BROKEN INPUT — and the first version of the code had this
     * exactly backwards, returning the *other* side and throwing away the perfectly known figure in
     * favour of the guess beside it. Whichever side is certain takes the whole answer, from either
     * position.
     */
    @Test
    fun aSideThatIsCertainTakesTheWholeAnswer() {
        val certainFormula = Expenditure.Estimate.Known(2400.0, 0.0, Expenditure.Source.FORMULA, 0.0, 0, 0.0)
        val certainMeasured = Expenditure.Estimate.Known(2900.0, 0.0, Expenditure.Source.MEASURED, 28.0, 28, 1.0)
        val ordinary = Expenditure.Estimate.Known(2600.0, 120.0, Expenditure.Source.MEASURED, 28.0, 28, 1.0)
        val ordinaryFormula = Expenditure.Estimate.Known(2500.0, 300.0, Expenditure.Source.FORMULA, 0.0, 0, 0.0)

        assertEquals(2400.0, Expenditure.blend(certainFormula, ordinary).kcal, 1e-9)
        assertEquals(0.0, Expenditure.measuredShare(certainFormula, ordinary), 1e-9)

        assertEquals(2900.0, Expenditure.blend(ordinaryFormula, certainMeasured).kcal, 1e-9)
        assertEquals(1.0, Expenditure.measuredShare(ordinaryFormula, certainMeasured), 1e-9)
    }

    /** An unusable interval is the opposite case: that side defers rather than taking over. */
    @Test
    fun anUnusableIntervalDefersToTheOtherSide() {
        val broken = Expenditure.Estimate.Known(2400.0, Double.NaN, Expenditure.Source.FORMULA, 0.0, 0, 0.0)
        val measured = Expenditure.Estimate.Known(2600.0, 120.0, Expenditure.Source.MEASURED, 28.0, 28, 1.0)
        assertEquals(2600.0, Expenditure.blend(broken, measured).kcal, 1e-9)
        assertEquals("the whole answer came from the measurement", 1.0, Expenditure.measuredShare(broken, measured), 1e-9)

        val brokenMeasured = measured.copy(sdKcal = -5.0)
        val formula = Expenditure.Estimate.Known(2400.0, 360.0, Expenditure.Source.FORMULA, 0.0, 0, 0.0)
        assertEquals(2400.0, Expenditure.blend(formula, brokenMeasured).kcal, 1e-9)
        assertEquals(0.0, Expenditure.measuredShare(formula, brokenMeasured), 1e-9)

        // Whatever happens, something renderable comes back.
        assertTrue(Expenditure.blend(broken, brokenMeasured).kcal.isFinite())
        assertTrue(Expenditure.measuredShare(broken, brokenMeasured).isFinite())
    }

    // ------------------------------------------------------------------------------------- wording

    /**
     * ⚠️ Rounding to fifty is not cosmetic. The interval on this figure is a hundred calories at best, so
     * printing "2,437" claims a precision the measurement has not got, and trailing digits read as
     * accuracy.
     */
    @Test
    fun caloriesAreRoundedToTheNearestFifty() {
        assertEquals("2,400", Expenditure.round50(2412.0))
        assertEquals("2,400", Expenditure.round50(2387.0))
        assertEquals("2,450", Expenditure.round50(2437.0))
        assertEquals("1,000", Expenditure.round50(997.75))
        assertEquals("—", Expenditure.round50(Double.NaN))
        // Locale-fixed: these are numbers, and a comma decimal separator would read as a different one.
        assertTrue(Expenditure.round50(2400.0).contains(","))
    }

    @Test
    fun eachOutcomeSaysSomethingDifferent() {
        val formula = Expenditure.fromFormula(1800.0, Expenditure.Activity.LIGHT)
        val (w, i) = record(29, 85.0, -0.05, 2000.0)
        val measured = measure(w, i)
        val notYet = Expenditure.measure(BodyTrend.Trend.TooLittle(0, "none"), emptyList(), t0)

        val sentences = listOf(formula, measured, notYet).map { Expenditure.sentence(it) }
        assertTrue(sentences.all { it.isNotBlank() })
        assertEquals("three outcomes, three sentences", 3, sentences.toSet().size)
        assertTrue("the formula one must admit what it is", sentences[0].contains("formula"))
        assertTrue("the measured one must say it was measured", sentences[1].contains("Measured"))
        assertNotEquals(sentences[0], sentences[1])
    }
}
