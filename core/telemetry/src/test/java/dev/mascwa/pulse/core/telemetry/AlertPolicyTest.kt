package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four owner-reported behaviours, pinned where they can't quietly come back.
 *
 * Deliberately its own file rather than cases appended to `UnifiedBriefTest`/`EmergencyNewsTest`:
 * these are one policy expressed across three cores, and reading them together is what makes the
 * policy legible. Each test names the report it answers.
 */
class AlertPolicyTest {

    private fun rowOf(b: UnifiedBrief?, kind: BriefRowKind): String? =
        b?.rows?.firstOrNull { it.kind == kind }?.text

    // ---- Report: "it's always on red alert" ----------------------------------------------------

    @Test fun aHeadlineLabelledBreakingIsNoLongerAMajorEvent() {
        // ⚠️ THE defect. "Breaking:", "Just in:" and "Developing:" were in the MAJOR list, and Google
        // News carries them on ordinary headlines all day — so isMajor was true almost always, which
        // drove condition red (recolouring every screen) and fired a full-screen takeover on routine
        // news. A label is how a story is presented, never what happened.
        assertFalse(EmergencyNews.isMajor("Breaking: council approves the budget"))
        assertFalse(EmergencyNews.isMajor("Just in: the transfer window closes"))
        assertFalse(EmergencyNews.isMajor("Developing: talks continue into a second day"))
        // Proceedings are not events either.
        assertFalse(EmergencyNews.isMajor("Senator faces impeachment inquiry"))
        // But a real disaster still clears the bar, label or no label.
        assertTrue(EmergencyNews.isMajor("Breaking: magnitude 7.1 earthquake strikes the coast"))
        assertTrue(EmergencyNews.isMajor("Governor declares a state of emergency"))
    }

    @Test fun newsCanNeverPutTheAppIntoConditionRed() {
        // Condition red recolours all thirty-odd screens, so the bar has to be a government feed and
        // not a keyword. Even the worst headline tops out at yellow.
        val b = UnifiedBriefComposer.compose(
            BriefSignals(
                emergencyHeadline = "Magnitude 7.1 earthquake strikes the coast",
                emergencySevere = true,
                emergencyMajor = true,
            ),
        )
        assertEquals(BriefUrgency.YELLOW, b?.urgency)
    }

    @Test fun aNotableDeathIsNewsRatherThanAnAlertRow() {
        // isMajor still covers it — it earns a card — but emergencySevere does not, so it must not
        // take the board's ALERT row. Conflating the two bars is what made everything an emergency.
        assertTrue(EmergencyNews.isMajor("Celebrated author dies at 91"))
        val b = UnifiedBriefComposer.compose(
            BriefSignals(emergencyHeadline = "Celebrated author dies at 91", emergencyMajor = true),
        )
        assertNull(rowOf(b, BriefRowKind.ALERT))
        assertEquals(BriefUrgency.ROUTINE, b?.urgency)
    }

    // ---- Report: "a real red alert in my area" -------------------------------------------------

    @Test fun aGovernmentAlertIsRedAndOutranksEverythingElseOnTheBoard() {
        // ⚠️ It sits above the user's own reminder, which is otherwise the one line they explicitly
        // asked to be interrupted for. A government emergency is the only thing that outranks a
        // person's stated intent, because it is the only one where being read late costs a life.
        val b = UnifiedBriefComposer.compose(
            BriefSignals(
                officialAlert = "Tornado Warning — Marion County",
                officialAlertKey = "nws-1",
                reminderNow = "Call the dentist",
                emergencyHeadline = "Magnitude 7.1 earthquake strikes the coast",
                emergencySevere = true,
            ),
        )
        assertEquals(BriefUrgency.RED, b?.urgency)
        assertEquals("Tornado Warning — Marion County", rowOf(b, BriefRowKind.ALERT))
        // Keyed on the issuer's own id, so it buzzes once and every later refresh is silent.
        assertEquals("gov:nws-1", b?.urgencyKey)
    }

    // ---- Report: "notifications repeat the same story" -----------------------------------------

    @Test fun aStoryAlreadyShownIsNotShownAgain() {
        val seen = setOf(StoryLedger.identity("Storm hits the coast - CNN"))
        val b = UnifiedBriefComposer.compose(
            BriefSignals(
                topHeadline = "Storm hits the coast",
                topSource = "Reuters",
                moreHeadlines = listOf("Budget passes at last"),
                seenStories = seen,
            ),
        )
        // It skipped past the repeat to something the reader has not had.
        assertEquals("Budget passes at last", rowOf(b, BriefRowKind.NEWS))
        assertEquals("budget passes at last", b?.newsIdentity)
    }

    @Test fun whenEveryStoryHasBeenShownTheRowIsOmittedRatherThanRepeated() {
        // ⚠️ The load-bearing half. Printing the top story "anyway" is exactly the behaviour being
        // removed. No news row, no reported identity, and the board still posts its other rows.
        val seen = setOf(StoryLedger.identity("Storm hits the coast"))
        val b = UnifiedBriefComposer.compose(
            BriefSignals(topHeadline = "Storm hits the coast", topSource = "Reuters", seenStories = seen, tempNow = 12.0),
        )
        assertNull(rowOf(b, BriefRowKind.NEWS))
        assertNull(b?.newsIdentity)
        assertNotNull(rowOf(b, BriefRowKind.WEATHER))
    }

    @Test fun aFreshStoryStillPrintsWithItsSource() {
        val b = UnifiedBriefComposer.compose(BriefSignals(topHeadline = "Budget passes", topSource = "AP"))
        assertEquals("Budget passes — AP", rowOf(b, BriefRowKind.NEWS))
        assertEquals("budget passes", b?.newsIdentity)
    }

    @Test fun onlyTheStoryActuallyPrintedIsRecordedAsSeen() {
        // Recording what was merely considered would burn stories the reader never saw, and they
        // would then never be shown at all.
        val b = UnifiedBriefComposer.compose(
            BriefSignals(topHeadline = "First story", moreHeadlines = listOf("Second story")),
        )
        assertEquals("first story", b?.newsIdentity)
    }
}
