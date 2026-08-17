package dev.mascwa.pulse.core.telemetry

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeismicTest {

    @Test fun severityBandsCoverTheWholeScaleWithoutGaps() {
        assertEquals(Seismic.Severity.MICRO, Seismic.severity(2.9))
        assertEquals(Seismic.Severity.MINOR, Seismic.severity(3.0))
        assertEquals(Seismic.Severity.LIGHT, Seismic.severity(4.9))
        assertEquals(Seismic.Severity.MODERATE, Seismic.severity(5.0))
        assertEquals(Seismic.Severity.STRONG, Seismic.severity(6.9))
        assertEquals(Seismic.Severity.MAJOR, Seismic.severity(7.0))
        assertEquals(Seismic.Severity.GREAT, Seismic.severity(9.1))
        // Monotonic: a bigger number is never a smaller band.
        var previous = Seismic.severity(0.0).ordinal
        var m = 0.0
        while (m <= 10.0) {
            val here = Seismic.severity(m).ordinal
            assertTrue("severity went backwards at M$m", here >= previous)
            previous = here
            m += 0.1
        }
    }

    @Test fun depthBandsFollowWhatIsFeltAtTheSurface() {
        assertEquals(Seismic.DepthBand.VERY_SHALLOW, Seismic.depthBand(0.0))
        assertEquals(Seismic.DepthBand.VERY_SHALLOW, Seismic.depthBand(9.9))
        assertEquals(Seismic.DepthBand.SHALLOW, Seismic.depthBand(10.0))
        assertEquals(Seismic.DepthBand.SHALLOW, Seismic.depthBand(69.0))
        assertEquals(Seismic.DepthBand.INTERMEDIATE, Seismic.depthBand(70.0))
        assertEquals(Seismic.DepthBand.DEEP, Seismic.depthBand(300.0))
        assertTrue(Seismic.depth(8.0).detail.contains("far more damage"))
    }

    @Test fun theMagnitudeScalesAreDistinguishedRatherThanTreatedAsInterchangeable() {
        // The saturation warning on mb is the whole point of surfacing the scale at all.
        val bodyWave = Seismic.magnitudeType("mb")
        assertTrue(bodyWave.detail.contains("saturates"))
        val moment = Seismic.magnitudeType("mww")
        assertTrue(moment.headline.contains("Moment"))
        assertNotEquals(bodyWave.detail, moment.detail)
        assertTrue(Seismic.magnitudeType("ml").headline.contains("Local"))
        assertTrue(Seismic.magnitudeType("MWW").headline.contains("Moment"), )
        // An unrecognised scale still explains itself instead of rendering blank.
        val unknown = Seismic.magnitudeType("mzz")
        assertTrue(unknown.headline.contains("mzz"))
        assertTrue(unknown.detail.isNotBlank())
    }

    @Test fun pagerAlertIsAboutImpactNotSizeAndIsAbsentWhenUnset() {
        assertNull("no alert must mean no claim", Seismic.pagerAlert(null))
        assertNull(Seismic.pagerAlert(""))
        assertNull(Seismic.pagerAlert("chartreuse"))
        assertTrue(Seismic.pagerAlert("green")!!.headline.contains("no significant impact"))
        assertTrue(Seismic.pagerAlert("RED")!!.detail.contains("International"))
        // The four levels must all read differently.
        val details = listOf("green", "yellow", "orange", "red").map { Seismic.pagerAlert(it)!!.detail }
        assertEquals(details.size, details.toSet().size)
    }

    @Test fun shakingIntensityIsClampedToTheMercalliScale() {
        assertTrue(Seismic.shaking(1.0).headline.contains("not felt"))
        assertTrue(Seismic.shaking(4.5).headline.contains("IV"))
        assertTrue(Seismic.shaking(5.9).headline.contains("V ·"))
        assertTrue(Seismic.shaking(12.0).headline.contains("X-XII"))
        // Out-of-range input must not produce a nonsense band.
        assertTrue(Seismic.shaking(-3.0).headline.contains("not felt"))
        assertTrue(Seismic.shaking(99.0).headline.contains("X-XII"))
    }

    @Test fun feltReportsScaleAndSayWhereTheyComeFrom() {
        assertTrue(Seismic.feltReports(0, null).detail.contains("Nobody"))
        assertTrue(Seismic.feltReports(5, null).detail.contains("handful"))
        assertTrue(Seismic.feltReports(50, null).detail.contains("Dozens"))
        assertTrue(Seismic.feltReports(500, null).detail.contains("Hundreds"))
        assertTrue(Seismic.feltReports(5000, null).detail.contains("Thousands"))
        // These are public reports, not instrument readings, and must say so.
        assertTrue(Seismic.feltReports(12, 4.9).detail.contains("public"))
        assertTrue(Seismic.feltReports(12, 4.9).detail.contains("4.9"))
    }

    @Test fun anAutomaticSolutionIsFlaggedAsRevisable() {
        assertTrue(Seismic.reviewStatus("automatic").detail.contains("revised"))
        assertTrue(Seismic.reviewStatus("reviewed").detail.contains("seismologist"))
        assertTrue(Seismic.reviewStatus(null).headline.contains("unknown"))
    }

    @Test fun theImpactLineCombinesSizeAndDepthRatherThanEither() {
        // The case that matters: same magnitude, opposite conclusions.
        assertTrue(Seismic.impact(6.1, 10.0).contains("causes damage"))
        assertTrue(Seismic.impact(6.1, 500.0).contains("deep"))
        // And the reverse: a modest but very shallow event is still worth noting.
        assertTrue(Seismic.impact(4.2, 3.0).contains("very shallow"))
        assertTrue(Seismic.impact(4.2, 120.0).contains("Unlikely"))
    }

    @Test fun theHeadlineIsLocaleStableAndSurvivesAMissingPlace() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val line = Seismic.headline(6.1, 10.0, "58 km N of Ende, Indonesia")
            assertTrue("a comma decimal must not leak in: '$line'", !line.contains("6,"))
            assertTrue(line.startsWith("M6.1"))
            assertTrue(line.contains("10 km deep"))
            // 6.15 is not exactly 6.15 as a double — it is a shade above, so it rounds up. Pinned
            // deliberately rather than left to chance: the same rounding subtlety turned a genuine
            // X45 solar flare into "X44" earlier in this codebase.
            assertTrue(Seismic.headline(6.15, 10.0, "x").startsWith("M6.2"))
        } finally {
            Locale.setDefault(previous)
        }
        assertTrue(Seismic.headline(3.0, 5.0, "").contains("location unknown"))
    }

    // --- alertLevel: the four rules, each written so that breaking it fails here ---

    @Test fun magnitudeAloneGradesExactlyAsTheAppAlreadyDid() {
        // The bands SafetyRepository shipped before this function existed. An event carrying
        // nothing but a magnitude — which is most of a cached blob — must not move.
        assertEquals(Seismic.Alert.EXTREME, Seismic.alertLevel(6.5))
        assertEquals(Seismic.Alert.HIGH, Seismic.alertLevel(5.5))
        assertEquals(Seismic.Alert.MODERATE, Seismic.alertLevel(4.0))
        assertEquals(Seismic.Alert.LOW, Seismic.alertLevel(3.9))
        assertEquals(Seismic.Alert.LOW, Seismic.alertLevel(null))
    }

    @Test fun pagerOutranksMagnitudeBecauseItKnowsWhoLivesThere() {
        // Rule 1. A modest quake under a city: magnitude says MODERATE, USGS says people are hurt.
        assertEquals(Seismic.Alert.EXTREME, Seismic.alertLevel(4.2, pagerAlert = "red"))
        assertEquals(Seismic.Alert.HIGH, Seismic.alertLevel(4.2, pagerAlert = "orange"))
        // ...and it only ever raises: green does not drag a great earthquake down on its own.
        assertEquals(Seismic.Alert.EXTREME, Seismic.alertLevel(7.5, pagerAlert = "green"))
        assertEquals(Seismic.Alert.HIGH, Seismic.alertLevel(5.6, pagerAlert = "yellow"))
    }

    @Test fun tsunamiPinsTheFloorAndCannotBeTalkedDown() {
        // Rule 2. The floor holds against everything that could lower it — including the depth
        // rule below, which is why the tsunami check returns before depth is considered at all.
        assertEquals(Seismic.Alert.HIGH, Seismic.alertLevel(3.0, tsunami = true))
        assertEquals(Seismic.Alert.HIGH, Seismic.alertLevel(6.0, depthKm = 550.0, tsunami = true))
        assertEquals(
            Seismic.Alert.HIGH,
            Seismic.alertLevel(6.0, depthKm = 550.0, tsunami = true, pagerAlert = "green"),
        )
        // A floor, not a ceiling: a genuinely extreme event stays extreme.
        assertEquals(Seismic.Alert.EXTREME, Seismic.alertLevel(8.0, tsunami = true))
    }

    @Test fun depthDeEscalatesOnlyWhereUsgsIsUnconcerned() {
        // Rule 3, the owner's rule. Deep and unremarkable: step down one grade.
        assertEquals(Seismic.Alert.HIGH, Seismic.alertLevel(6.6, depthKm = 560.0))
        assertEquals(Seismic.Alert.HIGH, Seismic.alertLevel(6.6, depthKm = 560.0, pagerAlert = "green"))
        // Deep but USGS has flagged impact: the step-down is forbidden.
        assertEquals(Seismic.Alert.EXTREME, Seismic.alertLevel(6.6, depthKm = 560.0, pagerAlert = "orange"))
        assertEquals(Seismic.Alert.EXTREME, Seismic.alertLevel(6.6, depthKm = 560.0, pagerAlert = "red"))
        // Only DEEP qualifies. 299 km is INTERMEDIATE and must not step down.
        assertEquals(Seismic.Alert.EXTREME, Seismic.alertLevel(6.6, depthKm = 299.0))
        assertEquals(Seismic.Alert.EXTREME, Seismic.alertLevel(6.6, depthKm = 300.0, pagerAlert = "yellow"))
        // At most one grade, and never below the bottom of the scale.
        assertEquals(Seismic.Alert.LOW, Seismic.alertLevel(2.0, depthKm = 600.0))
    }

    @Test fun anAbsentFieldNeverRaisesTheGrade() {
        // Rule 4. Unknown is not danger — 50 of the 54 events in a real feed carry no PAGER at
        // all, so "absent" must behave as no information rather than as either extreme.
        //
        // ⚠️ Pinned against the absolute band rather than against alertLevel's own bare result.
        // The first version of this test compared bare-against-explicit-null, and a perturbation
        // that made *every* absent PAGER escalate moved both sides together and slipped through.
        // A self-referential assertion cannot catch a rule that shifts the whole function.
        val expected = mapOf(
            2.0 to Seismic.Alert.LOW,
            4.0 to Seismic.Alert.MODERATE,
            5.5 to Seismic.Alert.HIGH,
            6.5 to Seismic.Alert.EXTREME,
        )
        for ((m, want) in expected) {
            assertEquals("M$m with nothing else", want, Seismic.alertLevel(m))
            assertEquals("M$m with an explicit null pager", want, Seismic.alertLevel(m, pagerAlert = null))
            assertEquals("M$m with an explicit null depth", want, Seismic.alertLevel(m, depthKm = null))
            assertEquals("M$m with a pager string we do not know", want, Seismic.alertLevel(m, pagerAlert = "chartreuse"))
            assertEquals("M$m with green, which is not an escalation", want, Seismic.alertLevel(m, pagerAlert = "green"))
        }
    }

    @Test fun compactFactsLeadWithWhatChangesWhatYouDo() {
        val f = Seismic.compactFacts(depthKm = 8.0, tsunami = true, pagerAlert = "orange", magType = "mb")
        assertEquals("TSUNAMI EVALUATION", f.first())          // evacuation beats everything
        assertEquals("PAGER ORANGE", f[1])                      // then USGS's own impact call
        assertTrue(f[2].contains("8 km deep"))
        assertTrue(f[2].contains("very shallow"))
        // Nothing to say is an empty list, not a row of blanks.
        assertTrue(Seismic.compactFacts().isEmpty())
        // Green is USGS saying "no significant impact" — it earns no space on a crowded row.
        assertTrue(Seismic.compactFacts(pagerAlert = "green").isEmpty())
        // The scale only earns a mention when it changes how to read the magnitude.
        assertTrue(Seismic.compactFacts(magType = "mww").isEmpty())
        assertTrue(Seismic.compactFacts(magType = "mb").single().contains("understate"))
    }

    @Test fun tsunamiExplainerIsHonestAboutWhatTheFlagMeans() {
        assertNull(Seismic.tsunami(false))
        val e = Seismic.tsunami(true)!!
        // It is an evaluation marker, not a warning in force. Overstating it is its own failure.
        assertTrue(e.detail.contains("not itself a warning"))
        assertTrue(e.detail.contains("inland"))
    }
}
