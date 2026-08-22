package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RebuttalTest {

    private val candidate = Fallacies.best("Everyone knows the whole funding scheme was never real.")!!
    private val grounding = Rebuttal.Grounding(
        guideTitle = "Logic and Argument",
        section = "Informal Fallacies",
        excerpt = "An appeal to popularity offers the number of believers as the reason to believe.",
        guideId = "logic-and-argument",
    )

    // ---- provenance: the load-bearing rule --------------------------------------------------

    /**
     * ⚠️ THE RULE THIS FILE EXISTS FOR. Provenance is derived from what [Rebuttal.compose] was
     * actually handed, so a caller cannot claim a tier it did not earn. Negative-tested: making
     * provenance a parameter, or defaulting it to REASONED, fails exactly this test.
     */
    @Test
    fun provenanceIsDerivedFromWhatWasActuallySupplied() {
        assertEquals(
            Rebuttal.Provenance.PATTERN_ONLY,
            Rebuttal.compose(candidate).provenance,
        )
        assertEquals(
            Rebuttal.Provenance.GROUNDED,
            Rebuttal.compose(candidate, grounding = grounding).provenance,
        )
        assertEquals(
            Rebuttal.Provenance.REASONED,
            Rebuttal.compose(candidate, modelDraft = "How many people hold it is not why it is true.").provenance,
        )
    }

    /** An empty or whitespace draft is not a draft. A model that returned nothing did not reason. */
    @Test
    fun anEmptyDraftDoesNotEarnTheReasonedTier() {
        for (blank in listOf(null, "", "   ", "\n\t ")) {
            assertEquals(
                "'$blank' must not count as the model having run",
                Rebuttal.Provenance.PATTERN_ONLY,
                Rebuttal.compose(candidate, modelDraft = blank).provenance,
            )
        }
    }

    /**
     * ⚠️ The caveat appears if and only if nothing read the argument. Its absence is a claim that
     * something did, so it cannot be decoration.
     */
    @Test
    fun theCaveatTracksTheProvenance() {
        assertTrue(Rebuttal.compose(candidate).display().contains(Rebuttal.UNREASONED_CAVEAT))
        assertFalse(Rebuttal.compose(candidate, grounding = grounding).display().contains(Rebuttal.UNREASONED_CAVEAT))
        assertFalse(Rebuttal.compose(candidate, modelDraft = "Not so.").display().contains(Rebuttal.UNREASONED_CAVEAT))
    }

    @Test
    fun theModelsWordsReplaceTheFrameOnlyWhenItRan() {
        assertEquals(candidate.fallacy.frame, Rebuttal.compose(candidate).question)
        assertEquals(
            "Would it stop being true if fewer people believed it?",
            Rebuttal.compose(candidate, modelDraft = "Would it stop being true if fewer people believed it?").question,
        )
    }

    // ---- what reaches the reader -------------------------------------------------------------

    /**
     * ⚠️ The spoken form is the question alone. A spoken label and confidence is unusable; the
     * question is the entire value of the feature when it is heard rather than read.
     */
    @Test
    fun theSpokenFormIsTheQuestionAlone() {
        val r = Rebuttal.compose(candidate, grounding = grounding, timesSeen = 3)
        assertEquals(r.question, r.speakable())
        assertFalse(r.speakable().contains(r.label))
        assertFalse(r.speakable().contains("Source:"))
    }

    @Test
    fun theCitationIsShownOnlyWhenSomethingWasRetrieved() {
        assertNull(Rebuttal.compose(candidate).citation)
        assertEquals("Logic and Argument — Informal Fallacies", Rebuttal.compose(candidate, grounding = grounding).citation)
        // A guide with no section named still cites the guide.
        assertEquals("Logic and Argument", grounding.copy(section = "").cite())
    }

    @Test
    fun theTriggerIsQuotedSoASillyMatchIsObvious() {
        assertTrue(Rebuttal.compose(candidate).quote.contains("everyone knows", ignoreCase = true))
    }

    /**
     * ⚠️ The evidence is the SENTENCE, and it has to reach the surface.
     *
     * `quote` is the few words the keyword screen matched on, and a reader shown only those cannot
     * tell a real appeal to popularity from somebody introducing a fact everyone does in fact know —
     * which is the commonest way this screen misfires. It is also the case where dismissing the
     * finding at a glance matters most.
     */
    @Test
    fun theWholeUtteranceReachesTheResponseAndTheDisplay() {
        val said = "Everyone knows the meeting always overruns, so there is no point booking the room."
        val r = Rebuttal.compose(candidate, heard = said)
        assertEquals(said, r.heard)
        assertTrue("the sentence must be on screen: ${r.display()}", r.display().contains(said))
        // And before the label, because a label cannot be checked against anything.
        assertTrue(r.display().indexOf(said) < r.display().indexOf(r.label))
    }

    /** A caller that supplies nothing gets no empty quotation marks. */
    @Test
    fun anAbsentUtteranceIsOmittedRatherThanShownBlank() {
        val d = Rebuttal.compose(candidate).display()
        assertEquals("", Rebuttal.compose(candidate).heard)
        assertFalse("no empty quotation marks: $d", d.contains("“”"))
    }

    /** The spoken form is still the question alone — the sentence was just said out loud. */
    @Test
    fun theSpokenFormDoesNotReadTheSentenceBack() {
        val said = "Everyone knows that."
        val r = Rebuttal.compose(candidate, heard = said)
        assertEquals(r.question, r.speakable())
        assertFalse(r.speakable().contains(said))
    }

    // ---- the repeat line ---------------------------------------------------------------------

    /**
     * ⚠️ Silent on the first sighting: "that is the first time" is not information, and the whole
     * reason [Discourse.CascadeState] counts repeats it does not raise is so this line can be said
     * once rather than once per occurrence.
     */
    @Test
    fun theRepeatLineIsSilentUntilThereIsARepeat() {
        assertNull(Rebuttal.repeatNote(0))
        assertNull(Rebuttal.repeatNote(1))
        assertTrue(Rebuttal.repeatNote(2)!!.contains("second"))
        assertTrue(Rebuttal.repeatNote(3)!!.contains("third"))
        assertTrue(Rebuttal.repeatNote(10)!!.contains("tenth"))
        // Past ten it falls back to digits with the right suffix rather than getting silly.
        assertTrue(Rebuttal.repeatNote(11)!!.contains("11th"))
        assertTrue(Rebuttal.repeatNote(21)!!.contains("21st"))
        assertTrue(Rebuttal.repeatNote(22)!!.contains("22nd"))
        assertTrue(Rebuttal.repeatNote(23)!!.contains("23rd"))
        assertTrue(Rebuttal.repeatNote(13)!!.contains("13th"))
        assertNull(Rebuttal.compose(candidate, timesSeen = 1).repeatNote)
        assertTrue(Rebuttal.compose(candidate, timesSeen = 4).display().contains("fourth"))
    }

    // ---- trimming the draft ------------------------------------------------------------------

    /**
     * ⚠️ Each case below was worked through the shipped rule before it was written. The
     * abbreviation rule keys on the LENGTH of the token before the full stop, not on its case — an
     * earlier version checked for an uppercase letter and cut "See e.g. the guide" down to
     * "See e.g.", because the letter before that stop is a lowercase `g`.
     */
    @Test
    fun onlyTheFirstCompleteThoughtSurvives() {
        assertEquals(
            "Is that really the only option?",
            Rebuttal.trimToOneThought("Is that really the only option? There are others worth naming."),
        )
        assertEquals(
            "That is one point.",
            Rebuttal.trimToOneThought("That is one point. Another follows from it directly."),
        )
        // Abbreviations are not sentence ends.
        assertEquals(
            "See e.g. the guide.",
            Rebuttal.trimToOneThought("See e.g. the guide."),
        )
        assertEquals(
            "Dr. Smith said so.",
            Rebuttal.trimToOneThought("Dr. Smith said so."),
        )
        // A single sentence is returned whole.
        assertEquals("Not so.", Rebuttal.trimToOneThought("Not so."))
    }

    /**
     * ⚠️ A draft with no sentence boundary is truncated rather than dropped. Discarding it would
     * make [Rebuttal.Provenance.REASONED] a lie: the model ran, so the reader is owed what it said.
     */
    @Test
    fun anUnpunctuatedDraftIsTruncatedNotDiscarded() {
        val runOn = "the number of people who believe a thing has never once been a reason " +
            "to believe it and the history of medicine is a long list of exactly that mistake " +
            "repeated by people who were certain at the time and wrong in the end regardless"
        val out = Rebuttal.trimToOneThought(runOn)
        assertTrue("must not be empty", out.isNotEmpty())
        assertTrue("must be trimmed", out.length <= Rebuttal.DRAFT_CHARS + 1)
        assertTrue("must be marked as cut", out.endsWith("…"))
        assertTrue("must not cut mid-word", runOn.startsWith(out.dropLast(1).trim()))
    }

    @Test
    fun whitespaceIsNormalisedSoAModelsLineBreaksDoNotReachTheScreen() {
        assertEquals("One thought only.", Rebuttal.trimToOneThought("  One\n\n  thought   only.  "))
    }

    // ---- stage 5: the prompt and the reply ---------------------------------------------------

    /**
     * ⚠️ THE LINE WORTH DEFENDING. A small instruct model asked "is this an appeal to popularity?"
     * will say yes. Leading with the instruction to refuse — stated as the EXPECTED outcome rather
     * than as a permission — is the difference between an adjudicator and a rubber stamp, and it is
     * why the whole cascade is not just a keyword matcher with a language model bolted on.
     */
    @Test
    fun thePromptLeadsWithTheInstructionToRefuse() {
        val p = Rebuttal.judgePrompt("Everyone knows the scheme was doomed.", candidate)
        val refuse = p.indexOf("NOT a fallacy")
        val suspect = p.indexOf("SUSPECTED")
        assertTrue("the prompt must say most of what it sees is not a fallacy", refuse >= 0)
        assertTrue("and it must say so BEFORE naming the suspicion", refuse < suspect)
    }

    @Test
    fun thePromptCarriesTheUtteranceTheTriggerAndTheExcerpt() {
        val p = Rebuttal.judgePrompt("Everyone knows the scheme was doomed.", candidate, grounding)
        assertTrue(p.contains("Everyone knows the scheme was doomed."))
        assertTrue(p.contains(candidate.fallacy.label))
        // ⚠️ THE WHOLE RENDERED LINE, NOT JUST THE TRIGGER — this guard was asleep when it asserted
        // only `p.contains(candidate.trigger)`. The trigger is by construction a substring of the
        // utterance, and the utterance is quoted in the prompt, so deleting the "matched on the
        // words" line entirely changed nothing the assertion could see. Same failure mode as the
        // redaction-ordering guard: an assertion too weak to see the damage.
        assertTrue(
            "the matched words let the model see a silly match",
            p.contains("(matched on the words: \"${candidate.trigger}\")"),
        )
        assertTrue(p.contains(grounding.excerpt))
        // Without retrieval there is simply no reference section, rather than an empty one.
        assertFalse(Rebuttal.judgePrompt("x y z", candidate).contains("REFERENCE"))
    }

    @Test
    fun aClearYesWithAQuestionIsAFinding() {
        val j = Rebuttal.parseJudgement("VERDICT: yes\nQUESTION: Would it stop being true if fewer believed it?")
        assertTrue(j.present)
        assertEquals("Would it stop being true if fewer believed it?", j.question)
    }

    /**
     * ⚠️ NO IS THE COMMONEST CORRECT ANSWER, and every shape of "not a finding" must reach it.
     * Negative-tested: making the parser default to `present = true` fails this.
     */
    @Test
    fun everyShapeOfDoubtIsReadAsNo() {
        for (raw in listOf(
            null,
            "",
            "   \n  ",
            "VERDICT: no\nQUESTION: -",
            "VERDICT: no\nQUESTION: Would it matter?",     // no wins over a stray question
            "VERDICT: yes\nQUESTION: -",                   // yes with nothing to ask is unusable
            "VERDICT: yes",                                // ditto, question line missing
            "I think this is probably an appeal to popularity, yes.",  // wandered off the format
            "{\"verdict\": \"yes\"}",                        // decided to emit JSON after all
        )) {
            val j = Rebuttal.parseJudgement(raw)
            assertFalse("'$raw' must not be read as a finding", j.present)
            assertNull(j.question)
        }
    }

    /** The format is read leniently on case and spacing, because a small model is not precise. */
    @Test
    fun theReplyIsReadLeniently() {
        val j = Rebuttal.parseJudgement("  verdict:  YES  \n  question:   Is that the only option?  ")
        assertTrue(j.present)
        assertEquals("Is that the only option?", j.question)
    }
}
