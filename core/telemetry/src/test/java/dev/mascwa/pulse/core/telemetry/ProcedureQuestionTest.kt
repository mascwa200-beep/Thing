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
     * ⚠️ **The load-bearing test.** Every option a learner is shown must be a real step of the same
     * procedure — verbatim, or a truthful prefix of one marked as abbreviated.
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
                    assertTrue("offered an option that is not a real step: '${it.text}'",
                        isRealStep(it.text, verbatim))
                }
                assertEquals(1, item.choices.count { it.correct })
                assertEquals(StudyQuestions.STANDARD_DISTRACTORS + 1, item.choices.size)
                // ⚠️ The step in the prompt must never also be an option — a free elimination.
                // Found by reading real generated questions, not by any fixture.
                val shown = steps.first { it in q.prompt }
                assertTrue("offered the step already shown in the prompt",
                    item.choices.none { isRealStep(it.text, setOf(shown)) })
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

    /**
     * An option is a real step, whole or abbreviated.
     *
     * A shortened option must still be something the author wrote — a genuine prefix, marked with the
     * ellipsis so the reader can see there is more. Anything else is invention.
     */
    private fun isRealStep(text: String, steps: Set<String>): Boolean {
        if (text in steps) return true
        if (!text.endsWith(StudyQuestions.ELLIPSIS)) return false
        val body = text.removeSuffix(StudyQuestions.ELLIPSIS).trimEnd()
        return body.isNotEmpty() && steps.any { it.startsWith(body) }
    }

    // ---- readable options ----------------------------------------------------------------------------

    /**
     * Steps this long are ordinary in the corpus — the median is 180 characters and the longest 600 —
     * so four of them verbatim is a wall to read rather than a question to answer.
     */
    private val longSteps = listOf(
        "Fill the vessel to the shoulder with the clearest water you can find, leaving a finger of air " +
            "beneath the neck so the contents can move when you shake it. Cloudy water must be settled " +
            "or strained through cloth before this stage, because suspended solids shield organisms.",
        "Bring the water to a rolling boil, meaning a boil that cannot be stirred flat, and hold it " +
            "there for one full minute at any altitude below two thousand metres. Above that, hold it " +
            "for three minutes, since the boiling point falls with the pressure.",
        "Allow the vessel to cool without a lid for at least twenty minutes, standing it out of direct " +
            "sun and away from anything that might tip it, and do not decant it while it is still hot " +
            "enough to soften the container.",
        "Decant carefully into a clean stoppered bottle, pouring in one movement and leaving the last " +
            "centimetre behind with whatever has settled into it, then label the bottle with the date " +
            "and the source it came from.",
        "Store the bottle upright somewhere dark and cool, and treat it as drinkable for two days once " +
            "opened, after which the safe assumption is that it has been recontaminated by handling.",
    )

    @Test
    fun aLongStepIsShownShortEnoughToRead() {
        val q = StudyQuestions.procedure("water", "Water", "Boiling", longSteps).first()
        val item = QuizBuilder.build(q, pool = emptyList(), seed = 2)!!
        item.choices.forEach {
            assertTrue("an option is still ${it.text.length} characters", it.text.length <= StudyQuestions.MAX_OPTION_CHARS + StudyQuestions.ELLIPSIS.length)
            assertTrue("shortened past meaning", it.text.length >= StudyQuestions.MIN_OPTION_CHARS)
            assertTrue("an option was invented rather than abbreviated",
                isRealStep(it.text, longSteps.toSet()))
        }
        // The lesson is the whole instruction, so the explanation is never abbreviated.
        assertTrue(item.explanation.contains(q.answer))
    }

    /**
     * ⚠️ **The rule that makes shortening safe.** Two options that truncate to the same text make the
     * question unanswerable, which is far worse than a long one — so shortening declines and hands
     * back the originals.
     *
     * Not one of the 804 ordering questions the real corpus generates hits this, which is the right
     * frequency for a safety valve. It is asserted here because it is the only place it can be.
     *
     * ⚠️ The shared prefix is 129 characters, and that number is derived rather than chosen: two
     * options only collide if they are still identical at the cut, so anything shorter than
     * [StudyQuestions.MAX_OPTION_CHARS] leaves them distinguishable. A first draft of this test shared
     * 84 characters and passed against the shipped rule for the honest reason that there was no
     * collision to catch.
     */
    @Test
    fun optionsThatWouldTruncateAlikeAreLeftLong() {
        val shared = "Put on nitrile gloves and sealed splash goggles before you open anything at all, " +
            "keep them on until the bench is clear, and then "
        val twins = listOf(
            shared + "check the seal against your face by breathing out hard through your nose.",
            shared + "wipe the bench down with a damp cloth so nothing dry is left to raise dust.",
            "Decant the acid slowly down a glass rod into the water, never the water into the acid.",
            "Label the flask with the contents, the concentration and the date before you leave it.",
            "Rinse the rod and the funnel into the flask so nothing measured is left clinging to them.",
        )
        val out = StudyQuestions.shortOptions(twins)
        assertEquals("shortening made two options look alike, so it should have declined", twins, out)
    }

    @Test
    fun optionsAlreadyShortEnoughAreUntouched() {
        assertEquals(steps, StudyQuestions.shortOptions(steps))
    }

    // ---- the guide's safety warning ------------------------------------------------------------------

    /**
     * ⚠️ Long on purpose, and the length is the point of the fixture.
     *
     * The real notes run to a median of 448 characters and a third exceed [StudyQuestions.RECALL_CHARS],
     * so a tidy two-line fixture cannot detect the one defect these tests exist to prevent — a warning
     * silently cut in half. A first draft used a 168-character note, and truncating the shipped code to
     * `RECALL_CHARS` left every assertion passing.
     *
     * Note also where the load-bearing instruction sits: past the 420th character, exactly as it does
     * in the corpus, because a safety note tends to build up to its "never do X".
     */
    private val note = "Concentrated acids and bases cause severe burns to skin and eyes, and the " +
        "damage from an alkali is often deeper than it first looks because it keeps saponifying " +
        "tissue after contact. Wear splash goggles rather than safety glasses, work behind a raised " +
        "sash, and keep an eyewash within arm's reach before you open any bottle. Rinse any splash " +
        "for a full twenty minutes under running water and seek medical advice even when the skin " +
        "looks unmarked. Always add concentrated acid to water, never water to acid: the reaction is " +
        "strongly exothermic and can boil the mixture out of the vessel and into your face."

    /**
     * 184 of the 581 bundled guides carry one of these and, until now, not one was ever asked about.
     */
    @Test
    fun aGuidesSafetyWarningBecomesACardThatComesBack() {
        val q = StudyQuestions.safety("chem", "Acids and Bases", note)!!
        assertEquals(StudyQuestions.QuestionKind.RECALL, q.kind)
        assertTrue(q.prompt.contains("Acids and Bases"))
        assertTrue(q.prompt.contains("safety warning"))
        // ⚠️ In full. A third of the real notes run past StudyQuestions.RECALL_CHARS, and a fixed cut
        // would drop the second half of a warning — which is where "never do X" tends to live.
        assertEquals(note, q.answer)
        assertTrue(StudyQuestions.isSafety(q))
        assertEquals(StudyQuestions.SAFETY_HEADING, q.heading)
    }

    @Test
    fun theSafetyCardIsOnePerGuideAndKeepsItsIdentity() {
        val a = StudyQuestions.safety("chem", "Acids and Bases", note)!!
        val b = StudyQuestions.safety("chem", "Acids and Bases", "  $note  ")!!
        assertEquals(a.id, b.id)
        assertEquals(a.answer, b.answer)
        // Not confusable with any other form's card for the same guide.
        val other = StudyQuestions.recall("chem", "Acids and Bases", "Dilution", note)
        assertTrue(a.id != other.id)
        assertTrue(!StudyQuestions.isSafety(other))
    }

    @Test
    fun nothingToWarnAboutMeansNoCard() {
        assertNull(StudyQuestions.safety("chem", "Acids and Bases", null))
        assertNull(StudyQuestions.safety("chem", "Acids and Bases", "   "))
        assertNull(StudyQuestions.safety("chem", "Acids and Bases", "Be careful."))
    }
}
