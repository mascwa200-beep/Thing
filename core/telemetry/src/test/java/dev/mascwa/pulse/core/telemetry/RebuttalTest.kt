package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RebuttalTest {

    private val candidate = Fallacies.best("Everyone knows the whole funding scheme was never real.")!!
    private val grounding = Rebuttal.Grounding(
        guideId = "logic-and-argument",
        guideTitle = "Logic and Argument",
        section = "Informal Fallacies",
        excerpt = "An appeal to popularity offers the number of believers as the reason to believe.",
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
}
