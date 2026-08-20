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
}
