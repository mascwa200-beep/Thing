package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizBuilderTest {

    private fun cloze(answer: String, heading: String = "Boiling") = StudyQuestions.Question(
        id = "cloze:water:$heading:$answer",
        kind = StudyQuestions.QuestionKind.CLOZE,
        prompt = "Hold a rolling boil for ${StudyQuestions.GAP} to make water safe.",
        answer = answer,
        guideId = "water",
        guideTitle = "Water",
        heading = heading,
    )

    // ---- term arithmetic ----------------------------------------------------------------------------

    @Test
    fun aTermSplitsIntoItsValueAndItsUnit() {
        assertEquals(3.0 to "minutes", QuizBuilder.split("3 minutes"))
        assertEquals(40.0 to "°C", QuizBuilder.split("40°C"))
        assertEquals(15.0 to "%", QuizBuilder.split("15%"))
        // Thousands separators are presentation, not value.
        assertEquals(2000.0 to "metres", QuizBuilder.split("2,000 metres"))
        assertNull(QuizBuilder.split("clean water"))
    }

    /**
     * The load-bearing rule for fairness. "10 minutes" beside "10 °C" could be eliminated by anyone who
     * had never read the guide, which makes the item measure nothing.
     */
    @Test
    fun everyDistractorCarriesTheSameUnitAsTheAnswer() {
        val pool = listOf("5 minutes", "20 minutes", "40°C", "15%", "2 litres", "1 minute")
        val out = QuizBuilder.distractors("3 minutes", pool, want = 3)
        assertEquals(3, out.size)
        assertTrue(out.toString(), out.all { it.endsWith("minutes") || it.endsWith("minute") })
    }

    @Test
    fun theNearestGenuineValuesArePreferred() {
        val pool = listOf("60 minutes", "5 minutes", "45 minutes", "1 minutes")
        val out = QuizBuilder.distractors("3 minutes", pool, want = 2)
        // 5 and 1 are closest to 3; 45 and 60 are not offered while better ones exist.
        assertEquals(listOf("5 minutes", "1 minutes"), out)
    }

    /** Offering the answer again under another spelling is the two-defensible-answers failure. */
    @Test
    fun aValueEqualToTheAnswerIsNeverOffered() {
        val out = QuizBuilder.distractors("3 minutes", listOf("3 minutes", "3.0 minutes", "8 minutes"), want = 3)
        assertTrue(out.toString(), out.none { QuizBuilder.split(it)!!.first == 3.0 })
    }

    /**
     * Found by generating items from the real library, not from a fixture. "Below pH ______ botulinum
     * cannot grow" (4.6) was offered **0.91 and 0.95** — water-activity figures from a nearby paragraph.
     * A bare number says nothing about what it measures, so unitless options additionally have to sit in
     * the same magnitude band.
     */
    @Test
    fun unitlessValuesOfADifferentMagnitudeAreNotMixedIn() {
        val pool = listOf("0.91", "0.95", "3.6", "4.0", "13.8")
        val out = QuizBuilder.distractors("4.6", pool, want = 4)
        assertTrue("water activity offered as pH: $out", out.none { it == "0.91" || it == "0.95" })
        assertTrue("plausible pH values were dropped: $out", out.any { it == "3.6" || it == "4.0" })
    }

    /**
     * Also from the corpus: the same pH question withheld 4.6 while listing **4.0**. Marking someone
     * wrong for not answering "none of these" there would be indefensible.
     */
    @Test
    fun withholdingTheAnswerRequiresTheOptionsToBeClearlyDifferentFromIt() {
        val pool = listOf("4.5", "4.55", "4.7", "1.2", "9.2", "13.8")
        val item = (0..40).firstNotNullOfOrNull { seed ->
            QuizBuilder.build(cloze("4.6"), pool, seed)?.takeIf { it.format == QuizBuilder.Format.NONE_OF_THESE }
        }
        assertNotNull("the rationed format never appeared", item)
        val offered = item!!.choices.filter { it.text != QuizBuilder.NONE_OF_THESE_TEXT }
        assertTrue(
            "an option close enough to be mistaken for the withheld answer: ${offered.map { it.text }}",
            offered.none { QuizBuilder.split(it.text)!!.first in 4.0..5.2 },
        )
    }

    @Test
    fun aThinPoolIsFilledWithPerturbationsThatKeepTheUnit() {
        val out = QuizBuilder.distractors("4 minutes", emptyList(), want = 3)
        assertEquals(3, out.size)
        assertTrue(out.toString(), out.all { it.endsWith("minutes") })
        assertTrue(out.toString(), out.none { QuizBuilder.split(it)!!.first == 4.0 })
    }

    // ---- items --------------------------------------------------------------------------------------

    /** The invariant the whole file exists to keep, asserted across every format it can produce. */
    @Test
    fun everyItemHasExactlyOneDefensibleAnswer() {
        val pool = listOf("1 minutes", "5 minutes", "10 minutes", "20 minutes", "30 minutes")
        var seen = 0
        for (seed in 0..40) {
            val item = QuizBuilder.build(cloze("3 minutes"), pool, seed) ?: continue
            seen++
            assertEquals(item.toString(), 1, item.choices.count { it.correct })
            assertTrue(item.correctIndex >= 0)
            // No duplicate option text — two identical options make one of them unanswerable.
            assertEquals(item.choices.size, item.choices.map { it.text }.distinct().size)
        }
        assertTrue("no items were produced at all", seen > 0)
    }

    /** With the answer withheld, none of the listed values may be it. */
    @Test
    fun theNoneOfTheseFormWithholdsTheAnswerEntirely() {
        val pool = listOf("1 minutes", "5 minutes", "10 minutes", "20 minutes", "30 minutes")
        val item = (0..40).firstNotNullOfOrNull { seed ->
            QuizBuilder.build(cloze("3 minutes"), pool, seed)?.takeIf { it.format == QuizBuilder.Format.NONE_OF_THESE }
        }
        assertNotNull("the rationed format never appeared", item)
        assertTrue(item!!.choices.none { it.text == "3 minutes" })
        assertEquals(QuizBuilder.NONE_OF_THESE_TEXT, item.choices.first { it.correct }.text)
    }

    /** A degraded item scores as though it measured something. Refusing is the honest outcome. */
    @Test
    fun anItemThatCannotBeMadeFairlyIsRefused() {
        assertNull(QuizBuilder.build(cloze("clean water"), listOf("5 minutes"), seed = 1))
        val recall = StudyQuestions.Question(
            id = "recall:water:Boiling",
            kind = StudyQuestions.QuestionKind.RECALL,
            prompt = "What does the section say?",
            answer = "…",
            guideId = "water", guideTitle = "Water", heading = "Boiling",
        )
        assertNull(QuizBuilder.build(recall, listOf("5 minutes"), seed = 1))
    }

    /** Answer-in-a-fixed-slot would make every score meaningless. */
    @Test
    fun theCorrectAnswerMovesAround() {
        val pool = listOf("1 minutes", "5 minutes", "10 minutes", "20 minutes")
        val positions = (0..40)
            .mapNotNull { QuizBuilder.build(cloze("3 minutes"), pool, it) }
            .filter { it.format == QuizBuilder.Format.STANDARD }
            .map { it.correctIndex }
            .toSet()
        assertTrue("correct answer only ever appeared at $positions", positions.size >= 3)
    }

    @Test
    fun theExplanationRestoresTheSentenceTheFactCameFrom() {
        val item = QuizBuilder.build(cloze("3 minutes"), listOf("1 minutes", "5 minutes", "9 minutes"), seed = 1)
        assertNotNull(item)
        assertTrue(item!!.explanation, item.explanation.contains("3 minutes"))
        assertFalse(item.explanation.contains(StudyQuestions.GAP))
    }

    /**
     * Over the real library both quiz forms together answered every single draw, so open recall had
     * vanished — a bad trade made silently, since producing an answer from nothing is stronger practice
     * than recognising it among four. A minority of reviews are rationed back to it.
     */
    @Test
    fun aMinorityOfReviewsAreAskedWithNoOptionsAtAll() {
        val open = (0..99).count { QuizBuilder.asksOpenRecall(it) }
        assertTrue("open recall never comes up: $open of 100", open in 10..40)
        // Negative seeds are ordinary here — the store derives them from a String hash.
        assertTrue((-50..-1).any { QuizBuilder.asksOpenRecall(it) })
    }

    // ---- statements ---------------------------------------------------------------------------------

    private val truths = listOf(
        "Boiling water at a rolling boil kills the organisms that cause most waterborne illness.",
        "Cloudy water should be filtered through cloth before it is boiled or treated.",
        "Water that has been boiled and cooled can be stored in a clean sealed container.",
    )
    private val foreign = listOf(
        "A bowline forms a fixed loop that will not slip under load and unties after tension.",
        "Frostbite is rewarmed in tepid water, never rubbed and never held against a heat source.",
        "Signal mirrors aim by sighting through the hole and walking the reflection onto the target.",
    )

    @Test
    fun aStatementItemAsksWhichSentenceBelongs() {
        val item = QuizBuilder.statementItem("water", "Water", "Boiling", truths, foreign, seed = 1)
        assertNotNull(item)
        assertEquals(1, item!!.choices.count { it.correct })
        assertTrue(item.choices.first { it.correct }.text in truths)
    }

    /**
     * The bug this test exists for: in "which is NOT said", the wrong answers must be statements the
     * section genuinely makes. Filling them with more borrowed sentences would leave three equally
     * defensible answers — an item that punishes whoever reads it most carefully.
     */
    @Test
    fun theNegativeFormPutsTheOddOneOutAloneAgainstRealStatements() {
        val item = (0..20).firstNotNullOfOrNull { seed ->
            QuizBuilder.statementItem("water", "Water", "Boiling", truths, foreign, seed)
                ?.takeIf { it.format == QuizBuilder.Format.NEGATIVE }
        }
        assertNotNull("the negative form never appeared", item)
        assertEquals(1, item!!.choices.count { it.correct })
        assertTrue("the answer must be the foreign sentence", item.choices.first { it.correct }.text in foreign)
        // Everything else must be genuinely from this section.
        assertTrue(item.choices.filter { !it.correct }.all { it.text in truths })
    }

    /** A borrowed sentence about the same subject could well also be true here. */
    @Test
    fun aDistractorSharingSubjectMatterWithTheSectionIsRejected() {
        val overlapping = listOf(
            "Boiling water for longer than necessary simply wastes fuel without improving safety.",
            "A bowline forms a fixed loop that will not slip under load and unties after tension.",
            "Signal mirrors aim by sighting through the hole and walking the reflection onto the target.",
            "Frostbite is rewarmed in tepid water, never rubbed and never held against a heat source.",
        )
        val item = QuizBuilder.statementItem("water", "Water", "Boiling", truths, overlapping, seed = 1)
        assertNotNull(item)
        assertTrue(
            "a sentence about boiling water was offered against a section about boiling water",
            item!!.choices.none { it.text.contains("Boiling water for longer") },
        )
    }

    @Test
    fun tooLittleMaterialIsRefusedRatherThanPadded() {
        assertNull(QuizBuilder.statementItem("water", "Water", "Boiling", truths, foreign.take(1), seed = 1))
        assertNull(QuizBuilder.statementItem("water", "Water", "Boiling", emptyList(), foreign, seed = 1))
    }
}
