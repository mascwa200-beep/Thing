package dev.mascwa.pulse.core.util

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Formatters.relativeTime] is the app's shared answer to "when was this?", used on news articles,
 * safety incidents and fetch times alike. The case worth pinning is the one it used to get wrong:
 * past a week it prints a date, and a date without a year is ambiguous the moment the reader is
 * looking at something from a previous year.
 *
 * The fixtures are built with [Calendar] in the default zone — the same zone the formatter reads —
 * so the assertions do not quietly depend on the machine running them being in UTC.
 */
class FormattersTest {

    /** Epoch millis for a local date at noon, so no fixture can straddle a day boundary. */
    private fun localNoon(year: Int, month: Int, day: Int): Long {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, day, 12, 0, 0)
        return cal.timeInMillis
    }

    /** How the platform renders a date here, so the expectation is not hard-coded to one locale. */
    private fun rendered(pattern: String, epochMs: Long): String =
        java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(java.util.Date(epochMs))

    private val now = localNoon(2026, 8, 16)

    @Test
    fun theRecentPastIsRelative() {
        assertEquals("just now", Formatters.relativeTime(now - 30_000L, now))
        assertEquals("5m ago", Formatters.relativeTime(now - 5L * 60_000L, now))
        assertEquals("3h ago", Formatters.relativeTime(now - 3L * 3_600_000L, now))
        assertEquals("yesterday", Formatters.relativeTime(now - 25L * 3_600_000L, now))
        assertEquals("4d ago", Formatters.relativeTime(now - 4L * 86_400_000L, now))
    }

    /**
     * The defect this pins: "yesterday" is a claim about which day it was, and it was being decided
     * by how much time had passed.
     *
     * `now` here is noon on Sunday 16 August. A story filed at 13:00 on **Friday** is 47 hours old,
     * which is one whole elapsed day — so the old `days < 2` branch called it yesterday, on a story
     * from the day before yesterday. Anything published in the afternoon reads as a day fresher than
     * it is for the whole of the following day, and a news feed is the one place that matters.
     */
    @Test
    fun theDayBeforeYesterdayIsNotYesterday() {
        val fridayAfternoon = localNoon(2026, 8, 14) + 3_600_000L      // 47h before noon Sunday
        assertEquals("2d ago", Formatters.relativeTime(fridayAfternoon, now))

        // …and the boundary either side of it still reads as it should.
        assertEquals("yesterday", Formatters.relativeTime(localNoon(2026, 8, 15), now))
        assertEquals("3d ago", Formatters.relativeTime(localNoon(2026, 8, 13), now))
    }

    /**
     * ⚠️ Where the clocks go back a local day runs twenty-five hours, so more than a day of elapsed
     * time can still be the same date — and "yesterday" would then be a statement about today.
     *
     * Pinned in a zone that genuinely observes it rather than the machine's own, because a runner in
     * UTC would never reach the branch and the guard would sit here proving nothing. 26 October 2025
     * is the European autumn transition: 00:30 to 23:30 that day is 24.5 hours inside one date.
     */
    @Test
    fun aTwentyFiveHourDayStillReadsInHours() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
            val cal = Calendar.getInstance()
            cal.clear(); cal.set(2025, 9, 26, 0, 30, 0)
            val earlyThatDay = cal.timeInMillis
            cal.clear(); cal.set(2025, 9, 26, 23, 30, 0)
            val lateThatDay = cal.timeInMillis

            val elapsedHours = (lateThatDay - earlyThatDay) / 3_600_000L
            assertTrue("the fixture must actually span a long day: ${elapsedHours}h", elapsedHours >= 24)
            assertEquals("${elapsedHours}h ago", Formatters.relativeTime(earlyThatDay, lateThatDay))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    /** A date inside the current year needs no year — the reader supplies it. */
    @Test
    fun aDateThisYearOmitsTheYear() {
        val jan = localNoon(2026, 1, 20)
        val out = Formatters.relativeTime(jan, now)
        assertEquals(rendered("MMM d", jan), out)
        assertTrue("a same-year date must not carry a year: $out", !out.contains("2026"))
    }

    /**
     * The defect this test exists for.
     *
     * Last August and this August rendered identically as "Aug 12", so a resurfaced old story looked
     * as current as a fresh one — in a news feed, the single place the distinction matters most.
     */
    @Test
    fun aDateFromAnotherYearCarriesItsYear() {
        val lastAugust = localNoon(2025, 8, 12)
        val out = Formatters.relativeTime(lastAugust, now)
        assertEquals(rendered("MMM d yyyy", lastAugust), out)
        assertTrue("an older-year date must carry its year: $out", out.contains("2025"))

        val thisAugust = localNoon(2026, 8, 2)
        assertTrue(
            "the two must not render identically",
            Formatters.relativeTime(thisAugust, now) != out,
        )
    }

    /** January reading a December date is a year apart by two weeks, which the year has to show. */
    @Test
    fun theYearAppearsOnCalendarYearBoundariesNotAfterTwelveMonths() {
        val newYear = localNoon(2026, 1, 5)
        val lateDecember = localNoon(2025, 12, 22)
        val out = Formatters.relativeTime(lateDecember, newYear)
        assertTrue("two weeks earlier but a different year: $out", out.contains("2025"))
    }

    /** Absent and future timestamps stay as they were — neither is a date to render. */
    @Test
    fun missingAndFutureTimesAreHandled() {
        assertEquals("", Formatters.relativeTime(0L, now))
        assertEquals("", Formatters.relativeTime(-5L, now))
        assertEquals("just now", Formatters.relativeTime(now + 60_000L, now))
    }

    /** The zone the fixtures assume is the zone the formatter uses; if that ever diverged, say so. */
    @Test
    fun theFixturesAndTheFormatterShareAZone() {
        assertEquals(TimeZone.getDefault(), Calendar.getInstance().timeZone)
    }

    // ---- axisLabel ---------------------------------------------------------------------------
    //
    // Untested for as long as it lived privately inside the phone's chart kit. It has two consumers
    // now — both chart kits draw their ticks through it — so a change here silently relabels every
    // axis on two platforms at once, which is exactly the kind of rule that earns a test.

    /** A tick is a number a reader glances at, so a whole number carries no decimal point. */
    @Test
    fun wholeNumbersLoseTheirDecimal() {
        assertEquals("0", Formatters.axisLabel(0.0))
        assertEquals("3", Formatters.axisLabel(3.0))
        assertEquals("-7", Formatters.axisLabel(-7.0))
        assertEquals("100", Formatters.axisLabel(100.0))
    }

    /**
     * Past a hundred a tenth is noise on an axis, so the label rounds to the integer.
     *
     * ⚠️ `roundToInt` breaks ties towards POSITIVE infinity, not away from zero, so +1234.5 goes to
     * 1235 and -1234.5 goes to -1234. Asserted rather than smoothed over: it is the shipped
     * behaviour, it is half a unit on an axis running past a hundred, and pinning it means a future
     * switch to a different rounding rule fails here instead of quietly moving every large tick.
     */
    @Test
    fun largeValuesRoundToTheInteger() {
        assertEquals("1235", Formatters.axisLabel(1234.5))
        assertEquals("-1234", Formatters.axisLabel(-1234.5))
        assertEquals("-1235", Formatters.axisLabel(-1234.6))
    }

    /** Between one and a hundred, one decimal is the most an axis can show without crowding. */
    @Test
    fun midRangeValuesKeepOneDecimal() {
        assertEquals("3.3", Formatters.axisLabel(3.25))
        assertEquals("99.5", Formatters.axisLabel(99.5))
    }

    /** Under one, one decimal would collapse distinct ticks onto each other. */
    @Test
    fun smallValuesKeepTwo() {
        assertEquals("0.25", Formatters.axisLabel(0.25))
        assertEquals("-0.50", Formatters.axisLabel(-0.5))
    }

    /**
     * The reason the scientific tail exists at all.
     *
     * An X-ray flux axis runs over several decades — the flare classes A through X ARE decades — so
     * rounding 4e-08 and 4e-06 to "0.00" would make every tick on that chart identical.
     */
    @Test
    fun valuesSpanningDecadesStayDistinguishable() {
        assertEquals("4e-03", Formatters.axisLabel(0.004))
        val ticks = listOf(1e-8, 1e-7, 1e-6, 1e-5, 1e-4).map { Formatters.axisLabel(it) }
        assertEquals("a decade axis must not collapse: $ticks", 5, ticks.toSet().size)
    }

    /**
     * ⚠️ Not defensive padding. `minOf`/`maxOf` propagate a NaN straight from any series value into
     * the axis bounds, and `roundToInt()` throws outright on one — so without this the chart takes
     * the whole screen down rather than drawing a blank tick.
     */
    @Test
    fun nonFiniteValuesDoNotThrow() {
        assertEquals("—", Formatters.axisLabel(Double.NaN))
        assertEquals("—", Formatters.axisLabel(Double.POSITIVE_INFINITY))
        assertEquals("—", Formatters.axisLabel(Double.NEGATIVE_INFINITY))
    }

    /**
     * ⚠️ `Locale.US` throughout, deliberately, and this is the assertion that holds it there. These
     * labels sit beside their own gridlines, where a comma decimal separator reads as a thousands
     * separator — "1,5" on an axis running to 2 is unreadable.
     */
    @Test
    fun theDecimalSeparatorIsNotTheDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("3.3", Formatters.axisLabel(3.25))
            assertEquals("0.25", Formatters.axisLabel(0.25))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
