package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyAlertTest {

    private val now = 1_700_000_000_000L

    private fun alert(
        id: String = "a1",
        event: String = "Flood Advisory",
        severity: String? = null,
        urgency: String? = null,
        certainty: String? = null,
        expires: Long? = null,
        effective: Long = now,
    ) = EmergencyAlert.Official(
        id = id, event = event, severity = severity, urgency = urgency,
        certainty = certainty, expiresMs = expires, effectiveMs = effective, source = "NWS",
    )

    @Test fun theGradeComesFromCapAlertsRatherThanASecondOpinion() {
        // Extreme is red; severe is yellow on its own. The mapping delegates entirely to the
        // existing tested grader, so there is exactly one definition of how bad an alert is.
        assertEquals(EmergencyAlert.Tier.RED, EmergencyAlert.tierFor(alert(severity = "Extreme"), now))
        assertEquals(EmergencyAlert.Tier.YELLOW, EmergencyAlert.tierFor(alert(severity = "Severe"), now))
        assertEquals(EmergencyAlert.Tier.ADVISORY, EmergencyAlert.tierFor(alert(severity = "Moderate"), now))
        assertEquals(EmergencyAlert.Tier.NONE, EmergencyAlert.tierFor(alert(severity = "Minor"), now))
    }

    @Test fun somethingHappeningNowIsLiftedAndADistantMaybeIsDropped() {
        // CapAlerts.grade lifts immediate+observed one grade and drops future+possible one grade;
        // both must show through here, or the tiers would disagree with the grading they claim to use.
        // Severe -> HIGH, lifted to EXTREME -> RED.
        assertEquals(
            EmergencyAlert.Tier.RED,
            EmergencyAlert.tierFor(alert(severity = "Severe", urgency = "Immediate", certainty = "Observed"), now),
        )
        // Moderate -> MODERATE, dropped to LOW -> NONE.
        assertEquals(
            EmergencyAlert.Tier.NONE,
            EmergencyAlert.tierFor(alert(severity = "Moderate", urgency = "Future", certainty = "Possible"), now),
        )
    }

    @Test fun theNamedHazardsAreRedEvenWhenTheFeedSaysNothing() {
        // ⚠️ The floor. A tornado warning with a missing or wrong severity field must not degrade to
        // "advisory" — the two errors here do not cost the same, so the event name overrides.
        assertEquals(EmergencyAlert.Tier.RED, EmergencyAlert.tierFor(alert(event = "Tornado Warning"), now))
        assertEquals(EmergencyAlert.Tier.RED, EmergencyAlert.tierFor(alert(event = "PDS Tornado Warning"), now))
        assertEquals(EmergencyAlert.Tier.RED, EmergencyAlert.tierFor(alert(event = "Tsunami Warning"), now))
        // And an ordinary watch with the same shape of name is not swept up by it.
        assertEquals(EmergencyAlert.Tier.NONE, EmergencyAlert.tierFor(alert(event = "Tornado Watch"), now))
    }

    @Test fun anExpiredAlertIsNotADanger() {
        // The feed is cached and served offline, so without this an alert that ended two hours ago
        // still sounds an alarm.
        val past = alert(event = "Tornado Warning", expires = now - 60_000L)
        assertEquals(EmergencyAlert.Tier.NONE, EmergencyAlert.tierFor(past, now))
        assertFalse(EmergencyAlert.warrantsTakeover(past, now))
        // Still live one minute before its expiry.
        assertTrue(EmergencyAlert.warrantsTakeover(alert(event = "Tornado Warning", expires = now + 60_000L), now))
    }

    @Test fun onlyRedEarnsTheAlarm() {
        // ⚠️ An alarm for a coastal flood advisory teaches its owner to ignore the one for a tornado.
        assertTrue(EmergencyAlert.warrantsTakeover(alert(severity = "Extreme"), now))
        assertFalse(EmergencyAlert.warrantsTakeover(alert(severity = "Severe"), now))
        assertFalse(EmergencyAlert.warrantsTakeover(alert(severity = "Moderate"), now))
    }

    @Test fun pickTakesTheNewestQualifyingAlertAndSkipsOnesAlreadyRaised() {
        val old = alert(id = "old", event = "Tornado Warning", effective = now - 3_600_000L)
        val new = alert(id = "new", event = "Tornado Warning", effective = now)
        val quiet = alert(id = "quiet", severity = "Moderate")
        assertEquals("new", EmergencyAlert.pick(listOf(old, new, quiet), emptySet(), now)?.id)
        // Already raised: it does not re-sound. An alert stays active for its whole life, and
        // re-alarming every poll for the same tornado is how the phone ends up in a drawer.
        assertEquals("old", EmergencyAlert.pick(listOf(old, new, quiet), setOf("new"), now)?.id)
        assertNull(EmergencyAlert.pick(listOf(old, new, quiet), setOf("new", "old"), now))
    }

    @Test fun highestReportsTheWorstLiveCondition() {
        assertEquals(
            EmergencyAlert.Tier.RED,
            EmergencyAlert.highest(listOf(alert(severity = "Moderate"), alert(severity = "Extreme")), now),
        )
        assertEquals(EmergencyAlert.Tier.NONE, EmergencyAlert.highest(emptyList(), now))
    }

    @Test fun theSummaryLeadsWithTheHazardAndDoesNotRepeatItself() {
        assertEquals(
            "Tornado Warning — Marion County",
            EmergencyAlert.summary(EmergencyAlert.Official(id = "x", event = "Tornado Warning", area = "Marion County")),
        )
        // The issuer's headline usually restates the area; it is not appended twice.
        assertEquals(
            "Tornado Warning for Marion County",
            EmergencyAlert.summary(
                EmergencyAlert.Official(id = "x", event = "Tornado Warning for Marion County", area = "Marion County"),
            ),
        )
    }

    @Test fun remainingSaysNothingRatherThanSayingUnknown() {
        // An expiry the issuer never published is not information; a row printing "unknown" has
        // spent a line telling the reader nothing.
        assertNull(EmergencyAlert.remaining(alert(), now))
        assertNull(EmergencyAlert.remaining(alert(expires = now - 1L), now))
        assertEquals("45 min left", EmergencyAlert.remaining(alert(expires = now + 45 * 60_000L), now))
        assertEquals("2h left", EmergencyAlert.remaining(alert(expires = now + 120 * 60_000L), now))
        assertEquals("1h 30m left", EmergencyAlert.remaining(alert(expires = now + 90 * 60_000L), now))
    }
}
