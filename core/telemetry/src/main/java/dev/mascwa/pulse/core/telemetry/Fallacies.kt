package dev.mascwa.pulse.core.telemetry

/**
 * The acoustic interrogator's fallacy taxonomy — stage 3 of the cascade.
 *
 * The interrogator transcribes ambient speech and looks for reasoning that does not hold. Running a
 * quantized LLM over every utterance would cook the battery, so this stage is a cheap, deterministic
 * SCREEN that decides whether an utterance is worth waking the model for. Same shape as
 * [EmergencyTriage] sitting in front of the ranker, and the Sensorium's cheap sensor fusion sitting
 * in front of a camera burst.
 *
 * ⚠️ **A cue hit is a CANDIDATE, never a verdict.** "Everyone knows" genuinely is the bandwagon
 * phrase, and it is also how people introduce a fact that everyone does in fact know. This stage
 * exists to be cheap and roughly right, not to be correct; correctness is stage 5's job, where the
 * model sees the whole utterance and a grounding excerpt from the offline library. Nothing here
 * should ever be shown to a human as a finding on its own.
 *
 * ⚠️ **Fallacies with no reliable surface cue are deliberately ABSENT.** Equivocation, composition
 * and division, and most begging-the-question turn on the *semantics* of the argument rather than
 * on any phrase, and a keyword rule for them would fire constantly on innocent speech. This session
 * spent an hour removing images that a sourcer picked because "Otto" the engine cycle and "Otto" the
 * dynasty share four letters — the same failure, and the lesson is that a matcher which sees only
 * tokens must be restricted to targets that tokens actually identify. Twenty-five with real cues
 * beats forty where fifteen are noise.
 *
 * ⚠️ **Cues match on WORD BOUNDARIES, and multi-word cues are preferred.** A bare `burn` in the
 * emergency table once matched "calorie burn"; `lock-` matched a canal lock-gate. Every pattern here
 * is anchored, and single common words are avoided in favour of the phrase that actually carries the
 * move ("what about" rather than "about").
 *
 * ⚠️ **An optional apostrophe must never collapse a contraction into a different real word.** The
 * first draft wrote `it'?s (all )?natural` for the appeal to nature, which also matches *its*
 * natural — as in "crumbles along its natural planes of weakness". Sweeping the bundled guide corpus
 * found 24 false positives from that one cue and **zero** occurrences of the apostrophised form, so
 * the leniency bought nothing and cost everything. Enumerating every `'?` in this file against the
 * corpus showed exactly three cues in that class (`it's`/`its` 12,159 uses, `answer's`/`answers` 407,
 * `can't`/`cant` 3); all three now require [APOS]. Leniency is kept only where the bare form is not
 * a word (`youre`, `thats`, `weve`), which is most of them. Whisper emits apostrophes, so requiring
 * one where it matters is free.
 *
 * ⚠️ **Cues were selected by sweeping the real corpus, not by writing what sounds right.** ~159,000
 * sentences and 4.0M words of neutral expository English — the largest body of non-argumentative
 * prose to hand, and a reference work almost never commits a fallacy, so nearly every hit there is a
 * false positive by construction. The first draft fired on 0.30% of sentences and **four cues
 * produced 79% of them**: `for centuries` (128 — a reference work states durations constantly and
 * means nothing by it), `at least one` (120 — "at least one full minute"), `by definition` (75) and a
 * bare `ever since` (62 — "maintained by hand weeding ever since"). None was the fallacy; all four
 * are gone or tightened to the form that carries the move. Ordinary prose now fires at roughly a
 * twentieth of that. This is the [GuideSearch] lesson again: unit tests passed while the real corpus
 * returned nonsense, and only running the shipped code over real data showed it.
 *
 * The rebuttal frames are deliberately **Socratic rather than combative** — they ask the question
 * that tests the claim instead of declaring the speaker wrong. That is more useful in a real
 * conversation, and it is far more defensible when the screen has misfired, which it will.
 */
object Fallacies {

    /**
     * @param label what to call it in front of a human — plain English, not Latin where possible.
     * @param what one sentence on the move itself.
     * @param why one sentence on why it does not support the conclusion.
     * @param frame the Socratic counter-argument frame; stage 5 fills it out against the real claim.
     * @param cues anchored patterns. Presence is weak evidence, never proof.
     * @param weight how much a cue hit is worth, 0..1. Distinctive phrases score higher than ones
     *   with common innocent uses — `no true` is nearly always the fallacy, `always` almost never is.
     */
    data class Fallacy(
        val id: String,
        val label: String,
        val what: String,
        val why: String,
        val frame: String,
        val cues: List<Regex>,
        val weight: Double,
    )

    /** A screened candidate: which fallacy, how confident, and the exact words that triggered it. */
    data class Candidate(val fallacy: Fallacy, val confidence: Double, val trigger: String)

    private fun cue(vararg patterns: String): List<Regex> =
        patterns.map { Regex("\\b" + it + "\\b", RegexOption.IGNORE_CASE) }

    /**
     * A REQUIRED apostrophe, straight or typographic.
     *
     * Used in place of `'?` wherever dropping the mark yields another real word — see the class KDoc.
     * Transcribers emit both `'` and `’` depending on the model, so both are accepted; what is not
     * accepted is the mark being absent.
     */
    private const val APOS = "['’]"

    val ALL: List<Fallacy> = listOf(
        Fallacy(
            "ad_hominem", "Attacking the person",
            "The reply targets who is speaking rather than what they said.",
            "A claim's truth does not depend on who makes it, so discrediting the speaker leaves the claim untouched.",
            "Set the person aside for a moment — is the claim itself true?",
            cue("you'?re just an?", "typical of (someone|people) who", "coming from someone who",
                "of course you'?d say", "what would you know about"),
            0.75,
        ),
        Fallacy(
            "authority", "Appeal to authority",
            "An unnamed expert is offered instead of the evidence.",
            "Expertise makes a claim more likely, not true; without the study or the reasoning there is nothing to check.",
            "Which expert, and what did they actually find?",
            cue("experts? (say|agree|believe)", "scientists? (say|agree)", "doctors? (say|recommend)",
                "studies show", "science says", "it'?s been proven"),
            0.6,
        ),
        Fallacy(
            "bandwagon", "Appeal to popularity",
            "How many people believe it is offered as why it is true.",
            "Popular beliefs have been wrong at every point in history; headcount is not evidence.",
            "If most people believed the opposite, would that make the opposite true?",
            cue("everyone knows", "everybody knows", "most people (think|know|agree)",
                "nobody (believes|thinks)", "no one (believes|thinks)", "common sense says"),
            0.7,
        ),
        Fallacy(
            "nature", "Appeal to nature",
            "Natural is treated as good and unnatural as bad.",
            "Hemlock and asbestos are natural; insulin and eyeglasses are not. The category predicts nothing.",
            "Plenty of natural things harm and plenty of made things heal — what does natural add here?",
            // ⚠️ `it${APOS}s`, not `it'?s` — the lenient form matches "its natural planes of weakness"
            // and scored 24 false positives against 0 true ones on the real corpus.
            cue("it${APOS}s (all )?natural", "unnatural", "chemical[- ]free", "full of chemicals",
                "not (found )?in nature", "the way nature intended"),
            0.7,
        ),
        Fallacy(
            "tradition", "Appeal to tradition",
            "How long something has been done is offered as why it should continue.",
            "Duration is not justification; the reason it started may never have been good, or may have expired.",
            "What was the original reason, and does it still hold?",
            // `for centuries` was the single noisiest cue in the corpus sweep (128 hits, every one a
            // plain statement of duration). Duration is only the fallacy when offered as the REASON,
            // which the remaining "we've always" phrasings carry and a bare timespan does not.
            cue("we'?ve always", "we have always", "always been done", "the way it'?s always been",
                "tried and true", "always done it (this|that) way"),
            0.65,
        ),
        Fallacy(
            "novelty", "Appeal to novelty",
            "Newness is offered as why something is better.",
            "Recency says nothing about quality; most new things are replaced by the next new thing.",
            "Newer than what, and better in which measurable way?",
            // `the newest` dropped (13 corpus hits, all factual — "the newest term of the three").
            // The move is a DISMISSAL, so the predicate form is required: "is outdated" scored 0 on
            // the corpus while "are outdated" caught two sentences about stale phone numbers.
            cue("it'?s the latest", "(that'?s|that is|is|so) outdated", "so last (year|decade)",
                "modern science has moved on", "nobody does that any ?more"),
            0.55,
        ),
        Fallacy(
            "slippery_slope", "Slippery slope",
            "A first step is treated as making an extreme end point inevitable.",
            "Each link in the chain needs its own argument; without them the conclusion is asserted, not shown.",
            "Which step in that chain is the one that has to happen, and why?",
            cue("next thing you know", "where does it end", "before you know it", "slippery slope",
                "opens? the floodgates", "what'?s next,", "leads? straight to"),
            0.8,
        ),
        Fallacy(
            "false_dilemma", "False choice",
            "Two options are offered as if they were the only ones.",
            "Presenting a spectrum as a switch hides every position that is not one of the two named.",
            "Are those really the only two options?",
            cue("either you", "you'?re either", "if you'?re not .{0,20} you'?re",
                "only two (options|choices|kinds)", "there are two kinds of people"),
            0.75,
        ),
        Fallacy(
            "hasty_generalization", "Hasty generalisation",
            "A sweeping rule is drawn from a handful of cases.",
            "A small or self-selected sample cannot support a claim about everyone.",
            "How many cases is that based on, and how were they chosen?",
            cue("all of them are", "every single one of them", "they'?re all",
                "that'?s just how .{0,15} are", "none of them ever"),
            0.6,
        ),
        Fallacy(
            "anecdote", "Anecdotal evidence",
            "One personal story stands in for data.",
            "A single case cannot separate a real effect from coincidence, and the memorable cases are the unrepresentative ones.",
            "That happened — but how often does it not?",
            cue("my (friend|cousin|mate|uncle|aunt|neighbour|neighbor)", "i know someone who",
                "worked for me", "happened to me", "i'?ve never had a problem"),
            0.6,
        ),
        Fallacy(
            "straw_man", "Straw man",
            "A weaker version of the other position is answered instead of the real one.",
            "Defeating a claim nobody made leaves the actual disagreement exactly where it was.",
            "Is that what they actually said, or a version that is easier to argue with?",
            cue("so you'?re saying", "what you really mean is", "so you (want|think) we should",
                "oh so", "so basically you"),
            0.7,
        ),
        Fallacy(
            "whataboutism", "Whataboutism",
            "A different wrongdoing is raised instead of answering the point.",
            "Someone else's failing does not make this claim false; two things can both be true.",
            "That may also be a problem — does it change whether this one is?",
            cue("what about", "but you also", "but they did", "you'?re one to talk",
                "and yet you"),
            0.6,
        ),
        Fallacy(
            "fear", "Appeal to fear",
            "The consequence is made vivid instead of the claim being supported.",
            "How frightening an outcome is has no bearing on how likely it is.",
            "How likely is that, actually?",
            cue("think of the children", "if we don'?t act", "before it'?s too late",
                "do you want .{0,25} to happen", "god forbid"),
            0.65,
        ),
        Fallacy(
            "post_hoc", "After, therefore because",
            "One thing followed another, so the first is treated as the cause.",
            "Sequence is not causation; coincidence, reverse causation and a shared cause all produce the same order.",
            "What rules out coincidence, or something else causing both?",
            // A bare `ever since` scored 62 corpus hits ("maintained by hand weeding ever since") and
            // carries no causal claim on its own; requiring the event that follows scored 0.
            cue("ever since .{0,25}(started|began|changed|came in|arrived|took over)",
                "right after .{0,25} started", "and that'?s (exactly )?why",
                "no coincidence that", "as soon as .{0,20} came in"),
            0.6,
        ),
        Fallacy(
            "no_true_scotsman", "No true Scotsman",
            "A counter-example is excluded by redefining the group.",
            "Redefining the category to protect the claim makes it unfalsifiable rather than correct.",
            "Was that definition in place before the counter-example, or after it?",
            cue("no (real|true) \\w+ would", "a (real|true) \\w+ (would|wouldn'?t)",
                "they'?re not (really|actually) a"),
            0.85,
        ),
        Fallacy(
            "burden_shift", "Shifting the burden of proof",
            "The listener is asked to disprove the claim rather than the speaker to support it.",
            "Whoever makes the claim owes the evidence; an unfalsifiable claim is not thereby true.",
            "What would count as evidence for it?",
            cue("prove me wrong", "you can'?t prove it (isn'?t|doesn'?t)", "prove that it doesn'?t",
                "nobody'?s disproven"),
            0.8,
        ),
        Fallacy(
            "loaded_question", "Loaded question",
            "The question smuggles in an assumption the listener has not accepted.",
            "Any answer concedes the buried premise, so the question cannot be answered honestly as asked.",
            "That question assumes something — can we check the assumption first?",
            cue("why do you always", "when did you stop", "why are you so",
                "how long have you been"),
            0.7,
        ),
        Fallacy(
            "appeal_to_ignorance", "Appeal to ignorance",
            "Absence of evidence is offered as evidence of absence, or the reverse.",
            "Not knowing something is false is not a reason to believe it is true.",
            "Has anyone looked, and what would they have found if it were false?",
            cue("no evidence that it (isn'?t|doesn'?t)", "nobody has ever proven",
                "there'?s no proof it'?s not", "you can'?t rule (it )?out"),
            0.75,
        ),
        Fallacy(
            "genetic", "Genetic fallacy",
            "The origin of an idea is treated as settling whether it is true.",
            "Where a claim came from is a reason to check it, not a reason to accept or reject it.",
            "Where it came from aside — does the evidence hold up?",
            cue("that came from", "that'?s a \\w+ (talking point|source)", "consider the source",
                "that'?s just (propaganda|marketing)"),
            0.6,
        ),
        Fallacy(
            "moving_goalposts", "Moving the goalposts",
            "The standard of proof changes once it has been met.",
            "A requirement that moves whenever it is satisfied can never be satisfied, so it tests nothing.",
            "Earlier the test was different — which one settles it?",
            cue("that'?s not what i meant", "but that still doesn'?t", "okay but what about",
                "that'?s not good enough either", "sure, but"),
            0.5,
        ),
        Fallacy(
            "circular", "Circular reasoning",
            "The conclusion is used as its own support.",
            "An argument whose premise assumes the conclusion gives no reason to accept it.",
            "Is there a reason that does not already assume the conclusion?",
            // `by definition` dropped: 75 corpus hits, overwhelmingly innocent ("100 by definition").
            cue("because it just is", "because that'?s what it (means|is)",
                "it'?s true because", "true because it'?s true"),
            0.65,
        ),
        Fallacy(
            "false_equivalence", "False equivalence",
            "Two things of very different scale or kind are treated as comparable.",
            "A shared feature does not make two things equivalent where the differences are what matter.",
            "They share that feature — are they alike in the way that counts here?",
            cue("that'?s (exactly )?the same as", "no different (from|than)", "might as well say",
                "so it'?s just like"),
            0.6,
        ),
        Fallacy(
            "cherry_picking", "Cherry picking",
            "The supporting cases are cited and the rest are not mentioned.",
            "Any conclusion can be supported by a selective sample; what matters is the whole body of evidence.",
            "What does the rest of the evidence show?",
            // `at least one` dropped: 120 corpus hits ("at least one full minute", "at least one full
            // cycle"), a quantifier rather than a claim about evidence. It was also the cue that
            // flagged a water-purification instruction as cherry picking during testing.
            cue("one study (found|showed)", "there was a case where", "i read an article",
                "there'?s at least one (study|case|example)"),
            0.55,
        ),
        Fallacy(
            "sunk_cost", "Sunk cost",
            "Past investment is offered as a reason to continue.",
            "Money and time already spent are gone whichever choice is made; only what happens next is decidable.",
            "Ignoring what has already gone in — is continuing the better option from here?",
            cue("we'?ve already (spent|put|invested)", "come too far", "after all this",
                "can${APOS}t back out now", "all that (money|time) would be wasted"),
            0.75,
        ),
        Fallacy(
            "middle_ground", "Splitting the difference",
            "The midpoint between two positions is assumed to be the right one.",
            "When one side is simply wrong, the middle is merely a smaller error.",
            "Is the truth actually between them, or is one of them right?",
            cue("truth is somewhere in (between|the middle)", "both sides have a point",
                "meet in the middle", "the answer${APOS}s in between"),
            0.6,
        ),
    )

    private val BY_ID: Map<String, Fallacy> = ALL.associateBy { it.id }

    fun byId(id: String): Fallacy? = BY_ID[id]

    /**
     * Screen one utterance for candidate fallacies, best first.
     *
     * Confidence is the fallacy's own [Fallacy.weight] lifted slightly when more than one distinct
     * cue for the SAME fallacy fires — two independent phrasings of the move are better evidence
     * than one — and capped below certainty, because this stage is never certain.
     *
     * ⚠️ Very short utterances are refused outright. "What about it?" is four words and trips the
     * whataboutism cue; a fragment that short carries no argument to be wrong about, and the
     * transcriber produces a lot of them.
     */
    fun screen(utterance: String, minConfidence: Double = MIN_CONFIDENCE): List<Candidate> {
        val text = utterance.trim()
        if (text.split(WHITESPACE).size < MIN_WORDS) return emptyList()
        val out = mutableListOf<Candidate>()
        for (f in ALL) {
            val hits = f.cues.mapNotNull { it.find(text)?.value }
            if (hits.isEmpty()) continue
            val boosted = f.weight + (hits.size - 1) * MULTI_CUE_BONUS
            val confidence = minOf(boosted, MAX_CONFIDENCE)
            if (confidence >= minConfidence) out += Candidate(f, confidence, hits.first())
        }
        return out.sortedByDescending { it.confidence }
    }

    /** The single best candidate, or null. What stage 4 uses to decide whether to retrieve at all. */
    fun best(utterance: String, minConfidence: Double = MIN_CONFIDENCE): Candidate? =
        screen(utterance, minConfidence).firstOrNull()

    private val WHITESPACE = Regex("\\s+")

    /** Below this many words there is no argument present, only a fragment. */
    const val MIN_WORDS = 6

    /** A second distinct cue for the same fallacy is real corroboration; it is not worth much more. */
    const val MULTI_CUE_BONUS = 0.08

    /** ⚠️ Never 1.0. A surface cue cannot establish a fallacy — that is stage 5's job. */
    const val MAX_CONFIDENCE = 0.92

    /** Default screening floor. Tuned so the weakest single-cue fallacies still surface. */
    const val MIN_CONFIDENCE = 0.5
}
