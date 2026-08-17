package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapAlertsTest {

    @Test fun severityAloneGradesExactlyAsTheAppAlreadyDid() {
        // The bands SafetyRepository shipped before urgency and certainty were read at all. An
        // alert carrying only a severity — or a cached one — must not move.
        assertEquals(CapAlerts.Grade.EXTREME, CapAlerts.grade("Extreme"))
        assertEquals(CapAlerts.Grade.HIGH, CapAlerts.grade("Severe"))
        assertEquals(CapAlerts.Grade.MODERATE, CapAlerts.grade("Moderate"))
        assertEquals(CapAlerts.Grade.LOW, CapAlerts.grade("Minor"))
        assertEquals(CapAlerts.Grade.LOW, CapAlerts.grade(null))
        assertEquals(CapAlerts.Grade.LOW, CapAlerts.grade("something the feed invented"))
    }

    @Test fun happeningNowOutranksTheSameThingForecast() {
        // The distinction the app could not previously draw: identical severity, opposite meaning.
        val watch = CapAlerts.grade("Severe", urgency = "Future", certainty = "Possible")
        val warning = CapAlerts.grade("Severe", urgency = "Immediate", certainty = "Observed")
        assertTrue("a warning must outrank a watch of the same severity", warning > watch)
        assertEquals(CapAlerts.Grade.EXTREME, warning)
        assertEquals(CapAlerts.Grade.MODERATE, watch)
    }

    @Test fun bothHalvesAreRequiredBeforeAnythingMoves() {
        // Immediate but only possible — a tornado that may be about to happen — must not be
        // de-escalated just because certainty is low.
        assertEquals(CapAlerts.Grade.HIGH, CapAlerts.grade("Severe", "Immediate", "Possible"))
        // Future but observed — a river crest already measured upstream — must not be either.
        assertEquals(CapAlerts.Grade.HIGH, CapAlerts.grade("Severe", "Future", "Observed"))
        // And escalation needs both halves too.
        assertEquals(CapAlerts.Grade.HIGH, CapAlerts.grade("Severe", "Immediate", "Likely"))
        assertEquals(CapAlerts.Grade.HIGH, CapAlerts.grade("Severe", "Expected", "Observed"))
    }

    @Test fun unknownMovesNothingAndTheScaleHasEnds() {
        for (s in listOf("Extreme", "Severe", "Moderate", "Minor")) {
            val bare = CapAlerts.grade(s)
            assertEquals("null urgency moved $s", bare, CapAlerts.grade(s, null, null))
            assertEquals("unknown strings moved $s", bare, CapAlerts.grade(s, "wat", "eh"))
        }
        // Escalation cannot exceed the top, de-escalation cannot fall off the bottom.
        assertEquals(CapAlerts.Grade.EXTREME, CapAlerts.grade("Extreme", "Immediate", "Observed"))
        assertEquals(CapAlerts.Grade.LOW, CapAlerts.grade("Minor", "Future", "Unlikely"))
    }

    @Test fun timingSaysSomethingOrSaysNothing() {
        assertEquals("HAPPENING NOW", CapAlerts.timing("Immediate", "Observed"))
        assertEquals("IMMEDIATE", CapAlerts.timing("Immediate", "Possible"))
        assertEquals("LIKELY SOON", CapAlerts.timing("Expected", "Likely"))
        assertEquals("FORECAST", CapAlerts.timing("Future", "Possible"))
        assertEquals("NO LONGER EXPECTED", CapAlerts.timing("Past", "Observed"))
        // Nothing useful in, nothing shown — never the word "unknown" on a row.
        assertNull(CapAlerts.timing(null, null))
        assertNull(CapAlerts.timing("Unknown", "Unknown"))
    }

    @Test fun anAlertIsNotDismissedForAMissingExpiry() {
        val now = 1_700_000_000_000L
        assertTrue(CapAlerts.hasExpired(now - 1, now))
        assertFalse(CapAlerts.hasExpired(now + 1, now))
        // Absent, zero and unparseable must all keep the alert. Hiding a live warning because a
        // field failed to parse is far worse than showing one a few minutes past its expiry.
        assertFalse(CapAlerts.hasExpired(null, now))
        assertFalse(CapAlerts.hasExpired(0L, now))
    }

    @Test fun instructionIsFlattenedForAPhone() {
        // CAP text arrives hard-wrapped at teletype width, so the newlines land mid-sentence.
        val raw = "Move to an interior room\non the lowest floor of a\nsturdy building."
        assertEquals("Move to an interior room on the lowest floor of a sturdy building.",
            CapAlerts.instruction(raw))
        assertNull(CapAlerts.instruction(null))
        assertNull(CapAlerts.instruction("   \n  "))
        // Long text stops at a sentence end rather than mid-clause.
        val long = "First sentence here. " + "Second sentence padding ".repeat(40)
        val cut = CapAlerts.instruction(long, limit = 60)!!
        assertTrue("should not exceed the limit", cut.length <= 61)
        assertTrue("should end cleanly", cut.endsWith(".") || cut.endsWith("…"))
    }
}
