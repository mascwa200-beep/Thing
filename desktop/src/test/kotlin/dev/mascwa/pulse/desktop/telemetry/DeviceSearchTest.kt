// MIRROR OF core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/DeviceSearchTest.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import dev.mascwa.pulse.desktop.telemetry.DeviceSearch.RecordKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The property that justifies the file: a corpus where one kind outnumbers the rest a hundred to one
 * still shows the rest.
 */
class DeviceSearchTest {

    /** A guide corpus of the shape the real one has: many, all plausibly about the query. */
    private fun guides(n: Int, word: String) = (1..n).map {
        DeviceSearch.of("g$it", RecordKind.GUIDE, "$word and Related Practice $it", "A written page about $word.")
    }

    // ---- diversity ------------------------------------------------------------------------------

    @Test
    fun oneKindCannotOccupyEveryPlace() {
        val records = guides(200, "water") + listOf(
            DeviceSearch.of("n1", RecordKind.NOTE, "Water filter spares", "Order water filter cartridges."),
            DeviceSearch.of("t1", RecordKind.TASK, "Buy water filter", ""),
        )
        val out = DeviceSearch.search(records, "water filter", limit = 10, perKind = 4)

        assertEquals("guides must be capped", 4, out.count { it.kind == RecordKind.GUIDE })
        assertTrue("the note the user wrote must survive", out.any { it.id == "n1" })
        assertTrue("so must the task", out.any { it.id == "t1" })
    }

    /**
     * The cap is doing the work, not the ranker.
     *
     * Stated as a comparison rather than an absolute: measuring the uncapped case showed the ranker
     * is better than assumed — a note matching both query words outscores guides matching one, so it
     * surfaces on merit. That is not something to rely on. Give the guides an equally good claim and
     * only the cap keeps the user's own writing on the page.
     */
    @Test
    fun theCapIsWhatMakesRoomNotTheRanker() {
        val records = guides(200, "cordage") + listOf(
            DeviceSearch.of("n1", RecordKind.NOTE, "Cordage", "Cordage for the shed."),
        )
        val capped = DeviceSearch.search(records, "cordage", limit = 10, perKind = 4)
        val uncapped = DeviceSearch.search(records, "cordage", limit = 10, perKind = Int.MAX_VALUE)
        assertTrue(
            "capping must reduce the dominant kind's share",
            capped.count { it.kind == RecordKind.GUIDE } < uncapped.count { it.kind == RecordKind.GUIDE },
        )
        assertTrue("the note must be on the page", capped.any { it.id == "n1" })
    }

    /** A cap must never shorten a list when there is nothing else to put in it. */
    @Test
    fun cappingDoesNotStarveAListWhenOnlyOneKindMatches() {
        val out = DeviceSearch.search(guides(50, "knots"), "knots", limit = 10, perKind = 2)
        assertEquals(10, out.size)
        // The first two are the capped pass; the rest are the fill. No duplicates either way.
        assertEquals(out.size, out.map { it.id }.distinct().size)
    }

    // ---- ranking --------------------------------------------------------------------------------

    @Test
    fun aQueryThatMatchesNothingReturnsNothing() {
        val records = guides(20, "water") + listOf(
            DeviceSearch.of("n1", RecordKind.NOTE, "Dentist", "Booked for Tuesday."),
        )
        assertTrue(DeviceSearch.search(records, "photosynthesis chlorophyll").isEmpty())
    }

    @Test
    fun anEmptyQueryOrCorpusIsNotAnError() {
        assertTrue(DeviceSearch.search(guides(5, "water"), "").isEmpty())
        assertTrue(DeviceSearch.search(guides(5, "water"), "   ").isEmpty())
        assertTrue(DeviceSearch.search(emptyList(), "water").isEmpty())
    }

    /**
     * A query of nothing but stopwords still searches, and that is [GuideSearch.tokens]'s documented
     * choice rather than an oversight here: it falls back to the raw words so a box someone typed
     * into does not silently return nothing. Asserted so a future change to that fallback shows up
     * as a decision rather than a surprise.
     */
    @Test
    fun aQueryOfOnlyStopwordsFallsBackToTheRawWords() {
        val records = guides(5, "water")   // titles literally read "water and Related Practice N"
        assertTrue(DeviceSearch.search(records, "the and for").isNotEmpty())
        // And it does not become a way to list the whole corpus: words nothing contains match nothing.
        assertTrue(DeviceSearch.search(records, "the from into").isEmpty())
    }

    /** Between two equally good matches in your own writing, the recent one is what was meant. */
    @Test
    fun recencyBreaksTiesAndNothingElse() {
        val old = DeviceSearch.of("old", RecordKind.NOTE, "Passport renewal", "Renew the passport.", atMs = 1_000)
        val new = DeviceSearch.of("new", RecordKind.NOTE, "Passport renewal", "Renew the passport.", atMs = 9_000)
        val out = DeviceSearch.search(listOf(old, new), "passport renewal")
        assertEquals(listOf("new", "old"), out.map { it.id })

        // But a better match beats a newer one — recency is a tiebreak, not a ranking.
        val better = DeviceSearch.of("better", RecordKind.NOTE, "Passport renewal appointment", "Passport renewal.", atMs = 1)
        val worse = DeviceSearch.of("worse", RecordKind.NOTE, "Shopping", "Passport photos maybe.", atMs = 9_999_999)
        val out2 = DeviceSearch.search(listOf(worse, better), "passport renewal")
        assertEquals("better", out2.first().id)
    }

    // ---- record building -------------------------------------------------------------------------

    @Test
    fun untitledWritingStillHasSomethingToShow() {
        val r = DeviceSearch.of("d1", RecordKind.DIARY, title = "", body = "Walked to the river and back.")
        assertTrue(r.entry.title.isNotBlank())
        assertTrue(r.entry.title.startsWith("Walked to the river"))
        // And it is still findable by its body.
        assertEquals("d1", DeviceSearch.search(listOf(r), "river").firstOrNull()?.id)
    }

    @Test
    fun theKindIsCarriedThroughSoAResultKnowsWhereItLives() {
        val r = DeviceSearch.of("t1", RecordKind.TASK, "File the tax return", "")
        assertEquals(RecordKind.TASK, r.kind)
        assertEquals("Task", r.entry.category)
        assertEquals("jarvis_memory", r.kind.route)
        // Every kind must be able to say where tapping it goes.
        for (k in RecordKind.entries) {
            assertTrue("${k.name} has no route", k.route.isNotBlank())
            assertTrue("${k.name} has no label", k.label.isNotBlank())
        }
    }

    // ---- presentation ------------------------------------------------------------------------------

    @Test
    fun resultsGroupByKindAndTheCorpusCanDescribeItself() {
        val records = guides(3, "fire") + listOf(
            DeviceSearch.of("n1", RecordKind.NOTE, "Fire drill", "Fire assembly point is the car park."),
            DeviceSearch.of("n2", RecordKind.NOTE, "Firewood", "Order firewood for winter."),
        )
        val grouped = DeviceSearch.byKind(DeviceSearch.search(records, "fire"))
        assertEquals(2, grouped.size)
        assertEquals(setOf(RecordKind.GUIDE, RecordKind.NOTE), grouped.map { it.first }.toSet())

        val summary = DeviceSearch.corpusSummary(records).toMap()
        assertEquals(3, summary[RecordKind.GUIDE])
        assertEquals(2, summary[RecordKind.NOTE])
        // Kinds with nothing in them are not advertised.
        assertTrue(RecordKind.DIARY !in summary)
    }
}
