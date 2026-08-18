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

    // ---- at(): an instant, read where the reader is standing -----------------------------------

    @Test fun anInstantDecomposesToTheSameNumberAsTheDatePartsWould() {
        // 1970-01-01T00:00:00Z is day 1 of a common year, hour 0 — so the epoch itself must give
        // exactly what of(1970, 1, 365, 0) gives. Working: (1970-2000)*1000 = -30000, day fraction
        // 0, hour 0. The two calls agree by construction or the civil conversion is wrong.
        assertEquals(Stardate.of(1970, 1, 365, 0), Stardate.at(0L, 0), 1e-9)
        // 2026-08-15T00:00:00Z: 20680 days after the epoch, day 227 of a 365-day year — the same
        // date the boot-scale test above pins at 26619. (Every epoch-day constant in this file was
        // computed from a calendar reference before the assertion was written; two of the four I
        // first wrote from arithmetic in my head were wrong, which is the habit this comment exists
        // to break.)
        val aug15 = 20_680L * 86_400_000L
        assertEquals(26619.0, Stardate.at(aug15, 0), 1e-9)
        assertEquals(Stardate.of(2026, 227, 365, 0), Stardate.at(aug15, 0), 1e-9)
    }

    @Test fun theOffsetActuallyMovesTheDay() {
        // ⚠️ The guard for the trap this parameter exists for. At 12:00 UTC on 15 August 2026 it is
        // already the 16th in Auckland (+13) and still the 15th in Midway (-11). A stardate derived
        // from the UTC day would hand both the same number, which is the bug the observatory and the
        // day-ahead core each shipped once.
        val noonUtc = 20_680L * 86_400_000L + 12 * 3_600_000L
        val auckland = Stardate.at(noonUtc, 13 * 3600)
        val midway = Stardate.at(noonUtc, -11 * 3600)
        assertTrue("the offset was ignored: $auckland vs $midway", auckland > midway)
        // Whole days apart, so the difference is at least a day's worth of the thousand.
        assertEquals(Stardate.of(2026, 228, 365, 1), auckland, 1e-9)
        assertEquals(Stardate.of(2026, 227, 365, 1), midway, 1e-9)
    }

    @Test fun itHandlesTheNegativeSideOfTheEpochWithoutSlippingADay() {
        // floorDiv rather than `/`. One millisecond before the epoch is 1969-12-31T23:59:59.999Z,
        // the 365th day of a common year — truncating division would round toward zero and call it
        // 1 January 1970.
        assertEquals(Stardate.of(1969, 365, 365, 23), Stardate.at(-1L, 0), 1e-9)
        // And a western offset applied to the epoch instant lands in the previous year the same way.
        assertEquals(Stardate.of(1969, 365, 365, 19), Stardate.at(0L, -5 * 3600), 1e-9)
    }

    @Test fun leapDaysLandWhereTheCalendarPutsThem() {
        // 2024-02-29T00:00:00Z is 19782 days after the epoch. A conversion that ignored the leap day
        // would report 1 March; one that applied it in a common year would be a day early all year.
        val leapDay = 19_782L * 86_400_000L
        assertEquals(Stardate.of(2024, 60, 366, 0), Stardate.at(leapDay, 0), 1e-9)
        // 2023 is not a leap year, so its 60th day is 1 March rather than 29 February.
        val mar1NonLeap = 19_417L * 86_400_000L
        assertEquals(Stardate.of(2023, 60, 365, 0), Stardate.at(mar1NonLeap, 0), 1e-9)
        // ⚠️ And a date AFTER February in a leap year, which is the case 29 February itself cannot
        // test. The extra day is added only for months past February, so a conversion that forgot it
        // still gets the leap day right and is then a day early for the remaining ten months. The
        // negative test found this gap: perturbing the shift away failed nothing until these landed.
        assertEquals(Stardate.of(2024, 61, 366, 0), Stardate.at(19_783L * 86_400_000L, 0), 1e-9)
        assertEquals(Stardate.of(2024, 366, 366, 0), Stardate.at(20_088L * 86_400_000L, 0), 1e-9)
        assertEquals(Stardate.of(2023, 365, 365, 0), Stardate.at(19_722L * 86_400_000L, 0), 1e-9)
        // 1900 was NOT a leap year — the century rule the naive every-fourth-year form gets wrong.
        // 1900-03-01 is epoch day -25508, and its day-of-year is 60 only because February was short.
        assertEquals(Stardate.of(1900, 60, 365, 0), Stardate.at(-25_508L * 86_400_000L, 0), 1e-9)
    }
}
