package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsInsightsTest {

    @Test fun upbeatNewsReadsUpbeat() {
        val (tone, score) = NewsInsights.tone("Markets rally to record high as profits beat forecasts")
        assertEquals(Tone.UPBEAT, tone)
        assertTrue(score > 0f)
    }

    @Test fun grimNewsReadsGrim() {
        val (tone, score) = NewsInsights.tone("Stocks crash as layoffs and bankruptcy fears spread")
        assertEquals(Tone.GRIM, tone)
        assertTrue(score < 0f)
    }

    @Test fun conflictNewsReadsTense() {
        val (tone, _) = NewsInsights.tone("Missile attack escalates the war overnight")
        assertEquals(Tone.TENSE, tone)
    }

    @Test fun neutralNewsIsMixed() {
        val (tone, score) = NewsInsights.tone("City council to review the annual budget schedule")
        assertEquals(Tone.MIXED, tone)
        assertEquals(0f, score, 0.0001f)
    }

    @Test fun topicsAreDetected() {
        val tags = NewsInsights.topics("Nvidia unveils new AI chip as tech stocks climb")
        assertTrue("Tech" in tags)
    }

    @Test fun regionsAreDetected() {
        val tags = NewsInsights.topics("Russia and Ukraine trade fresh accusations over the ceasefire")
        assertTrue("Ukraine" in tags || "Russia" in tags)
    }

    @Test fun topicsAreCapped() {
        val tags = NewsInsights.topics(
            "War economy politics markets tech science space climate health crime hits the US and China", max = 4,
        )
        assertTrue(tags.size <= 4)
    }

    @Test fun marketImpactScalesWithLinks() {
        assertEquals(ImpactLevel.NONE, NewsInsights.marketImpact(emptyList()))
        val many = NewsInsights.marketImpact(
            listOf(
                MarketLink("Oil", MarketImpact.UP, "", "", 3),
                MarketLink("Gold", MarketImpact.UP, "", "", 3),
            ),
        )
        assertEquals(ImpactLevel.HIGH, many)
        val one = NewsInsights.marketImpact(listOf(MarketLink("Tech", MarketImpact.MIXED, "", "", 2)))
        assertEquals(ImpactLevel.LOW, one)
    }

    @Test fun analyzeCombinesEverything() {
        val title = "Oil prices surge as OPEC agrees a production cut"
        val links = NewsMarketLink.linksFor(title)
        val insight = NewsInsights.analyze(title, "", links)
        assertTrue(insight.topics.isNotEmpty())        // Energy/Markets
        assertTrue(insight.marketImpact != ImpactLevel.NONE)
        assertEquals(NewsInsights.tone(title).first, insight.tone) // analyze delegates to tone()
    }

    @Test fun toneBreakdownExposesRealCounts() {
        val b = NewsInsights.toneBreakdown("Stocks crash as layoffs and bankruptcy fears spread")
        assertEquals(Tone.GRIM, b.tone)
        assertTrue(b.negative > 0)
        assertEquals(0, b.positive)
        assertEquals(0, b.tense)
    }

    @Test fun toneBreakdownAgreesWithTone() {
        val title = "Missile attack escalates the war overnight"
        val b = NewsInsights.toneBreakdown(title)
        val (tone, score) = NewsInsights.tone(title)
        assertEquals(tone, b.tone)
        assertEquals(score, b.score, 0.0001f)
        assertTrue(b.tense > 0)
    }

    @Test fun toneBreakdownZeroOnNeutral() {
        val b = NewsInsights.toneBreakdown("City council to review the annual budget schedule")
        assertEquals(0, b.positive)
        assertEquals(0, b.negative)
        assertEquals(0, b.tense)
        assertEquals(Tone.MIXED, b.tone)
    }

    @Test fun clusterSizeCountsSharedTagStories() {
        val tags = listOf("Tech", "Markets")
        val others = listOf(listOf("Tech"), listOf("Sports"), listOf("Markets", "Economy"), listOf("Health"))
        assertEquals(2, NewsInsights.clusterSize(tags, others))
    }

    @Test fun clusterSizeZeroWithNoOverlap() {
        assertEquals(0, NewsInsights.clusterSize(listOf("Tech"), listOf(listOf("Sports"), listOf("Health"))))
    }

    @Test fun clusterSizeZeroWhenNoTags() {
        assertEquals(0, NewsInsights.clusterSize(emptyList(), listOf(listOf("Tech"))))
    }
}
