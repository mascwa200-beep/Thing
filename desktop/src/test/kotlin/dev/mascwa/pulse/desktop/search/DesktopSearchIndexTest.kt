package dev.mascwa.pulse.desktop.search

import dev.mascwa.pulse.desktop.library.LibraryRepository
import dev.mascwa.pulse.desktop.notes.DiaryStore
import dev.mascwa.pulse.desktop.notes.NotesStore
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

    /**
     * Empty notes and diary stores, so a test about the library or the deck is not also a test about
     * them. ⚠️ A fresh path each call, or two tests in one class would share a file and a note
     * written by one would turn up in the other's corpus.
     */
    private var scratch = 0
    private fun notes() = NotesStore(path = tmp.root.toPath().resolve("notes-${scratch++}.json"))
    private fun diary() = DiaryStore(path = tmp.root.toPath().resolve("diary-${scratch++}.json"))

    @Test
    fun theWholeLibraryIsSearchable() = runBlocking {
        val records = DesktopSearchIndex.records(library, study(), notes(), diary())
        assertTrue("only ${records.size} records", records.size >= 500)
        assertTrue(records.all { it.kind == RecordKind.GUIDE })
        assertEquals(records.size, DeviceSearch.corpusSummary(records).single().second)
    }

    @Test
    fun studyCardsJoinTheCorpusOnceThereAreAny() = runBlocking {
        val s = study()
        s.teach(library.index().first().id)
        val kinds = DeviceSearch.corpusSummary(DesktopSearchIndex.records(library, s, notes(), diary())).map { it.first }
        assertTrue("study cards are not searchable", RecordKind.STUDY in kinds)
        // ⚠️ The second half is what makes this a gate rather than a restatement. This assertion read
        // `KNOWLEDGE in kinds` under that same message for as long as the mislabel shipped — the test
        // and its own failure text disagreeing about what the kind was. A card must not come back as a
        // document, and a machine with no ingested documents must not report holding any.
        assertTrue("a study card is being reported as a document", RecordKind.KNOWLEDGE !in kinds)
    }

    /**
     * A deck saved on disk reaches the index even though nothing has touched the store yet.
     *
     * ⚠️ **This is the case the test above structurally cannot reach, and it was a live defect.**
     * That one calls `teach` first, which loads the store as a side effect — so its fixture AVOIDS
     * the branch, which is this repository's third recorded way a green test proves nothing. The
     * index read `study.items.value`, a flow that holds an empty list until something causes a load,
     * so opening SEARCH first thing after launch found no cards at all while the deck sat on the
     * disk. Measured at the time: five cards warm, ZERO cold, over 3,328 bytes of saved deck.
     *
     * Two stores over one path is what makes it a real test rather than a restatement: the second
     * stands in for a fresh process, and nothing may touch it before the index is asked.
     */
    @Test
    fun aSavedDeckIsSearchableInAFreshProcess() = runBlocking {
        val path = tmp.root.toPath().resolve("cold-study.json")

        val warm = StudyStore(library, path = path)
        warm.teach(library.index().first().id)
        warm.flushNow()
        val taught = DesktopSearchIndex.records(library, warm, notes(), diary()).count { it.kind == RecordKind.STUDY }
        assertTrue("the fixture taught no cards, so the cold half proves nothing", taught > 0)

        val cold = StudyStore(library, path = path)
        val found = DesktopSearchIndex.records(library, cold, notes(), diary()).count { it.kind == RecordKind.STUDY }
        assertEquals("a saved deck did not reach a fresh process's search index", taught, found)
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
        val records = DesktopSearchIndex.records(library, study(), notes(), diary())
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

    /**
     * What the user wrote themselves is searchable, in a fresh process.
     *
     * ⚠️ This was missing entirely, and the file's own KDoc said why in a way that was false: it
     * claimed the phone searches "notes, diary, … none of those exist on the desktop". Both exist
     * here, with stores, screens and directory entries — they simply were not indexed, so the two
     * kinds of thing somebody TYPED could not be found on a machine whose search screen exists to
     * find things.
     *
     * The second store over each path is the same fresh-process check the deck gets above. Both
     * stores expose a `load()` today, so it passes by construction — which is exactly the point of
     * pinning it: the deck did too, until somebody reached for the flow instead.
     */
    @Test
    fun whatTheUserWroteIsSearchableInAFreshProcess() = runBlocking {
        val notesPath = tmp.root.toPath().resolve("written-notes.json")
        val diaryPath = tmp.root.toPath().resolve("written-diary.json")

        NotesStore(path = notesPath).run {
            add("Kayak portage route", "Put in below the weir, portage at the mill.", "Trips")
            flushNow()
        }
        DiaryStore(path = diaryPath).run {
            add("Thursday", "Walked the towpath as far as the aqueduct.", "")
            flushNow()
        }

        val records = DesktopSearchIndex.records(
            library, study(), NotesStore(path = notesPath), DiaryStore(path = diaryPath),
        )
        val kinds = DeviceSearch.corpusSummary(records).map { it.first }
        assertTrue("notes are not searchable", RecordKind.NOTE in kinds)
        assertTrue("diary entries are not searchable", RecordKind.DIARY in kinds)

        // ⚠️ Finding the RECORD is not finding the ENTRY. "Aqueduct" appears in the bundled library,
        // so the ranker has real competition here and this asserts the written entry wins its own
        // distinctive word rather than merely being present in the corpus somewhere.
        val hit = DeviceSearch.search(records, "portage").firstOrNull()
        assertNotNull("a note could not be found by a word only it contains", hit)
        assertEquals(RecordKind.NOTE, hit!!.kind)
        assertEquals("Kayak portage route", hit.title)
    }

    @Test
    fun noKindIsAllowedToCrowdOutTheOthers() = runBlocking {
        val s = study()
        // Teach a few guides so there is a second kind with enough entries to compete.
        library.index().take(4).forEach { s.teach(it.id) }
        val hits = DeviceSearch.search(DesktopSearchIndex.records(library, s, notes(), diary()), "water")
        val guides = hits.count { it.kind == RecordKind.GUIDE }
        assertTrue("guides took $guides of ${hits.size} places", guides <= DeviceSearch.DEFAULT_PER_KIND)
    }
}
