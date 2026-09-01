package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ Every expected value below was computed by RUNNING the shipped functions over the fixture before
 * the assertion was written, and the arithmetic is left in the comment beside it. The one place that
 * matters most is the calibration: the reconciliation's threshold was chosen from simulated faults,
 * and a test that merely restated my intention for it would have pinned nothing.
 */
class EnergyBalanceTest {

    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L
    private fun at(d: Int) = t0 + d * day

    /** 500 kcal a day under, which at 7,700 kcal/kg is 0.064935 kg a day. */
    private val perDay = 500.0 / Expenditure.KCAL_PER_KG

    private fun weighins(days: IntRange = 0..119, kgAt: (Int) -> Double = { 84.0 - perDay * it }) =
        days.map { BodyTrend.Weighin(at(it), kgAt(it)) }

    private fun intake(days: IntRange = 0..119, kcal: Double = 2000.0) =
        days.map { Expenditure.IntakeDay(at(it), kcal) }

    private fun grid(days: IntRange = 60..119) = days.map { at(it) }

    private fun ready(
        w: List<BodyTrend.Weighin> = weighins(),
        i: List<Expenditure.IntakeDay> = intake(),
        g: List<Long> = grid(),
    ): EnergyBalance.Reading.Ready {
        val r = EnergyBalance.build(g, w, i)
        assertTrue(r.toString(), r is EnergyBalance.Reading.Ready)
        return r as EnergyBalance.Reading.Ready
    }

    // -------------------------------------------------------------------------------- the totals

    @Test
    fun `the measurement is recovered from intake and the scale`() {
        // 2,000 kcal logged, 0.064935 kg a day lost. Over a 28-day window that is
        // 2000 + 7700 x 0.064935 = 2500 kcal a day, and the estimator lands within a couple of it.
        val r = ready()
        val perDayBurn = r.expenditureKcal / r.pairedDays
        assertEquals(2500.0, perDayBurn, 5.0)
        assertEquals(-500.0, r.perDayKcal, 5.0)
    }

    @Test
    fun `a day missing either side counts toward neither total`() {
        // ⚠️ The load-bearing rule. A day with intake and no expenditure is not a huge surplus and a
        // day with expenditure and no intake is not starvation — both are days we cannot speak about.
        // Food logged to day 109 and no further: the last ten of the sixty drawn days have no intake,
        // so the total must be exactly 50 x 2000 rather than 60 x 2000 or anything between.
        val r = ready(i = intake(0..109))
        assertEquals(50, r.pairedDays)
        assertEquals(50 * 2000.0, r.intakeKcal, 0.5)
        // Every day is still DRAWN — the gap is visible on the chart, it just does not count.
        assertEquals(60, r.days.size)
        assertEquals(10, r.days.count { it.intakeKcal == null })
        assertEquals(0, r.days.count { it.expenditureKcal == null })
    }

    @Test
    fun `a hole in the log takes the expenditure readings that leaned on it too`() {
        // ⚠️ Measured, and not obvious: dropping twenty days of food costs FIVE MORE days of chart
        // than that. A reading whose 28-day window is mostly hole falls under MIN_COMPLETENESS and
        // refuses to run, so the line goes quiet either side of the gap as well as through it. That is
        // the estimator being honest — with two thirds of a window missing it does not know — and it
        // is worth pinning because the alternative reading of the same picture is that the chart
        // broke.
        val holed = intake().filter { it.dayStartMs !in (80..99).map(::at) }
        val r = ready(i = holed)
        assertEquals(20, r.pairedDays)
        assertEquals(20, r.days.count { it.intakeKcal == null })
        assertEquals(25, r.days.count { it.expenditureKcal == null })
    }

    @Test
    fun `a logged fast is a logged day at zero, not a gap`() {
        // ⚠️ Reuses Expenditure.IntakeDay.counted rather than testing kcal > 0, which is the whole
        // reason that property exists. A fast day contributes 0 kcal and one paired day.
        val fasting = intake(0..118) + Expenditure.IntakeDay(at(119), 0.0, fasted = true)
        val r = ready(i = fasting)
        assertEquals(60, r.pairedDays)
        assertEquals(0.0, r.days.last().intakeKcal!!, 0.0)
        assertNotNull(r.days.last().balanceKcal)
    }

    @Test
    fun `the gap is per paired day, not per drawn day`() {
        // 50 paired days out of 60 drawn. gapPerDayKcal must divide by 50; dividing by 60 would
        // under-state it by a sixth, silently, and the bar it is measured against would not move.
        val r = ready(i = intake(0..109))
        assertEquals(50, r.pairedDays)
        val expected = r.gapKg!! * Expenditure.KCAL_PER_KG / 50
        assertEquals(expected, r.gapPerDayKcal!!, 1e-9)
    }

    // ------------------------------------------------------------------------------- causality

    @Test
    fun `a reading cannot see a weigh-in that has not happened yet`() {
        // ⚠️ The property the whole design rests on, and the one that is easiest to lose by accident:
        // BodyTrend's RTS smoother runs BACKWARDS, so filtering trend.points would leave every point
        // informed by the future. Adding an enormous later weigh-in must change nothing about a
        // reading taken before it.
        val base = weighins()
        val later = base + BodyTrend.Weighin(at(200), 60.0)
        val a = EnergyBalance.causalExpenditure(base, intake(), at(60))!!
        val b = EnergyBalance.causalExpenditure(later, intake(), at(60))!!
        assertEquals(a.kcal, b.kcal, 0.0)
        assertEquals(a.sdKcal, b.sdKcal, 0.0)
    }

    @Test
    fun `a reading cannot see food that has not been eaten yet`() {
        // ⚠️ 3,500 kcal and not 9,000: at 9,000 the estimator returns Doubtful, so both sides come
        // back null and the test passes without ever comparing anything — the recorded failure where
        // the fixture never reaches the branch. Measured, a uniform 3,500 gives 4,001 kcal against
        // 2,501 for the honest history, so the control is unmistakable.
        val honest = EnergyBalance.causalExpenditure(weighins(), intake(0..59), at(60))!!
        val futureFeast = intake(0..59) + intake(60..119, kcal = 3500.0)
        val blind = EnergyBalance.causalExpenditure(weighins(), futureFeast, at(60))!!
        assertEquals(honest.kcal, blind.kcal, 0.0)

        // And the control: fed those 3,500 kcal days as HISTORY, the same reading moves 1,500 kcal.
        val ifItCouldSee = EnergyBalance.causalExpenditure(weighins(), intake(0..119, kcal = 3500.0), at(60))!!
        assertEquals(1500.0, ifItCouldSee.kcal - honest.kcal, 5.0)
    }

    @Test
    fun `a weigh-in at the exact instant a day begins belongs to that day`() {
        // ⚠️ `< atMs` and never `<=`. A weigh-in taken as the day starts is information from that day.
        // The fixture makes the difference unmissable: a 6 kg drop recorded exactly at the boundary
        // would drag the window's delta hard if it were let in.
        val base = weighins(0..59)
        val onTheLine = base + BodyTrend.Weighin(at(60), 78.0)
        val a = EnergyBalance.causalExpenditure(base, intake(0..59), at(60))!!
        val b = EnergyBalance.causalExpenditure(onTheLine, intake(0..59), at(60))!!
        assertEquals(a.kcal, b.kcal, 0.0)
    }

    // ----------------------------------------------------------------------------------- the step

    @Test
    fun `the expenditure line steps rather than moving every day`() {
        // Seven days share a reading, and the boundaries are where it changes. Not a cosmetic choice:
        // a figure that moved daily would be the drifting target CheckIn exists to remove, drawn.
        val r = ready()
        val distinct = r.days.take(21).mapNotNull { it.expenditureKcal }.distinct()
        assertEquals(3, distinct.size)
        assertEquals(r.days[0].expenditureKcal, r.days[6].expenditureKcal)
        assertTrue(r.days[6].expenditureKcal != r.days[7].expenditureKcal)
    }

    @Test
    fun `a daily step is available for anyone who wants one`() {
        val r = EnergyBalance.build(grid(), weighins(), intake(), stepDays = 1)
        r as EnergyBalance.Reading.Ready
        assertTrue(r.days.take(7).mapNotNull { it.expenditureKcal }.distinct().size > 1)
    }

    // ------------------------------------------------------------------------------ the refusals

    @Test
    fun `an interval with nothing behind it says which side is missing`() {
        val noFood = EnergyBalance.build(grid(), weighins(), emptyList())
        assertTrue(noFood is EnergyBalance.Reading.NotYet)
        assertTrue((noFood as EnergyBalance.Reading.NotYet).why, noFood.why.contains("Nothing logged"))

        val noScale = EnergyBalance.build(grid(), emptyList(), intake())
        assertTrue(noScale is EnergyBalance.Reading.NotYet)
        assertTrue((noScale as EnergyBalance.Reading.NotYet).why, noScale.why.contains("weigh-ins"))
    }

    @Test
    fun `too few paired days is a refusal, and the days are still handed back to draw`() {
        // Six logged days against a floor of seven.
        val thin = intake(0..59) + intake(60..65)
        val r = EnergyBalance.build(grid(), weighins(), thin)
        assertTrue(r is EnergyBalance.Reading.NotYet)
        r as EnergyBalance.Reading.NotYet
        assertEquals(6, r.pairedDays)
        assertEquals(60, r.days.size)
        assertTrue(r.why, r.why.contains("${EnergyBalance.MIN_PAIRED_DAYS}"))
    }

    @Test
    fun `an empty interval is refused rather than divided by zero`() {
        val r = EnergyBalance.build(emptyList(), weighins(), intake())
        assertTrue(r is EnergyBalance.Reading.NotYet)
        assertEquals(0, r.pairedDays)
    }

    @Test
    fun `a doubtful reading is not plotted`() {
        // ⚠️ Expenditure.Doubtful is the estimator reporting that the intake and the scale disagree
        // with physics. Plotting its number would put the exact figure it refused to stand behind onto
        // a chart, where it reads as a measurement. 300 kcal a day logged while holding weight gives a
        // measured expenditure of 300, which is under PLAUSIBLE_MIN_KCAL.
        val flat = weighins { 84.0 }
        val starving = intake(kcal = 300.0)
        val e = Expenditure.measure(BodyTrend.estimate(flat), starving, at(60), 28)
        assertTrue("fixture must reach the Doubtful branch, got $e", e is Expenditure.Estimate.Doubtful)
        assertNull(EnergyBalance.causalExpenditure(flat, starving, at(60)))
    }

    // ------------------------------------------------------------------------- the observed change

    @Test
    fun `the observed change refuses a boundary no weigh-in is near`() {
        // ⚠️ BOTH ends, and that is not padding. My first version tested only the far end, so a
        // perturbation that widened the tolerance on the NEAR one failed nothing and the guard came
        // back asleep — the recorded mechanism where the fixture never reaches the branch.
        //
        // Weighing stops at day 100, so the far end of a 60..119 interval is nineteen days past the
        // last reading.
        val stopped = weighins(0..100)
        assertNull(EnergyBalance.observedChangeKg(stopped, at(60), at(119)))
        assertNotNull(EnergyBalance.observedChangeKg(stopped, at(60), at(99)))

        // Weighing starts at day 70, so the near end of the same interval is ten days before the
        // first reading.
        val late = weighins(70..119)
        assertNull(EnergyBalance.observedChangeKg(late, at(60), at(119)))
        assertNotNull(EnergyBalance.observedChangeKg(late, at(72), at(119)))
    }

    @Test
    fun `the observed change is the trend across the paired span`() {
        // 0.064935 kg a day x 59 days = 3.831 kg, and the smoother reproduces it: measured -3.8314.
        val v = EnergyBalance.observedChangeKg(weighins(), at(60), at(119))!!
        assertEquals(-3.83, v, 0.05)
    }

    // ------------------------------------------------------------------------- the reconciliation

    @Test
    fun `honest logging is silent at every realistic level of scale noise`() {
        // ⚠️ Measured, not asserted from intuition. Simulated gaps: 9 kcal/day with a perfect scale,
        // 16 at +-0.4 kg, 41 at +-0.8, 66 at +-1.2 — all under the 91 kcal/day bar the estimator's own
        // sdKcal sets. A flat kilogram threshold, which this used before the simulation, fires on the
        // last of those over a fortnight.
        for (noise in listOf(0.0, 0.4, 0.8, 1.2)) {
            var s = 12345L
            fun rnd(): Double {
                s = s * 6364136223846793005L + 1442695040888963407L
                return (s ushr 11).toDouble() / (1L shl 53).toDouble() - 0.5
            }
            val w = (0..119).map { BodyTrend.Weighin(at(it), 84.0 - perDay * it + rnd() * 2 * noise) }
            val r = ready(w = w)
            assertTrue(
                "noise +-$noise gave ${r.gapPerDayKcal} against a bar of ${r.sdKcal}",
                abs(r.gapPerDayKcal!!) <= EnergyBalance.REMARK_SDS * r.sdKcal,
            )
            assertTrue(EnergyBalance.reconciliation(r, BodyTrend.MassUnit.KG)!!.contains("about as much"))
        }
    }

    @Test
    fun `a single mistyped weigh-in does not raise it`() {
        // ⚠️ Measured: BodyTrend's outlier gate absorbs one bad reading completely. A 10 kg typo moves
        // the gap by 0.043 kg — 5 kcal a day. This is pinned because an earlier draft of the sentence
        // NAMED a mistyped weigh-in as a cause, which would have sent somebody hunting a fault the
        // system provably cannot see.
        for (err in listOf(1.0, 5.0, 10.0)) {
            val w = weighins { 84.0 - perDay * it + if (it == 100) err else 0.0 }
            val r = ready(w = w)
            assertTrue(
                "a +$err kg typo produced ${r.gapPerDayKcal} kcal/day",
                abs(r.gapPerDayKcal!!) < 10.0,
            )
        }
    }

    @Test
    fun `sustained under-logging is raised, and says how much a day`() {
        // 400 kcal a day unlogged for the last thirty days. Measured gap: +1.081 kg, 139 kcal a day,
        // against a 91 kcal bar.
        val kg = ArrayList<Double>()
        var k = 84.0
        for (d in 0..119) {
            kg += k
            k -= (if (d < 90) 500.0 else 100.0) / Expenditure.KCAL_PER_KG
        }
        val r = ready(w = (0..119).map { BodyTrend.Weighin(at(it), kg[it]) })
        assertEquals(139.0, r.gapPerDayKcal!!, 5.0)
        val s = EnergyBalance.reconciliation(r, BodyTrend.MassUnit.KG)!!
        assertTrue(s, s.contains("more than the food accounts for"))
        assertTrue(s, s.contains("139 kcal a day"))
        // ⚠️ It names food that went unlogged and a change in how you weigh, and explicitly RULES OUT
        // the one an earlier draft blamed — see the typo test above for the measurement that settled it.
        assertTrue(s, s.contains("unlogged"))
        assertTrue(s, s.contains("mistyped weigh-in would not do it"))
    }

    @Test
    fun `a scale that starts reading heavy is raised`() {
        // A kilogram of offset from day 95 gives 99 kcal a day, just over the bar. Half a kilogram
        // gives 54 and stays silent, which is correct: it is inside the estimate's own width.
        val heavy = weighins { 84.0 - perDay * it + if (it >= 95) 1.0 else 0.0 }
        val r = ready(w = heavy)
        assertTrue("${r.gapPerDayKcal}", abs(r.gapPerDayKcal!!) > EnergyBalance.REMARK_SDS * r.sdKcal)
        assertTrue(EnergyBalance.reconciliation(r, BodyTrend.MassUnit.KG)!!.contains("how you weigh"))

        val slight = weighins { 84.0 - perDay * it + if (it >= 95) 0.5 else 0.0 }
        val q = ready(w = slight)
        assertTrue("${q.gapPerDayKcal}", abs(q.gapPerDayKcal!!) <= EnergyBalance.REMARK_SDS * q.sdKcal)
    }

    @Test
    fun `the reconciliation never claims the estimate is proved right`() {
        // ⚠️ The two sides are worked out from overlapping data — a reading near the end of the interval
        // looks back over days inside it — so agreement is weak evidence and the words have to say so.
        val s = EnergyBalance.reconciliation(ready(), BodyTrend.MassUnit.KG)!!
        assertTrue(s, s.contains("does not prove"))
        assertTrue(s, s.contains("overlapping"))
    }

    @Test
    fun `no observed change means no reconciliation at all`() {
        val stopped = weighins(0..100)
        val r = ready(w = stopped)
        assertNull(r.observedChangeKg)
        assertNull(r.gapKg)
        assertNull(EnergyBalance.reconciliation(r, BodyTrend.MassUnit.KG))
    }

    // ---------------------------------------------------------------------------------- the words

    @Test
    fun `the summary states the deficit and what it implies`() {
        val s = EnergyBalance.summary(ready(), BodyTrend.MassUnit.KG)
        assertTrue(s, s.contains("60 logged days"))
        assertTrue(s, s.contains("under what you burned"))
        assertTrue(s, s.contains("down about 3.9 kg"))
    }

    @Test
    fun `a balance inside the estimate's own noise is called holding steady`() {
        // ⚠️ 2,500 kcal against a flat weight measures out at 2,500, so the balance is zero. A chart
        // that announced a 30 kcal deficit would be reporting the width of its own error bar.
        val r = ready(w = weighins { 84.0 }, i = intake(kcal = 2500.0))
        assertTrue(abs(r.perDayKcal) < EnergyBalance.NEUTRAL_KCAL)
        assertTrue(EnergyBalance.summary(r, BodyTrend.MassUnit.KG).contains("holding steady"))
    }

    @Test
    fun `pounds are pounds and not kilograms with a different word`() {
        val r = ready()
        val kg = EnergyBalance.summary(r, BodyTrend.MassUnit.KG)
        val lb = EnergyBalance.summary(r, BodyTrend.MassUnit.LB)
        assertTrue(kg, kg.contains("3.9 kg"))
        // 3.8997 kg x 2.20462 = 8.598 lb
        assertTrue(lb, lb.contains("8.6 lb"))
    }

    // ------------------------------------------------------------------------------ handing it in

    @Test
    fun `handing in only the interval's own data loses the start of the line`() {
        // ⚠️ Not a defect — a documented consequence, pinned so the KDoc's advice is enforced. A causal
        // reading at the start of the interval needs the window that precedes it, and the first 28 days
        // of a bare interval have nothing behind them.
        val bare = EnergyBalance.build(
            grid(),
            weighins(60..119),
            intake(60..119),
        )
        bare as EnergyBalance.Reading.Ready
        assertTrue("${bare.pairedDays}", bare.pairedDays in 25..40)
        assertTrue(bare.days.take(20).all { it.expenditureKcal == null })

        // With the preceding window handed in, every day of the same interval carries a reading.
        assertEquals(60, ready().pairedDays)
    }

    // -------------------------------------------------------------------------------- for drawing

    @Test
    fun `the intake series breaks wherever the record does`() {
        // ⚠️ A chart that joined across a hole would draw a smooth line through a fortnight of no
        // data, and it would read exactly like a fortnight of steady eating. Food logged to day 109
        // and none after: one run of fifty, then nothing.
        val r = ready(i = intake(0..109))
        val runs = EnergyBalance.intakeRuns(r.days)
        assertEquals(1, runs.size)
        assertEquals(50, runs[0].size)

        // Two stretches with a gap between them are two runs, and the boundary is exactly where the
        // record stops rather than a day either side of it.
        val split = intake(0..79) + intake(100..119)
        val q = EnergyBalance.build(grid(), weighins(), split)
        val two = EnergyBalance.intakeRuns(q.days)
        assertEquals(2, two.size)
        assertEquals(20, two[0].size)
        assertEquals(20, two[1].size)
        assertEquals(at(60), two[0].first().first)
        assertEquals(at(79), two[0].last().first)
        assertEquals(at(100), two[1].first().first)
    }

    @Test
    fun `a lone logged day is kept as a run of one`() {
        // ⚠️ Deliberate: whether one point can be DRAWN is the chart's business — a line kit refuses
        // it and is right to — but discarding it here would hide a real logged day from a caller that
        // plots bars, where a single day is perfectly plottable.
        val marooned = intake(0..79) + intake(90..90) + intake(100..119)
        val r = EnergyBalance.build(grid(), weighins(), marooned)
        val runs = EnergyBalance.intakeRuns(r.days)
        assertEquals(3, runs.size)
        assertEquals(1, runs[1].size)
        assertEquals(at(90), runs[1][0].first)
    }

    @Test
    fun `the burn line carries only the days that have a reading`() {
        val r = ready(i = intake(0..109))
        assertEquals(60, EnergyBalance.burnSeries(r.days).size)

        // Handed only the interval's own data, the first four weeks have nothing behind them and the
        // series starts where the readings do rather than at the left edge with a fabricated value.
        val bare = EnergyBalance.build(grid(), weighins(60..119), intake(60..119))
        val series = EnergyBalance.burnSeries(bare.days)
        assertTrue("${series.size}", series.size in 25..40)
        assertTrue(series.first().first > at(60))
    }

    @Test
    fun `the grid comes back sorted, one day out for one day in`() {
        val shuffled = grid().shuffled(java.util.Random(7).let { r -> kotlin.random.Random(7) })
        val r = ready(g = shuffled)
        assertEquals(60, r.days.size)
        assertEquals(grid(), r.days.map { it.dayStartMs })
    }
}
