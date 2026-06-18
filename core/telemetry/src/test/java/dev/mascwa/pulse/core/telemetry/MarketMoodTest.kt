package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketMoodTest {

    @Test
    fun emptyOrAllNonFiniteIsNull() {
        assertNull(MarketMood.summarize(emptyList()))
        assertNull(MarketMood.summarize(listOf(Double.NaN, Double.POSITIVE_INFINITY)))
    }

    @Test
    fun broadlyHigherWhenMostUp() {
        val m = MarketMood.summarize(listOf(1.0, 2.0, 0.5, 3.0, -0.2))!!
        assertTrue(m.headline.contains("broadly higher", ignoreCase = true))
        assertTrue(m.detail.contains("4 up"))
        assertTrue(m.detail.contains("1 down"))
        assertTrue(m.detail.contains("of 5"))
    }

    @Test
    fun broadlyLowerWhenMostDown() {
        val m = MarketMood.summarize(listOf(-1.0, -2.0, -0.5, -3.0, 0.2))!!
        assertTrue(m.headline.contains("broadly lower", ignoreCase = true))
    }

    @Test
    fun mixedWhenSplit() {
        val m = MarketMood.summarize(listOf(1.0, -1.0, 1.0, -1.0))!!
        assertTrue(m.headline.contains("Mixed", ignoreCase = true))
        assertTrue(m.detail.contains("2 up · 2 down"))
    }

    @Test
    fun flatsAreCounted() {
        val m = MarketMood.summarize(listOf(0.0, 1.0, -1.0, 0.0))!!
        assertTrue(m.detail.contains("flat"))
        assertTrue(m.detail.contains("of 4"))
    }

    @Test
    fun nonFiniteValuesAreIgnoredInTheCount() {
        val m = MarketMood.summarize(listOf(1.0, 2.0, Double.NaN))!!
        assertTrue(m.detail.contains("of 2"))
    }
}
