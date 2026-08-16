// MIRROR OF core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/GuideSearchTest.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import dev.mascwa.pulse.desktop.telemetry.GuideSearch.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ranking rules for finding a library guide from a question asked in plain words.
 *
 * Every expected score below is computed from the declared weights and written out in the comment
 * above it, rather than read back off the implementation.
 */
class GuideSearchTest {

    private fun e(
        id: String, title: String, category: String = "", summary: String = "",
        headings: List<String> = emptyList(),
    ) = Entry(id, title, category, summary, headings)

    // ---- tokenising ------------------------------------------------------------------------------

    @Test
    fun aQuestionIsReducedToTheWordsWorthMatching() {
        // "how do i" are all stopwords; "purify" and "water" are the question.
        assertEquals(listOf("purify", "water"), GuideSearch.tokens("How do I purify water?"))
    }

    @Test
    fun punctuationAndCaseAndDuplicatesAreStripped() {
        assertEquals(listOf("water", "safe"), GuideSearch.tokens("WATER -- water, is it safe?!"))
    }

    /** A subject word that merely looks generic is kept: in this library, water IS the subject. */
    @Test
    fun domainWordsSurviveEvenWhenTheyFeelCommon() {
        assertTrue("water" in GuideSearch.tokens("is the water safe"))
        assertTrue("fire" in GuideSearch.tokens("how to make a fire"))
    }

    /** A query of nothing but stopwords still searches for something rather than matching everything. */
    @Test
    fun anAllStopwordQueryFallsBackToItsRawWords() {
        val t = GuideSearch.tokens("what do I do")
        assertTrue("must not be empty, or the caller would rank the whole library: $t", t.isNotEmpty())
    }

    // ---- word matching ---------------------------------------------------------------------------

    @Test
    fun anExactWordOutscoresASharedStemWhichOutscoresNothing() {
        assertEquals(2, GuideSearch.wordMatch("water", "water"))
        // Both ends of the stem relation, which is what carries plurals without a stemmer.
        assertEquals(1, GuideSearch.wordMatch("knots", "knot"))
        assertEquals(1, GuideSearch.wordMatch("knot", "knots"))
        assertEquals(0, GuideSearch.wordMatch("water", "fire"))
    }

    /** Short words must not stem, or "ice" would match "icing" and half the cookery shelf. */
    @Test
    fun shortWordsDoNotStem() {
        assertEquals(0, GuideSearch.wordMatch("ice", "icy"))
    }

    @Test
    fun aFieldMatchesOnItsBestWord() {
        assertEquals(2, GuideSearch.fieldMatch("Purifying Water in the Field", "water"))
        assertEquals(1, GuideSearch.fieldMatch("Purifying Water in the Field", "purify"))
        assertEquals(0, GuideSearch.fieldMatch("Purifying Water in the Field", "bowline"))
        assertEquals(0, GuideSearch.fieldMatch("", "water"))
    }

    // ---- the load-bearing rule -------------------------------------------------------------------

    /**
     * A guide answering more of the question beats a guide repeating one word of it.
     *
     * Worked through, for tokens [purify, water]:
     *   A: purify→title stem 1×10 = 10; water→title 2×10 + category 2×4 + summary 2×2 = 32;
     *      matched 2 → +2×12.  Total 10+32+24 = 66.
     *   B: purify→nothing; water→title 2×10 + headings 2×5 + category 2×4 + summary 2×2 = 42;
     *      matched 1 → +12.    Total 42+12 = 54.
     */
    @Test
    fun coveringMoreOfTheQuestionBeatsRepeatingOneWordOfIt() {
        val a = e("a", "Purifying Water in the Field", "Water", "Making water safe to drink.")
        val b = e("b", "Water, Water, Water", "Water", "water water water", listOf("Water"))
        val t = GuideSearch.tokens("purify water")

        // Unit weights isolate the field/coverage arithmetic from corpus rarity.
        val w = t.associateWith { 1.0 }
        assertEquals(66.0, GuideSearch.score(a, t, w), 0.001)
        assertEquals(54.0, GuideSearch.score(b, t, w), 0.001)
        assertEquals(listOf("a", "b"), GuideSearch.rank(listOf(b, a), "purify water").map { it.entry.id })
    }

    @Test
    fun aTitleCarriesMoreWeightThanASummary() {
        val titled = e("t", "Water Purification", "Misc", "Unrelated text.")
        val mentioned = e("m", "Camp Cookery", "Misc", "You will need water purification tablets.")
        val t = GuideSearch.tokens("water purification")
        val w = t.associateWith { 1.0 }
        assertTrue(GuideSearch.score(titled, t, w) > GuideSearch.score(mentioned, t, w))
    }

    // ---- rarity ----------------------------------------------------------------------------------

    /**
     * A rare subject word must beat a common one, wherever each happens to sit.
     *
     * The guard on a defect the field weights alone produced against the real library: *"treating a
     * snake bite"* returned *Depression: Understanding and Treating It* ahead of *Wildlife & Insects*,
     * because a common verb in a title outweighed the actual subject noun in a summary. Modelled here
     * — "treating" is everywhere, "snake" is in one guide — and it fails without rarity weighting.
     */
    @Test
    fun aRareSubjectWordOutweighsACommonWordInABetterField() {
        val common = (1..20).map { e("c$it", "Treating Something $it", "Medicine", "treating things") }
        val subject = e("s", "Wildlife and Insects", "Hazards", "Bites from a snake and what to do.")
        val entries = common + subject

        assertEquals("s", GuideSearch.rank(entries, "treating a snake bite").first().entry.id)
        // And the reason: "snake" is in one guide of 21, "treating" in twenty.
        assertEquals(1, GuideSearch.documentFrequency(entries, "snake"))
        assertEquals(20, GuideSearch.documentFrequency(entries, "treating"))
        assertTrue(GuideSearch.idf(entries, "snake") > GuideSearch.idf(entries, "treating"))
    }

    /** A word in every guide still counts for something, rather than being erased outright. */
    @Test
    fun aUniversalWordIsWeakButNotWorthless() {
        val entries = (1..10).map { e("g$it", "Water Guide $it", "Water") }
        assertTrue(GuideSearch.idf(entries, "water") > 0.0)
        assertEquals(0.0, GuideSearch.idf(emptyList(), "water"), 0.001)
    }

    // ---- ranking ---------------------------------------------------------------------------------

    /** Identical scores must not reshuffle between identical queries — that reads as a malfunction. */
    @Test
    fun equalScoresAreBrokenByTitleSoTheOrderIsStable() {
        val x = e("x", "Water Zebra", "Water")
        val y = e("y", "Water Antelope", "Water")
        val w = mapOf("water" to 1.0)
        assertEquals(
            GuideSearch.score(x, listOf("water"), w), GuideSearch.score(y, listOf("water"), w), 0.001,
        )
        assertEquals(
            listOf("y", "x"), // "Water Antelope" sorts before "Water Zebra"
            GuideSearch.rank(listOf(x, y), "water").map { it.entry.id },
        )
    }

    @Test
    fun nothingRelevantYieldsNothingRatherThanTheWholeLibrary() {
        val only = e("a", "Tying a Bowline", "Knots")
        assertEquals(emptyList<String>(), GuideSearch.rank(listOf(only), "photosynthesis").map { it.entry.id })
        assertEquals(emptyList<String>(), GuideSearch.rank(listOf(only), "   ").map { it.entry.id })
        assertEquals(emptyList<String>(), GuideSearch.rank(emptyList(), "bowline").map { it.entry.id })
    }

    @Test
    fun theLimitIsHonouredAndAtLeastOneResultIsReturned() {
        val many = (1..10).map { e("g$it", "Water Guide $it", "Water") }
        assertEquals(3, GuideSearch.rank(many, "water", limit = 3).size)
        assertEquals(1, GuideSearch.rank(many, "water", limit = 0).size)
    }

    @Test
    fun aHitReportsHowMuchOfTheQuestionItAnswered() {
        val a = e("a", "Purifying Water", "Water")
        val hit = GuideSearch.rank(listOf(a), "purify water boiling").first()
        assertEquals(2, hit.matched) // purify + water; "boiling" appears nowhere
    }

    // ---- the body-scan decision -------------------------------------------------------------------

    /**
     * A body scan reads every shard, so it is only worth paying for the rarest token — and only when
     * that token is rare enough to be a real subject.
     */
    @Test
    fun theRarestTokenIsTheOneWorthScanningFor() {
        val entries = (1..10).map { e("w$it", "Water Guide $it", "Water") } +
            e("k", "Tying a Bowline", "Knots")
        assertEquals("bowline", GuideSearch.distinctiveToken(entries, "water bowline"))
    }

    @Test
    fun aQueryOfOnlyCommonWordsIsNotWorthAScan() {
        val entries = (1..30).map { e("w$it", "Water Guide $it", "Water") }
        assertNull(GuideSearch.distinctiveToken(entries, "water", maxEntries = 12))
        assertNull(GuideSearch.distinctiveToken(emptyList(), "water"))
        assertNull(GuideSearch.distinctiveToken(entries, "   "))
    }
}
