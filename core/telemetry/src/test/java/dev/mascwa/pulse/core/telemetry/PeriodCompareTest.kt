package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ Every expected string below was produced by RUNNING the shipped function on the fixture before
 * the assertion was written. The two that matter most are the refusals: a comparison that quietly
 * reaches ten weeks for a reading, or reports zero because one reading served as both ends, is a
 * wrong answer wearing a right label.
 */
class PeriodCompareTest {

    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L
    private fun at(d: Int) = t0 + d * day
    private fun p(d: Int, v: Double) = PeriodCompare.Point(at(d), v)

    // -------------------------------------------------------------------------------- comparing

    @Test
    fun `two readings give the change, the fraction and the dates`() {
        val c = PeriodCompare.compare("Waist", "cm", listOf(p(0, 96.0), p(90, 88.8)), at(0), at(90))
        assertEquals(96.0, c.from!!, 1e-9)
        assertEquals(88.8, c.to!!, 1e-9)
        // 88.8 - 96.0 = -7.2, and -7.2 / 96.0 = -0.075
        assertEquals(-7.2, c.delta!!, 1e-9)
        assertEquals(-0.075, c.fraction!!, 1e-9)
        assertTrue(c.known)
    }

    @Test
    fun `it reaches for the nearest reading, but not past the limit`() {
        // Nine days out is inside REACH_DAYS (10); eleven is not.
        val near = PeriodCompare.compare("Waist", "cm", listOf(p(9, 96.0), p(90, 90.0)), at(0), at(90))
        assertTrue(near.why ?: "", near.known)
        assertEquals(at(9), near.fromAtMs)

        val far = PeriodCompare.compare("Waist", "cm", listOf(p(11, 96.0), p(90, 90.0)), at(0), at(90))
        assertNull(far.delta)
        assertTrue(far.why!!, far.why!!.contains("earlier date"))
    }

    @Test
    fun `one reading is not two, and the refusal says so`() {
        // ⚠️ The sharpest of the refusals. Without it the same reading serves as both ends and the
        // comparison reports a change of exactly zero — which reads as "you held steady" rather than
        // "there is only one reading here", and those are very different things to tell somebody.
        val c = PeriodCompare.compare("Hips", "cm", listOf(p(45, 100.0)), at(42), at(48))
        assertNull(c.delta)
        assertTrue(c.why!!, c.why!!.contains("Only one"))
    }

    @Test
    fun `each missing end is named separately`() {
        val pts = listOf(p(0, 96.0), p(90, 90.0))
        assertTrue(PeriodCompare.compare("Waist", "cm", pts, at(0), at(200)).why!!.contains("later date"))
        assertTrue(PeriodCompare.compare("Waist", "cm", pts, at(-200), at(90)).why!!.contains("earlier date"))
        assertTrue(PeriodCompare.compare("Waist", "cm", pts, at(-200), at(200)).why!!.contains("either date"))
        assertTrue(
            PeriodCompare.compare("Waist", "cm", emptyList(), at(0), at(90)).why!!.contains("Nothing recorded"),
        )
    }

    @Test
    fun `a non-finite reading is not a reading`() {
        val c = PeriodCompare.compare("Waist", "cm", listOf(p(0, Double.NaN), p(90, 90.0)), at(0), at(90))
        assertNull(c.delta)
        assertTrue(c.why!!, c.why!!.contains("earlier date"))
    }

    @Test
    fun `a percentage needs a base that has one`() {
        // ⚠️ Zero is a legitimate reading for some series and dividing by it is not. The delta still
        // stands; only the percentage is withheld.
        val zero = PeriodCompare.compare("Deficit", "kcal", listOf(p(0, 0.0), p(30, 400.0)), at(0), at(30))
        assertEquals(400.0, zero.delta!!, 1e-9)
        assertNull(zero.fraction)
        assertTrue(PeriodCompare.sentence(zero, 0), !PeriodCompare.sentence(zero, 0).contains("%"))

        // ⚠️ And a NEGATIVE base, which is not hypothetical: a daily energy balance goes negative
        // every day of a deficit. Measured before this rule existed, -600 to -200 printed as
        // "Up 400.0 kcal (67%)" and -200 to 300 as "(250%)" — the absolute change right in both,
        // the percentage meaningless in both.
        for (pair in listOf(-600.0 to -200.0, -200.0 to 300.0, -0.4 to -0.9)) {
            val c = PeriodCompare.compare(
                "Balance", "kcal", listOf(p(0, pair.first), p(30, pair.second)), at(0), at(30),
            )
            assertEquals("${pair.first} to ${pair.second}", pair.second - pair.first, c.delta!!, 1e-9)
            assertNull("${pair.first} to ${pair.second} should have no percentage", c.fraction)
            assertTrue(PeriodCompare.sentence(c), !PeriodCompare.sentence(c).contains("%"))
        }
    }

    @Test
    fun `a negative reading keeps its sign in the sentence`() {
        // ⚠️ Both call sites of the formatter pass a value that happens to be positive, which is
        // exactly the situation in which a sign bug survives: integer division truncates toward zero,
        // so a naive -0.4 prints as 0.4. A balance series makes the branch reachable.
        val c = PeriodCompare.compare("Balance", "kcal", listOf(p(0, -600.0), p(30, -200.0)), at(0), at(30))
        val s = PeriodCompare.sentence(c)
        assertEquals("Up 400.0 kcal over 4 weeks — -600.0 to -200.0 kcal.", s)
    }

    // -------------------------------------------------------------------------------- the words

    @Test
    fun `the sentence carries how long it took, not just how much`() {
        // ⚠️ "Down 7.2 cm" over three weeks and over three months are different findings, and the
        // bare number cannot tell them apart.
        val quarter = PeriodCompare.compare("Waist", "cm", listOf(p(0, 96.0), p(90, 88.8)), at(0), at(90))
        val s = PeriodCompare.sentence(quarter)
        assertEquals("Down 7.2 cm (8%) over 3 months — 96.0 to 88.8 cm.", s)

        val fortnight = PeriodCompare.compare("Waist", "cm", listOf(p(0, 96.0), p(14, 94.5)), at(0), at(14))
        assertEquals("Down 1.5 cm (2%) over 2 weeks — 96.0 to 94.5 cm.", PeriodCompare.sentence(fortnight))

        val week = PeriodCompare.compare("Waist", "cm", listOf(p(0, 96.0), p(6, 95.5)), at(0), at(6))
        assertTrue(PeriodCompare.sentence(week), PeriodCompare.sentence(week).contains("over 6 days"))
    }

    @Test
    fun `up reads as up`() {
        val c = PeriodCompare.compare("Chest", "cm", listOf(p(0, 100.0), p(60, 103.0)), at(0), at(60))
        assertEquals("Up 3.0 cm (3%) over 2 months — 100.0 to 103.0 cm.", PeriodCompare.sentence(c))
    }

    @Test
    fun `a change too small to be one is called holding`() {
        val c = PeriodCompare.compare("Neck", "cm", listOf(p(0, 38.0), p(60, 38.02)), at(0), at(60))
        assertTrue(PeriodCompare.sentence(c), PeriodCompare.sentence(c).contains("is where it was"))
    }

    @Test
    fun `a refusal prints as the refusal, never as a number`() {
        val c = PeriodCompare.compare("Hips", "cm", emptyList(), at(0), at(90))
        assertEquals(c.why, PeriodCompare.sentence(c))
    }

    @Test
    fun `decimals are locale-independent and signed correctly`() {
        // ⚠️ A comma-decimal phone must read the same as a point-decimal one, so the formatting is
        // arithmetic rather than String.format. And a value between -1 and 0 must keep its sign:
        // integer division truncates toward zero, so the naive version prints -0.4 as 0.4.
        val c = PeriodCompare.compare("Arm", "cm", listOf(p(0, 33.0), p(30, 32.6)), at(0), at(30))
        assertEquals("Down 0.4 cm (1%) over 4 weeks — 33.0 to 32.6 cm.", PeriodCompare.sentence(c))
        val whole = PeriodCompare.compare("Eaten", "kcal", listOf(p(0, 2400.0), p(30, 2100.0)), at(0), at(30))
        assertEquals("Down 300 kcal (13%) over 4 weeks — 2400 to 2100 kcal.", PeriodCompare.sentence(whole, 0))
    }

    // ------------------------------------------------------------------------------- bucketing

    /** Seven-day buckets anchored on the grid's first day — a caller's calendar would do better. */
    private fun weekOf(first: Long): (Long) -> Long = { d -> first + ((d - first) / (7 * day)) * 7 * day }

    private fun grid(range: IntRange) = range.map { at(it) }

    @Test
    fun `days fall into their buckets and the totals are over logged days only`() {
        val g = grid(0..20)
        val values = g.filterIndexed { i, _ -> i % 2 == 0 }.associateWith { 2000.0 }
        val out = PeriodCompare.bucket(g, values, weekOf(at(0)))
        assertEquals(3, out.size)
        // Days 0..6: seven days, four of them even, so four logged.
        assertEquals(7, out[0].days)
        assertEquals(4, out[0].loggedDays)
        assertEquals(8000.0, out[0].total, 1e-9)
        assertEquals(2000.0, out[0].mean!!, 1e-9)
        // Days 14..20 is the tail, and here it happens to be a full seven.
        assertEquals(7, out[2].days)
    }

    @Test
    fun `an unlogged day is absent from the mean, not a zero in it`() {
        // ⚠️ The rule the whole health area holds: averaging zeros in reports a starving person for
        // anybody who skipped a weekend. Five logged days at 2,000 and two unlogged means 2,000 a
        // day, not 1,429.
        val g = grid(0..6)
        val values = grid(0..4).associateWith { 2000.0 }
        val b = PeriodCompare.bucket(g, values, weekOf(at(0))).single()
        assertEquals(7, b.days)
        assertEquals(5, b.loggedDays)
        assertEquals(2000.0, b.mean!!, 1e-9)
        assertEquals(10000.0, b.total, 1e-9)
        assertEquals(5.0 / 7.0, b.completeness, 1e-9)
    }

    @Test
    fun `a bucket with nothing logged still comes back`() {
        // Dropping it would make a fortnight off look like it never happened, and the gap is part of
        // the picture.
        val g = grid(0..20)
        val values = grid(0..6).associateWith { 2000.0 }
        val out = PeriodCompare.bucket(g, values, weekOf(at(0)))
        assertEquals(3, out.size)
        assertEquals(0, out[1].loggedDays)
        assertNull(out[1].mean)
        assertEquals(0.0, out[1].total, 1e-9)
    }

    @Test
    fun `a part bucket says how few days it holds`() {
        // ⚠️ The denominator is what stops a part-week's total being read against a whole week's.
        val g = grid(0..9)
        val out = PeriodCompare.bucket(g, g.associateWith { 2000.0 }, weekOf(at(0)))
        assertEquals(2, out.size)
        assertEquals(7, out[0].days)
        assertEquals(3, out[1].days)
        // The means are equal even though the totals are not, which is the point of reporting both.
        assertEquals(out[0].mean!!, out[1].mean!!, 1e-9)
        assertTrue(out[0].total > out[1].total)
    }

    @Test
    fun `buckets come back oldest first whatever order the grid arrives in`() {
        val g = grid(0..20).shuffled(kotlin.random.Random(4))
        val out = PeriodCompare.bucket(g, g.associateWith { 2000.0 }, weekOf(at(0)))
        assertEquals(out.map { it.startMs }.sorted(), out.map { it.startMs })
        assertEquals(at(0), out.first().startMs)
    }

    @Test
    fun `an empty grid buckets to nothing rather than throwing`() {
        assertEquals(emptyList<PeriodCompare.Bucket>(), PeriodCompare.bucket(emptyList(), emptyMap(), weekOf(at(0))))
    }

    @Test
    fun `a non-finite value is not a logged day`() {
        val g = grid(0..6)
        val values = g.associateWith { if (it == at(3)) Double.NaN else 2000.0 }
        val b = PeriodCompare.bucket(g, values, weekOf(at(0))).single()
        assertEquals(6, b.loggedDays)
        assertEquals(12000.0, b.total, 1e-9)
    }

    @Test
    fun `the step between buckets compares means, never totals`() {
        // ⚠️ Consecutive buckets are not the same size — the last of any grid is usually a part-week
        // — so a total-to-total step would report a collapse in intake every time somebody opened the
        // page mid-week. Here the second bucket holds three days at the same daily figure: the step
        // must be zero, and a total-based one would read as -8,000.
        val g = grid(0..9)
        val out = PeriodCompare.bucket(g, g.associateWith { 2000.0 }, weekOf(at(0)))
        val steps = PeriodCompare.steps(out)
        assertEquals(2, steps.size)
        assertNull(steps[0])
        assertEquals(0.0, steps[1]!!, 1e-9)
    }

    @Test
    fun `a gap breaks the step rather than reading as a fall to zero`() {
        val g = grid(0..20)
        val values = grid(0..6).associateWith { 2000.0 } + grid(14..20).associateWith { 2200.0 }
        val steps = PeriodCompare.steps(PeriodCompare.bucket(g, values, weekOf(at(0))))
        assertEquals(3, steps.size)
        assertNull(steps[0])
        assertNull(steps[1])
        assertNull(steps[2])
    }
}
