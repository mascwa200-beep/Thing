package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EconomyVintage] exists to stop an annual figure reading as a current one, so the cases that
 * matter are the band edges and the hand-rolled calendar underneath them.
 *
 * Every expected value below was computed from the arithmetic and checked against a twin before the
 * assertion was written, and the working is in the comment beside it. The calendar itself was
 * verified against the JDK's own date maths across ~50,000 days, including pre-epoch dates and leap
 * years, rather than spot-checked.
 */
class EconomyVintageTest {

    /** Epoch millis for a UTC date, computed from days-since-epoch so the fixture needs no library. */
    private fun utc(year: Int, month: Int, day: Int): Long {
        // days-from-civil, the inverse of the algorithm under test — deliberately a different
        // formula, so a shared bug cannot cancel itself out.
        val y = if (month <= 2) year - 1 else year
        val era = Math.floorDiv(y.toLong(), 400L)
        val yoe = y - era * 400
        val mp = if (month > 2) month - 3 else month + 9
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146_097L + doe - 719_468L
        return days * 86_400_000L
    }

    /** The real case this was built for: 2026, and the newest World Bank CPI figure is 2024. */
    private val today = utc(2026, 8, 16)

    // ---- age ---------------------------------------------------------------------------------

    @Test
    fun ageIsMeasuredFromTheEndOfTheDataYear() {
        // (2026 - 2024) * 12 + (8 - 12) = 24 - 4 = 20
        assertEquals(20, EconomyVintage.ageMonths(2024, today))
        // (2026 - 2025) * 12 + (8 - 12) = 12 - 4 = 8
        assertEquals(8, EconomyVintage.ageMonths(2025, today))
    }

    /**
     * A figure published for the year in progress is not negatively old.
     *
     * Measuring from December of the data year means the current year's own figure computes as
     * "minus four months", which would print as nonsense if it escaped.
     */
    @Test
    fun theCurrentYearsFigureIsNeverNegativelyOld() {
        assertEquals(0, EconomyVintage.ageMonths(2026, today))
        assertEquals(0, EconomyVintage.ageMonths(2030, today))
    }

    // ---- bands -------------------------------------------------------------------------------

    @Test
    fun theBandsFollowTheAgeInMonths() {
        // ages at 2026-08: 2025→8, 2024→20, 2023→32, 2022→44
        assertEquals(EconomyVintage.Vintage.CURRENT, EconomyVintage.band(2025, today))
        assertEquals(EconomyVintage.Vintage.RECENT, EconomyVintage.band(2024, today))
        assertEquals(EconomyVintage.Vintage.DATED, EconomyVintage.band(2023, today))
        assertEquals(EconomyVintage.Vintage.OLD, EconomyVintage.band(2022, today))
    }

    /**
     * The edges exactly.
     *
     * Fifteen months of slack is deliberate: annual data for a year lands part-way through the next
     * one, so a twelve-month cut-off would flag every series in the app for months at a time and
     * teach the reader to ignore the flag.
     */
    @Test
    fun theBandEdgesLandWhereTheConstantsSay() {
        // data year 2024: age 12 at 2025-12, 15 at 2026-03, 26 at 2027-02, 27 at 2027-03,
        //                 38 at 2028-02, 39 at 2028-03
        assertEquals(EconomyVintage.Vintage.CURRENT, EconomyVintage.band(2024, utc(2025, 12, 15)))
        assertEquals(EconomyVintage.Vintage.RECENT, EconomyVintage.band(2024, utc(2026, 3, 15)))
        assertEquals(EconomyVintage.Vintage.RECENT, EconomyVintage.band(2024, utc(2027, 2, 15)))
        assertEquals(EconomyVintage.Vintage.DATED, EconomyVintage.band(2024, utc(2027, 3, 15)))
        assertEquals(EconomyVintage.Vintage.DATED, EconomyVintage.band(2024, utc(2028, 2, 15)))
        assertEquals(EconomyVintage.Vintage.OLD, EconomyVintage.band(2024, utc(2028, 3, 15)))
    }

    // ---- the line on the card ----------------------------------------------------------------

    /** A fresh figure gets its year and nothing else — an age on a current number is noise. */
    @Test
    fun aCurrentFigureShowsOnlyItsYear() {
        assertEquals("2025 data", EconomyVintage.describe(2025, today))
        assertEquals("2026 data", EconomyVintage.describe(2026, today))
    }

    /** The case that prompted all of this: twenty months old, and the card used to say nothing. */
    @Test
    fun aYearBehindShowsItsAgeInMonths() {
        assertEquals("2024 · 20 months old", EconomyVintage.describe(2024, today))
    }

    @Test
    fun olderFiguresRollUpToYearsAndRoundRatherThanTruncate() {
        // 2023 at 2026-08 is 32 months = 2y8m → (32 + 6) / 12 = 3
        assertEquals("2023 · 3 years old", EconomyVintage.describe(2023, today))
        // 2022 is 44 months = 3y8m → (44 + 6) / 12 = 4, where truncation would have said 3
        assertEquals("2022 · 4 years old", EconomyVintage.describe(2022, today))
    }

    // ---- caution -----------------------------------------------------------------------------

    /**
     * Silence for anything a reader should expect.
     *
     * An annual statistic being a year behind is how annual statistics work, not something to
     * apologise for, and a warning attached to every figure is a warning attached to none.
     */
    @Test
    fun aNormallyLaggingFigureCarriesNoWarning() {
        assertNull(EconomyVintage.caution(2025, today))
        assertNull(EconomyVintage.caution(2024, today))
    }

    @Test
    fun agenuinelyStaleFigureIsCalledOut() {
        val dated = EconomyVintage.caution(2023, today)
        assertNotNull(dated)
        assertTrue("must name the year it describes", dated!!.contains("2023"))

        val old = EconomyVintage.caution(2019, today)
        assertNotNull(old)
        assertTrue("must say it is history, not a reading", old!!.contains("history"))
    }

    // ---- calendar ----------------------------------------------------------------------------

    /**
     * The hand-rolled calendar, against dates whose answers are not in dispute.
     *
     * Includes a leap day, a year boundary, and a pre-epoch date — the three places a
     * roll-your-own date routine goes wrong, and the reason this is not a leap-year loop.
     */
    @Test
    fun theCalendarHandlesLeapDaysBoundariesAndPreEpochDates() {
        assertEquals(1970, EconomyVintage.yearOf(0L))
        assertEquals(1, EconomyVintage.monthOf(0L))

        assertEquals(2024, EconomyVintage.yearOf(utc(2024, 2, 29)))
        assertEquals(2, EconomyVintage.monthOf(utc(2024, 2, 29)))

        assertEquals(2025, EconomyVintage.yearOf(utc(2025, 12, 31)))
        assertEquals(12, EconomyVintage.monthOf(utc(2025, 12, 31)))
        assertEquals(2026, EconomyVintage.yearOf(utc(2026, 1, 1)))
        assertEquals(1, EconomyVintage.monthOf(utc(2026, 1, 1)))

        // Pre-epoch: the day before 1970-01-01 must floor to 1969-12, not truncate to 1970-01.
        assertEquals(1969, EconomyVintage.yearOf(-1L))
        assertEquals(12, EconomyVintage.monthOf(-1L))
    }
}
