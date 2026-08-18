package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchWindowTest {

    private val minute = 60_000L
    private val hour = 3_600_000L

    /**
     * The case that made this worth building, with the figures straight off the live feed:
     * Starlink Group 17-50, T-0 03:45:08 at **second** precision, window 02:00 → 06:00. The
     * precision guard passes, so without this the four hours are invisible.
     */
    @Test fun theFourHourWindowBehindASecondPreciseTZero() {
        val start = 2 * hour
        val end = 6 * hour
        assertEquals(4 * hour, LaunchWindow.widthMs(start, end))
        assertTrue(LaunchWindow.isMeaningful(start, end))
        assertEquals("4 hours", LaunchWindow.describeWidth(4 * hour))
        // The T-0 sits inside the window here, not at its opening — 03:45 against a 02:00 open.
        assertFalse(LaunchWindow.netIsWindowOpen(3 * hour + 45 * minute, start))
    }

    /** Zhuque-3, the narrowest real window in the sample: 23:27 → 00:04, thirty-seven minutes. */
    @Test fun theNarrowestRealWindowStillEarnsItsLine() {
        assertTrue(LaunchWindow.isMeaningful(0L, 37 * minute))
        assertEquals("37 minutes", LaunchWindow.describeWidth(37 * minute))
    }

    /** A window no wider than the printed time's own noise says nothing and is suppressed. */
    @Test fun aWindowInsideTheTZerosOwnNoiseIsNotWorthSaying() {
        assertFalse(LaunchWindow.isMeaningful(0L, 5 * minute))
        assertFalse(LaunchWindow.isMeaningful(0L, 0L))
        assertTrue(LaunchWindow.isMeaningful(0L, LaunchWindow.MIN_MEANINGFUL_MS))
    }

    /** Missing or backwards ends are an absence of information, never a zero-width window. */
    @Test fun aMissingOrBackwardsWindowIsNull() {
        assertNull(LaunchWindow.widthMs(null, 5L))
        assertNull(LaunchWindow.widthMs(5L, null))
        assertNull(LaunchWindow.widthMs(null, null))
        assertNull(LaunchWindow.widthMs(6 * hour, 2 * hour))
        assertFalse(LaunchWindow.isMeaningful(6 * hour, 2 * hour))
    }

    /**
     * Rounded, not truncated — 3 h 50 m must not be called three hours, which is the direction that
     * under-states the room the flight has.
     */
    @Test fun theWidthIsRoundedNotTruncated() {
        assertEquals("4 hours", LaunchWindow.describeWidth(3 * hour + 50 * minute))
        assertEquals("2 hours", LaunchWindow.describeWidth(hour + 50 * minute))
        assertEquals("30 minutes", LaunchWindow.describeWidth(29 * minute + 40_000L))
    }

    /** The unit ladder, at each of its two boundaries, and the singular. */
    @Test fun theUnitLadderReadsNaturallyAtEveryScale() {
        assertEquals("89 minutes", LaunchWindow.describeWidth(89 * minute))
        assertEquals("2 hours", LaunchWindow.describeWidth(90 * minute))
        assertEquals("35 hours", LaunchWindow.describeWidth(35 * hour))
        assertEquals("2 days", LaunchWindow.describeWidth(36 * hour))
        assertEquals("1 minute", LaunchWindow.describeWidth(minute))
        assertEquals("instantaneous", LaunchWindow.describeWidth(0L))
    }

    /** The ordinary case: the T-0 is the moment the window opens. */
    @Test fun aTZeroAtTheOpeningIsRecognised() {
        assertTrue(LaunchWindow.netIsWindowOpen(15 * hour, 15 * hour))
        assertFalse(LaunchWindow.netIsWindowOpen(null, 15 * hour))
        assertFalse(LaunchWindow.netIsWindowOpen(15 * hour, null))
    }
}
