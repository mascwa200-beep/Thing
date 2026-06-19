package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertTrue
import org.junit.Test

class EconomyExplainersTest {

    @Test
    fun inflationBandsByValue() {
        assertTrue(EconomyExplainers.forIndicator("FP.CPI.TOTL.ZG", -1.0).detail.contains("Deflation"))
        assertTrue(EconomyExplainers.forIndicator("FP.CPI.TOTL.ZG", 2.4).detail.contains("On target"))
        assertTrue(EconomyExplainers.forIndicator("FP.CPI.TOTL.ZG", 8.0).detail.contains("High"))
        // Headline carries the value.
        assertTrue(EconomyExplainers.forIndicator("FP.CPI.TOTL.ZG", 4.2).headline.contains("4.2%"))
    }

    @Test
    fun gdpGrowthContractionFlagged() {
        assertTrue(EconomyExplainers.forIndicator("NY.GDP.MKTP.KD.ZG", -0.5).detail.contains("Contracting"))
        assertTrue(EconomyExplainers.forIndicator("NY.GDP.MKTP.KD.ZG", 3.0).detail.contains("Healthy"))
    }

    @Test
    fun unemploymentBands() {
        assertTrue(EconomyExplainers.forIndicator("SL.UEM.TOTL.ZS", 3.0).detail.contains("Very low"))
        assertTrue(EconomyExplainers.forIndicator("SL.UEM.TOTL.ZS", 12.0).detail.contains("High"))
    }

    @Test
    fun currentAccountSurplusVsDeficit() {
        assertTrue(EconomyExplainers.forIndicator("BN.CAB.XOKA.GD.ZS", 2.0).detail.contains("Surplus"))
        assertTrue(EconomyExplainers.forIndicator("BN.CAB.XOKA.GD.ZS", -3.0).detail.contains("Deficit"))
    }

    @Test
    fun staticIndicatorsHaveExplanations() {
        assertTrue(EconomyExplainers.forIndicator("NY.GDP.PCAP.CD", null).headline.contains("per capita", true))
        assertTrue(EconomyExplainers.forIndicator("SP.POP.TOTL", null).detail.isNotBlank())
    }

    @Test
    fun nullValueFallsBackToWhatItMeans() {
        val e = EconomyExplainers.forIndicator("FP.CPI.TOTL.ZG", null)
        assertTrue(e.detail.contains("consumer prices"))
    }

    @Test
    fun unknownIndicatorGivesGenericExplainer() {
        val e = EconomyExplainers.forIndicator("XX.UNKNOWN", 1.0)
        assertTrue(e.detail.contains("World Bank"))
    }
}
