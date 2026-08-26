package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ Every expected value is computed from the published formula the shipped code implements, with
 * the arithmetic left in the comment beside it.
 */
class TrainingTest {

    private val squat = Training.Exercise("squat", "Back squat", Training.Pattern.SQUAT)
    private val bench = Training.Exercise("bench", "Bench press", Training.Pattern.PUSH)
    private val pressUp = Training.Exercise("pressup", "Press-up", Training.Pattern.PUSH, loaded = false)

    private fun set(reps: Int, kg: Double? = null, rpe: Double? = null) =
        Training.SetEntry(reps, kg, rpe)

    // -------------------------------------------------------------------------------------- a set

    @Test
    fun `a set with no load can never contribute to anything that multiplies by weight`() {
        assertFalse(set(15).weighed)
        assertFalse(set(15, 0.0).weighed)
        assertFalse(set(15, Double.NaN).weighed)
        assertFalse(set(0, 100.0).weighed)
        assertTrue(set(5, 100.0).weighed)
    }

    @Test
    fun `reps in reserve is ten minus the effort, and refuses a figure off the scale`() {
        assertEquals(2.0, set(5, 100.0, rpe = 8.0).repsInReserve!!, 1e-9)
        assertEquals(0.0, set(5, 100.0, rpe = 10.0).repsInReserve!!, 1e-9)
        assertNull(set(5, 100.0, rpe = 11.0).repsInReserve)
        assertNull(set(5, 100.0, rpe = 0.5).repsInReserve)
        assertNull(set(5, 100.0).repsInReserve)
    }

    // ------------------------------------------------------------------------------------ tonnage

    @Test
    fun `tonnage is sets times reps times load`() {
        // 3 x 5 x 100 = 1500, plus 2 x 10 x 40 = 800.
        val session = Training.Session(
            0L,
            listOf(
                Training.Movement(squat, List(3) { set(5, 100.0) }),
                Training.Movement(bench, List(2) { set(10, 40.0) }),
            ),
        )
        assertEquals(2_300.0, Training.tonnageKg(session), 1e-9)
    }

    @Test
    fun `unweighted work leaves tonnage alone instead of taking the figure down with it`() {
        // ⚠️ Stated honestly: "excluded" and "counted as zero" are NOT numerically distinguishable
        // here, because a sum of zeros is the same sum. What the filter genuinely does is stop the
        // non-null assertion on the next line throwing on a set that has no load — so what this pins
        // is that a bodyweight movement cannot bring the whole figure down, by any route.
        val session = Training.Session(
            0L,
            listOf(
                Training.Movement(squat, List(3) { set(5, 100.0) }),
                Training.Movement(pressUp, List(4) { set(20) }),
            ),
        )
        assertEquals(1_500.0, Training.tonnageKg(session), 1e-9)
    }

    // ---------------------------------------------------------------------------------- hard sets

    @Test
    fun `effort decides a hard set when it was recorded`() {
        val m = Training.Movement(
            squat,
            listOf(
                set(5, 60.0, rpe = 5.0),
                set(5, 80.0, rpe = 6.9),
                set(5, 100.0, rpe = 7.0),
                set(5, 100.0, rpe = 9.0),
            ),
        )
        // HARD_RPE is 7.0 and the comparison is inclusive, so the last two count.
        assertEquals(2, Training.hardSets(Training.Session(0L, listOf(m))))
    }

    @Test
    fun `with no effort recorded the load proxy decides, at the stated share of the top set`() {
        // Top set 100, WARMUP_LOAD_SHARE 0.85 -> threshold exactly 85.0, and the comparison is
        // inclusive, so 85 is work and 84 is a warm-up.
        val m = Training.Movement(squat, listOf(set(5, 40.0), set(5, 84.0), set(5, 85.0), set(5, 100.0)))
        assertEquals(2, Training.hardSets(Training.Session(0L, listOf(m))))
    }

    @Test
    fun `unloaded work with no effort recorded counts, because no proxy exists to refuse it`() {
        // ⚠️ Four sets of press-ups have no load to compare, so the load proxy cannot speak. Counting
        // them is the right default: refusing would report a bodyweight session as no training.
        val m = Training.Movement(pressUp, List(4) { set(20) })
        assertEquals(4, Training.hardSets(Training.Session(0L, listOf(m))))
    }

    @Test
    fun `a set of no reps is not a set`() {
        val m = Training.Movement(squat, listOf(set(0, 100.0, rpe = 9.0), set(5, 100.0, rpe = 9.0)))
        assertEquals(1, Training.hardSets(Training.Session(0L, listOf(m))))
    }

    @Test
    fun `the top set is per movement, not per session`() {
        // ⚠️ A heavy squat must not make every bench set look like a warm-up. Squat top 200 (threshold
        // 170) admits only the 200; bench top 60 (threshold 51) admits only the 60.
        val session = Training.Session(
            0L,
            listOf(
                Training.Movement(squat, listOf(set(5, 100.0), set(5, 200.0))),
                Training.Movement(bench, listOf(set(5, 40.0), set(5, 60.0))),
            ),
        )
        assertEquals(2, Training.hardSets(session))
    }

    @Test
    fun `hard sets are grouped by pattern for somebody asking whether a week was balanced`() {
        val week = listOf(
            Training.Session(0L, listOf(Training.Movement(squat, List(3) { set(5, 100.0, rpe = 8.0) }))),
            Training.Session(1L, listOf(Training.Movement(bench, List(4) { set(5, 60.0, rpe = 8.0) }))),
            Training.Session(2L, listOf(Training.Movement(squat, List(2) { set(5, 100.0, rpe = 8.0) }))),
        )
        val by = Training.setsByPattern(week)
        assertEquals(5, by[Training.Pattern.SQUAT])
        assertEquals(4, by[Training.Pattern.PUSH])
        assertNull(by[Training.Pattern.HINGE])
    }

    // -------------------------------------------------------------------------------- one-rep max

    @Test
    fun `Epley reproduces its published formula`() {
        // 1RM = w * (1 + r / 30).  100 * (1 + 5/30) = 100 * 1.1666667 = 116.66667
        assertEquals(116.66666666666667, Training.oneRepMax(5, 100.0, Training.Formula.EPLEY)!!, 1e-9)
        // 60 * (1 + 8/30) = 60 + 16 = 76 exactly.
        assertEquals(76.0, Training.oneRepMax(8, 60.0, Training.Formula.EPLEY)!!, 1e-9)
    }

    @Test
    fun `Brzycki reproduces its published formula`() {
        // 1RM = w * 36 / (37 - r).  100 * 36 / 32 = 112.5
        assertEquals(112.5, Training.oneRepMax(5, 100.0, Training.Formula.BRZYCKI)!!, 1e-9)
        // 60 * 36 / 29 = 2160 / 29 = 74.482759
        assertEquals(74.48275862068965, Training.oneRepMax(8, 60.0, Training.Formula.BRZYCKI)!!, 1e-9)
    }

    @Test
    fun `a single rep is the load itself, which is what both formulas already say`() {
        // Epley at r=1: w * (1 + 1/30) = 103.33, which is WRONG — a single is a single. Brzycki at
        // r=1: w * 36/36 = w, which is right. The special case makes both agree with the fact.
        assertEquals(100.0, Training.oneRepMax(1, 100.0, Training.Formula.EPLEY)!!, 1e-9)
        assertEquals(100.0, Training.oneRepMax(1, 100.0, Training.Formula.BRZYCKI)!!, 1e-9)
    }

    @Test
    fun `past the reps either formula was fitted for it refuses rather than clamping`() {
        // ⚠️ MAX_REPS_FOR_ESTIMATE is 10 and the boundary is inclusive.  100 * (1 + 10/30) = 133.333
        assertEquals(133.33333333333334, Training.oneRepMax(10, 100.0)!!, 1e-9)
        assertNull(Training.oneRepMax(11, 100.0))
        assertNull(Training.oneRepMax(20, 100.0))
        // ⚠️ And Brzycki divides by zero at 37, so refusing is not a nicety.
        assertNull(Training.oneRepMax(37, 100.0, Training.Formula.BRZYCKI))
    }

    @Test
    fun `no load and no reps are refusals, because people load a bar from this number`() {
        assertNull(Training.oneRepMax(0, 100.0))
        assertNull(Training.oneRepMax(5, 0.0))
        assertNull(Training.oneRepMax(5, Double.NaN))
    }

    @Test
    fun `a set recorded as far from failure says nothing about a maximum`() {
        // ⚠️ Both formulas were fitted on sets taken near failure. MAX_RIR_FOR_ESTIMATE is 3, so an
        // RPE of 7 (three left) is the last one accepted and 6 (four left) is refused.
        assertEquals(116.66666666666667, Training.estimateOneRepMax(set(5, 100.0, rpe = 7.0))!!, 1e-9)
        assertNull(Training.estimateOneRepMax(set(5, 100.0, rpe = 6.0)))
    }

    @Test
    fun `an unrecorded effort is accepted, because a caller with a maximal set should not invent one`() {
        assertEquals(116.66666666666667, Training.estimateOneRepMax(set(5, 100.0))!!, 1e-9)
    }

    @Test
    fun `the best estimate across a movement wins, and none at all is null`() {
        // 3 x 100 -> 100 * (1 + 3/30) = 110.  5 x 100 -> 116.667.  The five is the better set.
        val m = Training.Movement(squat, listOf(set(3, 100.0, rpe = 9.0), set(5, 100.0, rpe = 9.0)))
        assertEquals(116.66666666666667, Training.bestOneRepMax(m)!!, 1e-9)
        assertNull(Training.bestOneRepMax(Training.Movement(pressUp, listOf(set(20)))))
    }

    // -------------------------------------------------------------------------------- progression

    @Test
    fun `easier than asked for puts the load up by one step`() {
        // Bench is a PUSH, so the small increment: 60 + 1.25 = 61.25.
        val m = Training.Movement(bench, listOf(set(5, 60.0, rpe = 6.0)))
        val a = Training.nextLoad(m, targetRpe = 8.0) as Training.Advice.Load
        assertEquals(1.25, a.deltaKg, 1e-9)
        assertEquals(61.25, a.toKg, 1e-9)
        assertTrue(a.sentence, a.sentence.contains("61.25"))
    }

    @Test
    fun `the step is larger where the plates and the movement allow it`() {
        // Squat takes the large increment: 100 + 2.5 = 102.5.
        val m = Training.Movement(squat, listOf(set(5, 100.0, rpe = 6.0)))
        val a = Training.nextLoad(m, targetRpe = 8.0) as Training.Advice.Load
        assertEquals(2.5, a.deltaKg, 1e-9)
        assertEquals(102.5, a.toKg, 1e-9)
    }

    @Test
    fun `harder than asked for takes it back down by one step`() {
        val m = Training.Movement(squat, listOf(set(5, 100.0, rpe = 9.5)))
        val a = Training.nextLoad(m, targetRpe = 8.0) as Training.Advice.Load
        assertEquals(-2.5, a.deltaKg, 1e-9)
        assertEquals(97.5, a.toKg, 1e-9)
        assertTrue(a.sentence, a.sentence.contains("harder than asked"))
    }

    @Test
    fun `inside the dead band the load holds, at the boundary as well as within it`() {
        // ⚠️ RPE_DEADBAND is 0.5 and the comparison is inclusive, so a gap of exactly half a point is
        // a hold. Without a dead band a load would move on every session from noise in a subjective
        // rating, which is the thing autoregulation is meant to filter out.
        for (rpe in listOf(7.5, 7.6, 8.0, 8.4, 8.5)) {
            val a = Training.nextLoad(
                Training.Movement(squat, listOf(set(5, 100.0, rpe = rpe))),
                targetRpe = 8.0,
            ) as Training.Advice.Load
            assertEquals("rpe $rpe", 0.0, a.deltaKg, 1e-9)
            assertEquals("rpe $rpe", 100.0, a.toKg, 1e-9)
        }
        // Just outside it, both ways.
        val up = Training.nextLoad(
            Training.Movement(squat, listOf(set(5, 100.0, rpe = 7.4))),
            targetRpe = 8.0,
        ) as Training.Advice.Load
        assertEquals(2.5, up.deltaKg, 1e-9)
        val down = Training.nextLoad(
            Training.Movement(squat, listOf(set(5, 100.0, rpe = 8.6))),
            targetRpe = 8.0,
        ) as Training.Advice.Load
        assertEquals(-2.5, down.deltaKg, 1e-9)
    }

    @Test
    fun `however far off the set landed the load moves one step, not two`() {
        // ⚠️ A gap of four points is still one increment. Two steps from a single data point is
        // extrapolation, and the next session supplies another point anyway.
        val m = Training.Movement(squat, listOf(set(5, 100.0, rpe = 4.0)))
        val a = Training.nextLoad(m, targetRpe = 8.0) as Training.Advice.Load
        assertEquals(2.5, a.deltaKg, 1e-9)
    }

    @Test
    fun `a decrease that cannot happen holds the load rather than adding weight to it`() {
        // ⚠️ At 2 kg with a 2.5 kg step, `load + delta` is -0.5. Flooring that at the step alone gives
        // 2.5 — an INCREASE, under a sentence saying the set was too hard. Bounded by the current load
        // as well, so it holds and says why.
        val m = Training.Movement(squat, listOf(set(10, 2.0, rpe = 10.0)))
        val a = Training.nextLoad(m, targetRpe = 8.0) as Training.Advice.Load
        assertEquals(0.0, a.deltaKg, 1e-9)
        assertEquals(2.0, a.toKg, 1e-9)
        assertTrue(a.sentence, a.sentence.contains("lightest"))
    }

    @Test
    fun `it judges the LAST working set, not the first`() {
        val m = Training.Movement(
            squat,
            listOf(set(5, 60.0, rpe = 4.0), set(5, 100.0, rpe = 9.5)),
        )
        val a = Training.nextLoad(m, targetRpe = 8.0) as Training.Advice.Load
        assertEquals(97.5, a.toKg, 1e-9)
    }

    @Test
    fun `no effort recorded is a refusal that asks for one, not an invented progression`() {
        val m = Training.Movement(squat, listOf(set(5, 100.0)))
        val a = Training.nextLoad(m)
        assertTrue("got $a", a is Training.Advice.Unknown)
        assertTrue((a as Training.Advice.Unknown).sentence.contains("how hard the last set felt"))
    }

    @Test
    fun `an unloaded movement is answered as unloaded even when the effort WAS recorded`() {
        // ⚠️ The ordering that had to be fixed: an unloaded movement has no weighed sets by
        // construction, so asking for a weighed set first told somebody who had recorded their effort
        // to go and record it.
        val m = Training.Movement(pressUp, listOf(set(20, rpe = 9.0)))
        val a = Training.nextLoad(m)
        assertTrue("got $a", a is Training.Advice.Unknown)
        assertTrue((a as Training.Advice.Unknown).sentence.contains("reps or leverage"))
    }

    // --------------------------------------------------------------------------- the join to eating

    @Test
    fun `heavy days are the days work happened on, and nothing more`() {
        // ⚠️ No calorie figure appears anywhere in this — the join moves a MEASURED weekly budget onto
        // the days that need it, and inventing an energy cost here would double-count what
        // Expenditure has already measured from weight change and intake.
        val sessions = listOf(
            Training.Session(0L, listOf(Training.Movement(squat, List(3) { set(5, 100.0, rpe = 8.0) }))),
            Training.Session(1L, listOf(Training.Movement(bench, List(3) { set(5, 60.0, rpe = 8.0) }))),
            Training.Session(2L, listOf(Training.Movement(squat, emptyList()))),
        )
        val days = Training.heavyDays(sessions) { it.toInt() }
        assertEquals(setOf(0, 1), days)
    }

    @Test
    fun `a day index outside the week is dropped rather than corrupting the plan`() {
        val sessions = listOf(
            Training.Session(0L, listOf(Training.Movement(squat, List(3) { set(5, 100.0, rpe = 8.0) }))),
            Training.Session(9L, listOf(Training.Movement(squat, List(3) { set(5, 100.0, rpe = 8.0) }))),
        )
        assertEquals(setOf(0), Training.heavyDays(sessions) { it.toInt() })
    }

    @Test
    fun `what heavy days produce is something WeeklyPlan accepts unchanged`() {
        // The two halves have to actually meet: every index this yields must be a legal day of the
        // week the plan builds.
        val sessions = (0L until 7L).map {
            Training.Session(it, listOf(Training.Movement(squat, List(3) { set(5, 100.0, rpe = 8.0) })))
        }
        val heavy = Training.heavyDays(sessions) { it.toInt() }
        assertEquals(WeeklyPlan.DAYS, heavy.size)
        assertTrue(heavy.all { it in 0 until WeeklyPlan.DAYS })
    }

    // ---------------------------------------------------------------------------------- sentences

    @Test
    fun `a session reads as a sentence, with the plural agreeing`() {
        val one = Training.Session(0L, listOf(Training.Movement(squat, listOf(set(5, 100.0, rpe = 8.0)))))
        assertEquals("1 hard set across 1 movement · 500 kg moved", Training.sentence(one))

        val many = Training.Session(
            0L,
            listOf(
                Training.Movement(squat, List(3) { set(5, 100.0, rpe = 8.0) }),
                Training.Movement(bench, List(2) { set(10, 40.0, rpe = 8.0) }),
            ),
        )
        // 3 x 5 x 100 = 1500, plus 2 x 10 x 40 = 800, = 2300.
        assertEquals("5 hard sets across 2 movements · 2300 kg moved", Training.sentence(many))
    }

    @Test
    fun `a bodyweight session says what was done without inventing a tonnage`() {
        val s = Training.Session(0L, listOf(Training.Movement(pressUp, List(4) { set(20) })))
        assertEquals("4 hard sets across 1 movement", Training.sentence(s))
    }

    @Test
    fun `an empty session says so rather than reading as a zero`() {
        assertEquals(
            "Nothing logged on this session yet.",
            Training.sentence(Training.Session(0L, emptyList())),
        )
    }
}
