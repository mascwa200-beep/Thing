package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asking about a procedure — the most checkable thing the corpus holds, and until now the only
 * structured content it never asked about at all.
 */
class ProcedureQuestionTest {

    private val steps = listOf(
        "Put on goggles and gloves.",
        "Weigh the empty flask and note its mass.",
        "Add 25 ml of vinegar to the flask.",
        "Fit the balloon over the neck and seal it.",
        "Tip the bicarbonate in and start the timer.",
    )

    private fun make(list: List<String> = steps) =
        StudyQuestions.procedure("chem", "Chemistry", "Step-by-step", list)

    // ---- the safety rule ----------------------------------------------------------------------------

    /**
     * ⚠️ **The load-bearing test.** Every option a learner is shown must be a verbatim step of the
     * same procedure.
     *
     * A wrong option is then a true instruction in the wrong position — somebody who misreads one has
     * still read something correct. A synthesised "plausible" step would put invented instructions in
     * front of a reader working through CPR or water purification, which is the one failure mode this
     * whole feature must not have.
     */
    @Test
    fun everyOfferedOptionIsAVerbatimStepOfTheSameProcedure() {
        val verbatim = steps.toSet()
        var built = 0
        for (q in make()) {
            for (seed in 0 until 12) {
                val item = QuizBuilder.build(q, pool = listOf("INVENTED", "42 ml", "boil it"), seed = seed)
                    ?: continue
                built++
                item.choices.forEach {
                    assertTrue("offered an option that is not a real step: '${it.text}'", it.text in verbatim)
                }
                assertEquals(1, item.choices.count { it.correct })
                assertEquals(StudyQuestions.STANDARD_DISTRACTORS + 1, item.choices.size)
                // ⚠️ The step in the prompt must never also be an option — a free elimination.
                // Found by reading real generated questions, not by any fixture.
                val shown = steps.first { it in q.prompt }
                assertTrue("offered the step already shown in the prompt",
                    item.choices.none { it.text == shown })
            }
        }
        assertTrue("nothing was built, so the assertions above proved nothing", built > 0)
    }

    /**
     * ⚠️ The caller's distractor pool is not consulted for a procedure, and that is what makes the
     * rule above structural rather than remembered. Passing a pool full of garbage changes nothing.
     */
    @Test
    fun theCallersPoolCannotReachAProcedureQuestion() {
        val q = make().first()
        val withGarbage = QuizBuilder.build(q, pool = List(50) { "FABRICATED STEP $it" }, seed = 3)!!
        val withNothing = QuizBuilder.build(q, pool = emptyList(), seed = 3)!!
        assertEquals(withNothing.choices, withGarbage.choices)
        assertTrue(withGarbage.choices.none { it.text.startsWith("FABRICATED") })
    }

    // ---- what gets asked ----------------------------------------------------------------------------

    @Test
    fun theAnswerIsTheStepThatActuallyComesNext() {
        val qs = make()
        assertTrue(qs.isNotEmpty())
        qs.forEach { q ->
            val at = steps.indexOf(q.answer)
            assertTrue("the answer is not one of the steps", at > 0)
            // The prompt shows the step before it, so the pairing has to be adjacent.
            assertTrue("prompt does not show the preceding step", q.prompt.contains(steps[at - 1]))
            assertTrue(q.answer !in q.options)
        }
    }

    /**
     * ⚠️ A short procedure produces nothing rather than something weak.
     *
     * Three steps cannot yield three honest distractors, and the only ways to fill the gap are to
     * repeat an option or invent one. Its steps still reach the learner through cloze.
     */
    @Test
    fun aProcedureTooShortToAskFairlyIsNotAskedAtAll() {
        assertTrue(make(steps.take(3)).isEmpty())
        assertTrue(make(steps.take(1)).isEmpty())
        assertTrue(make(emptyList()).isEmpty())
        assertTrue("four leaves only two distractors once the shown step is excluded",
            make(steps.take(4)).isEmpty())
        assertTrue("five steps is exactly enough", make(steps.take(5)).isNotEmpty())
    }

    @Test
    fun blanksAndRepeatsAreCleanedBeforeCounting() {
        // Five entries, but only three distinct real steps — not enough to ask fairly.
        val messy = listOf("Do a.", "  ", "Do a.", "Do b.", "", "Do c.")
        assertTrue(make(messy).isEmpty())
    }

    @Test
    fun aCardAsksTheSameQuestionEveryTimeItComesBack() {
        val q = make().first()
        val a = QuizBuilder.build(q, pool = emptyList(), seed = 7)!!
        val b = QuizBuilder.build(q, pool = emptyList(), seed = 7)!!
        assertEquals(a.choices, b.choices)
        assertEquals(a.prompt, b.prompt)
    }

    /**
     * The clever formats are wrong for a procedure and are not used.
     *
     * "Which is NOT the next step" would make three true-but-misplaced instructions read as
     * endorsements, and withholding the answer would ask somebody to certify that a real step of this
     * very procedure does not belong in it.
     */
    @Test
    fun aProcedureIsAlwaysAskedStraight() {
        val q = make().first()
        for (seed in 0 until 20) {
            val item = QuizBuilder.build(q, pool = emptyList(), seed = seed) ?: continue
            assertEquals(QuizBuilder.Format.STANDARD, item.format)
            assertTrue(item.choices.none { it.text == QuizBuilder.NONE_OF_THESE_TEXT })
        }
    }

    // ---- the section-level join ---------------------------------------------------------------------

    /**
     * The gap this whole change closes: `forSection` had the steps available at the call site and was
     * never given them, so 3,298 steps across the real corpus produced no questions.
     */
    @Test
    fun aSectionWithAProcedureNowAsksAboutIt() {
        val body = "Bicarbonate and vinegar react to make carbon dioxide. Collect it in a balloon."
        val without = StudyQuestions.forSection("chem", "Chemistry", "Step-by-step", body)
        val with = StudyQuestions.forSection("chem", "Chemistry", "Step-by-step", body, steps = steps)

        assertTrue(without.none { it.kind == StudyQuestions.QuestionKind.ORDER })
        assertTrue(with.any { it.kind == StudyQuestions.QuestionKind.ORDER })
        // Still capped — a section is a study prompt, not a worksheet.
        assertTrue(with.size <= StudyQuestions.MAX_PER_SECTION)
        // And still always closable by recall, so no section becomes unteachable.
        assertEquals(StudyQuestions.QuestionKind.RECALL, with.last().kind)
    }

    /**
     * Measurements inside steps and materials lists are where the doses live; cloze should reach them.
     *
     * ⚠️ The fixtures are long on purpose. `isClozeable` requires [StudyQuestions.MIN_SENTENCE] (45)
     * characters, and a first draft of this test used tidy 30-character lines and failed — my
     * expectation was wrong, not the code. Measured against the real corpus before rewriting it:
     * steps have a median length of 179 and **73%** sit inside the 45..220 window, ingredient lines
     * a median of 121 with **91%** inside. So the feature does fire on real content, and these
     * fixtures now look like real content rather than like something invented to pass.
     */
    @Test
    fun quantitiesInsideStepsAndMaterialsBecomeAnswerable() {
        val body = "A short introduction that carries no measurements of its own at all, anywhere."
        val out = StudyQuestions.forSection(
            "chem", "Chemistry", "Materials", body,
            max = 8,
            ingredients = listOf(
                "White vinegar, about 25 millilitres per run, from any supermarket — cheap and plentiful.",
                "Bicarbonate of soda, roughly 5 grams a run, sold as baking soda in the baking aisle.",
            ),
        )
        val clozes = out.filter { it.kind == StudyQuestions.QuestionKind.CLOZE }
        assertTrue("no measurement was picked up from the materials list", clozes.isNotEmpty())
        assertTrue(
            "the blanked term was not a measurement: ${clozes.map { it.answer }}",
            clozes.any { it.answer.contains("millilitres") || it.answer.contains("grams") },
        )
    }

    @Test
    fun everyQuestionKeepsAStableId() {
        val first = make().map { it.id }
        val again = make().map { it.id }
        assertEquals(first, again)
        assertEquals(first.size, first.distinct().size)
        assertNotNull(first.firstOrNull())
    }

    @Test
    fun anOrderQuestionWithNoOptionsCannotBeBuilt() {
        val stripped = make().first().copy(options = emptyList())
        assertNull(QuizBuilder.build(stripped, pool = List(20) { "step $it" }, seed = 1))
    }
}
