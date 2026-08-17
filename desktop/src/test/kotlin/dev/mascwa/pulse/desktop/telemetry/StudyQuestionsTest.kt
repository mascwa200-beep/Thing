// MIRROR OF core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/StudyQuestionsTest.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import dev.mascwa.pulse.desktop.telemetry.StudyQuestions.QuestionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extraction has one job: never produce a question the reader cannot answer.
 *
 * Four of these pin defects found by running the extractor over all 8,277 real guide sections rather
 * than by reading it — the gap fusing onto the next word, an answer carrying trailing punctuation, a
 * digit blanked out of a chemical formula, and the gap landing on a different character from the one
 * the answer names.
 */
class StudyQuestionsTest {

    private fun q(sentence: String) = StudyQuestions.cloze(sentence, "g1", "A Guide", "A Section")

    // ---- defects the real corpus exposed ---------------------------------------------------------

    /** "capped near ______micrometres" — a unitless number swallowed the space after it. */
    @Test
    fun theGapNeverFusesOntoTheFollowingWord() {
        val c = q("Resolution is capped near 0.2 micrometres by the wavelength of the visible light used.")
        assertNotNull(c)
        assertFalse("gap fused to the next word: ${c!!.prompt}", c.prompt.contains(StudyQuestions.GAP + "micro"))
        assertTrue(c.prompt.contains(StudyQuestions.GAP + " micrometres") || c.answer.contains("micrometres"))
    }

    /** "pH 4.6, or frozen" gave the answer "4.6," — a number must end in a digit. */
    @Test
    fun anAnswerNeverCarriesTrailingPunctuation() {
        val c = q("Homemade garlic in oil must be acidified to below pH 4.6, or else frozen until it is used.")
        assertNotNull(c)
        assertFalse("answer has punctuation: '${c!!.answer}'", c.answer.trim().last() in ".,;:")
        assertEquals("4.6", c.answer.trim())
    }

    /** "follows the formula CnH2n+2" blanked a digit out of the formula. */
    @Test
    fun aDigitInsideATokenIsNotBlankable() {
        assertTrue(StudyQuestions.blankableTerms("A saturated hydrocarbon follows the formula CnH2n+2 exactly.").isEmpty())
        assertTrue(StudyQuestions.blankableTerms("The compound H2O is water and CO2 is carbon dioxide here.").isEmpty())
    }

    /**
     * The subtle one: blanking by string search finds the first textual occurrence, which need not be
     * the occurrence the pattern matched — so the gap and the answer describe different characters.
     */
    @Test
    fun theGapLandsOnTheTermTheAnswerNames() {
        val s = "Version 2 of the standard requires the sample to rest for 2 hours before testing."
        // Two blankable terms, so this is refused outright rather than guessed at.
        assertNull(q(s))

        val one = "The sample must be left to rest for 2 hours before any testing is carried out on it."
        val c = one.let { q(it) }
        assertNotNull(c)
        // Whatever was blanked, putting the answer back must reconstruct the original sentence.
        assertEquals(one, c!!.prompt.replace(StudyQuestions.GAP, c.answer))
    }

    // ---- the ambiguity rule ------------------------------------------------------------------------

    @Test
    fun aSentenceWithTwoNumbersIsRefusedBecauseTheGapWouldBeAmbiguous() {
        assertNull(q("Bring it to a rolling boil for 1 minute, or 3 minutes above 2000 metres of elevation."))
    }

    @Test
    fun aSentenceWithNoNumberHasNothingUnambiguousToRemove() {
        assertNull(q("Keep the wound clean and covered, and watch it carefully for any sign of infection."))
    }

    @Test
    fun sentencesTooShortOrTooLongAreRefused() {
        assertNull(q("Boil 1 minute."))
        assertNull(q("It must rest 3 hours " + "and then be inspected carefully by a competent person ".repeat(6)))
    }

    @Test
    fun aQuestionOrABulletIsNotTurnedIntoACloze() {
        assertNull(q("Did the sample really need to rest for 2 hours before it was tested at all?"))
        assertNull(q("- Leave the mixture to rest for 2 hours before testing it in the usual way."))
    }

    /** Blanking the opening term leaves a sentence starting with a gap, which reads as broken text. */
    @Test
    fun theOpeningTermIsNotBlanked() {
        assertNull(q("2 hours is the minimum resting time before the sample can be tested properly."))
    }

    // ---- what a good cloze looks like -----------------------------------------------------------------

    @Test
    fun aCleanFactBecomesAnAnswerableGap() {
        val c = q("The sample should be left to rest for 3 hours before it is tested in the laboratory.")
        assertNotNull(c)
        assertEquals(QuestionKind.CLOZE, c!!.kind)
        assertEquals("3 hours", c.answer)
        assertTrue(c.prompt.contains(StudyQuestions.GAP))
        assertFalse("the answer must not still be visible", c.prompt.contains("3 hours"))
    }

    @Test
    fun unitsTemperaturesAndPercentagesAllSurvive() {
        assertEquals("40°C", q("Hold the water at 40°C for the whole of the resting period before use.")?.answer)
        assertEquals("15%", q("Reduce the moisture content to 15% before the timber is used in construction.")?.answer)
        assertEquals("2,000 metres", q("The treatment changes above 2,000 metres because water boils cooler there.")?.answer)
    }

    // ---- recall, the always-available fallback --------------------------------------------------------

    @Test
    fun everySectionIsTeachableEvenWithNoCleanCloze() {
        val body = "Keep the wound clean and covered. Watch it for redness, swelling or heat."
        val qs = StudyQuestions.forSection("g1", "First Aid", "Wounds", body)
        assertEquals(1, qs.size)
        assertEquals(QuestionKind.RECALL, qs.single().kind)
        assertTrue(qs.single().prompt.contains("First Aid"))
        assertTrue(qs.single().prompt.contains("Wounds"))
        // The answer is the passage to check yourself against, not an invented one.
        assertTrue(qs.single().answer.startsWith("Keep the wound clean"))
    }

    @Test
    fun aSectionWithFactsLeadsWithTheCheckableQuestions() {
        val body = "The sample should be left to rest for 3 hours before testing in the lab. " +
            "Hold the water at 40°C for the whole of the resting period before use. " +
            "Keep everything covered and clean while you wait for it."
        val qs = StudyQuestions.forSection("g1", "A Guide", "A Section", body, max = 3)
        assertEquals(3, qs.size)
        assertEquals(listOf(QuestionKind.CLOZE, QuestionKind.CLOZE, QuestionKind.RECALL), qs.map { it.kind })
    }

    @Test
    fun anEmptySectionYieldsNothingRatherThanAnEmptyQuestion() {
        assertTrue(StudyQuestions.forSection("g1", "G", "H", "").isEmpty())
        assertTrue(StudyQuestions.forSection("g1", "G", "H", "   ").isEmpty())
        assertTrue(StudyQuestions.forSection("g1", "G", "", "Some real body text goes here.").isEmpty())
    }

    // ---- identity ---------------------------------------------------------------------------------------

    /** Ids key review history, so the same passage must produce the same id on every run. */
    @Test
    fun idsAreStableAndDistinguishTheTwoKinds() {
        val body = "The sample should be left to rest for 3 hours before testing in the lab."
        val a = StudyQuestions.forSection("g1", "G", "H", body)
        val b = StudyQuestions.forSection("g1", "G", "H", body)
        assertEquals(a.map { it.id }, b.map { it.id })
        assertEquals(a.size, a.map { it.id }.distinct().size)
        assertTrue(a.any { it.id.startsWith("cloze:") })
        assertTrue(a.any { it.id.startsWith("recall:") })
    }

    @Test
    fun sentenceSplittingHandlesRealProseAndBlankInput() {
        assertEquals(
            listOf("Boil the water.", "Let it cool.", "Do not add ice."),
            StudyQuestions.sentences("Boil the water.  Let it cool.\n Do not add ice."),
        )
        assertTrue(StudyQuestions.sentences("").isEmpty())
        assertTrue(StudyQuestions.sentences("   \n ").isEmpty())
    }
}
