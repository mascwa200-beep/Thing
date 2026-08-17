// MIRROR OF core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/LibraryConsultTest.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The logic that decides whether the library speaks, and how much of it.
 *
 * `firstSentences` shipped once with a defect — a lone full stop for an empty body — that reading it
 * did not reveal and running it did. It had no test until now.
 */
class LibraryConsultTest {

    private fun entry(
        title: String = "Water Purification",
        category: String = "Water",
        summary: String = "Making water safe to drink in the field.",
        headings: List<String> = listOf("Boiling", "Chemical treatment", "Filtration"),
    ) = GuideSearch.Entry(id = "g1", title = title, category = category, summary = summary, headings = headings)

    // ---- the relevance bar --------------------------------------------------------------------

    @Test
    fun aGuideIsTopicalWhenTheDistinctiveWordAppearsInAnyOfItsFields() {
        assertTrue(LibraryConsult.isTopical(entry(), "purification"))   // title
        assertTrue(LibraryConsult.isTopical(entry(), "water"))          // category
        assertTrue(LibraryConsult.isTopical(entry(), "drink"))          // summary
        assertTrue(LibraryConsult.isTopical(entry(), "filtration"))     // heading
    }

    /**
     * The failure this bar exists to prevent: the ranker returns its closest match no matter what, so
     * without this a question about something else gets a confident paragraph about water.
     */
    @Test
    fun aGuideThatMerelyRankedHighestIsNotTopical() {
        assertFalse(LibraryConsult.isTopical(entry(), "carburettor"))
        assertFalse(LibraryConsult.isTopical(entry(), "mortgage"))
    }

    @Test
    fun theCheckIsCaseBlindBecauseQuestionsAreNotCapitalised() {
        assertTrue(LibraryConsult.isTopical(entry(), "WATER"))
        assertTrue(LibraryConsult.isTopical(entry(title = "Knots and Cordage"), "cordage"))
    }

    /**
     * ⚠️ The bar was a substring test until running it over the real 581-guide library caught "car"
     * matching **Newborn Care Basics for New Parents**. A word inside another word is a coincidence,
     * not a subject.
     */
    @Test
    fun aWordInsideAnotherWordIsNotAMatch() {
        assertFalse(LibraryConsult.isTopical(entry(title = "Newborn Care Basics"), "car"))
        assertFalse(LibraryConsult.isTopical(entry(title = "Release Notes"), "lease"))
        assertFalse(LibraryConsult.isTopical(entry(summary = "Wrap it in tape."), "tap"))
        assertFalse(LibraryConsult.isTopical(entry(summary = "Meet at the station."), "ion"))
    }

    /** But a genuine stem relation still counts, exactly as it does when ranking. */
    @Test
    fun theStemRuleStillApplies() {
        assertTrue(LibraryConsult.isTopical(entry(title = "Knot Tying"), "knots"))
        assertTrue(LibraryConsult.isTopical(entry(title = "Waterproofing a Shelter"), "water"))
    }

    // ---- picking a section ----------------------------------------------------------------------

    @Test
    fun theSectionWhoseHeadingSharesTheQuestionsWordsWins() {
        val h = listOf("Boiling", "Chemical treatment", "Filtration")
        assertEquals(2, LibraryConsult.bestSection(h, "how do I improvise a filtration setup"))
        assertEquals(1, LibraryConsult.bestSection(h, "chemical dosing"))
    }

    /** No heading matches: the opening section is the guide's own orientation, so it is the answer. */
    @Test
    fun withNoHeadingMatchTheOpeningSectionIsUsed() {
        val h = listOf("Overview", "Chemical treatment")
        assertEquals(0, LibraryConsult.bestSection(h, "purify"))
        // Short words alone cannot pick a section — they match nearly any heading.
        assertEquals(0, LibraryConsult.bestSection(h, "is it ok"))
    }

    @Test
    fun aGuideWithNoSectionsHasNoBestSection() {
        assertNull(LibraryConsult.bestSection(emptyList(), "anything"))
    }

    // ---- quoting -------------------------------------------------------------------------------

    /** The defect that shipped: an empty body produced ".", which read aloud is nonsense. */
    @Test
    fun anEmptyOrBlankBodyQuotesNothingRatherThanAFullStop() {
        assertEquals("", LibraryConsult.firstSentences(""))
        assertEquals("", LibraryConsult.firstSentences("   \n  "))
    }

    @Test
    fun quotingStopsAtASentenceAndCollapsesTheWhitespaceOfAWrittenPage() {
        val body = """Boiling is the most reliable way to make water safe.
            Bring it to a rolling boil for one minute. Let it cool naturally; do not add ice."""
        assertEquals(
            "Boiling is the most reliable way to make water safe. Bring it to a rolling boil for one minute.",
            LibraryConsult.firstSentences(body, sentences = 2, maxChars = 320),
        )
        assertEquals(
            "Boiling is the most reliable way to make water safe.",
            LibraryConsult.firstSentences(body, sentences = 1, maxChars = 320),
        )
    }

    @Test
    fun aSentenceWithNoFullStopStillGetsOne() {
        assertEquals("Keep moving.", LibraryConsult.firstSentences("Keep moving", sentences = 2, maxChars = 320))
    }

    /**
     * One sentence longer than the cap has no break to cut at. A truncated real answer beats silence,
     * so the opening is given — and it must never exceed the cap it was given.
     */
    @Test
    fun aSingleOverlongSentenceIsTruncatedRatherThanDropped() {
        val long = "A".repeat(400) + ". Second sentence."
        val out = LibraryConsult.firstSentences(long, sentences = 2, maxChars = 320)
        assertEquals(320, out.length)
        assertTrue(out.all { it == 'A' })
    }

    @Test
    fun theQuoteNeverRunsPastTheCapItWasGiven() {
        val body = (1..40).joinToString(" ") { "Sentence number $it is here." }
        for (cap in listOf(40, 80, 160, 320)) {
            assertTrue(
                "cap $cap overrun",
                LibraryConsult.firstSentences(body, sentences = 2, maxChars = cap).length <= cap,
            )
        }
    }

    // ---- what the model and the reader are told ---------------------------------------------------

    @Test
    fun theGroundingBlockNamesTheSourceAndSaysWhenToIgnoreIt() {
        val block = LibraryConsult.groundingBlock("Water Purification ▸ Boiling", "Bring it to a boil.")
        assertTrue(block.contains("Water Purification ▸ Boiling"))
        assertTrue(block.contains("Bring it to a boil."))
        // The escape hatch matters more than the citation: a retrieved page that does not fit must be
        // dropped silently rather than worked into the answer.
        assertTrue(block.contains("ignore it entirely and never mention it"))
        assertTrue(block.contains("name the guide"))
    }

    @Test
    fun theGroundingBlockRespectsItsCap() {
        val body = "x".repeat(5_000)
        val block = LibraryConsult.groundingBlock("G", body, maxChars = 100)
        assertFalse(block.contains("x".repeat(101)))
        assertTrue(block.contains("x".repeat(100)))
    }

    @Test
    fun theCitationPointsAtSomethingTheReaderCanGoAndOpen() {
        val c = LibraryConsult.citation("Water Purification")
        assertTrue(c.contains("Water Purification"))
        assertTrue(c.contains("library"))
        // It is appended to a quote, so it has to begin with a separator rather than run words together.
        assertTrue(c.startsWith(" "))
    }
}
