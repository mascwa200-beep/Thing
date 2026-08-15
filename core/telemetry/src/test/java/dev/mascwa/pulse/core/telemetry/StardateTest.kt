package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StardateTest {

    @Test fun itAgreesWithTheScaleTheBootSequenceAlreadyShipped() {
        // The shipped flourish computed (year % 100) * 1000 + dayOfYear * 1000 / 365, so 15 August
        // 2026 -- day 227 of a 365-day year -- landed on 26619. Working: 226/365 = 0.61917,
        // x1000 = 619.17, floor -> 26619. Promoting the convention must not move the number the
        // owner already sees on the device.
        val sd = Stardate.of(year = 2026, dayOfYear = 227, daysInYear = 365, hourOfDay = 0)
        assertEquals(26619.0, sd, 1e-9)
    }

    @Test fun theYearIsAThousandUnitsWide() {
        val janFirst = Stardate.of(2026, 1, 365)
        val nextJanFirst = Stardate.of(2027, 1, 365)
        assertEquals(26000.0, janFirst, 1e-9)
        assertEquals(1000.0, nextJanFirst - janFirst, 1e-9)
    }

    @Test fun aLeapYearDoesNotDrift() {
        // The defect this fixes. Dividing by a fixed 365 in a 366-day year overshoots by the last
        // day's worth: on 31 December 2028 the old form gave 366*1000/365 = 1002, i.e. it ran past
        // the end of its own year. The fraction must never reach a full thousand.
        val lastDayOfLeapYear = Stardate.of(2028, 366, 366, hourOfDay = 23)
        assertTrue("leap year overran its thousand: $lastDayOfLeapYear", lastDayOfLeapYear < 29000.0)
        assertEquals(28997.9, lastDayOfLeapYear, 1e-9)
        // And the first day of the next year is exactly the next thousand.
        assertEquals(29000.0, Stardate.of(2029, 1, 365), 1e-9)
    }

    @Test fun itNeverReadsAsAMomentThatHasNotHappened() {
        // Truncated rather than rounded. Midnight on a day must not show the next day's value.
        val midnight = Stardate.of(2026, 227, 365, hourOfDay = 0)
        val lateEvening = Stardate.of(2026, 227, 365, hourOfDay = 23)
        val nextMidnight = Stardate.of(2026, 228, 365, hourOfDay = 0)
        assertTrue(midnight < lateEvening)
        assertTrue(lateEvening < nextMidnight)
        // 23 * 10 / 24 = 9 by integer division, so the last hour of a day reads .9 and never .10.
        assertEquals(0.9, lateEvening - midnight, 1e-9)
    }

    @Test fun theHourContributesExactlyOneDigit() {
        // Every hour of a day maps into 0..9 and the sequence never decreases.
        var previous = -1
        for (h in 0..23) {
            // Rounded, not truncated: 26271.9 is not exactly representable, so `* 10` lands a
            // hair under 262719 and `toLong()` would silently read the previous digit.
            val tenth = (kotlin.math.round(Stardate.of(2026, 100, 365, h) * 10).toLong() % 10).toInt()
            assertTrue("hour $h went backwards", tenth >= previous)
            assertTrue("hour $h out of range: $tenth", tenth in 0..9)
            previous = tenth
        }
        assertEquals(9, previous)
    }

    @Test fun formattingIsLocaleFreeAndOneDecimal() {
        // The recurring trap in this codebase: a number a person reads back must not change shape
        // by region, so no formatter is involved and there is no grouping separator.
        assertEquals("26619.0", Stardate.format(26619.0))
        assertEquals("26619.5", Stardate.format(26619.5))
        assertEquals("STARDATE 26619.5", Stardate.stamp(26619.5))
        val text = Stardate.format(26619.5) + Stardate.format(1234.5)
        assertTrue(text.none { it == ',' || it == ' ' })
    }

    @Test fun nonsenseInDoesNotProduceANumberOut() {
        assertEquals("—", Stardate.format(Double.NaN))
        assertEquals("—", Stardate.format(Double.POSITIVE_INFINITY))
        // A zero-length year would divide by zero; it falls back rather than producing NaN.
        assertTrue(Stardate.of(2026, 100, 0).isFinite())
        // Day zero from a mis-set clock clamps to the start of the year rather than going negative
        // into the previous one.
        assertEquals(26000.0, Stardate.of(2026, 0, 365), 1e-9)
    }

    @Test fun theEpochCountsUpwardPastTheCenturyRatherThanRollingOver() {
        // The one deliberate divergence from the shipped form, which used year % 100 and would have
        // dropped from 99000 back to 0 in 2100. Identical everywhere this century.
        assertEquals(26000.0, Stardate.of(2026, 1, 365), 1e-9)
        assertTrue(Stardate.of(2100, 1, 365) > Stardate.of(2099, 1, 365))
    }
}
