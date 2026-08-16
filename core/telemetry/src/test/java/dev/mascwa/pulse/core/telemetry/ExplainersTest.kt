package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ---- market session ---------------------------------------------------------------------

    /**
     * A closed venue must say the price is not live. This is the whole point of the explainer: the
     * number looks identical either way, so only the words can carry the difference.
     */
    @Test
    fun aClosedMarketSaysThePriceIsNotLive() {
        val e = MarketExplainers.session(
            MarketSession.Phase.CLOSED, "Closed · last traded 3h ago", "NasdaqGS",
        )
        assertNotNull(e)
        assertEquals("Closed · last traded 3h ago", e!!.headline)
        assertTrue("must name the venue", e.detail.contains("NasdaqGS"))
        assertTrue("must deny liveness", e.detail.contains("not a live one"))
    }

    @Test
    fun anOpenMarketSaysThePriceIsLive() {
        val e = MarketExplainers.session(MarketSession.Phase.OPEN, "Open · 2h to the bell")
        assertNotNull(e)
        assertTrue(e!!.detail.contains("live"))
        // No venue given, so no venue named — never an empty "on ".
        assertFalse(e.detail.contains(" on "))
    }

    /** Thin after-hours moves overstate themselves; the explainer has to say so. */
    @Test
    fun afterHoursWarnsThatTheMoveMayNotHold() {
        val e = MarketExplainers.session(MarketSession.Phase.AFTER, "After hours")
        assertNotNull(e)
        assertTrue(e!!.detail.contains("thinly"))
    }

    /** An unestablished session explains nothing rather than asserting the market is shut. */
    @Test
    fun anUnknownSessionProducesNoExplainer() {
        assertNull(MarketExplainers.session(MarketSession.Phase.UNKNOWN, "whatever"))
        assertNull(MarketExplainers.session(MarketSession.Phase.CLOSED, "   "))
    }

    // ---- fifty-two-week range ---------------------------------------------------------------

    @Test
    fun theYearRangeReportsWhereInsideItThePriceSits() {
        // (150 - 100) / (200 - 100) = 0.5 → 50%, and 0.5 is the mid band
        val e = MarketExplainers.yearRange(150.0, 100.0, 200.0)
        assertNotNull(e)
        assertTrue(e!!.headline.contains("mid-range for the year"))
        assertTrue(e.detail.contains("about 50%"))
        assertTrue(e.detail.contains("100.00") && e.detail.contains("200.00"))
    }

    @Test
    fun aPriceAtTheTopOfItsYearSaysSo() {
        // (198 - 100) / 100 = 0.98 → 98%, above the 0.95 band
        val e = MarketExplainers.yearRange(198.0, 100.0, 200.0)
        assertNotNull(e)
        assertTrue(e!!.headline.contains("at its 52-week high"))
        assertTrue(e.detail.contains("near the top"))
    }

    /** Sub-unit instruments need the decimals that actually move. */
    @Test
    fun smallPricesKeepTheirPrecision() {
        val e = MarketExplainers.yearRange(0.5000, 0.1000, 0.9000)
        assertNotNull(e)
        assertTrue("a currency pair rendered at 2dp loses the moving digits", e!!.detail.contains("0.1000"))
        assertTrue(e.detail.contains("0.9000"))
    }

    @Test
    fun anUnusableYearRangeExplainsNothing() {
        assertNull(MarketExplainers.yearRange(150.0, null, 200.0))
        assertNull(MarketExplainers.yearRange(150.0, 200.0, 200.0))
        assertNull(MarketExplainers.yearRange(null, 100.0, 200.0))
    }
}
