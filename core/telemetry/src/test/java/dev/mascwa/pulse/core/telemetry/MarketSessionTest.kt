package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MarketSession] decides whether a price is a market or a memory, so the boundaries are the whole
 * point: the instant of the bell, a venue with no pre/post session at all, and a fifty-two-week
 * range that has collapsed to a single number.
 *
 * Every expected value below is arithmetic on the fixtures, worked out and written down in the
 * comment beside it rather than recalled.
 */
class MarketSessionTest {

    // A venue day built from round numbers so every expectation can be checked by hand.
    //   pre     999_980_200_000 .. 1_000_000_000_000   (5h30m)
    //   regular 1_000_000_000_000 .. 1_000_023_400_000 (6h30m = 23_400_000 ms)
    //   post    1_000_023_400_000 .. 1_000_037_800_000 (4h    = 14_400_000 ms)
    private val open = 1_000_000_000_000L
    private val close = open + 23_400_000L
    private val windows = MarketSession.Windows(
        pre = MarketSession.Window(open - 19_800_000L, open),
        regular = MarketSession.Window(open, close),
        post = MarketSession.Window(close, close + 14_400_000L),
    )

    // ---- phase ------------------------------------------------------------------------------

    @Test
    fun eachWindowReportsItsOwnPhase() {
        assertEquals(MarketSession.Phase.PRE, MarketSession.phaseAt(windows, open - 1_000_000L))
        assertEquals(MarketSession.Phase.OPEN, MarketSession.phaseAt(windows, open + 1_000_000L))
        assertEquals(MarketSession.Phase.AFTER, MarketSession.phaseAt(windows, close + 1_000_000L))
    }

    @Test
    fun outsideEveryWindowIsClosed() {
        assertEquals(MarketSession.Phase.CLOSED, MarketSession.phaseAt(windows, open - 99_000_000L))
        assertEquals(MarketSession.Phase.CLOSED, MarketSession.phaseAt(windows, close + 99_000_000L))
    }

    /** The windows are half-open, so the closing instant already belongs to the after-hours session. */
    @Test
    fun theBellItselfIsAlreadyAfterHours() {
        assertEquals(MarketSession.Phase.OPEN, MarketSession.phaseAt(windows, open))
        assertEquals(MarketSession.Phase.AFTER, MarketSession.phaseAt(windows, close))
    }

    /**
     * No windows, or no regular window, is UNKNOWN — never CLOSED. CLOSED is a claim about a venue;
     * absent data is a claim about us, and the two must not be rendered the same way.
     */
    @Test
    fun anUnestablishedSessionIsUnknownRatherThanClosed() {
        assertEquals(MarketSession.Phase.UNKNOWN, MarketSession.phaseAt(null, open))
        val noRegular = MarketSession.Windows(pre = windows.pre, regular = null, post = windows.post)
        assertEquals(MarketSession.Phase.UNKNOWN, MarketSession.phaseAt(noRegular, open - 1_000_000L))
        val inverted = MarketSession.Windows(regular = MarketSession.Window(close, open))
        assertEquals(MarketSession.Phase.UNKNOWN, MarketSession.phaseAt(inverted, open + 1L))
    }

    /** A venue with only a regular session must still work — most instruments have no pre or post. */
    @Test
    fun aVenueWithOnlyARegularSessionStillResolves() {
        val bare = MarketSession.Windows(regular = MarketSession.Window(open, close))
        assertEquals(MarketSession.Phase.OPEN, MarketSession.phaseAt(bare, open + 5L))
        assertEquals(MarketSession.Phase.CLOSED, MarketSession.phaseAt(bare, close + 5L))
    }

    // ---- countdowns -------------------------------------------------------------------------

    @Test
    fun countdownsAreMeasuredAgainstTheRegularSession() {
        // open + 1_000_000 → close - now = 23_400_000 - 1_000_000 = 22_400_000
        assertEquals(22_400_000L, MarketSession.msUntilClose(windows, open + 1_000_000L))
        // pre, 1_000_000 in → open - now = 19_800_000 - 1_000_000 = 18_800_000
        assertEquals(18_800_000L, MarketSession.msUntilOpen(windows, open - 18_800_000L))
    }

    @Test
    fun countdownsRefuseToRunTheWrongWay() {
        assertNull(MarketSession.msUntilClose(windows, close + 1L))   // not open: nothing to count
        assertNull(MarketSession.msUntilOpen(windows, open + 1L))     // already opened
        assertNull(MarketSession.msUntilClose(null, open))
    }

    // ---- description ------------------------------------------------------------------------

    @Test
    fun anOpenMarketCountsDownToTheBell() {
        // 22_400_000 ms / 60_000 = 373 min → 373 / 60 = 6h, 373 % 60 = 13m
        assertEquals("Open · 6h 13m to the bell", MarketSession.describe(windows, open + 1_000_000L))
    }

    @Test
    fun aClosedMarketAgesItsLastPrint() {
        // 10_800_000 ms = 180 min → 3h exactly, so no trailing minutes
        val now = close + 20_000_000L
        assertEquals(
            "Closed · last traded 3h ago",
            MarketSession.describe(windows, now, lastPrintMs = now - 10_800_000L),
        )
        // Without a print time it still says what it knows, and nothing more.
        assertEquals("Closed", MarketSession.describe(windows, now))
    }

    @Test
    fun anUnknownSessionSaysNothingAtAll() {
        assertEquals("", MarketSession.describe(null, open))
    }

    @Test
    fun pricesOutsideTheRegularSessionAreFlaggedAsPrints() {
        assertFalse(MarketSession.isStalePrint(windows, open + 1L))          // OPEN
        assertFalse(MarketSession.isStalePrint(windows, open - 1_000_000L))  // PRE — the market is coming
        assertTrue(MarketSession.isStalePrint(windows, close + 1L))          // AFTER
        assertTrue(MarketSession.isStalePrint(windows, close + 99_000_000L)) // CLOSED
        assertFalse(MarketSession.isStalePrint(null, open))                  // unknown claims nothing
    }

    // ---- durations --------------------------------------------------------------------------

    @Test
    fun aCountdownNeverRoundsDownToNothing() {
        assertEquals("under a minute", MarketSession.compactDuration(30_000L))
        assertEquals("under a minute", MarketSession.compactDuration(0L))
        assertEquals("1m", MarketSession.compactDuration(60_000L))
        assertEquals("59m", MarketSession.compactDuration(59L * 60_000L))
    }

    @Test
    fun longerDurationsRollUpToHoursAndDays() {
        assertEquals("1h", MarketSession.compactDuration(3_600_000L))
        assertEquals("1h 1m", MarketSession.compactDuration(3_660_000L))
        assertEquals("23h", MarketSession.compactDuration(23L * 3_600_000L))
        assertEquals("1d", MarketSession.compactDuration(24L * 3_600_000L))
        assertEquals("1d 1h", MarketSession.compactDuration(25L * 3_600_000L))
    }

    // ---- venue calendar ---------------------------------------------------------------------

    /**
     * The fixtures are the real thing: the last daily candle and the last trade of AAPL's
     * 2026-08-14 session, taken from a live response, with New York's summer offset of −4h.
     */
    @Test
    fun aCandleAndItsOwnSessionsLastTradeShareAVenueDay() {
        val ny = -14_400L
        val lastCandle = 1_786_714_200L   // 2026-08-14 09:30 New York — the bar opens with the bell
        val lastTrade = 1_786_737_601L    // 2026-08-14 16:00 New York — the closing print
        assertTrue(MarketSession.sameVenueDay(lastCandle, lastTrade, ny))
        // The previous session's candle is a different day and must not lend its open to today.
        assertFalse(MarketSession.sameVenueDay(lastCandle - 86_400L, lastTrade, ny))
    }

    /**
     * The venue's calendar, not the phone's. 21:00 New York on the 14th is 01:00 UTC on the 15th, so
     * a UTC comparison would split one trading session across two days.
     */
    @Test
    fun theDayBoundaryIsTheVenuesNotUtc() {
        val ny = -14_400L
        val duringSession = 1_786_730_400L        // 2026-08-14 14:00 New York = 18:00 UTC
        val lateSameEvening = 1_786_755_600L      // 2026-08-14 21:00 New York = 01:00 UTC on the 15th
        assertTrue(MarketSession.sameVenueDay(duringSession, lateSameEvening, ny))
        assertFalse(MarketSession.sameVenueDay(duringSession, lateSameEvening, 0L))
    }

    /** Negative epochs must floor toward the earlier day, not truncate toward zero. */
    @Test
    fun daysBeforeTheEpochStillDivideCorrectly() {
        // -1 s is 1969-12-31; +1 s is 1970-01-01. Truncating division would call both day 0.
        assertFalse(MarketSession.sameVenueDay(-1L, 1L, 0L))
        assertTrue(MarketSession.sameVenueDay(-1L, -86_400L, 0L))
    }

    // ---- fifty-two-week range ---------------------------------------------------------------

    @Test
    fun rangePositionIsTheFractionBetweenTheExtremes() {
        // (150 - 100) / (200 - 100) = 0.5
        assertEquals(0.5f, MarketSession.rangePosition(150.0, 100.0, 200.0)!!, 1e-6f)
        assertEquals(0f, MarketSession.rangePosition(100.0, 100.0, 200.0)!!, 1e-6f)
        assertEquals(1f, MarketSession.rangePosition(200.0, 100.0, 200.0)!!, 1e-6f)
    }

    /** A fresh high beats the venue's own yearly figure to the tape; "at the top" is the honest read. */
    @Test
    fun aPriceBeyondTheKnownRangeClamps() {
        assertEquals(1f, MarketSession.rangePosition(250.0, 100.0, 200.0)!!, 1e-6f)
        assertEquals(0f, MarketSession.rangePosition(50.0, 100.0, 200.0)!!, 1e-6f)
    }

    @Test
    fun anUnusableRangeYieldsNothing() {
        assertNull(MarketSession.rangePosition(150.0, null, 200.0))
        assertNull(MarketSession.rangePosition(null, 100.0, 200.0))
        assertNull(MarketSession.rangePosition(150.0, 200.0, 200.0))  // collapsed: not a range
        assertNull(MarketSession.rangePosition(150.0, 200.0, 100.0))  // inverted
        assertNull(MarketSession.rangePosition(Double.NaN, 100.0, 200.0))
        assertNull(MarketSession.rangePosition(150.0, 100.0, Double.POSITIVE_INFINITY))
    }

    /** Narrow bands at the ends, one wide band through the middle — the extremes are the news. */
    @Test
    fun theRangeBandsFavourTheExtremes() {
        assertEquals("at its 52-week high", MarketSession.describeRange(0.96f))
        assertEquals("near its 52-week high", MarketSession.describeRange(0.85f))
        assertEquals("mid-range for the year", MarketSession.describeRange(0.5f))
        assertEquals("mid-range for the year", MarketSession.describeRange(0.79f))
        assertEquals("near its 52-week low", MarketSession.describeRange(0.15f))
        assertEquals("at its 52-week low", MarketSession.describeRange(0.03f))
        assertNull(MarketSession.describeRange(null))
    }
}
