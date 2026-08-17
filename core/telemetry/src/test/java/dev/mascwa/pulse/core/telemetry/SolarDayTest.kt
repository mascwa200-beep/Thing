package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are the feed's real answers, probed live at 78.22 N / 15.65 E (Svalbard) before any of
 * this was written — not values recalled or inferred from the parser.
 *
 * ```
 * 2026-06-21  sunrise/sunset 1970-01-01T00:00:01+00:00  day_length 0   (midnight sun)
 * 2026-08-17  sunrise/sunset 1970-01-01T00:00:01+00:00  day_length 0   (midnight sun)
 * 2026-12-21  sunrise/sunset 1970-01-01T00:00:00+00:00  day_length 0   (polar night)
 * 2026-03-20  sunrise 04:44:24Z  sunset 17:25:19Z       day_length 45655
 * ```
 */
class SolarDayTest {

    // The three responses above, as the repository's ISO parser yields them.
    private val midnightSun = 1_000L      // 1970-01-01T00:00:01Z
    private val polarNight = 0L           // 1970-01-01T00:00:00Z
    // ⚠️ Computed from the probed timestamps, not recalled. The check that proves them: their
    // difference is 45655 s, exactly the day_length the same response carried.
    private val marchRise = 1_773_981_864_000L  // 2026-03-20T04:44:24Z
    private val marchSet = 1_774_027_519_000L   // 2026-03-20T17:25:19Z

    @Test fun theMidnightSunSentinelIsNotMistakenForOneInTheMorning() {
        // The whole defect: this is one second past the old `epochMs <= 0` guard, so it survived
        // and formatted as a real-looking 01:00.
        assertEquals(
            SolarDay.Kind.MIDNIGHT_SUN,
            SolarDay.classify(midnightSun, midnightSun, dayLengthSec = 0L),
        )
        assertEquals("The Sun does not set today", SolarDay.describe(SolarDay.Kind.MIDNIGHT_SUN))
    }

    @Test fun thePolarNightSentinelStillResolves() {
        assertEquals(
            SolarDay.Kind.POLAR_NIGHT,
            SolarDay.classify(polarNight, polarNight, dayLengthSec = 0L),
        )
        assertEquals("The Sun does not rise today", SolarDay.describe(SolarDay.Kind.POLAR_NIGHT))
    }

    @Test fun anOrdinaryDayIsUntouched() {
        // The common case must be exactly as it was, or this fix costs more than it saves.
        assertEquals(SolarDay.Kind.NORMAL, SolarDay.classify(marchRise, marchSet, 45_655L))
        assertNull(SolarDay.describe(SolarDay.Kind.NORMAL))
        // And it must not depend on day_length being present.
        assertEquals(SolarDay.Kind.NORMAL, SolarDay.classify(marchRise, marchSet, null))
    }

    @Test fun anUnrecognisedSentinelSaysNothingRatherThanGuessing() {
        // If the feed ever changes its placeholder, the safe outcome is silence — never a time of
        // day formatted out of 1 January 1970, and never a confident polar claim we cannot support.
        for (odd in listOf(2L, 500L, 999L, 1_001L, 60_000L, -1_000L)) {
            assertEquals(
                "sentinel $odd should not be named",
                SolarDay.Kind.UNKNOWN,
                SolarDay.classify(odd, odd, 0L),
            )
        }
        assertNull(SolarDay.describe(SolarDay.Kind.UNKNOWN))
    }

    @Test fun aStatedDayLengthThatContradictsTheSentinelIsRefused() {
        // A day the Sun never crosses the horizon has no length. If the feed says otherwise we have
        // misread it, and the honest answer is that we do not know.
        assertEquals(SolarDay.Kind.UNKNOWN, SolarDay.classify(midnightSun, midnightSun, 45_655L))
        assertEquals(SolarDay.Kind.UNKNOWN, SolarDay.classify(polarNight, polarNight, 1L))
        // Absent day_length is not evidence either way, so it must not block classification.
        assertEquals(SolarDay.Kind.MIDNIGHT_SUN, SolarDay.classify(midnightSun, midnightSun, null))
        assertEquals(SolarDay.Kind.POLAR_NIGHT, SolarDay.classify(polarNight, polarNight, null))
    }

    @Test fun aHalfSentinelDayHasNoHonestReading() {
        assertEquals(SolarDay.Kind.UNKNOWN, SolarDay.classify(midnightSun, marchSet, 0L))
        assertEquals(SolarDay.Kind.UNKNOWN, SolarDay.classify(marchRise, polarNight, 0L))
    }

    @Test fun absentTimesAreUnknownNotNormal() {
        assertEquals(SolarDay.Kind.UNKNOWN, SolarDay.classify(null, null, null))
        // One real time and one absent is still an ordinary day — the caller prints what it has.
        assertEquals(SolarDay.Kind.NORMAL, SolarDay.classify(marchRise, null, null))
        assertEquals(SolarDay.Kind.NORMAL, SolarDay.classify(null, marchSet, null))
    }

    @Test fun theSentinelWindowIsAboutTheDateNotTheMagicNumber() {
        // A real instant is never on 1 Jan 1970, so the window — not the exact value — is what makes
        // the test safe against the feed changing its placeholder.
        assertTrue(SolarDay.isSentinel(0L))
        assertTrue(SolarDay.isSentinel(1_000L))
        assertTrue(SolarDay.isSentinel(SolarDay.SENTINEL_WINDOW_MS - 1))
        assertFalse(SolarDay.isSentinel(SolarDay.SENTINEL_WINDOW_MS))
        assertFalse(SolarDay.isSentinel(marchRise))
        assertFalse(SolarDay.isSentinel(null))
    }
}
