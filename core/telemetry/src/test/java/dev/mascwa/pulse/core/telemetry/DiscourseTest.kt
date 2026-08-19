package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ Every expected number below was computed from the shipped weights before it was written, not
 * recalled. That habit is the only reason [Discourse.RHETORICAL_WEIGHT] exists: working the
 * arithmetic for the straw-man fixture showed it scoring 0.29 against a 0.45 floor, so the
 * question-shaped fallacies were unreachable while the source looked as though it handled them.
 * The arithmetic is left in the comments so the next person can check it rather than trust it.
 */
class DiscourseTest {

    private fun u(text: String, start: Long = 0, end: Long = 5_000) = Discourse.Utterance(text, start, end)

    // ---- stage 2: is there an argument here ------------------------------------------------

    @Test
    fun fillerIsNotContent() {
        // "um", "so", "well", "like", "yeah", "basically" are all filler; four words survive.
        val words = Discourse.contentWords("Um, so, well, like, yeah, basically the funding was never real")
        assertEquals(listOf("the", "funding", "was", "never", "real"), words)
    }

    @Test
    fun acknowledgementIsNotAClaim() {
        for (s in listOf("Yeah.", "Mm hmm.", "Right, ok.", "Exactly.", "Fair enough.", "Thanks!")) {
            val c = Discourse.assess(u(s))
            assertEquals("'$s' scored ${c.strength}", 0.0, c.strength, 1e-9)
            assertFalse(c.carriesClaim)
        }
    }

    /**
     * ⚠️ A LONG sentence that merely opens with an acknowledgement is still a claim. The backchannel
     * rule is gated on [Discourse.BACKCHANNEL_MAX_WORDS] precisely so "Right, the whole scheme
     * failed because the funding was never real" is not thrown away on its first word.
     */
    @Test
    fun anAcknowledgementPrefixDoesNotDiscardTheSentence() {
        val c = Discourse.assess(u("Right, the whole scheme failed because the funding was never real."))
        assertTrue("scored ${c.strength}", c.carriesClaim)
        assertTrue(c.reasoning)
    }

    @Test
    fun aFragmentIsNotAClaim() {
        // 3 content words, and a question: 3/12*0.50 = 0.125, minus the 0.30 question penalty → 0.
        val c = Discourse.assess(u("What about it?"))
        assertEquals(0.0, c.strength, 1e-9)
        assertFalse(c.carriesClaim)
    }

    @Test
    fun aConnectiveIsTheStrongestCheapSignalOfReasoning() {
        val plain = Discourse.assess(u("The whole scheme failed and the funding was never real."))
        val reasoned = Discourse.assess(u("The whole scheme failed because the funding was never real."))
        assertFalse(plain.reasoning)
        assertTrue(reasoned.reasoning)
        assertEquals(Discourse.CONNECTIVE_WEIGHT, reasoned.strength - plain.strength, 1e-9)
    }

    /**
     * ⚠️ `so` is deliberately NOT a connective. It is the commonest discourse filler in spoken
     * English, and treating it as reasoning would mark most conversational openings as arguments.
     */
    @Test
    fun soIsFillerRatherThanReasoning() {
        assertFalse(Discourse.assess(u("So the funding for the whole scheme was never real at all.")).reasoning)
        assertTrue("so" !in Discourse.contentWords("So the funding was never real"))
    }

    /**
     * ⚠️ Question-shaped fallacies must reach stage 3. Nine content words: 9/12*0.50 = 0.375, plus
     * RHETORICAL_WEIGHT 0.25 = 0.625, over the 0.45 floor. Without the rhetorical term this scores
     * 0.375 and the straw man is unreachable — which is exactly what the first draft did.
     */
    @Test
    fun aRhetoricalQuestionIsTheClaim() {
        val strawMan = Discourse.assess(u("So you're saying we should abolish the entire department overnight?"))
        assertEquals(9, Discourse.contentWords(strawMan.utterance.text).size)
        assertEquals(
            9 / Discourse.LENGTH_SATURATES * Discourse.LENGTH_WEIGHT + Discourse.RHETORICAL_WEIGHT,
            strawMan.strength, 1e-9,
        )
        assertTrue("scored ${strawMan.strength}", strawMan.carriesClaim)

        val loaded = Discourse.assess(u("Why do you always take their side whenever this subject comes up?"))
        assertTrue("scored ${loaded.strength}", loaded.carriesClaim)

        // An ordinary question is not, and pays the penalty instead.
        assertFalse(Discourse.assess(u("Do you happen to know what time the last train leaves tonight?")).carriesClaim)
    }

    /**
     * ⚠️ Hedging is recorded and does NOT reduce the claim strength. An earlier draft subtracted a
     * penalty, which pushed ordinary hedged sentences under the floor so they returned NO_CLAIM and
     * [Discourse.Verdict.HEDGED] became unreachable — the refusal existed in the source and could
     * never happen. Hedging is a verdict question, not a "is this a claim" question.
     */
    @Test
    fun aHedgeIsRecordedButIsStillAClaim() {
        val flat = Discourse.assess(u("The whole scheme failed because the funding was never real."))
        val hedged = Discourse.assess(u("I think the whole scheme failed because the funding was never real."))
        assertFalse(flat.hedged)
        assertTrue(hedged.hedged)
        assertTrue("a hedged sentence is still a claim", hedged.carriesClaim)
        // ⚠️ Hedging must not SUBTRACT. It cannot be asserted as equality: "I think" adds two content
        // words, so the hedged sentence is 12 against the flat one's 10 and scores higher —
        // 12/12*0.50 + 0.35 = 0.850 against 10/12*0.50 + 0.35 = 0.767. An earlier draft asserted
        // equality on the belief that both had saturated, which was simply wrong about the lengths.
        assertEquals(10 / 12.0 * 0.50 + Discourse.CONNECTIVE_WEIGHT, flat.strength, 1e-9)
        assertEquals(1.0 * 0.50 + Discourse.CONNECTIVE_WEIGHT, hedged.strength, 1e-9)
        assertTrue("hedging must never reduce the strength", hedged.strength >= flat.strength)
    }

    // ---- the cascade -----------------------------------------------------------------------

    private val bandwagon = "The whole scheme failed because everyone knows the funding was never real."

    @Test
    fun aClaimCarryingACueEscalates() {
        val d = Discourse.consider(u(bandwagon), Discourse.CascadeState(), nowMs = 1_000)
        assertEquals(Discourse.Verdict.ESCALATE, d.verdict)
        assertTrue(d.escalate)
        assertEquals("bandwagon", d.candidate?.fallacy?.id)
        assertEquals(1, d.timesSeen)
        assertEquals(listOf(1_000L), d.state.recentMs)
    }

    @Test
    fun chatDoesNotEscalate() {
        assertEquals(Discourse.Verdict.NO_CLAIM, Discourse.consider(u("Yeah, ok."), Discourse.CascadeState()).verdict)
        assertEquals(
            Discourse.Verdict.NO_CANDIDATE,
            Discourse.consider(
                u("The whole scheme failed because the funding ran out in the second year."),
                Discourse.CascadeState(),
            ).verdict,
        )
    }

    /** A speaker who has already conceded uncertainty is not worth correcting. */
    @Test
    fun aHedgedClaimIsNotEscalated() {
        val d = Discourse.consider(u("I think everyone knows the whole funding scheme was never real."), Discourse.CascadeState())
        assertEquals(Discourse.Verdict.HEDGED, d.verdict)
        assertFalse(d.escalate)
    }

    /**
     * ⚠️ THE LOAD-BEARING RULE. A repeat inside the cooldown is COUNTED and not raised, and the count
     * is what lets the surface say "that is the third time" once instead of three times. Negative
     * tested both ways: dropping the cooldown makes the second call escalate, and moving the `seen`
     * increment below the cooldown check leaves the count stuck at 1.
     */
    @Test
    fun aRepeatIsCountedRatherThanRaisedAgain() {
        var st = Discourse.CascadeState()
        val first = Discourse.consider(u(bandwagon), st, nowMs = 0)
        assertEquals(Discourse.Verdict.ESCALATE, first.verdict)
        st = first.state

        val second = Discourse.consider(u(bandwagon), st, nowMs = Discourse.COOLDOWN_MS - 1)
        assertEquals(Discourse.Verdict.COOLING_DOWN, second.verdict)
        assertEquals("the repeat must still be counted", 2, second.timesSeen)
        assertEquals("a refusal must not move the fired time", 0L, second.state.lastFiredMs["bandwagon"])
        st = second.state

        // Once the window has passed it is raised again — and the count keeps climbing.
        val third = Discourse.consider(u(bandwagon), st, nowMs = Discourse.COOLDOWN_MS)
        assertEquals(Discourse.Verdict.ESCALATE, third.verdict)
        assertEquals(3, third.timesSeen)
    }

    /** A different fallacy is not held back by another one's cooldown. */
    @Test
    fun theCooldownIsPerFallacy() {
        val first = Discourse.consider(u(bandwagon), Discourse.CascadeState(), nowMs = 0)
        val other = Discourse.consider(
            u("No real engineer would ever sign off on a joint built like that one."),
            first.state,
            nowMs = 1_000,
        )
        assertEquals(Discourse.Verdict.ESCALATE, other.verdict)
        assertEquals("no_true_scotsman", other.candidate?.fallacy?.id)
    }

    @Test
    fun theHourlyCeilingHolds() {
        val now = 10_000_000L
        val full = Discourse.CascadeState(recentMs = (1..Discourse.MAX_PER_WINDOW).map { now - it * 1_000L })
        val d = Discourse.consider(u(bandwagon), full, nowMs = now)
        assertEquals(Discourse.Verdict.RATE_LIMITED, d.verdict)
        assertEquals("a rate-limited candidate is still counted", 1, d.timesSeen)

        // Escalations that have aged out of the window do not count against it.
        val stale = Discourse.CascadeState(
            recentMs = (1..Discourse.MAX_PER_WINDOW).map { now - Discourse.RATE_WINDOW_MS - it * 1_000L },
        )
        val ok = Discourse.consider(u(bandwagon), stale, nowMs = now)
        assertEquals(Discourse.Verdict.ESCALATE, ok.verdict)
        assertEquals("the aged-out entries must be dropped", listOf(now), ok.state.recentMs)
    }

    // ---- segmentation ----------------------------------------------------------------------

    @Test
    fun anOpenChunkJoinsTheNextOne() {
        val merged = Discourse.segment(
            listOf(u("The whole scheme failed", 0, 2_000), u("because the funding was never real.", 2_200, 5_000)),
        )
        assertEquals(1, merged.size)
        assertEquals("The whole scheme failed because the funding was never real.", merged[0].text)
        assertEquals(0L, merged[0].startMs)
        assertEquals(5_000L, merged[0].endMs)
        // And the merge is the point: only the joined form carries the connective.
        assertTrue(Discourse.assess(merged[0]).reasoning)
    }

    @Test
    fun aFinishedSentenceStandsAlone() {
        val out = Discourse.segment(
            listOf(u("The whole scheme failed.", 0, 2_000), u("It cost a fortune.", 2_200, 5_000)),
        )
        assertEquals(2, out.size)
    }

    @Test
    fun aLongSilenceEndsTheUtterance() {
        val out = Discourse.segment(
            listOf(u("The whole scheme failed", 0, 2_000), u("because of the funding", 2_000 + Discourse.MAX_GAP_MS + 1, 5_000)),
        )
        assertEquals(2, out.size)
    }

    /**
     * ⚠️ Without [Discourse.MAX_MERGE_MS] a transcriber that never emits terminal punctuation — which
     * happens on music, on noise, and on some accents — produces one utterance that grows for as long
     * as the service runs. Six 5s chunks 100ms apart must not become one 30s+ span.
     */
    @Test
    fun mergingIsBounded() {
        val chunks = (0 until 7).map { u("and then another clause with no full stop", it * 5_100L, it * 5_100L + 5_000) }
        val out = Discourse.segment(chunks)
        assertTrue("expected the run to be broken up, got ${out.size}", out.size > 1)
        for (o in out) {
            assertTrue("span ${o.endMs - o.startMs} exceeds the cap", o.endMs - o.startMs <= Discourse.MAX_MERGE_MS)
        }
    }

    @Test
    fun segmentingNothingYieldsNothing() {
        assertEquals(emptyList<Discourse.Utterance>(), Discourse.segment(emptyList()))
        assertNull(Discourse.segment(emptyList()).firstOrNull())
    }

    // ---- the two stages agree ---------------------------------------------------------------

    /**
     * ⚠️ An integration guard, and the reason it earns its place: [Fallacies] and [Discourse] were
     * tuned separately, so a fallacy whose canonical phrasing never clears [Discourse.CLAIM_FLOOR] is
     * dead in the shipped cascade while both files' own tests pass. Any entry listed here as
     * unreachable is a deliberate, recorded decision — not an accident.
     */
    @Test
    fun everyFallacyIsReachableThroughTheCascade() {
        val worked = mapOf(
            "ad_hominem" to "Of course you'd say that, you've never held down a real job in your life.",
            "authority" to "Studies show this approach works far better than the old one ever did.",
            "bandwagon" to bandwagon,
            "nature" to "I only ever use that remedy because it's natural and nothing else is.",
            "tradition" to "We've always run the accounts this way and it has never once let us down.",
            "novelty" to "That whole method is outdated and nobody serious works like that any more.",
            "slippery_slope" to "If we allow this, next thing you know they will be tracking absolutely everything.",
            "false_dilemma" to "Either you support the proposal or you do not care about safety at all.",
            "hasty_generalization" to "I have dealt with three of their engineers and they're all completely useless.",
            "anecdote" to "My cousin took it for a fortnight and his back pain vanished completely afterwards.",
            "straw_man" to "So you're saying we should abolish the entire department overnight?",
            "whataboutism" to "What about all the money the last administration wasted on outside consultants?",
            "fear" to "Think of the children who will grow up in the world that this leaves behind.",
            "post_hoc" to "Ever since the new supplier came in the failures have gone through the roof.",
            "no_true_scotsman" to "No real engineer would ever sign off on a design as sloppy as that one.",
            "burden_shift" to "You can't prove it isn't happening, so I will carry on assuming that it is.",
            "loaded_question" to "Why do you always take their side whenever this subject comes up?",
            "appeal_to_ignorance" to "Nobody has ever proven that it causes harm, so it must be perfectly safe.",
            "genetic" to "Consider the source, because that came from a company with everything to gain.",
            "moving_goalposts" to "Sure, but that's not what I meant when I asked you for the actual numbers.",
            "circular" to "It's true because that's what the manual says, and the manual is authoritative.",
            "false_equivalence" to "Fining a company for that is no different from jailing an innocent man.",
            "cherry_picking" to "There's at least one study showing the opposite, which is enough to settle it.",
            "sunk_cost" to "We've already spent two whole years on it so we can't back out now, can we.",
            "middle_ground" to "The truth is somewhere in between what the two of them are actually claiming.",
        )
        assertEquals(Fallacies.ALL.map { it.id }.toSet(), worked.keys)
        val unreachable = worked.filterNot { (id, text) ->
            val d = Discourse.consider(u(text), Discourse.CascadeState())
            d.escalate && d.candidate?.fallacy?.id == id
        }
        assertEquals("these fallacies cannot be reached through the cascade", emptyMap<String, String>(), unreachable)
    }
}
