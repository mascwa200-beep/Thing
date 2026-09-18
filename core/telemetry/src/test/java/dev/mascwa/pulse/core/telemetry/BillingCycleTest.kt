package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ Every expected value below was computed from a real calendar library BEFORE the assertion was
 * written — `datetime.date` arithmetic over the same inputs — rather than reasoned about. Two of
 * them are not what the obvious reading gives, and both are the cases the whole file exists for:
 *
 * ```
 *   2026-04-30 cycleDay=31 -> starts 2026-04-30, day 1 of 31
 *   2026-04-29 cycleDay=31 -> starts 2026-03-31, day 30 of 30
 * ```
 *
 * The 30th of April IS the day a 31st-of-the-month cycle rolls over, and the cycle it ends is 30
 * days long rather than 31.
 */
class BillingCycleTest {

    // ---- the calendar underneath ---------------------------------------------------------

    @Test
    fun `the leap rule is the whole rule, not the every-fourth-year half of it`() {
        assertTrue(BillingCycle.isLeap(2024))
        assertFalse(BillingCycle.isLeap(2026))
        // The two exceptions, which a naive `% 4` gets wrong in opposite directions.
        assertFalse("1900 was not a leap year", BillingCycle.isLeap(1900))
        assertTrue("2000 was", BillingCycle.isLeap(2000))
    }

    @Test
    fun `every month is as long as it is`() {
        val lengths = (1..12).map { BillingCycle.daysInMonth(2026, it) }
        assertEquals(listOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31), lengths)
        assertEquals(29, BillingCycle.daysInMonth(2024, 2))
        // A month number that is not a month: zero rather than an exception, because this is
        // reached from a stored setting and a crash there would take the widget with it.
        assertEquals(0, BillingCycle.daysInMonth(2026, 13))
        assertEquals(0, BillingCycle.daysInMonth(2026, 0))
    }

    @Test
    fun `a cycle day the month does not have falls back to its last`() {
        assertEquals(30, BillingCycle.effectiveDay(2026, 4, 31))
        assertEquals(28, BillingCycle.effectiveDay(2026, 2, 31))
        assertEquals(29, BillingCycle.effectiveDay(2024, 2, 31))
        assertEquals(28, BillingCycle.effectiveDay(2026, 2, 30))
        assertEquals(31, BillingCycle.effectiveDay(2026, 5, 31))
        // A day the month does have is left exactly alone.
        assertEquals(15, BillingCycle.effectiveDay(2026, 2, 15))
    }

    @Test
    fun `a stored setting outside one to thirty-one is coerced rather than trusted`() {
        assertEquals(1, BillingCycle.effectiveDay(2026, 5, 0))
        assertEquals(1, BillingCycle.effectiveDay(2026, 5, -7))
        assertEquals(31, BillingCycle.effectiveDay(2026, 5, 99))
        assertEquals(30, BillingCycle.effectiveDay(2026, 4, 99))
    }

    // ---- where the cycle started ---------------------------------------------------------

    @Test
    fun `an ordinary cycle starts this month once the day has come round`() {
        assertEquals(BillingCycle.Date(2026, 4, 1), BillingCycle.startOf(2026, 4, 1, 1))
        assertEquals(BillingCycle.Date(2026, 4, 1), BillingCycle.startOf(2026, 4, 17, 1))
        assertEquals(BillingCycle.Date(2026, 4, 20), BillingCycle.startOf(2026, 4, 25, 20))
    }

    @Test
    fun `before the day comes round it is still last month's cycle`() {
        assertEquals(BillingCycle.Date(2025, 12, 20), BillingCycle.startOf(2026, 1, 5, 20))
        assertEquals(BillingCycle.Date(2026, 3, 15), BillingCycle.startOf(2026, 4, 2, 15))
    }

    @Test
    fun `the thirtieth of April is when a thirty-first cycle rolls over`() {
        // ⚠️ THE case the whole file exists for. Comparing the day against the RAW setting says 30
        // is less than 31, so the cycle would still read as March's — and then it would read that
        // way on the 1st of May too, because May does have a 31st and 1 is less than that. April
        // would have no rollover at all.
        assertEquals(BillingCycle.Date(2026, 4, 30), BillingCycle.startOf(2026, 4, 30, 31))
        assertEquals(BillingCycle.Date(2026, 3, 31), BillingCycle.startOf(2026, 4, 29, 31))
        assertEquals(BillingCycle.Date(2026, 5, 31), BillingCycle.startOf(2026, 5, 31, 31))
    }

    @Test
    fun `February takes the same treatment, leap year or not`() {
        assertEquals(BillingCycle.Date(2026, 2, 28), BillingCycle.startOf(2026, 2, 28, 31))
        assertEquals(BillingCycle.Date(2024, 2, 29), BillingCycle.startOf(2024, 2, 29, 31))
        // The 1st of March, before a 30th-of-the-month cycle has come round: back into February,
        // which ends on a different day depending on the year.
        assertEquals(BillingCycle.Date(2026, 2, 28), BillingCycle.startOf(2026, 3, 1, 30))
        assertEquals(BillingCycle.Date(2024, 2, 29), BillingCycle.startOf(2024, 3, 1, 30))
    }

    @Test
    fun `January reaches back into the previous year`() {
        assertEquals(BillingCycle.Date(2026, 12, 15), BillingCycle.startOf(2027, 1, 1, 15))
        assertEquals(BillingCycle.Date(2025, 12, 31), BillingCycle.startOf(2026, 1, 30, 31))
    }

    // ---- how far in, and how long ----------------------------------------------------------

    @Test
    fun `the day it resets is day one`() {
        // Day 0 on the morning it rolls over reads like a fault rather than a fresh cycle.
        assertEquals(1, BillingCycle.daysInto(2026, 4, 1, 1))
        assertEquals(1, BillingCycle.daysInto(2026, 4, 30, 31))
        assertEquals(1, BillingCycle.daysInto(2024, 2, 29, 31))
    }

    @Test
    fun `counting across a month boundary counts the days, not the dates`() {
        assertEquals(30, BillingCycle.daysInto(2026, 4, 29, 31))
        assertEquals(17, BillingCycle.daysInto(2026, 1, 5, 20))
        assertEquals(18, BillingCycle.daysInto(2027, 1, 1, 15))
        assertEquals(2, BillingCycle.daysInto(2026, 3, 1, 30))
        assertEquals(2, BillingCycle.daysInto(2024, 3, 1, 30))
    }

    @Test
    fun `a cycle is as long as the gap to the next one, not as long as its month`() {
        // ⚠️ 31 March to 30 April is THIRTY days even though March has thirty-one, and 28 February
        // to 31 March is thirty-one even though February has twenty-eight. Taking the month's own
        // length would be wrong in both directions.
        assertEquals(30, BillingCycle.lengthDays(2026, 4, 29, 31))
        assertEquals(31, BillingCycle.lengthDays(2026, 2, 28, 31))
        assertEquals(30, BillingCycle.lengthDays(2026, 4, 1, 1))
        // ⚠️ And this one is neither: a 31-day month with a 31st cycle day gives a THIRTY-day
        // cycle, because the next start is the 30th of June. My own expectation here said 31 and
        // the code was right — the length belongs to the pair of months, not to either one.
        assertEquals(30, BillingCycle.lengthDays(2026, 5, 31, 31))
        assertEquals(31, BillingCycle.lengthDays(2026, 12, 31, 15))
    }

    @Test
    fun `how far in never runs past how long`() {
        // The property that makes "day 12 of 30" readable at all: it must never say day 32 of 31.
        for (cycleDay in 1..31) {
            for (month in 1..12) {
                for (day in 1..BillingCycle.daysInMonth(2026, month)) {
                    val into = BillingCycle.daysInto(2026, month, day, cycleDay)
                    val len = BillingCycle.lengthDays(2026, month, day, cycleDay)
                    assertTrue("$month/$day cycleDay=$cycleDay: day $into of $len", into in 1..len)
                }
            }
        }
    }

    @Test
    fun `a date renders as a date, in one order, with no locale in it`() {
        assertEquals("2026-04-05", BillingCycle.Date(2026, 4, 5).toString())
        assertEquals("2026-12-31", BillingCycle.Date(2026, 12, 31).toString())
    }
}
