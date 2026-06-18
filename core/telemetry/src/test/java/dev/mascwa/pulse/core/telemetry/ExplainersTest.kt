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
}
