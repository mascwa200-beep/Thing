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
}
