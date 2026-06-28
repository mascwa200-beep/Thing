package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
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
        assertTrue(m.headline.contains("Mostly up", ignoreCase = true))
        assertTrue(m.plain.contains("worth more", ignoreCase = true))
        assertTrue(m.detail.contains("4 up"))
        assertTrue(m.detail.contains("1 down"))
        assertTrue(m.detail.contains("of 5"))
    }

    @Test
    fun broadlyLowerWhenMostDown() {
        val m = MarketMood.summarize(listOf(-1.0, -2.0, -0.5, -3.0, 0.2))!!
        assertTrue(m.headline.contains("Mostly down", ignoreCase = true))
        assertTrue(m.plain.contains("worth less", ignoreCase = true))
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

    @Test
    fun structuredBreadthFieldsArePopulated() {
        val m = MarketMood.summarize(listOf(2.0, -1.0, 0.0, 4.0))!!
        assertEquals(2, m.up)
        assertEquals(1, m.down)
        assertEquals(1, m.flat)
        assertEquals(4, m.total)
        assertEquals(0.5, m.upShare, 1e-9)
    }

    @Test
    fun netChangeIsTheEqualWeightedAverageOfFiniteChanges() {
        // (2 - 1 + 0 + 3) / 4 = 1.0; the non-finite value is excluded from both count and average.
        val m = MarketMood.summarize(listOf(2.0, -1.0, 0.0, 3.0, Double.POSITIVE_INFINITY))!!
        assertEquals(4, m.total)
        assertEquals(1.0, m.netChangePct, 1e-9)
    }
}
