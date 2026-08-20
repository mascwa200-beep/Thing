package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TheaterModelTest {

    // ------------------------------------------------------------------ videoId

    /**
     * The shared fixture table both regexes must agree on. The Python side
     * (lcars_extract.video_id) is asserted against the SAME table by the scratchpad harness —
     * keeping the twins from drifting is this table's whole job.
     */
    private val idFixtures = listOf(
        "https://www.youtube.com/watch?v=h6faatE8b9Q" to "h6faatE8b9Q",
        "https://youtu.be/tahaaHxhbsA" to "tahaaHxhbsA",
        "https://www.youtube.com/shorts/AbCdEfGhIj_" to "AbCdEfGhIj_",
        "https://www.youtube.com/embed/AbCdEfGhIj-" to "AbCdEfGhIj-",
        "https://m.youtube.com/watch?v=h6faatE8b9Q&t=42s" to "h6faatE8b9Q",
        "https://example.com/article" to "",
        "https://vimeo.com/12345678" to "",
        "" to "",
    )

    @Test
    fun videoIdMatchesTheFixtureTable() {
        for ((url, want) in idFixtures) {
            assertEquals("for $url", want, TheaterModel.videoId(url))
        }
    }

    // ------------------------------------------------------------------ remember

    private fun v(
        id: String,
        pos: Long = 0,
        dur: Long = 100_000,
        at: Long = 1_000_000,
    ) = TheaterModel.Viewing(id, "t", "https://p/$id", "", pos, dur, at)

    @Test
    fun rememberDedupesByIdKeepingTheNewEntry() {
        val ledger = listOf(v("a", pos = 10_000, at = 500_000))
        val out = TheaterModel.remember(ledger, v("a", pos = 60_000, at = 1_000_000))
        assertEquals(1, out.size)
        assertEquals(60_000L, out[0].positionMs)
    }

    @Test
    fun rememberOrdersNewestFirstAndCaps() {
        var ledger = emptyList<TheaterModel.Viewing>()
        for (i in 1..15) {
            ledger = TheaterModel.remember(ledger, v("id$i", at = 1_000_000 + i.toLong()))
        }
        assertEquals(TheaterModel.LEDGER_MAX, ledger.size)
        assertEquals("id15", ledger[0].id)
        assertEquals("id6", ledger.last().id)
    }

    @Test
    fun aFinishedViewingLeavesTheShelf() {
        // 96% of a known duration is past FINISHED_FRACTION (95%).
        val ledger = listOf(v("done", pos = 96_000, dur = 100_000, at = 900_000))
        val out = TheaterModel.remember(ledger, v("fresh", at = 1_000_000))
        assertEquals(listOf("fresh"), out.map { it.id })
    }

    @Test
    fun anUnknownDurationNeverCountsAsFinished() {
        // ⚠️ The rule that protects live streams and unresolved durations: 0-duration must never
        // satisfy "past 95% of the duration", or the shelf silently empties for exactly the
        // entries whose duration the flat listing could not state.
        val ledger = listOf(v("live", pos = 3_600_000, dur = 0, at = 900_000))
        val out = TheaterModel.remember(ledger, v("fresh", at = 1_000_000))
        assertEquals(setOf("fresh", "live"), out.map { it.id }.toSet())
    }

    @Test
    fun aStaleViewingLeavesTheShelf() {
        val old = v("old", at = 1_000_000)
        val nowMs = 1_000_000 + TheaterModel.LEDGER_MAX_AGE_MS + 1
        val out = TheaterModel.remember(listOf(old), v("fresh", at = nowMs))
        assertEquals(listOf("fresh"), out.map { it.id })
    }

    // ------------------------------------------------------------------ storyQueries

    @Test
    fun storyQueriesStripAttributionTailsAndQuotes() {
        val out = TheaterModel.storyQueries(
            listOf(
                "Volcano erupts near city, thousands evacuated - Reuters",
                "“Historic” storm slams the coast | BBC News",
            ),
        )
        assertEquals(
            listOf(
                "Volcano erupts near city, thousands evacuated",
                "Historic storm slams the coast",
            ),
            out,
        )
    }

    @Test
    fun storyQueriesCapWordsAndDropFragments() {
        val long = "one two three four five six seven eight nine ten eleven"
        val out = TheaterModel.storyQueries(listOf(long, "Too short", "Markets", "Third real headline here"))
        assertEquals("one two three four five six seven eight", out[0])
        assertTrue("fragments dropped", out.none { it == "Too short" || it == "Markets" })
        assertTrue(out.contains("Third real headline here"))
    }

    @Test
    fun storyQueriesDedupeAndHonourMax() {
        val out = TheaterModel.storyQueries(
            listOf("Same story here - CNN", "Same story here | AP", "Another story entirely", "A fourth headline arrives"),
            max = 2,
        )
        assertEquals(listOf("Same story here", "Another story entirely"), out)
    }

    @Test
    fun categoryShelvesHaveUniqueIdsAndNonBlankQueries() {
        val shelves = TheaterModel.CATEGORY_SHELVES
        assertEquals(shelves.size, shelves.map { it.id }.toSet().size)
        assertTrue(shelves.all { it.query.isNotBlank() && it.label.isNotBlank() })
    }
}
