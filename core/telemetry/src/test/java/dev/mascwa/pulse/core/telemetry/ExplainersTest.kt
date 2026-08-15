package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplainersTest {

    @Test
    fun kpBandsMapToStormLevels() {
        assertTrue(SpaceWeatherExplainers.kp(1.0).headline.contains("Quiet"))
        assertTrue(SpaceWeatherExplainers.kp(4.0).headline.contains("Unsettled"))
        assertTrue(SpaceWeatherExplainers.kp(5.0).headline.contains("G1"))
        assertTrue(SpaceWeatherExplainers.kp(7.0).headline.contains("G3"))
        assertTrue(SpaceWeatherExplainers.kp(9.0).headline.contains("G5"))
    }

    @Test
    fun kpHeadlineShowsTheValue() {
        assertTrue(SpaceWeatherExplainers.kp(5.0).headline.contains("Kp 5"))
        assertTrue(SpaceWeatherExplainers.kp(3.5).headline.contains("Kp 3.5"))
    }

    @Test
    fun kpClampsOutOfRange() {
        // Should not throw and should land in the extreme/quiet bands.
        assertTrue(SpaceWeatherExplainers.kp(99.0).headline.contains("G5"))
        assertTrue(SpaceWeatherExplainers.kp(-3.0).headline.contains("Quiet"))
    }

    @Test
    fun solarWindBands() {
        assertTrue(SpaceWeatherExplainers.solarWind(300.0).headline.contains("Calm"))
        assertTrue(SpaceWeatherExplainers.solarWind(450.0).headline.contains("Elevated"))
        assertTrue(SpaceWeatherExplainers.solarWind(600.0).headline.contains("Fast"))
        assertTrue(SpaceWeatherExplainers.solarWind(800.0).headline.contains("Very fast"))
    }

    @Test
    fun bzNorthwardIsQuietSouthwardIsStormy() {
        assertTrue(SpaceWeatherExplainers.bz(5.0).headline.contains("Northward"))
        assertTrue(SpaceWeatherExplainers.bz(-12.0).headline.contains("Strongly southward"))
        assertNotEquals(
            SpaceWeatherExplainers.bz(5.0).detail,
            SpaceWeatherExplainers.bz(-12.0).detail,
        )
    }

    @Test
    fun auroraChanceBands() {
        assertTrue(SpaceWeatherExplainers.aurora(5).headline.contains("Low"))
        assertTrue(SpaceWeatherExplainers.aurora(30).headline.contains("Possible"))
        assertTrue(SpaceWeatherExplainers.aurora(80).headline.contains("Likely"))
    }

    @Test
    fun stormScaleKnownAndUnknown() {
        assertTrue(SpaceWeatherExplainers.stormScale("G3 Strong").headline.contains("G3"))
        assertTrue(SpaceWeatherExplainers.stormScale("None").headline.contains("No storm"))
    }

    @Test
    fun changePercentDirectionAndMagnitude() {
        assertTrue(MarketExplainers.changePercent(0.03).headline.contains("flat"))
        assertTrue(MarketExplainers.changePercent(2.0).headline.contains("notable"))
        assertTrue(MarketExplainers.changePercent(5.0).headline.contains("big"))
        assertTrue(MarketExplainers.changePercent(1.5).headline.startsWith("▲"))
        assertTrue(MarketExplainers.changePercent(-1.5).headline.startsWith("▼"))
    }

    @Test
    fun instrumentKnownIdGivesSpecificBlurb() {
        val vix = MarketExplainers.instrument("^vix", "VIX", "INDEX")
        assertEquals("VIX", vix.headline)
        assertTrue(vix.detail.contains("fear gauge"))
        val spx = MarketExplainers.instrument("^spx", "S&P 500", "INDEX")
        assertTrue(spx.detail.contains("500"))
    }

    @Test
    fun instrumentUnknownIdFallsBackToAssetClass() {
        val stock = MarketExplainers.instrument("aapl.us", "Apple", "STOCK")
        assertTrue(stock.detail.contains("Apple"))
        val unknownIndex = MarketExplainers.instrument("^unknown", "Mystery", "INDEX")
        assertTrue(unknownIndex.detail.contains("index"))
    }

    // ---- the heliophysics metrics added with the space-weather console --------------------

    @Test fun xrayFluxNamesTheFlareClassAndScalesItsWarning() {
        // Below the A-class floor there is nothing to name, and it must not pretend otherwise.
        assertTrue(SpaceWeatherExplainers.xrayFlux(null).headline.contains("background"))
        assertTrue(SpaceWeatherExplainers.xrayFlux(1e-9).headline.contains("background"))

        assertTrue(SpaceWeatherExplainers.xrayFlux(5.0e-7).headline.startsWith("B5"))
        assertTrue(SpaceWeatherExplainers.xrayFlux(2.3e-6).headline.startsWith("C2.3"))
        val m = SpaceWeatherExplainers.xrayFlux(4.0e-5)
        assertTrue(m.headline.startsWith("M4"))
        assertTrue("an M flare must mention radio", m.detail.contains("radio", ignoreCase = true))
        val x = SpaceWeatherExplainers.xrayFlux(4.5e-3)
        assertTrue("expected X45, got ${x.headline}", x.headline.startsWith("X45"))
        assertTrue(x.headline.contains("Major"))
    }

    @Test fun solarFluxSpansMinimumToMaximum() {
        assertTrue(SpaceWeatherExplainers.solarFlux(68.0).headline.contains("minimum"))
        assertTrue(SpaceWeatherExplainers.solarFlux(100.0).headline.contains("Moderate"))
        assertTrue(SpaceWeatherExplainers.solarFlux(150.0).headline.contains("Active"))
        assertTrue(SpaceWeatherExplainers.solarFlux(220.0).headline.contains("maximum"))
        // The number is rendered, not just the band.
        assertTrue(SpaceWeatherExplainers.solarFlux(150.4).headline.contains("150"))
    }

    @Test fun protonFluxOnlyClaimsAStormWhenThereIsOne() {
        val quiet = SpaceWeatherExplainers.protonFlux(0.3)
        assertTrue(quiet.headline.contains("background"))
        val storm = SpaceWeatherExplainers.protonFlux(150.0)
        assertTrue("expected an S-scale label, got ${storm.headline}", storm.headline.startsWith("S2"))
        assertNotEquals(quiet.detail, storm.detail)
    }

    @Test fun theNoaaScalesReadTheSameWayInEveryDirection() {
        for (prefix in listOf('R', 'S', 'G')) {
            val none = SpaceWeatherExplainers.noaaScale(prefix, 0)
            assertTrue(none.headline.startsWith("${prefix}0"))
            assertTrue(none.detail.isNotBlank())
            for (level in 1..5) {
                val e = SpaceWeatherExplainers.noaaScale(prefix, level)
                assertTrue("$prefix$level headline: ${e.headline}", e.headline.startsWith("$prefix$level"))
                assertTrue("$prefix$level has no effect text", e.detail.isNotBlank())
            }
        }
        // Each scale explains a different cause, so the three must not read identically.
        assertNotEquals(
            SpaceWeatherExplainers.noaaScale('R', 0).detail,
            SpaceWeatherExplainers.noaaScale('G', 0).detail,
        )
    }

    @Test fun theShortwaveExplainersTrackTheBandReport() {
        assertTrue(SpaceWeatherExplainers.maxUsableFrequency(7.0).headline.contains("Low"))
        assertTrue(SpaceWeatherExplainers.maxUsableFrequency(32.0).headline.contains("Excellent"))
        assertTrue(SpaceWeatherExplainers.maxUsableFrequency(21.4).headline.contains("21"))

        // Drive it from the real core rather than a hand-built fixture.
        val report = HfPropagation.report(f107 = 150.0, kp = 2.0, xrayLongChannelWm2 = null)
        assertTrue(report.isNotEmpty())
        for (b in report) {
            val e = SpaceWeatherExplainers.band(b)
            assertTrue(e.headline.startsWith(b.name))
            assertTrue("${b.name} detail should carry its frequency", e.detail.contains("MHz"))
        }
    }
}
