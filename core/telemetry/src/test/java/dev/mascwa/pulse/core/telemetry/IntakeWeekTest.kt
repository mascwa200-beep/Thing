package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ Every expected number was computed from the defining formula before the assertion was written,
 * and the arithmetic is left in the comment beside it.
 */
class IntakeWeekTest {

    private val TODAY = 1_700_000_000_000L / IntakeWeek.DAY_MS * IntakeWeek.DAY_MS
    private fun daysAgo(n: Int) = TODAY - n * IntakeWeek.DAY_MS
    private fun n(kcal: Double, protein: Double = 0.0) =
        NutritionDay.Nutrients(kcal = kcal, proteinG = protein)

    // ------------------------------------------------------------------------------- the refusals

    /** "On target" needs a target, and there is no sensible default to substitute. */
    @Test
    fun noTargetMeansNoWeek() {
        assertNull(IntakeWeek.score(mapOf(daysAgo(1) to n(2000.0)), null, TODAY))
        assertNull(IntakeWeek.score(mapOf(daysAgo(1) to n(2000.0)), 0, TODAY))
    }

    /**
     * ⚠️ THE LOAD-BEARING RULE. A day nobody logged is not a day nobody ate — the store's own note
     * says exactly that where it builds the intake series. Averaging a zero in would report a
     * starving person's week for anybody who skipped a weekend.
     */
    @Test
    fun anUnloggedDayIsAbsentRatherThanZero() {
        // Three logged days at 1950 / 2100 / 1700 inside a seven-day window.
        // Their mean is 5750/3 = 1916.67. Counting four zeros in would give 5750/7 = 821.
        val w = IntakeWeek.score(
            mapOf(daysAgo(1) to n(1950.0), daysAgo(3) to n(2100.0), daysAgo(5) to n(1700.0)),
            2000, TODAY,
        )!!
        assertEquals(1916.6666667, w.meanKcal, 1e-6)
        assertEquals(3, w.loggedDays)
        assertEquals(7, w.windowDays)
        // 3 logged days / 7-day window = 0.428571…
        assertEquals(0.4285714, w.completeness, 1e-6)
    }

    /** A day present in the map with nothing in it is a day somebody opened and did not use. */
    @Test
    fun aDayLoggedWithNothingInItDoesNotCount() {
        val w = IntakeWeek.score(
            mapOf(daysAgo(1) to n(1950.0), daysAgo(2) to n(0.0), daysAgo(3) to n(2100.0)),
            2000, TODAY,
        )!!
        assertEquals(2, w.loggedDays)
    }

    /**
     * ⚠️ THE OTHER LOAD-BEARING RULE. Today is not finished. Counting it would mark every day under
     * target until dinner, and drag the week's average into a deficit nobody ran.
     */
    @Test
    fun todayIsChartedButNeverJudged() {
        // Yesterday 1950 (on target). Today 400 so far — plainly "under" if it were counted.
        val w = IntakeWeek.score(mapOf(daysAgo(1) to n(1950.0), TODAY to n(400.0)), 2000, TODAY)!!
        assertEquals("both days are charted", 2, w.loggedDays)
        assertEquals("only yesterday is judged", 1, w.judgedDays)
        assertEquals(0, w.underDays)
        assertEquals(1, w.onTargetDays)
        // The mean is yesterday alone: today's partial 400 must not appear in it.
        assertEquals(1950.0, w.meanKcal, 1e-9)
        assertTrue("today is in the series", w.days.any { it.partial })
    }

    // ---------------------------------------------------------------------------------- the bands

    @Test
    fun theBandIsATenthOfTheTargetAndItsEdgesAreInclusive() {
        // 2000 ± 10% = 1800..2200 inclusive.
        fun standing(kcal: Double) = IntakeWeek.DayScore(daysAgo(1), kcal, false).standing(2000)
        assertEquals(IntakeWeek.Standing.ON_TARGET, standing(1800.0))
        assertEquals(IntakeWeek.Standing.ON_TARGET, standing(2200.0))
        assertEquals(IntakeWeek.Standing.ON_TARGET, standing(2000.0))
        assertEquals(IntakeWeek.Standing.UNDER, standing(1500.0))
        assertEquals(IntakeWeek.Standing.OVER, standing(2400.0))
    }

    /** A target that is not a target cannot classify anything, and must say so rather than pick. */
    @Test
    fun aZeroTargetYieldsUnknownRatherThanUnder() {
        assertEquals(
            IntakeWeek.Standing.UNKNOWN,
            IntakeWeek.DayScore(daysAgo(1), 1500.0, false).standing(0),
        )
    }

    @Test
    fun theWindowExcludesWhatFellOutOfIt() {
        val w = IntakeWeek.score(
            mapOf(daysAgo(1) to n(2000.0), daysAgo(6) to n(2000.0), daysAgo(7) to n(2000.0)),
            2000, TODAY,
        )!!
        // A seven-day window ending today spans daysAgo(6)..today. daysAgo(7) is outside it.
        assertEquals(2, w.loggedDays)
    }

    @Test
    fun theSeriesIsOldestFirstSoAChartReadsLeftToRight() {
        val w = IntakeWeek.score(
            mapOf(daysAgo(1) to n(1900.0), daysAgo(5) to n(2100.0), daysAgo(3) to n(2000.0)),
            2000, TODAY,
        )!!
        assertEquals(listOf(daysAgo(5), daysAgo(3), daysAgo(1)), w.days.map { it.dayStartMs })
    }

    /**
     * ⚠️ The window's own start, because a chart has to know where its row begins.
     *
     * Deriving it from the logged days — the obvious shortcut — is wrong whenever either end of the
     * window has nothing in it: the whole row shifts and the gaps land on the wrong days. The two
     * cases below are exactly the ones that shortcut gets wrong.
     */
    @Test
    fun theWindowKnowsWhereItStartsEvenWhenBothEndsAreUnlogged() {
        // A seven-day window ending today starts six days ago, whatever is or is not logged.
        val expected = daysAgo(6)
        assertEquals(
            expected,
            IntakeWeek.score(mapOf(daysAgo(3) to n(2000.0)), 2000, TODAY)!!.windowStartMs,
        )
        assertEquals(
            "an empty window still knows its own shape",
            expected,
            IntakeWeek.score(emptyMap(), 2000, TODAY)!!.windowStartMs,
        )
        // And it follows the window length rather than the data.
        assertEquals(
            daysAgo(13),
            IntakeWeek.score(mapOf(daysAgo(2) to n(2000.0)), 2000, TODAY, windowDays = 14)!!.windowStartMs,
        )
    }

    // -------------------------------------------------------------------------------- the verdict

    /** Two logged days is not a pattern, and "100% on target" off one Tuesday is confident nonsense. */
    @Test
    fun tooFewFinishedDaysGetsNoVerdict() {
        val thin = IntakeWeek.score(
            mapOf(daysAgo(1) to n(2000.0), daysAgo(2) to n(2000.0)), 2000, TODAY,
        )!!
        assertFalse(thin.judgeable)
        assertNull(IntakeWeek.verdict(thin))

        val enough = IntakeWeek.score(
            mapOf(daysAgo(1) to n(2000.0), daysAgo(2) to n(2000.0), daysAgo(3) to n(2000.0)),
            2000, TODAY,
        )!!
        assertTrue(enough.judgeable)
        assertNotNull(IntakeWeek.verdict(enough))
    }

    /**
     * ⚠️ The floor counts FINISHED days, not logged ones. Otherwise a person two days into using the
     * app gets a verdict as soon as they log breakfast, off one and a half days of data.
     */
    @Test
    fun todayDoesNotHelpReachTheFloor() {
        val w = IntakeWeek.score(
            mapOf(daysAgo(1) to n(2000.0), daysAgo(2) to n(2000.0), TODAY to n(2000.0)),
            2000, TODAY,
        )!!
        assertEquals(3, w.loggedDays)
        assertEquals(2, w.judgedDays)
        assertFalse(w.judgeable)
    }

    @Test
    fun theVerdictNamesWhichWayTheRestWent() {
        fun verdictFor(vararg kcal: Double) = IntakeWeek.verdict(
            IntakeWeek.score(kcal.mapIndexed { i, k -> daysAgo(i + 1) to n(k) }.toMap(), 2000, TODAY)!!,
        )!!
        assertTrue(verdictFor(2000.0, 2500.0, 2600.0).contains("mostly over"))
        assertTrue(verdictFor(2000.0, 1400.0, 1300.0).contains("mostly under"))
        assertTrue(verdictFor(2000.0, 2500.0, 1300.0).contains("split either way"))
        // A perfect week says nothing about "the rest" — there is no rest.
        val perfect = verdictFor(2000.0, 1950.0, 2050.0)
        assertFalse(perfect.contains("the rest"))
        assertTrue(perfect.startsWith("On target 3 of 3"))
    }

    /**
     * ⚠️ Not a style note. Praise and blame are judgements the data cannot support — a week under
     * target may be a week of being ill — and this tab already tells a real person how much to eat.
     */
    @Test
    fun noVerdictPraisesOrScolds() {
        val words = listOf("well done", "good", "great", "bad", "poor", "failed", "should", "too much")
        for (a in listOf(1500.0, 2000.0, 2600.0)) {
            for (b in listOf(1500.0, 2000.0, 2600.0)) {
                for (cc in listOf(1500.0, 2000.0, 2600.0)) {
                    val v = IntakeWeek.verdict(
                        IntakeWeek.score(
                            mapOf(daysAgo(1) to n(a), daysAgo(2) to n(b), daysAgo(3) to n(cc)),
                            2000, TODAY,
                        )!!,
                    )!!
                    for (w in words) assertFalse("must not say '$w': $v", v.lowercase().contains(w))
                }
            }
        }
    }

    // ---------------------------------------------------------------------------- the completeness

    /**
     * ⚠️ Silent on a nearly-full week, because a caveat that appears every time stops being read —
     * and that would cost it its force on the week that genuinely is three days in ten.
     */
    @Test
    fun theCompletenessNoteIsSilentWhenTheWeekIsEssentiallyFull() {
        fun noteFor(logged: Int) = IntakeWeek.completenessNote(
            IntakeWeek.score((1..logged).associate { daysAgo(it) to n(2000.0) }, 2000, TODAY)!!,
        )
        assertNull("seven of seven", noteFor(7))
        assertNull("six of seven is not worth a caveat", noteFor(6))
        assertNotNull("five of seven is", noteFor(5))
        assertTrue(noteFor(4)!!.contains("4 of the last 7"))
    }

    /** Nothing at all is a different sentence: there is no average to caveat. */
    @Test
    fun anEmptyWeekSaysThereIsNothingToMeasure() {
        val w = IntakeWeek.score(emptyMap(), 2000, TODAY)!!
        assertEquals(0, w.loggedDays)
        assertEquals(0.0, w.meanKcal, 1e-9)
        assertNull(IntakeWeek.verdict(w))
        assertTrue(IntakeWeek.completenessNote(w)!!.contains("Nothing logged"))
    }

    @Test
    fun theMacroMeansAreOverTheSameFinishedDays() {
        val w = IntakeWeek.score(
            mapOf(
                daysAgo(1) to n(2000.0, protein = 120.0),
                daysAgo(2) to n(2000.0, protein = 90.0),
                TODAY to n(500.0, protein = 30.0),
            ),
            2000, TODAY,
        )!!
        // (120 + 90) / 2 = 105 — today's 30 g is on an unfinished day.
        assertEquals(105.0, w.meanProteinG, 1e-9)
    }
}
