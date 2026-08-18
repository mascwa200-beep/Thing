// MIRROR OF core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/StudyQuestions.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

/**
 * Turning written guide prose into something the Computer can ask you.
 *
 * The library is hundreds of guides the app can retrieve from and has never been able to **teach**
 * from. Teaching needs questions, and questions have to come from the prose, because nothing else is
 * on the device.
 *
 * **The governing rule: a confident nonsense question is worse than no question.** Everything here is
 * deterministic and extractive — it never writes a sentence the author did not write. A passage that
 * cannot be turned into an unambiguous gap becomes an open, self-graded prompt instead, which is what
 * real spaced-repetition does for anything not cleanly cloze-able. That is a deliberate ceiling on
 * how clever this can be, chosen over the alternative of inventing plausible rubbish.
 */
object StudyQuestions {

    enum class QuestionKind {
        /** A gap in a real sentence. Checkable, because the answer is a word the author wrote. */
        CLOZE,

        /** An open prompt the reader grades themselves against the passage. Never wrong, never fake. */
        RECALL,

        /**
         * Which step of a procedure comes next.
         *
         * The most checkable question the corpus can ask, and for the material richest in procedures —
         * first aid, food safety, chemistry — knowing the *order* is the entire skill.
         */
        ORDER,
    }

    /**
     * @param prompt what the reader is shown.
     * @param answer the blanked term for [QuestionKind.CLOZE]; the passage to check yourself against
     *   for [QuestionKind.RECALL].
     * @param id stable across runs so a card keeps its review history when the corpus is rebuilt.
     */
    data class Question(
        val id: String,
        val kind: QuestionKind,
        val prompt: String,
        val answer: String,
        val guideId: String,
        val guideTitle: String,
        val heading: String,
        /**
         * For [QuestionKind.ORDER]: the other real steps of the **same** procedure, and the only
         * things a wrong option may ever be.
         *
         * ⚠️ This field exists so the safety rule is structural rather than remembered. A caller
         * cannot hand [QuizBuilder] a fabricated or foreign distractor for a procedure, because the
         * builder does not consult the caller's pool for this kind at all. Every option a learner
         * sees is therefore a genuine instruction from the procedure in front of them — merely in
         * the wrong position. Inventing a plausible-sounding step would put made-up instructions in
         * front of somebody studying CPR or water purification, which is not a risk worth any
         * amount of question variety.
         *
         * Empty for every other kind. Defaulted, so existing construction sites are unaffected.
         */
        val options: List<String> = emptyList(),
    )

    // ---- sentence selection -----------------------------------------------------------------------

    /**
     * Split prose into candidate sentences.
     *
     * Deliberately crude: the corpus is edited prose, not arbitrary text, so a terminator followed by
     * whitespace is a reliable enough boundary. Abbreviations occasionally split a sentence early,
     * which produces a slightly short candidate that the length filter then rejects — a miss, not a
     * wrong question, which is the right way for this to fail.
     */
    fun sentences(body: String): List<String> {
        val flat = body.replace(WHITESPACE, " ").trim()
        if (flat.isEmpty()) return emptyList()
        return SENTENCE_END.split(flat).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Whether a sentence can carry an unambiguous gap.
     *
     * Each rejection is a way a cloze goes wrong in practice:
     * - too short and there is no context left once a word is removed; too long and the reader is
     *   parsing a paragraph rather than recalling a fact.
     * - **more than one blankable term and the reader cannot tell which gap was meant** — the single
     *   most common way auto-cloze produces unanswerable questions.
     * - a question, a bullet fragment or a sentence opening on the blanked term reads as broken text
     *   rather than as a test.
     */
    fun isClozeable(sentence: String): Boolean = blankableTerms(sentence).size == 1 &&
        sentence.length in MIN_SENTENCE..MAX_SENTENCE &&
        !sentence.endsWith("?") &&
        !sentence.startsWith("-") && !sentence.startsWith("•") &&
        // Blanking the opening word leaves a sentence starting with a gap, which reads as a typo.
        blankableTerms(sentence).firstOrNull()?.let { !sentence.startsWith(it) } == true

    /**
     * The terms worth removing: a measurement, a duration, a temperature, a count.
     *
     * Restricted to numeric facts on purpose. A blanked *noun* is usually guessable from context or
     * has several equally good answers ("keep the wound ___" — clean? dry? covered?), whereas "boil
     * for ___ minute" has exactly one answer the author wrote down.
     */
    fun blankableTerms(sentence: String): List<String> =
        blankableRanges(sentence).map { sentence.substring(it) }.distinct()

    /**
     * The same terms as [blankableTerms], as positions.
     *
     * Positions, not strings, because blanking by string search is wrong: `replaceFirst("2", …)` on
     * "follows the formula CnH2n+2" blanks the 2 inside the formula rather than the one the pattern
     * actually matched, so the gap and the answer end up describing different characters. Found by
     * running this over the real corpus.
     */
    internal fun blankableRanges(sentence: String): List<IntRange> =
        NUMERIC_TERM.findAll(sentence)
            .map { it.range }
            .filter { it.last - it.first + 1 >= MIN_BLANK }
            .toList()

    // ---- building questions -------------------------------------------------------------------------

    /** A cloze from [sentence], or null when it cannot carry one. */
    fun cloze(sentence: String, guideId: String, guideTitle: String, heading: String): Question? {
        if (!isClozeable(sentence)) return null
        val at = blankableRanges(sentence).firstOrNull() ?: return null
        val term = sentence.substring(at)
        // Replace by position, never by string search — see blankableRanges.
        val gapped = sentence.substring(0, at.first) + GAP + sentence.substring(at.last + 1)
        return Question(
            id = "cloze:$guideId:$heading:$term".take(ID_MAX),
            kind = QuestionKind.CLOZE,
            prompt = gapped,
            answer = term,
            guideId = guideId,
            guideTitle = guideTitle,
            heading = heading,
        )
    }

    /**
     * An open prompt for a section.
     *
     * Always available, which is what makes the strictness above affordable: a guide with no clean
     * cloze anywhere is still teachable.
     */
    fun recall(guideId: String, guideTitle: String, heading: String, body: String): Question = Question(
        id = "recall:$guideId:$heading".take(ID_MAX),
        kind = QuestionKind.RECALL,
        prompt = "From \"$guideTitle\" — what does \"$heading\" say? Recall what you can, then check.",
        answer = LibraryConsult.firstSentences(body, sentences = RECALL_SENTENCES, maxChars = RECALL_CHARS),
        guideId = guideId,
        guideTitle = guideTitle,
        heading = heading,
    )

    /**
     * The guide's safety warning, as a card that comes back.
     *
     * **184 of the 581 bundled guides carry a `safetyNote` and not one of them was ever taught.** It
     * was rendered in the reader and referenced nowhere else — the same defect as the ordered steps
     * (structured content present and never read), at considerably higher stakes. These are the
     * sentences that matter most in the whole library: "always add concentrated acid to water, never
     * the reverse", "never enter a soil pit deeper than about 1.2 m without shoring". Read once,
     * forgotten by the weekend.
     *
     * ⚠️ **Deliberately a [QuestionKind.RECALL] and never a graded multiple choice.** Distractors
     * would have to be other guides' real safety warnings, and two guides may warn about the same
     * thing in different words — so marking one "wrong here" would teach a reader that a true and
     * possibly applicable precaution does not apply. That is a hazard, and no amount of question
     * variety is worth it. The corpus agrees independently: these notes run to a median of 448
     * characters and only 7 of the 184 sit inside the cloze window, so the checkable forms were never
     * on offer anyway.
     *
     * ⚠️ **The answer is the note in full, never trimmed**, unlike [recall] which caps at
     * [RECALL_CHARS]. A third of these run past that, and cutting one at a fixed length would drop
     * the second half of a warning — which on this material is exactly where "never do X" tends to
     * live.
     *
     * @return null when there is no note, or when it is too slight to be worth a card.
     */
    fun safety(guideId: String, guideTitle: String, note: String?): Question? {
        val text = note?.replace(WHITESPACE, " ")?.trim().orEmpty()
        if (text.length < MIN_SAFETY_NOTE) return null
        return Question(
            id = "$SAFETY_ID_PREFIX$guideId".take(ID_MAX),
            kind = QuestionKind.RECALL,
            prompt = "\"$guideTitle\" carries a safety warning. What is it? " +
                "Recall what you can, then check.",
            answer = text,
            guideId = guideId,
            guideTitle = guideTitle,
            heading = SAFETY_HEADING,
        )
    }

    /**
     * Whether this card is a guide's safety warning.
     *
     * ⚠️ Load-bearing for the callers that build a multiple choice by looking the question's [heading]
     * up among the guide's real sections: [SAFETY_HEADING] is synthetic, and a guide that happened to
     * title a section the same way would otherwise have its safety card quietly answered by a
     * comprehension item about that section instead. Checked by prefix because that is already how
     * ids are namespaced here.
     */
    fun isSafety(question: Question): Boolean = question.id.startsWith(SAFETY_ID_PREFIX)

    /**
     * "What comes next" over an ordered procedure.
     *
     * ⚠️ Needs [MIN_ORDER_STEPS] steps, because three honest distractors have to exist. A shorter
     * procedure produces nothing here rather than something weak — its steps still reach the learner
     * through cloze, which is checkable on its own terms.
     *
     * Positions are 1-based in the prompt because that is how the reader saw them on the page.
     */
    fun procedure(
        guideId: String,
        guideTitle: String,
        heading: String,
        steps: List<String>,
        max: Int = MAX_ORDER_PER_SECTION,
    ): List<Question> {
        val clean = steps.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean.size < MIN_ORDER_STEPS) return emptyList()
        return clean.dropLast(1).mapIndexedNotNull { index, current ->
            val next = clean[index + 1]
            // Every other step of THIS procedure — all true instructions, merely out of place here.
            // ⚠️ The step shown in the prompt is excluded as well as the answer. Leaving it in offers
            // the reader an option they were just told is the *current* step, which is a free
            // elimination and makes a four-choice question a three-choice one. Found by reading real
            // generated questions rather than fixtures — every sample had it sitting there as option D.
            val others = clean.filterIndexed { i, _ -> i != index && i != index + 1 }
            if (others.size < STANDARD_DISTRACTORS) return@mapIndexedNotNull null
            Question(
                id = "order:$guideId:$heading:${index + 1}".take(ID_MAX),
                kind = QuestionKind.ORDER,
                prompt = "In \"$guideTitle — $heading\", step ${index + 1} is:\n\n" +
                    "“$current”\n\nWhat comes next?",
                answer = next,
                guideId = guideId,
                guideTitle = guideTitle,
                heading = heading,
                options = others,
            )
        }.take(max)
    }

    /**
     * Option text a reader can actually take in at a glance.
     *
     * Measured over the real corpus: a step has a median length of 180 characters, so an ordering
     * question puts a **median 962 characters on screen** (p90 1337, max 2153) and 58% of them exceed
     * 900. At phone width that is four options of a dozen lines each and a scroll to see them all,
     * which turns a recall exercise into a reading-speed one — the exact failure [QuizBuilder]'s own
     * documentation says is not a legitimate difficulty.
     *
     * ⚠️ **The load-bearing rule: shortening must never make two options look alike.** Steps of one
     * procedure often open identically ("Put on goggles…", "Put on gloves…"), and two options that
     * truncate to the same text make the question unanswerable — a far worse outcome than a long one.
     * So this returns the **originals unchanged** whenever shortening would reduce how many distinct
     * options are on offer, or would leave one too clipped to mean anything. An over-long readable
     * question beats a compact unanswerable one.
     *
     * Nothing is invented: a shortened option is a genuine prefix of a real step, cut at a sentence
     * boundary where one is available, and always marked with an ellipsis so the reader can see that
     * there is more. The full text is what the explanation shows once they have answered.
     */
    fun shortOptions(options: List<String>, maxChars: Int = MAX_OPTION_CHARS): List<String> {
        val original = options.map { it.trim() }
        if (original.none { it.length > maxChars }) return original
        val short = original.map { shorten(it, maxChars) }
        val keptApart = short.distinctBy { it.lowercase() }.size ==
            original.distinctBy { it.lowercase() }.size
        val keptMeaning = short.all { it.length >= MIN_OPTION_CHARS }
        return if (keptApart && keptMeaning) short else original
    }

    /** One option, cut at the latest sentence boundary that fits, else at a word boundary. */
    private fun shorten(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val head = text.substring(0, maxChars)
        // Half the budget is the floor for a cut: any earlier and the option loses its subject.
        val floor = maxChars / 2
        val sentence = maxOf(head.lastIndexOf(". "), head.lastIndexOf("! "), head.lastIndexOf("? "))
        if (sentence >= floor) return head.substring(0, sentence + 1) + ELLIPSIS
        val space = head.lastIndexOf(' ')
        val cut = if (space >= floor) head.substring(0, space) else head
        return cut.trimEnd(' ', ',', ';', ':') + ELLIPSIS
    }

    /**
     * Questions for one section, best first.
     *
     * Cloze leads because it is checkable; recall always closes the list so a section is never
     * unteachable. Capped because a study prompt is one question, not a worksheet.
     *
     * @param steps an ordered procedure, when the section carries one. **404 sections of the bundled
     *   corpus do, holding 3,298 individual steps, and until this parameter existed not one of them
     *   produced a question** — the call site had them in hand and dropped them.
     * @param ingredients a materials or quantities list. Not turned into "which is missing" questions
     *   on purpose: asserting that something is absent from a materials list is a claim worth getting
     *   wrong, whereas its lines are dense with doses that cloze handles honestly.
     */
    fun forSection(
        guideId: String,
        guideTitle: String,
        heading: String,
        body: String,
        max: Int = MAX_PER_SECTION,
        steps: List<String> = emptyList(),
        ingredients: List<String> = emptyList(),
    ): List<Question> {
        if (body.isBlank() || heading.isBlank()) return emptyList()
        val ordering = procedure(guideId, guideTitle, heading, steps)
        // Steps and materials are sentences too, and they are where the measurements live.
        val extra = (steps + ingredients).mapNotNull { cloze(it.trim(), guideId, guideTitle, heading) }
        val clozes = (sentences(body).mapNotNull { cloze(it, guideId, guideTitle, heading) } + extra)
            .distinctBy { it.id }
        // Ordering first: it is the only form that tests the sequence, which is the point of a procedure.
        return (ordering + clozes).take(max - 1) + recall(guideId, guideTitle, heading, body)
    }

    private val WHITESPACE = Regex("\\s+")

    /** A terminator followed by a space and a capital or digit — an edited-prose sentence boundary. */
    private val SENTENCE_END = Regex("(?<=[.!?])\\s+(?=[A-Z0-9])")

    /**
     * A number carrying a unit or standing as a plain quantity: "3 minutes", "40°C", "2,000 metres",
     * "1/2 cup", "15%".
     *
     * Closed with a negative lookahead rather than `\b`, because a word boundary cannot match after a
     * non-word character: "15%" followed by a space has no boundary after the "%", so the pattern
     * backtracked, dropped the unit, and answered "15" — a percentage silently losing its sign. The
     * lookahead still rejects a digit run glued to letters, which is what the boundary was there for.
     */
    private val NUMERIC_TERM = Regex(
        // Lookbehind for whitespace: a blankable term must begin a whitespace-delimited token. A word
        // boundary alone matches inside "CnH2n+2" and blanks a digit out of a chemical formula.
        // The number must also END in a digit, or "pH 4.6, or frozen" yields the answer "4.6," — and
        // the unit's leading space lives INSIDE the optional group, or a unitless number swallows the
        // space after it and the gap fuses onto the next word ("capped near ______micrometres").
        "(?<=\\s)\\d(?:[\\d,./]*\\d)?(?:\\s?(?:°[CF]|%|" +
            "millilitres|milliliters|millimetres|millimeters|centimetres|centimeters|kilometres|kilometers|" +
            "kilograms|kilogram|minutes|minute|seconds|second|hours|hour|days|day|weeks|week|months|month|" +
            "years|year|metres|meters|litres|liters|grams|gram|pounds|pound|ounces|ounce|inches|inch|" +
            "feet|foot|miles|mile|cups|cup|tablespoons|tablespoon|teaspoons|teaspoon|" +
            "ml|mm|cm|km|kg|mg|°|C|F|L|g|m))?(?!\\w)",
    )

    const val GAP = "______"

    /** Shortest blanked term worth removing. */
    const val MIN_BLANK = 1

    // A sentence needs enough around the gap to be answerable, and few enough words to be a question
    // rather than a comprehension exercise.
    const val MIN_SENTENCE = 45
    const val MAX_SENTENCE = 220

    const val MAX_PER_SECTION = 3

    /** Wrong options on a four-choice question. */
    const val STANDARD_DISTRACTORS = 3

    /**
     * Below this, a procedure cannot be asked about in order.
     *
     * **Five**, not four: two steps are spoken for on every question — the one shown in the prompt
     * and the answer — leaving three to draw distractors from, which is exactly
     * [STANDARD_DISTRACTORS]. A four-step procedure would have to repeat an option, offer the step
     * already on screen, or invent one, and inventing is the thing that must never happen.
     */
    const val MIN_ORDER_STEPS = 5

    /** A long procedure is still one study prompt, not a worksheet on the whole thing. */
    const val MAX_ORDER_PER_SECTION = 2
    const val RECALL_SENTENCES = 3
    const val RECALL_CHARS = 420

    /**
     * How much of a step an option may show — roughly three lines at phone width.
     *
     * Chosen by measuring: at 120 the median ordering question drops from 962 characters on screen to
     * about 700, 96% of options are shortened, and **not one of the 804 questions the real corpus
     * generates hits a collision**. Shorter reads better still but shows almost nothing of any step;
     * longer stops earning the truncation.
     */
    const val MAX_OPTION_CHARS = 120

    /** Below this an option has been clipped past the point of meaning anything. */
    const val MIN_OPTION_CHARS = 24

    /** Marks an option as abbreviated, so nothing is silently dropped. */
    const val ELLIPSIS = " …"

    /** Shorter than this, a "safety warning" is not worth a card of its own. */
    const val MIN_SAFETY_NOTE = 60

    /** Synthetic — a safety note belongs to the guide, not to any one of its sections. */
    const val SAFETY_HEADING = "Safety note"

    /** Ids are namespaced by form; this one is also how [isSafety] recognises the card. */
    const val SAFETY_ID_PREFIX = "safety:"

    /** Ids are persisted as review-card keys; keep them bounded. */
    const val ID_MAX = 180
}
