package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class BreakingNewsTest {

    private data class Item(val id: String, val t: Long)

    private fun pick(
        items: List<Item>,
        now: Long,
        minRecent: Int = 6,
        cap: Int = 24,
        window: Long = BreakingNews.DEFAULT_WINDOW_MS,
    ) = BreakingNews.select(items, now, key = { it.id }, timeMs = { it.t }, windowMs = window, minRecent = minRecent, cap = cap)

    @Test
    fun newestFirstAndDeduped() {
        val now = 1_000_000_000L
        val out = pick(
            listOf(Item("a", now - 100), Item("b", now - 50), Item("a", now - 10)), // dup "a" → first kept
            now, minRecent = 1,
        )
        assertEquals(listOf("b", "a"), out.map { it.id }) // newest first
        assertEquals(2, out.size)                          // deduped
    }

    @Test
    fun prefersRecentWithinWindow() {
        val now = 10_000_000L
        val items = listOf(Item("fresh1", now - 100), Item("fresh2", now - 200), Item("old", now - 50_000))
        assertEquals(listOf("fresh1", "fresh2"), pick(items, now, minRecent = 2, window = 1_000L).map { it.id })
    }

    @Test
    fun fallsBackToFreshestWhenTooFewRecent() {
        val now = 10_000_000L
        val items = listOf(Item("old1", now - 50_000), Item("old2", now - 60_000))
        assertEquals(listOf("old1", "old2"), pick(items, now, minRecent = 6, window = 1_000L).map { it.id })
    }

    @Test
    fun futureDatedNotCountedAsRecent() {
        val now = 10_000_000L
        val items = listOf(Item("future", now + 5_000), Item("a", now - 100), Item("b", now - 200))
        assertEquals(listOf("a", "b"), pick(items, now, minRecent = 2, window = 1_000L).map { it.id })
    }

    @Test
    fun blankKeysDropped() {
        assertEquals(listOf("x"), pick(listOf(Item("", 5), Item("x", 4)), 1_000L, minRecent = 1).map { it.id })
    }

    @Test
    fun capLimitsCount() {
        val now = 100_000L
        val items = (1..50).map { Item("i$it", now - it) }
        assertEquals(10, pick(items, now, minRecent = 1, cap = 10).size)
    }
}
