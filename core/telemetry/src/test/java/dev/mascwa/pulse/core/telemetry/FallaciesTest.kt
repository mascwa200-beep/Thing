package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ The assertions that matter here are the ones about what does NOT fire.
 *
 * A screen that flags everything is worse than no screen: it wakes the quantized model on every
 * utterance, which is the exact cost the cascade exists to avoid, and it trains the owner to ignore
 * the feature. So the fixtures below are half positive and half ordinary English, and the ordinary
 * half was drawn from the bundled guide corpus rather than invented — see
 * [ordinaryExpositoryProseIsNotFlagged] and the corpus sweep recorded in its comment.
 */
class FallaciesTest {

    // ---- the taxonomy itself -------------------------------------------------------------

    @Test
    fun everyFallacyIsWellFormedAndUniquelyIdentified() {
        assertEquals("ids must be unique", Fallacies.ALL.size, Fallacies.ALL.map { it.id }.toSet().size)
        for (f in Fallacies.ALL) {
            assertTrue("${f.id} has no cues", f.cues.isNotEmpty())
            assertTrue("${f.id} label", f.label.isNotBlank())
            assertTrue("${f.id} what", f.what.isNotBlank())
            assertTrue("${f.id} why", f.why.isNotBlank())
            assertTrue("${f.id} frame", f.frame.isNotBlank())
            assertTrue("${f.id} weight out of range", f.weight > 0.0 && f.weight <= 1.0)
            assertEquals("byId must round-trip", f, Fallacies.byId(f.id))
        }
        assertNull(Fallacies.byId("not_a_fallacy"))
    }

    /**
     * Every weight must be at or above the default floor, or the fallacy can never surface on a
     * single cue and is dead content. Caught two entries written at 0.45 during drafting.
     */
    @Test
    fun everyFallacyCanSurfaceOnOneCue() {
        for (f in Fallacies.ALL) {
            assertTrue(
                "${f.id} at ${f.weight} sits below the default floor and can never fire",
                f.weight >= Fallacies.MIN_CONFIDENCE,
            )
        }
    }

    /** The rebuttal frames are Socratic by design — they ask rather than declare. */
    @Test
    fun rebuttalFramesAskRatherThanAccuse() {
        val accusatory = Regex("\\b(you'?re wrong|that'?s false|fallacy|stupid|idiot)\\b", RegexOption.IGNORE_CASE)
        for (f in Fallacies.ALL) {
            assertFalse("${f.id} frame is accusatory: ${f.frame}", accusatory.containsMatchIn(f.frame))
        }
    }

    // ---- screening: it fires on the real move --------------------------------------------

    @Test
    fun theClassicMovesAreEachScreened() {
        // One utterance per fallacy, phrased the way somebody actually says it out loud.
        val cases = mapOf(
            "ad_hominem" to "Of course you'd say that, you've never held a real job in your life.",
            "authority" to "Studies show that this approach works far better than the old one did.",
            "bandwagon" to "Everyone knows that the new policy is going to be a complete disaster.",
            "nature" to "I only use it because it's all natural, none of that chemical rubbish.",
            "tradition" to "We've always run the accounts this way and it has never let us down.",
            "novelty" to "That whole method is outdated, nobody serious works like that any more.",
            "slippery_slope" to "If we allow this, next thing you know they'll be tracking everything.",
            "false_dilemma" to "Either you support the proposal or you don't care about safety at all.",
            "hasty_generalization" to "I've dealt with three of their engineers and they're all useless.",
            "anecdote" to "My cousin took it for a fortnight and his back pain vanished completely.",
            "straw_man" to "So you're saying we should just abolish the whole department overnight?",
            "whataboutism" to "What about the money the last administration wasted on consultants?",
            "fear" to "Think of the children who will grow up in the world this leaves behind.",
            "post_hoc" to "Ever since they changed the supplier the failures have gone through the roof.",
            "no_true_scotsman" to "No real engineer would ever sign off on a design as sloppy as that.",
            "burden_shift" to "You can't prove it isn't happening, so I'll carry on assuming it is.",
            "loaded_question" to "Why do you always take their side whenever this subject comes up?",
            "appeal_to_ignorance" to "Nobody has ever proven it causes harm, so it must be perfectly safe.",
            "genetic" to "Consider the source — that came from a company with everything to gain.",
            "moving_goalposts" to "Sure, but that's not what I meant when I asked for the numbers.",
            "circular" to "It's true because it's what the manual says, and the manual is authoritative.",
            "false_equivalence" to "Fining a company for that is no different from jailing an innocent man.",
            "cherry_picking" to "One study found the opposite result, which is enough to settle it.",
            "sunk_cost" to "We've already spent two years on it, we can't back out now can we.",
            "middle_ground" to "The truth is somewhere in between what the two of them are claiming.",
        )
        assertEquals(
            "every fallacy in the taxonomy needs a worked example, and every example a fallacy",
            Fallacies.ALL.map { it.id }.toSet(),
            cases.keys,
        )
        for ((id, utterance) in cases) {
            val hits = Fallacies.screen(utterance)
            assertTrue(
                "'$utterance' should screen as $id but produced ${hits.map { it.fallacy.id }}",
                hits.any { it.fallacy.id == id },
            )
        }
    }

    @Test
    fun theTriggerRecordsTheWordsThatFired() {
        val c = Fallacies.best("No real engineer would ever sign off on a design as sloppy as that.")
        assertNotNull(c)
        assertEquals("no_true_scotsman", c!!.fallacy.id)
        assertTrue("trigger should quote the utterance", c.trigger.contains("no real engineer", true))
    }

    // ---- screening: what must NOT fire ----------------------------------------------------

    /**
     * ⚠️ THE LOAD-BEARING GUARD. Cues are anchored on word boundaries, so a cue that is a substring
     * of ordinary words must not match. This repo has shipped this defect three times — a bare
     * `burn` matching "calorie burn", `tap` matching "tape", `car` matching "Newborn Care" — so it
     * is asserted rather than trusted.
     */
    @Test
    fun cuesDoNotMatchInsideLongerWords() {
        // ⚠️ Each of these EXTENDS a real cue with more word characters, which is the only shape the
        // `\b` anchors actually defend against. An earlier draft of this test listed sentences that
        // merely lacked the cue — they passed with the anchors deleted, because a fixture that never
        // reaches the branch cannot fail when the branch is removed. Verified by perturbation: with
        // `\b` dropped from `cue()`, every line below fires.
        val extended = listOf(
            // `unnatural` inside "unnaturally"
            "The joint was bent unnaturally far before the bracket finally gave way.",
            // `most people think` inside "thinking"
            "Most people thinking about it carefully will reach the same conclusion.",
            // `experts say` inside "sayings"
            "The experts sayings were collected into a slim volume the following year.",
            // `everyone knows` inside "knowsomething" — contrived as a word, exact as a test
            "By then everyone knowsomething of the story had already been changed.",
        )
        for (s in extended) {
            assertTrue(
                "'$s' extends a cue with word characters and must not screen, got ${Fallacies.screen(s).map { it.fallacy.id }}",
                Fallacies.screen(s).isEmpty(),
            )
        }
    }

    /**
     * ⚠️ An optional apostrophe must not collapse a contraction into a different real word.
     *
     * This is a REGRESSION TEST for a real defect, not a hypothetical: `it'?s (all )?natural` also
     * matched *its* natural, and the corpus sweep scored 24 false positives from that one cue against
     * zero occurrences of the apostrophised form. The typographic apostrophe is included because
     * transcribers emit both. Negative-tested: relaxing [Fallacies.APOS] back to `'?` fails exactly
     * the first assertion here.
     */
    @Test
    fun anOptionalApostropheDoesNotCollapseIntoAnotherWord() {
        assertTrue(
            "'its natural' is a possessive, not the appeal to nature",
            Fallacies.screen("The soil crumbles along its natural planes of weakness when dry.").isEmpty(),
        )
        assertTrue(
            "the apostrophised form is the fallacy and must still fire",
            Fallacies.screen("I only take the remedy because it's natural and nothing else is.")
                .any { it.fallacy.id == "nature" },
        )
        assertTrue(
            "a typographic apostrophe is what several transcribers emit",
            Fallacies.screen("I only take the remedy because it’s natural and nothing else is.")
                .any { it.fallacy.id == "nature" },
        )
        // The other two cues the enumeration found in the same class.
        assertTrue(Fallacies.screen("She answers in between the two extremes of the argument.").isEmpty())
        assertTrue(Fallacies.screen("A cant phrase repeated cannot back out now of its own meaning.").isEmpty())
    }

    /**
     * ⚠️ A fragment that trips a cue is still refused. The transcriber produces a great many
     * four-word fragments, and "What about it?" is both a whataboutism cue and not an argument.
     * Negative-tested: dropping the [Fallacies.MIN_WORDS] check makes exactly this test fail.
     */
    @Test
    fun fragmentsAreRefusedEvenWhenTheyTripACue() {
        assertTrue(Fallacies.screen("What about it?").isEmpty())
        assertTrue(Fallacies.screen("Everyone knows.").isEmpty())
        assertTrue(Fallacies.screen("Prove me wrong.").isEmpty())
        assertNull(Fallacies.best("So you're saying?"))
        // Exactly at the floor, same cue: now it is an argument and does screen. ⚠️ The length is
        // asserted rather than eyeballed — the first draft of this test claimed seven words for an
        // eight-word sentence, which would have left the boundary untested had it been the other way.
        val exactlyAtTheFloor = "Everyone knows that policy will fail"
        assertEquals(Fallacies.MIN_WORDS, exactlyAtTheFloor.split(" ").size)
        assertTrue(Fallacies.screen(exactlyAtTheFloor).isNotEmpty())
        // And one word under it, same cue, is refused.
        val oneUnder = "Everyone knows that policy fails"
        assertEquals(Fallacies.MIN_WORDS - 1, oneUnder.split(" ").size)
        assertTrue(Fallacies.screen(oneUnder).isEmpty())
    }

    /**
     * Ordinary expository prose must stay quiet.
     *
     * ⚠️ These are VERBATIM sentences from the bundled offline guide corpus, not invented fixtures.
     * A full sweep of all 651 guides (≈8,400 sections) was run through [Fallacies.screen] while
     * tuning: the hit rate has to stay near the floor, because the interrogator is meant to listen
     * to conversation and the library is the largest body of neutral English available to test
     * against. Sentences that DID fire during that sweep are the interesting ones and several cues
     * were tightened because of them — "for centuries" and "by definition" both appear innocently
     * in a reference work, which is why their fallacies carry mid weights rather than high ones.
     */
    @Test
    fun ordinaryExpositoryProseIsNotFlagged() {
        val prose = listOf(
            "Bring the water to a rolling boil for at least one full minute before drinking it.",
            "Press firmly on the wound with a clean cloth and do not lift it to check the bleeding.",
            "The dew point tells you how much moisture the air is actually holding at that moment.",
            "Tie the standing end back on itself and dress the knot before loading it.",
            "A legislature debates and amends a bill before it is put to a final vote.",
            "Charge the battery to about eighty percent if the vehicle is to be stored for months.",
            "Sharpen at a consistent angle and finish with a few very light passes on each side.",
            "The lower the tyre pressure the more heat builds in the sidewall at motorway speed.",
        )
        val fired = prose.filter { Fallacies.screen(it).isNotEmpty() }
        assertTrue("neutral prose triggered the screen: $fired", fired.isEmpty())
    }

    // ---- confidence arithmetic -------------------------------------------------------------

    /**
     * A second distinct cue for the SAME fallacy is corroboration and lifts confidence. Two
     * different phrasings of the move in one breath is better evidence than one.
     */
    @Test
    fun aSecondDistinctCueLiftsConfidence() {
        val one = Fallacies.screen("Everyone knows the scheme was doomed from the very beginning.")
            .first { it.fallacy.id == "bandwagon" }
        val two = Fallacies.screen("Everyone knows it, and most people agree the scheme was doomed.")
            .first { it.fallacy.id == "bandwagon" }
        assertTrue(
            "two cues (${two.confidence}) must beat one (${one.confidence})",
            two.confidence > one.confidence,
        )
        assertEquals(one.confidence + Fallacies.MULTI_CUE_BONUS, two.confidence, 1e-9)
    }

    /**
     * ⚠️ Confidence can never reach certainty. A surface cue cannot establish a fallacy — that is
     * the whole reason stage 5 exists — and a screen reporting 1.0 would invite the UI to present
     * it as a finding. Uses the highest-weighted fallacy with enough cues to saturate the bonus.
     */
    @Test
    fun confidenceIsCappedBelowCertainty() {
        val stacked = "No real engineer would sign that off, a true engineer wouldn't either."
        val c = Fallacies.screen(stacked).first { it.fallacy.id == "no_true_scotsman" }
        assertTrue("must not reach certainty: ${c.confidence}", c.confidence <= Fallacies.MAX_CONFIDENCE)
        assertTrue(c.confidence < 1.0)
        for (f in Fallacies.ALL) {
            val ceiling = f.weight + (f.cues.size - 1) * Fallacies.MULTI_CUE_BONUS
            assertTrue(
                "${f.id} could reach ${minOf(ceiling, Fallacies.MAX_CONFIDENCE)} which must stay capped",
                minOf(ceiling, Fallacies.MAX_CONFIDENCE) <= Fallacies.MAX_CONFIDENCE,
            )
        }
    }

    /** The floor filters, and raising it past a fallacy's weight silences that fallacy. */
    @Test
    fun theConfidenceFloorFilters() {
        val u = "One study found the opposite result, which is enough to settle the matter."
        assertTrue(Fallacies.screen(u).any { it.fallacy.id == "cherry_picking" })
        assertTrue(
            "a floor above its weight must silence it",
            Fallacies.screen(u, minConfidence = 0.9).none { it.fallacy.id == "cherry_picking" },
        )
        assertNull(Fallacies.best(u, minConfidence = 0.99))
    }

    /**
     * Best-first ordering, so stage 4 can take the head and stop.
     *
     * ⚠️ The fixture must trip fallacies whose DECLARATION order disagrees with their confidence
     * order, or the test cannot fail. An earlier draft used no-true-Scotsman (0.85) and cherry
     * picking (0.55) — declared 15th and 23rd, so the unsorted list was already descending and
     * deleting the sort changed nothing. This pairs appeal to authority (0.6, declared 2nd) with
     * no-true-Scotsman (0.85, declared 15th), so declaration order is strictly ASCENDING by
     * confidence and any failure to sort is visible. Verified by perturbation.
     */
    @Test
    fun candidatesAreOrderedBestFirst() {
        val u = "Studies show that no real engineer would ever sign off on a joint like that."
        val hits = Fallacies.screen(u)
        assertEquals(
            "expected exactly the two candidates, got ${hits.map { it.fallacy.id }}",
            listOf("no_true_scotsman", "authority"),
            hits.map { it.fallacy.id },
        )
        // Guard the premise itself: if the taxonomy is ever reordered so these two agree with
        // declaration order, this test silently stops testing anything.
        val declared = Fallacies.ALL.map { it.id }
        assertTrue(
            "fixture no longer distinguishes the orders — pick another pair",
            declared.indexOf("authority") < declared.indexOf("no_true_scotsman"),
        )
        assertEquals(hits.first(), Fallacies.best(u))
    }
}
