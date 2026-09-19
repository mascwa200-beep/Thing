package dev.mascwa.pulse.desktop.search

import dev.mascwa.pulse.core.telemetry.DeviceSearch
import dev.mascwa.pulse.core.telemetry.GuideSearch
import dev.mascwa.pulse.desktop.DESK_ENTRIES
import dev.mascwa.pulse.desktop.DESK_GROUPS
import dev.mascwa.pulse.desktop.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search box can find a screen, and finding one costs the library nothing.
 *
 * ⚠️ **The second half is the one worth a test.** Screens are left out of the ranked slate on
 * purpose: [DeviceSearch.search] caps a kind at four places and fills the rest only when everything
 * that matched is one kind, so folding screens into the library corpus would quietly take a
 * guides-only query from twelve results down to four. That is invisible in a screenshot and obvious
 * in a measurement, which is what this pins.
 */
class DesktopScreenSearchTest {

    private val screens = DesktopSearchIndex.screenRecords()

    @Test
    fun `every screen in the directory is searchable`() {
        assertEquals("one record per directory entry", DESK_ENTRIES.size, screens.size)
        assertEquals(
            "every screen, and only screens",
            DESK_ENTRIES.keys,
            screens.mapNotNull { r -> Screen.entries.firstOrNull { it.name == r.entry.id } }.toSet(),
        )
    }

    @Test
    fun `a screen is found by its own name`() {
        // Every entry, by the label a person reads in the directory. If the box cannot answer with
        // the screen when handed the screen's own name, nothing else it does here matters.
        val missed = DESK_GROUPS.flatMap { it.entries }.filter { e ->
            DeviceSearch.search(screens, e.label, limit = 3).none { it.id == e.screen.name }
        }
        assertTrue("not found by their own label: ${missed.map { it.label }}", missed.isEmpty())
    }

    @Test
    fun `a screen is found by a word someone would actually type for it`() {
        // The searchTerms exist for people who call it something else, so they have to reach it too.
        // This is what would fail if the terms were dropped from the record, or put somewhere the
        // scorer does not read.
        val missed = DESK_GROUPS.flatMap { it.entries }.flatMap { e ->
            e.searchTerms.mapNotNull { term ->
                if (DeviceSearch.search(screens, term, limit = 3).any { it.id == e.screen.name }) {
                    null
                } else {
                    "${e.label} <- $term"
                }
            }
        }
        assertTrue("search terms that do not reach their own screen: $missed", missed.isEmpty())
    }

    /**
     * ⚠️ The split that keeps the rendered row readable: a search term must be MATCHED and never
     * DRAWN. `ScreenRow` prints `summary`, so terms appended there would render as
     * "Internet radio — near you, starred, and always on music stream station listen fm somafm audio".
     *
     * ⚠️ **Equality is the whole assertion, and a "no term appears in the summary" check is NOT a
     * stronger version of it — it is a different and wrong property.** I wrote that check first and
     * it failed, correctly: sixteen entries across eleven screens use one of their own search terms
     * inside ordinary prose, because that is what English does. "Your daily log" contains "log"; "The
     * forecast, and what it will actually feel like" contains "forecast". None of those is a keyword
     * list leaking into the interface, which is the only thing worth preventing — and these two
     * equalities prevent it exactly, since a summary that equals the description cannot also be a
     * concatenation of anything.
     */
    @Test
    fun `search terms are matchable but never rendered`() {
        for (r in screens) {
            val entry = DESK_ENTRIES.getValue(Screen.valueOf(r.entry.id))
            assertEquals("${entry.label}: summary is the description", entry.description, r.entry.summary)
            assertEquals("${entry.label}: terms belong in headings", entry.searchTerms, r.entry.headings)
        }
    }

    /**
     * The whole reason the two are searched separately, asserted rather than asserted-in-a-comment.
     *
     * Builds a library-shaped corpus of one kind, searches it alone, then searches it again with the
     * screens folded in — the design that was rejected — and requires the second to lose guides. If
     * this ever stops showing a loss, the separation has stopped being necessary and the KDoc
     * arguing for it has gone stale.
     */
    @Test
    fun `folding screens into the library corpus would cost the library its places`() {
        // Twenty guides that all match "weather", the way a real library does.
        val guides = (1..20).map { i ->
            DeviceSearch.Record(
                entry = GuideSearch.Entry(
                    id = "g$i",
                    title = "Weather and how to read it, part $i",
                    category = "Weather",
                    summary = "Reading the weather.",
                ),
                kind = DeviceSearch.RecordKind.GUIDE,
            )
        }

        val aloneNow = DeviceSearch.search(guides, "weather")
        assertEquals(
            "one kind takes the fill, so the page is full",
            DeviceSearch.DEFAULT_LIMIT, aloneNow.size,
        )

        val merged = DeviceSearch.search(guides + screens, "weather")
        val guidesAfter = merged.count { it.kind == DeviceSearch.RecordKind.GUIDE }
        assertEquals(
            "merging caps the library at its per-kind budget — this is the cost being avoided",
            DeviceSearch.DEFAULT_PER_KIND, guidesAfter,
        )
        assertTrue("and it is a real loss, not a rounding difference", guidesAfter < aloneNow.size)

        // Whereas searching them apart leaves the library exactly as it was.
        assertEquals(aloneNow.size, DeviceSearch.search(guides, "weather").size)
    }

    @Test
    fun `a result resolves back to somewhere to go`() {
        val hit = DeviceSearch.search(screens, "weather", limit = 1).single()
        assertEquals(Screen.WEATHER, DesktopSearchIndex.screenOf(hit))
    }

    @Test
    fun `a record that is not a screen resolves to nothing`() {
        // A guide id must never be read as a screen name — that is what would send someone to the
        // wrong place rather than to no place.
        val guide = DeviceSearch.of(
            id = "first-aid",
            kind = DeviceSearch.RecordKind.GUIDE,
            title = "First aid",
            body = "CPR",
        )
        assertNull(DesktopSearchIndex.screenOf(DeviceSearch.Result(guide, 1.0)))
    }

    @Test
    fun `the directory group rides along, so a row can say where it lives`() {
        val hit = DeviceSearch.search(screens, "crash console", limit = 1).single()
        assertEquals(Screen.CRASH, DesktopSearchIndex.screenOf(hit))
        assertEquals("THIS MACHINE", hit.record.entry.category)
    }
}
