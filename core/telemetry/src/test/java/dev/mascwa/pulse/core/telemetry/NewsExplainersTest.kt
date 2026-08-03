package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertTrue
import org.junit.Test

class NewsExplainersTest {

    @Test
    fun moodExplainsAllThreeCounts() {
        val e = NewsExplainers.mood(ToneBreakdown(Tone.MIXED, 0f, positive = 2, negative = 1, tense = 3))
        assertTrue(e.detail.contains("2 upbeat"))
        assertTrue(e.detail.contains("1 downbeat"))
        assertTrue(e.detail.contains("3 tense"))
    }

    @Test
    fun moodExplainsZeroHits() {
        val e = NewsExplainers.mood(ToneBreakdown(Tone.MIXED, 0f, positive = 0, negative = 0, tense = 0))
        assertTrue(e.detail.contains("No strongly charged words", ignoreCase = true))
    }

    @Test
    fun moodCaveatsItIsNotAGoodBadJudgment() {
        val e = NewsExplainers.mood(ToneBreakdown(Tone.TENSE, -0.5f, positive = 0, negative = 0, tense = 2))
        assertTrue(e.detail.contains("not whether the news is good or bad", ignoreCase = true))
    }

    @Test
    fun biasExplainsRatedVsUnrated() {
        val e = NewsExplainers.bias(BiasBreakdown(left = 2, leanLeft = 1, unrated = 3))
        assertTrue(e.detail.contains("3 of 6"))
        assertTrue(e.detail.contains("3 aren't in our table yet"))
    }

    @Test
    fun biasExplainsSingularOutletWording() {
        val e = NewsExplainers.bias(BiasBreakdown(center = 1))
        assertTrue(e.detail.contains("1 of 1 outlet "))
    }

    @Test
    fun biasExplainsEmptyCoverage() {
        val e = NewsExplainers.bias(BiasBreakdown())
        assertTrue(e.detail.contains("No other outlets", ignoreCase = true))
    }

    @Test
    fun biasNeverClaimsToGuess() {
        val e = NewsExplainers.bias(BiasBreakdown(left = 1, unrated = 1))
        assertTrue(e.detail.contains("never a guess", ignoreCase = true))
    }

    @Test
    fun buzzExplainsItIsNotALiveCount() {
        val e = NewsExplainers.buzz(BuzzLevel.HIGH)
        assertTrue(e.detail.contains("isn't a live count", ignoreCase = true))
        assertTrue(e.headline.contains("trending", ignoreCase = true))
    }

    @Test
    fun buzzHeadlineMatchesEveryLevel() {
        for (level in BuzzLevel.entries) {
            val e = NewsExplainers.buzz(level)
            assertTrue(e.headline.contains(level.label, ignoreCase = true))
        }
    }
}
