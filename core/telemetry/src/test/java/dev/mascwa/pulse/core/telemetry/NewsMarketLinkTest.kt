package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsMarketLinkTest {

    private fun markets(title: String, summary: String = "") =
        NewsMarketLink.linksFor(title, summary).map { it.market }

    private fun impact(title: String, market: String) =
        NewsMarketLink.linksFor(title).firstOrNull { it.market == market }?.impact

    @Test fun associatesStoriesWithTheRightMarkets() {
        assertTrue("Oil" in markets("Oil prices surge as OPEC agrees a production cut"))
        assertTrue("Bitcoin" in markets("Bitcoin rallies past a new milestone"))
        assertTrue("Tech" in markets("Apple unveils its next iPhone"))
        assertTrue("Chips" in markets("Nvidia lifts its guidance on AI chip demand"))
        assertTrue("Housing" in markets("Mortgage rates cool as home sales climb"))
    }

    @Test fun directionUpWhenTheHeadlineSaysSo() {
        assertEquals(MarketImpact.UP, impact("Oil prices surge as OPEC agrees a production cut", "Oil"))
        assertEquals(MarketImpact.UP, impact("Bitcoin rallies to a record high", "Bitcoin"))
    }

    @Test fun directionDownOnBadNews() {
        assertEquals(MarketImpact.DOWN, impact("Tech stocks tumble on weak earnings", "Tech"))
        assertEquals(MarketImpact.DOWN, impact("Bitcoin crashes below key support", "Bitcoin"))
    }

    @Test fun havensRiseOnCrisisNews() {
        assertEquals(MarketImpact.UP, impact("Gold climbs as war fears grip investors", "Gold"))
        assertEquals(MarketImpact.UP, impact("Defense stocks gain amid missile attack fears", "Defense"))
    }

    @Test fun plainAssociationIsMixedWhenNoDirectionIsStated() {
        assertEquals(MarketImpact.MIXED, impact("Apple unveils its next iPhone", "Tech"))
    }

    @Test fun ordinaryNewsHasNoMarketLink() {
        assertTrue(NewsMarketLink.linksFor("City council debates new parking rules").isEmpty())
        assertTrue(NewsMarketLink.linksFor("Local team wins the championship").isEmpty())
    }

    @Test fun capsToThreeMarketsForACompactStrip() {
        val many = NewsMarketLink.linksFor(
            "Oil, gold, tech, banks and airlines all react as war, chip and mortgage news hits",
        )
        assertTrue(many.size <= 3)
    }

    @Test fun summarizeReadsAsAWhyLine() {
        val links = NewsMarketLink.linksFor("Oil surges on supply cut while airlines tumble")
        val line = NewsMarketLink.summarize(links)
        assertTrue(line.isNotBlank())
        assertTrue(line.contains("Oil") || line.contains("Airlines"))
    }

    @Test fun everyLinkCarriesACausalRationale() {
        val links = NewsMarketLink.linksFor("Oil prices surge as OPEC agrees a production cut")
        assertTrue(links.isNotEmpty())
        assertTrue(links.all { it.rationale.isNotBlank() })
    }

    @Test fun aKeywordMatchOutranksACategoryBaseline() {
        // A keyword-matched market with a stated direction is the strongest read; a market added only
        // because of the news category is the weakest.
        val links = NewsMarketLink.linksFor("Bitcoin surges to a record high", category = "Business")
        assertEquals("Bitcoin", links.first().market)
        val stocks = links.firstOrNull { it.market == "Stocks" }
        assertTrue(stocks != null && links.first().strength > stocks.strength)
    }

    @Test fun theFrozenCropStorySpikesOjFutures() {
        // The literal Trading Places setup: a bad freeze report → OJ up.
        val oj = NewsMarketLink.linksFor("Florida orange crop hit by hard freeze, shortage feared")
            .firstOrNull { it.market == "OJ Futures" }
        assertTrue(oj != null && oj.impact == MarketImpact.UP)
    }

    @Test fun aCategoryGivesABroadReadWhenNoKeywordMatches() {
        // A business headline with no sector keyword still surfaces the broad market (low strength).
        val links = NewsMarketLink.linksFor("Quarterly figures come in ahead of forecasts", category = "Business")
        assertTrue(links.any { it.market == "Stocks" })
        // But it stays a weak, watch-list association, not a confident call.
        assertEquals(1, links.first { it.market == "Stocks" }.strength)
    }

    @Test fun headlineIsTheSharpestRationale() {
        val links = NewsMarketLink.linksFor("Gold climbs as war fears grip investors")
        assertTrue(NewsMarketLink.headline(links).isNotBlank())
    }
}
