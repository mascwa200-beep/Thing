package dev.mascwa.pulse.core.telemetry

/**
 * The acoustic interrogator's output layer — stage 6, where a screened candidate becomes something a
 * person reads.
 *
 * ⚠️ **The load-bearing rule of this file is that the reader can always tell what actually judged the
 * utterance.** The cascade has two very different outcomes that look identical if composed
 * carelessly: the quantized model read the whole utterance against a retrieved excerpt and concluded
 * something, or a keyword tripped and nothing else happened. [Provenance] is not decoration — a
 * template dressed up as reasoning is the single most damaging thing this feature could ship,
 * because the whole point is to help someone reason better and it would be doing the opposite.
 * [compose] therefore takes the model draft and the grounding as *nullable inputs* and derives the
 * provenance from what it was actually given, so a caller cannot claim a tier it did not earn.
 *
 * ⚠️ **Nothing here accuses.** The frames come from [Fallacies] and ask the question that tests the
 * claim. That is more useful in a live conversation, and it is far more defensible when the screen
 * has misfired — which, being a keyword screen, it regularly will. A wrong question is a moment's
 * irritation; a wrong accusation is the feature being switched off for good.
 */
object Rebuttal {

    /**
     * How much actually stands behind what is on screen. Ordered weakest first.
     *
     * ⚠️ There is deliberately no tier for "the model was asked and declined to adjudicate". That
     * outcome is not a weaker rebuttal, it is *no rebuttal*, and [compose] is never called for it —
     * see [Discourse.Verdict] for the refusals, which are logged rather than shown.
     */
    enum class Provenance {
        /**
         * A phrase matched. Nothing read the argument. Shown only when the model is unavailable —
         * no download yet, or the device is too hot — and the wording says so plainly.
         */
        PATTERN_ONLY,

        /** A phrase matched and the offline library had something relevant, but the model did not run. */
        GROUNDED,

        /** The model read the utterance and the excerpt and wrote the response. */
        REASONED,
    }

    /**
     * A passage from the offline library, as [LibraryConsult] produces it.
     *
     * @param excerpt the text the model was given, and which the citation points at.
     */
    data class Grounding(
        val guideTitle: String,
        val section: String,
        val excerpt: String,
        /** Optional: the retrieval path exposes a title and a section, not always an id. */
        val guideId: String? = null,
    ) {
        /** How the source is named to the reader. The library is curated, so the title is worth showing. */
        fun cite(): String = if (section.isBlank()) guideTitle else "$guideTitle — $section"
    }

    /**
     * What the surface renders.
     *
     * @param label the fallacy's plain-English name.
     * @param question the Socratic frame — the thing actually worth saying out loud.
     * @param note one line on why the move does not support the conclusion.
     * @param quote the words that tripped the screen, so the reader can see what was matched and
     *   dismiss it instantly when the match was silly.
     * @param citation present only when [Grounding] was supplied.
     * @param provenance derived, never passed in.
     * @param repeatNote set when this move has been screened before in the same conversation.
     */
    data class Response(
        val label: String,
        val question: String,
        val note: String,
        val quote: String,
        val citation: String?,
        val provenance: Provenance,
        val repeatNote: String?,
    ) {
        /**
         * The one-line form: what to put on a notification row, or hand to the speech engine.
         *
         * ⚠️ The question alone, with no label and no hedging. A spoken "Appeal to popularity,
         * confidence zero point seven" is unusable; "If most people believed the opposite, would that
         * make the opposite true?" is the entire value of the feature.
         */
        fun speakable(): String = question

        /** The full form, for the screen. Ordered so the useful line is first. */
        fun display(): String = buildString {
            append(question)
            append("\n\n")
            append(label)
            append(" · ")
            append(note)
            repeatNote?.let { append("\n"); append(it) }
            citation?.let { append("\n"); append("Source: "); append(it) }
            if (provenance == Provenance.PATTERN_ONLY) {
                append("\n")
                append(UNREASONED_CAVEAT)
            }
        }
    }

    /**
     * Compose the response.
     *
     * @param modelDraft what llama.cpp wrote, if it ran. Blank or null means it did not.
     * @param grounding the retrieved passage, if retrieval found one.
     * @param timesSeen [Discourse.Decision.timesSeen] — used only to add the repeat line.
     */
    fun compose(
        candidate: Fallacies.Candidate,
        modelDraft: String? = null,
        grounding: Grounding? = null,
        timesSeen: Int = 1,
    ): Response {
        val draft = modelDraft?.trim()?.takeIf { it.isNotEmpty() }
        val provenance = when {
            draft != null -> Provenance.REASONED
            grounding != null -> Provenance.GROUNDED
            else -> Provenance.PATTERN_ONLY
        }
        // ⚠️ The model's own words replace the frame when it ran, but the frame is what is shown
        // otherwise — never a truncated excerpt pretending to be an argument.
        val question = draft?.let { trimToOneThought(it) } ?: candidate.fallacy.frame
        return Response(
            label = candidate.fallacy.label,
            question = question,
            note = candidate.fallacy.why,
            quote = candidate.trigger,
            citation = grounding?.cite(),
            provenance = provenance,
            repeatNote = repeatNote(timesSeen),
        )
    }

    /**
     * ⚠️ Said once, not once per occurrence — see [Discourse.CascadeState], which counts repeats
     * inside the cooldown precisely so this line can exist. Silent on the first sighting, because
     * "that is the first time" is not information.
     */
    internal fun repeatNote(timesSeen: Int): String? = when {
        timesSeen < REPEAT_FLOOR -> null
        timesSeen == 2 -> "That is the second time this has come up."
        else -> "That is the ${ordinal(timesSeen)} time this has come up."
    }

    private fun ordinal(n: Int): String = when (n) {
        3 -> "third"; 4 -> "fourth"; 5 -> "fifth"; 6 -> "sixth"; 7 -> "seventh"
        8 -> "eighth"; 9 -> "ninth"; 10 -> "tenth"
        // Past ten the exact count stops mattering and the phrasing gets silly.
        else -> "$n" + when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
    }

    /**
     * Keep the model's first complete thought and drop the rest.
     *
     * A quantized model asked for one sentence will often produce three, and the surface this feeds
     * is a row on a screen somebody is glancing at mid-conversation. Cuts at a sentence boundary when
     * there is one inside the budget, and never mid-word otherwise.
     *
     * ⚠️ A draft with no sentence boundary at all is truncated with an ellipsis rather than dropped:
     * the model ran, so the reader is owed what it said, and silently discarding its output would
     * make [Provenance.REASONED] a lie.
     */
    internal fun trimToOneThought(draft: String, limit: Int = DRAFT_CHARS): String {
        val one = draft.trim().replace(WHITESPACE, " ")
        if (one.length <= limit) {
            val stop = firstTerminal(one)
            return if (stop != null && stop < one.length - 1) one.substring(0, stop + 1) else one
        }
        val window = one.substring(0, limit)
        firstTerminal(window)?.let { return window.substring(0, it + 1) }
        val lastSpace = window.lastIndexOf(' ')
        return (if (lastSpace > limit / 2) window.substring(0, lastSpace) else window).trimEnd() + "…"
    }

    /**
     * Index of the first sentence-ending mark that is not an abbreviation's full stop.
     *
     * ⚠️ `?`, `!` and `…` are unambiguous. A full stop is not, and the rule below was arrived at by
     * working four real drafts through it rather than by writing what sounded right — an earlier
     * version keyed on the preceding character being uppercase and cut "See e.g. the guide" down to
     * "See e.g.", because the letter before that stop is a lowercase `g`. What actually separates the
     * two cases is the LENGTH of the token before the stop: "e", "g", "Dr" are abbreviations, "point"
     * and "failed" are words. So a stop ends a sentence when it is the last character, or when a
     * word of more than two letters is followed by a space and a capital.
     *
     * Verified against: "That is one point. Another follows." cuts at the stop; "See e.g. the guide."
     * and "Dr. Smith said so." are both left whole; "It failed. It cost money." cuts after "failed.".
     */
    private fun firstTerminal(s: String): Int? {
        for (i in s.indices) {
            when (s[i]) {
                '?', '!', '…' -> return i
                '.' -> {
                    if (i == s.lastIndex) return i
                    if (s[i + 1] != ' ') continue
                    if (!(s.getOrNull(i + 2)?.isUpperCase() ?: true)) continue
                    var start = i
                    while (start > 0 && s[start - 1].isLetter()) start--
                    if (i - start > ABBREVIATION_MAX) return i
                }
            }
        }
        return null
    }

    /** Tokens this long or shorter before a full stop are abbreviations, not sentence ends. */
    private const val ABBREVIATION_MAX = 2

    // ---- stage 5: asking the model ------------------------------------------------------------

    /**
     * What the adjudicator concluded.
     *
     * ⚠️ [present] false is the WHOLE POINT of stage 5 and the commonest correct answer. The cue
     * screen is a keyword matcher and most of what it flags is innocent — "everyone knows" is how
     * people introduce a fact everyone does in fact know. A model that could only agree would make
     * the cascade a keyword matcher with a language model bolted on for confidence.
     */
    data class Judgement(val present: Boolean, val question: String?)

    /**
     * Build the prompt.
     *
     * ⚠️ **The instruction to refuse comes FIRST and is stated as the expected outcome**, because
     * a small instruct model asked "is this an appeal to popularity?" will say yes. Leading with
     * "most of what you see is not a fallacy, and saying so is the useful answer" is the difference
     * between an adjudicator and a rubber stamp, and it is the single line here worth defending.
     *
     * The reply format is two fixed lines rather than JSON: a quantized 1.5B model produces
     * malformed JSON often enough that the parser would be doing most of the work, and there is
     * nothing here that two lines cannot carry.
     */
    fun judgePrompt(
        utterance: String,
        candidate: Fallacies.Candidate,
        grounding: Grounding? = null,
    ): String = buildString {
        append("You are checking one thing somebody said for a reasoning mistake.\n")
        append("Be strict. Most of what you are shown is NOT a fallacy, and saying so is the useful answer.\n")
        append("Judge only what was actually said - not what it might have implied.\n\n")
        append("WHAT WAS SAID: \"").append(utterance.trim()).append("\"\n\n")
        append("SUSPECTED: ").append(candidate.fallacy.label).append(" - ")
        append(candidate.fallacy.what).append("\n")
        append("(matched on the words: \"").append(candidate.trigger).append("\")\n")
        grounding?.let {
            append("\nREFERENCE, from an offline library:\n")
            append(it.excerpt.trim()).append("\n")
        }
        append("\nReply on exactly two lines:\n")
        append("VERDICT: yes or no\n")
        append("QUESTION: one short question that tests the claim, or - if the verdict is no\n")
    }

    /**
     * Read the reply.
     *
     * ⚠️ **Anything unparseable is read as NO.** A model that wandered off the format has not made a
     * judgement, and treating an ambiguous reply as a finding would surface exactly the false
     * positives stage 5 exists to remove. Refusing on doubt costs a missed fallacy, which is the
     * cheap direction — the same asymmetry the whole cascade is built on.
     */
    fun parseJudgement(raw: String?): Judgement {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Judgement(false, null)
        var yes = false
        var question: String? = null
        for (line in text.lineSequence()) {
            val l = line.trim()
            when {
                l.startsWith("VERDICT", ignoreCase = true) ->
                    yes = l.substringAfter(':', "").trim().startsWith("yes", ignoreCase = true)
                l.startsWith("QUESTION", ignoreCase = true) ->
                    question = l.substringAfter(':', "").trim().takeIf { it.isNotEmpty() && it != "-" }
            }
        }
        // A yes with nothing to ask is not usable: the question IS the output, so a verdict without
        // one leaves the surface with a label and no help.
        return if (yes && question != null) Judgement(true, question) else Judgement(false, null)
    }

    private val WHITESPACE = Regex("\\s+")

    /** Below this many sightings the repeat line says nothing worth saying. */
    const val REPEAT_FLOOR = 2

    /** How much of the model's draft survives to the screen. About two lines on a phone. */
    const val DRAFT_CHARS = 220

    /**
     * ⚠️ Shown whenever nothing read the argument. The wording is deliberately plain rather than
     * apologetic — it tells the reader exactly how much weight to give what is above it.
     */
    const val UNREASONED_CAVEAT = "Matched on wording only — nothing has read the argument itself."
}
