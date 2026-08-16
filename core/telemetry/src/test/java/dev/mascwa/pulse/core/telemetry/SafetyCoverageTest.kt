package dev.mascwa.pulse.core.telemetry

import dev.mascwa.pulse.core.telemetry.SafetyCoverage.Availability as A
import dev.mascwa.pulse.core.telemetry.SafetyCoverage.Source as S
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SafetyCoverage] exists to stop an empty list reading as "your area is quiet" when it actually
 * means "we cannot see your area", so the cases that matter are the boundaries of what we claim and
 * the exact wording of what we admit.
 *
 * The coordinates are real places, chosen because the right answer for each is not in dispute.
 */
class SafetyCoverageTest {

    // ---- street-crime geography --------------------------------------------------------------

    @Test
    fun theCrimeFeedCoversEnglandWalesAndNorthernIreland() {
        assertEquals(A.COVERED, SafetyCoverage.crimeCoverage(51.5074, -0.1278))  // London
        assertEquals(A.COVERED, SafetyCoverage.crimeCoverage(53.4808, -2.2426))  // Manchester
        assertEquals(A.COVERED, SafetyCoverage.crimeCoverage(51.4816, -3.1791))  // Cardiff
        assertEquals(A.COVERED, SafetyCoverage.crimeCoverage(54.5973, -5.9301))  // Belfast
        assertEquals(A.COVERED, SafetyCoverage.crimeCoverage(50.0657, -5.7132))  // Land's End
        assertEquals(A.COVERED, SafetyCoverage.crimeCoverage(49.9160, -6.3160))  // St Mary's, Scilly
    }

    /**
     * Police Scotland does not publish to this feed — Edinburgh returns an empty list exactly as
     * Berlin does, which is the whole reason geography has to stand in for the API's own answer.
     */
    @Test
    fun scotlandsCitiesAreCorrectlyOutsideTheClaim() {
        assertEquals(A.NOT_COVERED, SafetyCoverage.crimeCoverage(55.9533, -3.1883))  // Edinburgh
        assertEquals(A.NOT_COVERED, SafetyCoverage.crimeCoverage(55.8642, -4.2518))  // Glasgow
        assertEquals(A.NOT_COVERED, SafetyCoverage.crimeCoverage(56.4620, -2.9707))  // Dundee
        assertEquals(A.NOT_COVERED, SafetyCoverage.crimeCoverage(57.1497, -2.0943))  // Aberdeen
    }

    @Test
    fun theRestOfTheWorldIsOutsideTheClaim() {
        assertEquals(A.NOT_COVERED, SafetyCoverage.crimeCoverage(52.52, 13.40))     // Berlin
        assertEquals(A.NOT_COVERED, SafetyCoverage.crimeCoverage(40.7128, -74.006)) // New York
        // Dublin sits at Welsh latitudes across the Irish Sea, and a single England/Wales box wide
        // enough to reach the Isles of Scilly swallows it. It must not be claimed.
        assertEquals(A.NOT_COVERED, SafetyCoverage.crimeCoverage(53.3498, -6.2603)) // Dublin
        assertEquals(A.NOT_COVERED, SafetyCoverage.crimeCoverage(51.8985, -8.4756)) // Cork
        assertEquals(A.NOT_COVERED, SafetyCoverage.crimeCoverage(-33.8688, 151.209))// Sydney
    }

    /** A missing or broken fix is not a claim in either direction. */
    @Test
    fun anUnusableFixYieldsUnknown() {
        assertEquals(A.UNKNOWN, SafetyCoverage.crimeCoverage(Double.NaN, -0.1278))
        assertEquals(A.UNKNOWN, SafetyCoverage.crimeCoverage(51.5, Double.POSITIVE_INFINITY))
    }

    // ---- what we admit -----------------------------------------------------------------------

    /** Everything that reaches here answered, so the silence is the world's and needs no excuse. */
    @Test
    fun fullCoverageNeedsNoExplanation() {
        assertNull(
            SafetyCoverage.explainSilence(
                mapOf(
                    S.QUAKES to A.COVERED,
                    S.DISASTERS to A.COVERED,
                    S.WEATHER_ALERTS to A.COVERED,
                    S.STREET_CRIME to A.COVERED,
                ),
            ),
        )
    }

    /** The common case abroad: both region-locked sources are simply absent. */
    @Test
    fun sourcesThatDoNotReachHereAreNamed() {
        val out = SafetyCoverage.explainSilence(
            mapOf(
                S.QUAKES to A.COVERED,
                S.DISASTERS to A.COVERED,
                S.WEATHER_ALERTS to A.NOT_COVERED,
                S.STREET_CRIME to A.NOT_COVERED,
            ),
        )
        assertNotNull(out)
        assertEquals("Weather alerts and street crime aren't published for your area.", out)
    }

    /** One absent source takes the singular verb. */
    @Test
    fun oneAbsentSourceReadsAsSingular() {
        val out = SafetyCoverage.explainSilence(
            mapOf(S.QUAKES to A.COVERED, S.STREET_CRIME to A.NOT_COVERED),
        )
        assertEquals("Street crime isn't published for your area.", out)
    }

    /**
     * A failure is named before a gap, because a retry can fix one and cannot fix the other.
     */
    @Test
    fun failuresAreDistinguishedFromGapsAndComeFirst() {
        val out = SafetyCoverage.explainSilence(
            mapOf(
                S.QUAKES to A.FAILED,
                S.DISASTERS to A.COVERED,
                S.WEATHER_ALERTS to A.NOT_COVERED,
                S.STREET_CRIME to A.NOT_COVERED,
            ),
        )
        assertNotNull(out)
        assertTrue("the failure must lead: $out", out!!.startsWith("Earthquakes couldn't be reached"))
        assertTrue("the gaps must still be named: $out", out.contains("aren't published"))
        assertTrue(out.endsWith("."))
    }

    /** UNKNOWN is neither a gap nor a failure and must not be reported as either. */
    @Test
    fun unknownSourcesAreNotAccusedOfAnything() {
        assertNull(
            SafetyCoverage.explainSilence(
                mapOf(S.QUAKES to A.COVERED, S.STREET_CRIME to A.UNKNOWN),
            ),
        )
    }

    // ---- what we checked ---------------------------------------------------------------------

    /** Silence only reassures if you know what was listening. */
    @Test
    fun theSourcesThatDidLookAreListed() {
        val out = SafetyCoverage.describeChecked(
            mapOf(
                S.QUAKES to A.COVERED,
                S.DISASTERS to A.COVERED,
                S.WEATHER_ALERTS to A.NOT_COVERED,
                S.STREET_CRIME to A.NOT_COVERED,
            ),
        )
        assertEquals("Checked: earthquakes and major disasters.", out)
    }

    @Test
    fun threeCoveredSourcesReadAsAList() {
        val out = SafetyCoverage.describeChecked(
            mapOf(
                S.QUAKES to A.COVERED,
                S.DISASTERS to A.COVERED,
                S.WEATHER_ALERTS to A.COVERED,
            ),
        )
        assertEquals("Checked: earthquakes, major disasters and weather alerts.", out)
    }

    @Test
    fun nothingCoveredMeansNothingToReport() {
        assertNull(SafetyCoverage.describeChecked(mapOf(S.QUAKES to A.FAILED)))
        assertNull(SafetyCoverage.describeChecked(emptyMap()))
    }
}
