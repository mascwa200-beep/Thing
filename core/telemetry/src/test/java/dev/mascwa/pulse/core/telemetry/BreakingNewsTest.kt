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

    // ── perOutlet ────────────────────────────────────────────────────────────────────────────────

    private data class Story(val outlet: String, val headline: String, val t: Long)

    private fun spread(
        items: List<Story>,
        max: Int = 3,
        prefer: (String) -> Boolean = { false },
    ) = BreakingNews.perOutlet(items, outlet = { it.outlet }, timeMs = { it.t }, max = max, prefer = prefer)

    @Test
    fun `one story per outlet, and it is that outlet's newest`() {
        // ⚠️ THE RULE THAT `select` CANNOT PROVIDE. Its dedupe keeps the FIRST occurrence in input
        // order and sorts afterwards, so keying it on the outlet would pick "bbc-old" here. The
        // input is deliberately ordered oldest-first so that a first-wins implementation is visibly
        // wrong rather than accidentally right.
        val out = spread(
            listOf(
                Story("BBC", "bbc-old", 100),
                Story("BBC", "bbc-new", 900),
                Story("Reuters", "reuters", 500),
            ),
        )
        assertEquals(listOf("bbc-new", "reuters"), out.map { it.headline })
    }

    @Test
    fun `outlets are ordered newest first`() {
        val out = spread(
            listOf(Story("A", "a", 100), Story("B", "b", 300), Story("C", "c", 200)),
        )
        assertEquals(listOf("b", "c", "a"), out.map { it.headline })
    }

    @Test
    fun `preferred outlets take the slots first, even when a stranger is fresher`() {
        // Trust is a BUCKET, not a tiebreak: "aggregator" is the newest thing here and still loses,
        // because three slots are better spent on newsrooms than on whoever reposted last.
        val out = spread(
            listOf(
                Story("Aggregator", "agg", 9_000),
                Story("BBC", "bbc", 100),
                Story("Reuters", "reuters", 200),
            ),
            max = 2,
            prefer = { it == "BBC" || it == "Reuters" },
        )
        assertEquals(listOf("reuters", "bbc"), out.map { it.headline })
    }

    @Test
    fun `an unpreferred outlet is still shown rather than leaving the block empty`() {
        // The failure this guards: treating `prefer` as a filter, so a morning when no known
        // newsroom has published renders nothing at all.
        val out = spread(listOf(Story("Stranger", "s", 100)), prefer = { it == "BBC" })
        assertEquals(listOf("s"), out.map { it.headline })
    }

    @Test
    fun `an outlet that cannot be named is dropped`() {
        val out = spread(listOf(Story("  ", "anon", 900), Story("BBC", "bbc", 100)))
        assertEquals(listOf("bbc"), out.map { it.headline })
    }

    @Test
    fun `max bounds the result and zero asks for nothing`() {
        val many = (1..10).map { Story("outlet$it", "h$it", it.toLong()) }
        assertEquals(3, spread(many, max = 3).size)
        assertEquals(emptyList<Story>(), spread(many, max = 0))
    }
}
