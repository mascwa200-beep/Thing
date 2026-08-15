package dev.mascwa.pulse.core.telemetry

import dev.mascwa.pulse.core.telemetry.AirQualityGuide.Band
import dev.mascwa.pulse.core.telemetry.AirQualityGuide.Pollutant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirQualityGuideTest {

    @Test fun bandsSitWhereTheGuidelineSaysTheyDo() {
        // PM2.5's guideline is 15. The boundaries are inclusive downward, so a reading exactly on
        // one lands in the gentler band.
        assertEquals(Band.WELL_UNDER, AirQualityGuide.assess(Pollutant.PM2_5, 7.5)!!.band)
        assertEquals(Band.WITHIN, AirQualityGuide.assess(Pollutant.PM2_5, 15.0)!!.band)
        assertEquals(Band.ABOVE, AirQualityGuide.assess(Pollutant.PM2_5, 30.0)!!.band)
        assertEquals(Band.FAR_ABOVE, AirQualityGuide.assess(Pollutant.PM2_5, 30.1)!!.band)
    }

    @Test fun aMissingOrNonsensePollutantStaysMissing() {
        assertNull(AirQualityGuide.assess(Pollutant.OZONE, null))
        assertNull(AirQualityGuide.assess(Pollutant.OZONE, Double.NaN))
        assertNull(AirQualityGuide.assess(Pollutant.OZONE, -1.0))
        // Zero is a real reading, not a missing one.
        assertNotNull(AirQualityGuide.assess(Pollutant.OZONE, 0.0))
    }

    @Test fun theDriverIsTheOneFurthestAboveItsOwnGuidelineNotTheBiggestNumber() {
        // The exact case this exists for. Carbon monoxide is numerically enormous and utterly
        // ordinary; the particulates are a tenth its size and are the problem.
        val readings = listOfNotNull(
            AirQualityGuide.assess(Pollutant.CARBON_MONOXIDE, 171.0),
            AirQualityGuide.assess(Pollutant.OZONE, 65.0),
            AirQualityGuide.assess(Pollutant.PM2_5, 19.3),
        )
        assertEquals(Pollutant.PM2_5, AirQualityGuide.dominant(readings)!!.pollutant)
    }

    @Test fun theLondonReadingComesOutAsCleanAir() {
        // Values probed live from the forecast service for London: EAQI 30, and nothing near a
        // guideline. Pinned so a later change to the bands has to justify itself against real air.
        val readings = listOfNotNull(
            AirQualityGuide.assess(Pollutant.PM2_5, 6.0),
            AirQualityGuide.assess(Pollutant.PM10, 7.6),
            AirQualityGuide.assess(Pollutant.OZONE, 76.0),
            AirQualityGuide.assess(Pollutant.NITROGEN_DIOXIDE, 5.6),
            AirQualityGuide.assess(Pollutant.SULPHUR_DIOXIDE, 0.9),
            AirQualityGuide.assess(Pollutant.CARBON_MONOXIDE, 149.0),
        )
        // Ozone at 76 of a 100 guideline is the highest ratio here, and is still within it.
        val worst = AirQualityGuide.dominant(readings)!!
        assertEquals(Pollutant.OZONE, worst.pollutant)
        assertEquals(Band.WITHIN, worst.band)
        assertTrue(AirQualityGuide.summary(readings)!!.contains("Ozone"))
    }

    @Test fun ratiosAreSaidTheWayAPersonWouldSayThem() {
        assertEquals("about 50% of the guideline", AirQualityGuide.describeRatio(0.5))
        assertEquals("right at the guideline", AirQualityGuide.describeRatio(1.0))
        assertEquals("1.8× the guideline", AirQualityGuide.describeRatio(1.8))
        assertEquals("negligible against the guideline", AirQualityGuide.describeRatio(0.001))
        assertEquals("—", AirQualityGuide.describeRatio(Double.NaN))
    }

    @Test fun theRatioTextNeverCarriesALocaleDecimalSeparator() {
        // A core module has no business emitting "1,8" on a phone set to German, because these
        // strings get concatenated, compared and occasionally parsed downstream.
        val text = AirQualityGuide.describeRatio(2.25) + AirQualityGuide.describeRatio(0.333)
        assertTrue(text.none { it == ',' })
    }

    @Test fun summaryEscalatesAndNamesTheDriver() {
        val smoke = listOfNotNull(AirQualityGuide.assess(Pollutant.PM2_5, 90.0))
        val s = AirQualityGuide.summary(smoke)!!
        assertTrue(s.contains("PM2.5"))
        assertTrue(s.contains("windows"))
        assertNull(AirQualityGuide.summary(emptyList()))
    }

    @Test fun pollenIsBandedAndAbsentWhereTheFeedHasNone() {
        // Null outside Europe, which is the real behaviour of the feed rather than a hypothetical.
        assertNull(AirQualityGuide.pollenBand(null))
        assertEquals("None", AirQualityGuide.pollenBand(0.0))
        assertEquals("Low", AirQualityGuide.pollenBand(2.4))
        assertEquals("Moderate", AirQualityGuide.pollenBand(30.0))
        assertEquals("High", AirQualityGuide.pollenBand(120.0))
        assertEquals("Very high", AirQualityGuide.pollenBand(600.0))
    }

    @Test fun everyPollutantStatesThePeriodItsGuidelineIsAveragedOver() {
        // The reason this field exists: ozone's guideline is an 8-hour daily maximum while the rest
        // are 24-hour averages, and describing an hourly reading against the wrong period would be
        // a quiet falsehood nothing else here would catch.
        assertEquals("8-hour daily maximum", Pollutant.OZONE.averaging)
        Pollutant.entries.forEach { p ->
            assertTrue(p.averaging.isNotBlank())
            assertTrue(p.guideline > 0.0)
        }
    }

    @Test fun theTwoIndexScalesAreOnlyRemarkedOnWhenTheyActuallyDisagree() {
        // London: 30 and 29, no comment worth making.
        assertNull(AirQualityGuide.scaleGap(30.0, 29.0))
        // New York, same moment, same feed: 39 and 69. Both real, both probed.
        val gap = AirQualityGuide.scaleGap(39.0, 69.0)!!
        assertTrue(gap.startsWith("The US index reads higher"))
        assertTrue(AirQualityGuide.scaleGap(69.0, 39.0)!!.startsWith("The European index reads higher"))
        assertNull(AirQualityGuide.scaleGap(null, 40.0))
    }
}
