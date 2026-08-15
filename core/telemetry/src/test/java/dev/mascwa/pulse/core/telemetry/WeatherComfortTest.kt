package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The expected values here come from an independent implementation of the same published
 * formulas, run separately and compared — so a transcription slip in a coefficient shows up as a
 * disagreement rather than as two copies of the same mistake.
 *
 * The heat-index cases are additionally anchored to the NWS's own published table, which that
 * regression is the definition of: 90°F at 70% is tabulated as 105°F and 110°F at 40% as 136°F,
 * and both come out within a degree.
 */
class WeatherComfortTest {

    @Test fun theHeatIndexMatchesTheRegressionItIsDefinedBy() {
        assertEquals(41.0014, WeatherComfort.heatIndexC(32.2, 70.0)!!, 0.001)
        assertEquals(57.4862, WeatherComfort.heatIndexC(43.3, 40.0)!!, 0.001)
        // Rounded back to Fahrenheit these are the published table entries.
        assertEquals(105.0, WeatherComfort.heatIndexC(32.2, 70.0)!! * 9 / 5 + 32, 1.0)
        assertEquals(136.0, WeatherComfort.heatIndexC(43.3, 40.0)!! * 9 / 5 + 32, 1.0)
    }

    @Test fun bothOfTheRegressionsCorrectionsAreApplied() {
        // Very dry air below 13% is subtracted from the index...
        assertEquals(31.9164, WeatherComfort.heatIndexC(35.0, 10.0)!!, 0.001)
        // ...and very humid air at the low end is added to it. Dropping either correction changes
        // these by more than a degree, so this is what pins them.
        assertEquals(38.6107, WeatherComfort.heatIndexC(29.4, 90.0)!!, 0.001)
    }

    @Test fun theHeatIndexRefusesToAnswerWhenItIsNotHot() {
        // The regression is not fitted below 80°F and the NWS does not publish it there. A number
        // would still come out; it would just be wrong.
        assertNull(WeatherComfort.heatIndexC(20.0, 50.0))
        assertNull(WeatherComfort.heatIndexC(0.0, 90.0))
        assertNull(WeatherComfort.heatIndexC(-10.0, 30.0))
        // Nonsense in, nothing out.
        assertNull(WeatherComfort.heatIndexC(Double.NaN, 50.0))
        assertNull(WeatherComfort.heatIndexC(30.0, Double.NaN))
    }

    @Test fun heatRiskFollowsTheNwsBands() {
        assertEquals(WeatherComfort.HeatRisk.NONE, WeatherComfort.heatRisk(null))
        assertEquals(WeatherComfort.HeatRisk.CAUTION, WeatherComfort.heatRisk(28.0))
        assertEquals(WeatherComfort.HeatRisk.EXTREME_CAUTION, WeatherComfort.heatRisk(35.0))
        assertEquals(WeatherComfort.HeatRisk.DANGER, WeatherComfort.heatRisk(45.0))
        assertEquals(WeatherComfort.HeatRisk.EXTREME_DANGER, WeatherComfort.heatRisk(60.0))
    }

    @Test fun windChillMatchesTheJagTiStandard() {
        assertEquals(-19.5205, WeatherComfort.windChillC(-10.0, 30.0)!!, 0.001)
        assertEquals(-50.3177, WeatherComfort.windChillC(-30.0, 60.0)!!, 0.001)
        // At the warm edge of its range it barely moves the temperature, which is the formula
        // behaving correctly rather than a bug.
        assertEquals(9.7551, WeatherComfort.windChillC(10.0, 5.0)!!, 0.001)
    }

    @Test fun windChillRefusesWarmOrStillAir() {
        // Asked about a warm afternoon the equation reports a chill that does not exist, so it is
        // not asked. This gate is the whole reason these return null rather than a number.
        assertNull(WeatherComfort.windChillC(25.0, 40.0))
        assertNull(WeatherComfort.windChillC(10.1, 30.0))
        // Still air has no chill to give.
        assertNull(WeatherComfort.windChillC(-20.0, 0.0))
        assertNull(WeatherComfort.windChillC(-20.0, 4.7))
        assertNotNull(WeatherComfort.windChillC(-20.0, 4.8))
    }

    @Test fun frostbiteTimesOnlyAppearWhenThereIsRisk() {
        assertNull(WeatherComfort.frostbiteMinutes(null))
        assertNull(WeatherComfort.frostbiteMinutes(-10.0))
        assertEquals(30, WeatherComfort.frostbiteMinutes(-30.0))
        assertEquals(10, WeatherComfort.frostbiteMinutes(-42.0))
        assertEquals(2, WeatherComfort.frostbiteMinutes(-60.0))
    }

    @Test fun dewPointMatchesMagnusTetens() {
        assertEquals(18.447, WeatherComfort.dewPointC(30.0, 50.0)!!, 0.001)
        assertEquals(16.4444, WeatherComfort.dewPointC(20.0, 80.0)!!, 0.001)
        // Saturated air has its dew point at the temperature.
        assertEquals(25.0, WeatherComfort.dewPointC(25.0, 100.0)!!, 0.01)
        // Zero humidity is unphysical; clamping keeps it from taking the log of zero.
        assertNotNull(WeatherComfort.dewPointC(20.0, 0.0))
    }

    @Test fun mugginessReadsDewPointNotRelativeHumidity() {
        // Eighty percent relative humidity means something entirely different at five degrees than
        // at thirty, which is the point of grading on dew point instead.
        assertEquals("Very dry", WeatherComfort.mugginess(WeatherComfort.dewPointC(5.0, 80.0)))
        assertEquals("Very humid", WeatherComfort.mugginess(WeatherComfort.dewPointC(25.0, 80.0)))
        assertNull(WeatherComfort.mugginess(null))
    }

    @Test fun fogAndFrostNeedTheirOwnConditions() {
        assertTrue(WeatherComfort.fogLikely(12.0, 11.0))
        assertFalse(WeatherComfort.fogLikely(12.0, 2.0))
        // Frost wants cold, dry-ish and calm together; wind alone rules it out.
        assertTrue(WeatherComfort.frostPossible(2.0, 0.0, 5.0))
        assertFalse(WeatherComfort.frostPossible(2.0, 0.0, 20.0))
        assertFalse(WeatherComfort.frostPossible(10.0, 0.0, 5.0))
    }

    @Test fun beaufortCoversItsWholeRange() {
        assertEquals(0, WeatherComfort.beaufort(0.5).first)
        assertEquals(4, WeatherComfort.beaufort(25.0).first)
        assertEquals(8, WeatherComfort.beaufort(70.0).first)
        assertEquals(12, WeatherComfort.beaufort(200.0).first)
        // Forces never go backwards as the wind rises.
        var previous = -1
        var kmh = 0.0
        while (kmh <= 200.0) {
            val force = WeatherComfort.beaufort(kmh).first
            assertTrue("force fell at $kmh", force >= previous)
            previous = force
            kmh += 1.0
        }
    }

    @Test fun gustsAreOnlyMentionedWhenTheyAreTheStory() {
        // Ordinary turbulence is not worth a line.
        assertNull(WeatherComfort.gustNote(20.0, 24.0))
        assertNull(WeatherComfort.gustNote(50.0, 55.0))
        assertNull(WeatherComfort.gustNote(null, 80.0))
        // A gust well above the mean is the thing that takes a branch down.
        assertNotNull(WeatherComfort.gustNote(20.0, 45.0))
        assertTrue(WeatherComfort.gustNote(30.0, 95.0)!!.contains("damaging"))
    }

    @Test fun burnTimeAndUvBandsAgree() {
        assertNull(WeatherComfort.burnMinutes(null))
        assertNull(WeatherComfort.burnMinutes(2.0))
        assertEquals("Low", WeatherComfort.uvLabel(2.0))
        assertEquals(25, WeatherComfort.burnMinutes(8.0))
        assertEquals("Very high", WeatherComfort.uvLabel(8.0))
        // Never advises less than five minutes even at absurd indices.
        assertTrue(WeatherComfort.burnMinutes(80.0)!! >= 5)
    }

    @Test fun capeIsDescribedAsFuelNotAsForecast() {
        assertNull(WeatherComfort.thunderPotential(null))
        assertNull(WeatherComfort.thunderPotential(100.0))
        assertTrue(WeatherComfort.thunderPotential(2500.0)!!.contains("if triggered"))
        assertTrue(WeatherComfort.thunderPotential(3500.0)!!.contains("severe"))
    }

    @Test fun humidexMatchesTheCanadianDefinition() {
        assertEquals(36.3412, WeatherComfort.humidex(30.0, 18.4493)!!, 0.001)
        // The humidex equals the plain temperature exactly where the vapour pressure is 10 hPa,
        // which the definition subtracts. That crossing is at a dew point of about 6.97°C —
        // solved for, not guessed at; an earlier guess of -12.2 was out by twenty degrees and the
        // code was right.
        assertEquals(10.0, WeatherComfort.vapourPressureHpa(6.9678), 0.001)
        assertEquals(20.0, WeatherComfort.humidex(20.0, 6.9678)!!, 0.01)
    }

    @Test fun theHeadlinePicksTheOneThingThatMatters() {
        // Heat outranks everything.
        val hot = WeatherComfort.headline(35.0, 70.0, 5.0)
        assertNotNull(hot)
        assertTrue(hot!!.contains("Feels like"))
        // Cold with wind reports the chill, and the frostbite time when there is one.
        val cold = WeatherComfort.headline(-25.0, 40.0, 40.0)!!
        assertTrue(cold.contains("in the wind"))
        assertTrue(cold.contains("freezes in about"))
        // A pleasant day has nothing worth saying that the temperature does not already say.
        assertNull(WeatherComfort.headline(18.0, 50.0, 8.0))
        // Fahrenheit readers get Fahrenheit.
        assertTrue(WeatherComfort.headline(35.0, 70.0, 5.0, unitSymbol = "°F")!!.contains("°F"))
    }

    @Test fun theHeatIndexIsClampedToTheTopOfThePublishedChart() {
        // The Rothfusz regression is a curve fit and keeps climbing outside the table it was fitted
        // to: 41 C at 70% humidity comes out of the raw polynomial as an apparent 77 C. That input
        // is barely physical, but a bad parse or a stuck sensor can produce it, and printing "feels
        // like 77" would be worse than saying nothing. 137 F is the top of the NWS chart.
        val saturated = WeatherComfort.heatIndexC(41.0, 70.0)!!
        assertEquals((WeatherComfort.HEAT_INDEX_MAX_F - 32.0) * 5.0 / 9.0, saturated, 1e-9)
        assertEquals(WeatherComfort.HeatRisk.EXTREME_DANGER, WeatherComfort.heatRisk(saturated))
        // Inside the chart nothing moves: this is the value the earlier tests already pin.
        assertEquals(40.6754, WeatherComfort.heatIndexC(35.0, 50.0)!!, 0.001)
    }

    @Test fun theCompactFormFitsARowAndStaysSilentOnAnOrdinaryDay() {
        // A dense surface joins its parts with separators and has one line, so the advice sentence
        // that headline() carries has to go -- what survives is the number and the word for it.
        assertEquals(
            "Feels 42°C — danger",
            WeatherComfort.compactFeelsLike(32.0, 75.0, 5.0, "°C"),
        )
        // -5 C in 40 km/h is -14.08 by the JAG/TI regression, computed rather than recalled:
        // 13.12 + 0.6215(-5) - 11.37(40^0.16) + 0.3965(-5)(40^0.16), with 40^0.16 = 1.8044.
        assertEquals(
            "Feels -14°C in wind",
            WeatherComfort.compactFeelsLike(-5.0, 60.0, 40.0, "°C"),
        )
        // Mild, still, ordinary: nothing to add, so nothing is added.
        assertNull(WeatherComfort.compactFeelsLike(18.0, 50.0, 8.0, "°C"))
        // A degree or two of wind chill is not worth a slot in a one-line row.
        assertNull(WeatherComfort.compactFeelsLike(8.0, 60.0, 6.0, "°C"))
        assertNull(WeatherComfort.compactFeelsLike(null, 60.0, 40.0, "°C"))
    }

    @Test fun theCompactFormFollowsTheUnitTheReaderUses() {
        val f = WeatherComfort.compactFeelsLike(32.0, 75.0, 5.0, "°F")!!
        // 42.3 C is about 108 F. The index is computed in Celsius either way; only the display moves.
        assertTrue(f.contains("108°F"))
    }
}
