package dev.mascwa.pulse.desktop.search

import dev.mascwa.pulse.desktop.library.LibraryRepository
import dev.mascwa.pulse.desktop.study.StudyStore
import dev.mascwa.pulse.core.telemetry.DeviceSearch
import dev.mascwa.pulse.core.telemetry.DeviceSearch.RecordKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Search, over the real bundled corpus rather than a fixture. */
class DesktopSearchIndexTest {

    @get:Rule val tmp = TemporaryFolder()

    private val library = LibraryRepository()
    private fun study() = StudyStore(library, path = tmp.root.toPath().resolve("study.json"))

    @Test
    fun theWholeLibraryIsSearchable() = runBlocking {
        val records = DesktopSearchIndex.records(library, study())
        assertTrue("only ${records.size} records", records.size >= 500)
        assertTrue(records.all { it.kind == RecordKind.GUIDE })
        assertEquals(records.size, DeviceSearch.corpusSummary(records).single().second)
    }

    @Test
    fun studyCardsJoinTheCorpusOnceThereAreAny() = runBlocking {
        val s = study()
        s.teach(library.index().first().id)
        val kinds = DeviceSearch.corpusSummary(DesktopSearchIndex.records(library, s)).map { it.first }
        assertTrue("study cards are not searchable", RecordKind.KNOWLEDGE in kinds)
    }

    /**
     * The ranking itself is already held by the mirrored `DeviceSearchTest`; what this adds is that it
     * behaves on the REAL corpus, where a common word appears in hundreds of guides.
     *
     * Each pair below is a query whose subject is unambiguous to a human reader, paired with a word the
     * winning guide's title must contain. They are not arbitrary: **without rarity weighting these exact
     * two queries lose** — "treating a snake bite" is won by *Depression: Understanding and Treating It*
     * (a common verb in a title outweighing the subject noun in a summary) and "tie a bowline" ties with
     * *Association Football Rules and Positions*. So this fails if the ranker ever regresses to plain
     * field weights.
     */
    @Test
    fun aRealQuestionFindsRelevantGuides() = runBlocking {
        val records = DesktopSearchIndex.records(library, study())
        listOf(
            "treating a snake bite" to listOf("wildlife", "snake"),
            "tie a bowline" to listOf("knot", "cordage"),
            // Purification is the one whose best answer does NOT contain the query's noun — the top hit is
            // "Distillation, Extraction & Purifying Liquids". Matching on "water" alone would have called
            // the right answer wrong, so the accepted words are what a purification guide is actually named.
            "how do I purify water" to listOf("purif", "water", "distill", "disinfect"),
        ).forEach { (query, expected) ->
            val hits = DeviceSearch.search(records, query)
            assertTrue("nothing found for \"$query\", which the library plainly covers", hits.isNotEmpty())
            val top = hits.first().title
            assertTrue(
                "\"$query\" was answered with \"$top\" — expected a title mentioning one of $expected",
                expected.any { top.contains(it, ignoreCase = true) },
            )
            // Every hit must be a guide that actually opens — a search result pointing at nothing is worse
            // than no result.
            hits.filter { it.kind == RecordKind.GUIDE }.forEach { assertNotNull(library.guide(it.id)) }
        }
    }

    @Test
    fun noKindIsAllowedToCrowdOutTheOthers() = runBlocking {
        val s = study()
        // Teach a few guides so there is a second kind with enough entries to compete.
        library.index().take(4).forEach { s.teach(it.id) }
        val hits = DeviceSearch.search(DesktopSearchIndex.records(library, s), "water")
        val guides = hits.count { it.kind == RecordKind.GUIDE }
        assertTrue("guides took $guides of ${hits.size} places", guides <= DeviceSearch.DEFAULT_PER_KIND)
    }
}
