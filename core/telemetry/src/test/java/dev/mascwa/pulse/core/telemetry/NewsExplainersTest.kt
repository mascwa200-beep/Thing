package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertTrue
import org.junit.Test

class NewsExplainersTest {

    @Test
    fun marketListsEveryLinkedMarket() {
        val links = listOf(
            MarketLink("Oil", MarketImpact.UP, why = "energy"),
            MarketLink("Gold", MarketImpact.MIXED, why = "haven"),
        )
        val e = NewsExplainers.market(ImpactLevel.HIGH, links)
        assertTrue(e.detail.contains("Oil"))
        assertTrue(e.detail.contains("Gold"))
        assertTrue(e.headline.contains("high", ignoreCase = true))
    }

    @Test
    fun marketExplainsNoTiesFound() {
        val e = NewsExplainers.market(ImpactLevel.NONE, emptyList())
        assertTrue(e.detail.contains("No market ties", ignoreCase = true))
        assertTrue(e.headline.contains("what this measures", ignoreCase = true))
    }

    @Test
    fun marketNeverClaimsToBeAdvice() {
        val e = NewsExplainers.market(ImpactLevel.MEDIUM, listOf(MarketLink("Chips", MarketImpact.UP, why = "supply")))
        assertTrue(e.detail.contains("not financial advice", ignoreCase = true))
    }
}
