package dev.mascwa.pulse.core.telemetry

/**
 * The acoustic interrogator's discourse layer — stage 2 of the cascade, and its rate governor.
 *
 * [Fallacies] answers *what might be wrong with this*. This answers the two questions either side of
 * it: **is there an argument here at all**, and **is it worth spending the quantized model on**.
 * Both are policy, both are pure, and both belong under CI rather than in a service where they can
 * only be judged on a device.
 *
 * ⚠️ **The scarce resource is the LLM, not the microphone.** Transcription runs continuously and
 * costs what it costs; a llama.cpp adjudication is seconds of sustained compute on a phone that is
 * also in someone's pocket. Every rule here exists to spend that budget on the utterances most
 * likely to repay it, and the honest framing is that this stage is a *filter*, so its failures are
 * missed detections rather than wrong ones. Missing a fallacy is a much cheaper mistake than
 * flattening the battery, and the design leans that way deliberately.
 *
 * ⚠️ **Speaker identity is deliberately absent.** Whisper produces no reliable diarization, and a
 * feature that guessed at who was speaking would attribute someone's argument to the wrong person —
 * in a transcript that is retained. Nothing here distinguishes speakers, including the owner, which
 * also means the interrogator will screen the owner's own reasoning. That is the point.
 */
object Discourse {

    // ---- what came out of the transcriber -------------------------------------------------

    /**
     * One transcribed span. [startMs]/[endMs] are wall-clock so the cooldown arithmetic works on the
     * same axis as everything else in the app; whisper's own frame offsets are converted at the edge.
     */
    data class Utterance(val text: String, val startMs: Long, val endMs: Long)

    /**
     * What [assess] concluded about an utterance.
     *
     * @param strength 0..1, how much this looks like a claim somebody is arguing for.
     * @param reasoning true when an argument connective is present — "because", "therefore", "which
     *   means". The single most useful cheap signal that reasoning is happening rather than chat.
     * @param hedged true when the speaker marked it as an opinion or a guess. A hedged claim is a
     *   worse target: they have already conceded it might be wrong, so correcting them is pedantry.
     */
    data class Claim(
        val utterance: Utterance,
        val strength: Double,
        val reasoning: Boolean,
        val hedged: Boolean,
    ) {
        val carriesClaim: Boolean get() = strength >= CLAIM_FLOOR
    }

    // ---- vocabulary ------------------------------------------------------------------------

    /**
     * Words a transcriber emits that carry no content. Removed before length is measured, so
     * "um, well, you know, I mean, yeah" does not read as a nine-word argument.
     */
    private val FILLER = setOf(
        "um", "uh", "erm", "ah", "eh", "hmm", "mm", "mhm", "er", "like", "so", "well",
        "okay", "ok", "right", "yeah", "yep", "nah", "anyway", "basically", "literally",
    )

    /** Whole utterances that are acknowledgement rather than speech. Never escalated. */
    private val BACKCHANNEL = Regex(
        "^(y(e|ea)h|yep|yes|no|nah|ok(ay)?|right|sure|mm+|hmm+|uh huh|i see|got it|exactly|" +
            "true|fair enough|of course|thanks|thank you|hi|hello|hey|bye|goodbye|sorry|please)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * ⚠️ Connectives are matched with word boundaries and in their *conjunction* forms only.
     * A bare `so` was tried and rejected — it is the commonest discourse filler in spoken English
     * ("so, anyway…") and appears in [FILLER] for that reason.
     */
    private val CONNECTIVE = Regex(
        "\\b(because|therefore|hence|thus|consequently|since|given that|which means|" +
            "that proves|that shows|it follows that|as a result|the reason is|" +
            "that'?s why|so obviously|which is why)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Markers that the speaker is asserting rather than musing. */
    private val ASSERTIVE = Regex(
        "\\b(obviously|clearly|the fact is|in fact|definitely|certainly|undeniably|" +
            "everyone knows|the truth is|no question|without a doubt|absolutely)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Markers that the speaker has already conceded uncertainty. */
    private val HEDGE = Regex(
        "\\b(i think|i guess|i suppose|maybe|perhaps|possibly|probably|might be|could be|" +
            "not sure|i'?m no expert|correct me if|it seems|apparently|allegedly)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A question, which is usually not a claim — with two deliberate exceptions.
     *
     * ⚠️ The loaded question, the straw man and whataboutism are *question-shaped fallacies*. "Why do
     * you always take their side?", "So you're saying we should abolish it?" and "What about the money
     * they wasted?" are all interrogative and all are the move. Treating every question as claim-free
     * made three of the taxonomy's twenty-five entries permanently unreachable — found by an
     * integration test that runs a worked example of every fallacy through the whole cascade, which
     * neither file's own tests could have caught.
     *
     * ⚠️ `what about` is matched only in its longer forms. A bare "what about" is also how somebody
     * raises a genuine omission; the whataboutism cue in [Fallacies] fires on it either way, and the
     * model at stage 5 is what tells the two apart. What this list controls is only whether stage 2
     * lets the utterance reach that judgement at all.
     */
    private val RHETORICAL = Regex(
        "\\b(why do you always|why are you so|when did you stop|how long have you been|" +
            "what about all|what about the|what about your|" +
            "so you'?re saying|so you (want|think)|do you want .{0,25} to happen|" +
            "where does it end|what would you know about|prove me wrong)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val WORD = Regex("[\\p{L}'’]+")

    // ---- stage 2: is there an argument here ------------------------------------------------

    /** The content words of an utterance: filler dropped, so length means what it looks like. */
    fun contentWords(text: String): List<String> =
        WORD.findAll(text).map { it.value.lowercase() }.filter { it !in FILLER }.toList()

    /**
     * Judge one utterance.
     *
     * The arithmetic is a small weighted sum rather than anything cleverer, because the output feeds
     * a threshold and the inputs are themselves rough. What matters is the ordering it produces:
     * a long sentence with a connective outranks a long sentence without one, which outranks a short
     * one, and a backchannel scores zero however it is phrased.
     */
    fun assess(u: Utterance): Claim {
        val words = contentWords(u.text)
        val trimmed = u.text.trim()
        val backchannel = words.size <= BACKCHANNEL_MAX_WORDS && BACKCHANNEL.containsMatchIn(trimmed)
        if (backchannel || words.isEmpty()) {
            return Claim(u, 0.0, reasoning = false, hedged = false)
        }

        val reasoning = CONNECTIVE.containsMatchIn(trimmed)
        val hedged = HEDGE.containsMatchIn(trimmed)
        val assertive = ASSERTIVE.containsMatchIn(trimmed)
        val rhetorical = RHETORICAL.containsMatchIn(trimmed)
        // A plain question is not a claim; a rhetorical one is the fallacy itself.
        val question = trimmed.endsWith("?") && !rhetorical

        // Length saturates: past a sentence or so, more words stop being evidence of an argument.
        var s = (words.size.toDouble() / LENGTH_SATURATES).coerceAtMost(1.0) * LENGTH_WEIGHT
        if (reasoning) s += CONNECTIVE_WEIGHT
        if (assertive) s += ASSERTIVE_WEIGHT
        // ⚠️ Rhetorical form has to ADD weight, not merely dodge the question penalty. The first
        // draft only suppressed the penalty, and computing the arithmetic before writing the
        // assertion showed why that was useless: "So you're saying we should abolish the entire
        // department overnight?" is nine content words, scores 0.29 against a 0.45 floor, and never
        // reaches stage 3 — so the straw man and the loaded question, two of the taxonomy's
        // twenty-five, were unreachable in practice while looking handled in the source. These
        // phrasings are not ambiguous the way a bare cue is: nobody says "why do you always" as an
        // honest request for information, so the form itself is the evidence.
        if (rhetorical) s += RHETORICAL_WEIGHT
        if (question) s -= QUESTION_PENALTY
        // ⚠️ Hedging does NOT reduce the strength, and that is a correction rather than an omission.
        // The first draft subtracted a penalty, which pushed ordinary hedged sentences below
        // CLAIM_FLOOR — so they returned NO_CLAIM and [Verdict.HEDGED] was unreachable, making the
        // refusal it exists for dead code. "I think everyone knows the funding was never real" is
        // plainly a claim; what hedging changes is whether it is worth correcting, which is a verdict
        // question and is answered in [consider]. Strength measures whether a claim is present, and
        // now only means that.
        return Claim(u, s.coerceIn(0.0, 1.0), reasoning, hedged)
    }

    // ---- the rate governor -----------------------------------------------------------------

    /**
     * What the cascade remembers between utterances.
     *
     * ⚠️ **Repetition is evidence, but escalating on every repeat is nagging.** Somebody who leans on
     * the same move four times in a conversation is doing something more worth naming than somebody
     * who did it once — and being told so four times is intolerable. So a repeat inside the cooldown
     * is COUNTED in [seen] rather than escalated, and the count is available to the surface, which
     * can say "that is the fourth time" once rather than four times. The count is the feature; the
     * cooldown is what makes it bearable.
     *
     * @param lastFiredMs when each fallacy id last escalated.
     * @param seen how many times each fallacy id has been screened, escalated or not.
     * @param recentMs the wall-clock times of recent escalations, for the hourly ceiling.
     */
    data class CascadeState(
        val lastFiredMs: Map<String, Long> = emptyMap(),
        val seen: Map<String, Int> = emptyMap(),
        val recentMs: List<Long> = emptyList(),
    )

    /** Why the cascade did or did not spend the model. Every refusal names itself, for the log. */
    enum class Verdict {
        /** Escalate: wake retrieval and the model. */
        ESCALATE,

        /** No claim in the utterance — backchannel, fragment, or chat. */
        NO_CLAIM,

        /** A claim, but no fallacy cue tripped. */
        NO_CANDIDATE,

        /** The speaker already marked it as a guess; correcting a hedge is pedantry. */
        HEDGED,

        /** This fallacy fired too recently. Counted, not raised. */
        COOLING_DOWN,

        /** The hourly ceiling is reached. Protects the battery and the owner's patience alike. */
        RATE_LIMITED,
    }

    data class Decision(
        val verdict: Verdict,
        val claim: Claim,
        val candidate: Fallacies.Candidate?,
        val state: CascadeState,
    ) {
        val escalate: Boolean get() = verdict == Verdict.ESCALATE

        /** How many times this fallacy has now been screened, including this one. */
        val timesSeen: Int get() = candidate?.let { state.seen[it.fallacy.id] } ?: 0
    }

    /**
     * The whole of stages 2–3 plus the spend policy, in one pure function.
     *
     * ⚠️ [state] is returned rather than mutated, and the returned state is updated on EVERY path
     * that saw a candidate — including the refusals. A cooling-down repeat still increments [seen],
     * which is what makes the count above meaningful; only [Verdict.ESCALATE] moves [lastFiredMs].
     */
    fun consider(u: Utterance, state: CascadeState, nowMs: Long = u.endMs): Decision {
        val claim = assess(u)
        if (!claim.carriesClaim) return Decision(Verdict.NO_CLAIM, claim, null, state)

        val candidate = Fallacies.best(u.text)
            ?: return Decision(Verdict.NO_CANDIDATE, claim, null, state)

        // Count it the moment it is screened, whatever happens next.
        val counted = state.copy(seen = state.seen + (candidate.fallacy.id to (state.seen[candidate.fallacy.id] ?: 0) + 1))

        if (claim.hedged) return Decision(Verdict.HEDGED, claim, candidate, counted)

        val last = counted.lastFiredMs[candidate.fallacy.id]
        if (last != null && nowMs - last < COOLDOWN_MS) {
            return Decision(Verdict.COOLING_DOWN, claim, candidate, counted)
        }

        val withinHour = counted.recentMs.filter { nowMs - it < RATE_WINDOW_MS }
        if (withinHour.size >= MAX_PER_WINDOW) {
            return Decision(Verdict.RATE_LIMITED, claim, candidate, counted.copy(recentMs = withinHour))
        }

        return Decision(
            Verdict.ESCALATE, claim, candidate,
            counted.copy(
                lastFiredMs = counted.lastFiredMs + (candidate.fallacy.id to nowMs),
                recentMs = withinHour + nowMs,
            ),
        )
    }

    // ---- segmentation ----------------------------------------------------------------------

    /**
     * Join transcriber chunks that are really one sentence.
     *
     * Whisper is given fixed-length audio windows, so it cuts wherever the window ended rather than
     * where the speaker did — an argument routinely arrives as two chunks with the connective in the
     * second one, which is precisely the signal [assess] is looking for. Chunks are merged while the
     * earlier one does not end in terminal punctuation and the gap between them is short enough to be
     * the same breath.
     *
     * ⚠️ Merging is capped by [MAX_MERGE_MS]. Without it, a transcriber that never emits a full stop
     * — which happens on music, noise, and some accents — produces one unbounded utterance that grows
     * for as long as the service runs.
     */
    fun segment(chunks: List<Utterance>): List<Utterance> {
        if (chunks.isEmpty()) return emptyList()
        val out = mutableListOf<Utterance>()
        var cur = chunks.first()
        for (next in chunks.drop(1)) {
            val open = cur.text.trimEnd().lastOrNull()?.let { it !in TERMINAL } ?: true
            val gap = next.startMs - cur.endMs
            val span = next.endMs - cur.startMs
            if (open && gap in 0..MAX_GAP_MS && span <= MAX_MERGE_MS) {
                cur = Utterance((cur.text.trimEnd() + " " + next.text.trimStart()).trim(), cur.startMs, next.endMs)
            } else {
                out += cur
                cur = next
            }
        }
        out += cur
        return out
    }

    private val TERMINAL = charArrayOf('.', '!', '?', '…')

    // ---- constants, all owner-tunable -------------------------------------------------------

    /** Below this, [Claim.carriesClaim] is false and the cascade stops at stage 2. */
    const val CLAIM_FLOOR = 0.45

    /** An utterance this short that opens with an acknowledgement is one. */
    const val BACKCHANNEL_MAX_WORDS = 4

    /** Content words past which more length stops being evidence of an argument. */
    const val LENGTH_SATURATES = 12.0

    const val LENGTH_WEIGHT = 0.50
    const val CONNECTIVE_WEIGHT = 0.35
    const val ASSERTIVE_WEIGHT = 0.15

    /**
     * ⚠️ Sized so the shortest real straw man clears [CLAIM_FLOOR]. "So you're saying we should
     * abolish the entire department overnight?" is nine content words — 0.29 from length alone —
     * and these phrasings are short by nature, so anything less leaves the question-shaped fallacies
     * unreachable.
     */
    const val RHETORICAL_WEIGHT = 0.25
    const val QUESTION_PENALTY = 0.30

    /** The same fallacy will not be raised again inside this window. Repeats are counted instead. */
    const val COOLDOWN_MS = 10 * 60 * 1000L

    /** The ceiling window, and how many escalations it allows. */
    const val RATE_WINDOW_MS = 60 * 60 * 1000L
    const val MAX_PER_WINDOW = 6

    /** Chunks further apart than this are separate utterances. */
    const val MAX_GAP_MS = 1_200L

    /** No merged utterance may span longer than this, however the punctuation falls. */
    const val MAX_MERGE_MS = 30_000L
}
