package dev.mascwa.pulse.core.telemetry

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Multiple-choice questions that are hard for the right reason.
 *
 * Until now every answer in this app was **self-graded** — the reader was shown the answer and told the
 * app how they did — so the app could never know whether they were right. That is why this exists ahead
 * of any progress tracking: an honest correct-to-incorrect ratio is impossible without questions the
 * machine can actually mark.
 *
 * ## What "hard" is allowed to mean
 *
 * The aim is a **desirable difficulty**: retrieval that feels harder and sticks better. That is a real
 * thing, and it has a line in it.
 *
 * *Legitimate, and the point:* distractors drawn from genuinely related material, so no option can be
 * eliminated without knowing the subject; asking which statement is **not** true, which forces every
 * option to be read; two options separated by one load-bearing detail; and occasionally offering no
 * correct option at all, so "pick the plausible-looking one" stops working.
 *
 * *Not legitimate, and excluded by construction:* two defensible answers, options eliminable on shape
 * alone, and anything that tests reading speed rather than understanding. **Every item this produces
 * has exactly one defensible answer**, and the tests assert it.
 *
 * ## Why the numeric restriction helps
 *
 * [StudyQuestions] only ever blanks a *numeric* fact, because a blanked noun usually has several equally
 * good answers. That restriction is inherited here and is what makes safe distractors possible at all:
 * only one number is the one the author wrote, so a wrong option cannot quietly also be right. Options
 * are held to the **same unit** as the answer — "10 minutes" beside "10 °C" would be eliminable without
 * knowing anything.
 *
 * Pure: the caller supplies the candidate pool (`StudyStore.teach()` already holds the whole guide), so
 * nothing here reads content, and everything is deterministic given a seed.
 */
object QuizBuilder {

    enum class Format {
        /** One right answer among near misses. */
        STANDARD,

        /** Two options a single detail apart. Fewer choices, much less room to guess. */
        DISCRIMINATE,

        /** The right answer is absent and saying so is correct. Rationed — see [NONE_OF_THESE_EVERY]. */
        NONE_OF_THESE,

        /** Which statement is **not** true of this material. Every option has to be evaluated. */
        NEGATIVE,
    }

    data class Choice(val text: String, val correct: Boolean)

    /**
     * @param explanation shown after answering — the sentence the fact actually came from. This is the
     *   half that teaches: being told "wrong" is a score, being shown the passage is a lesson.
     */
    data class QuizItem(
        val questionId: String,
        val prompt: String,
        val choices: List<Choice>,
        val format: Format,
        val guideId: String,
        val guideTitle: String,
        val heading: String,
        val explanation: String,
    ) {
        val correctIndex: Int get() = choices.indexOfFirst { it.correct }
    }

    // ---- numeric terms ------------------------------------------------------------------------------

    /**
     * A blankable term split into its magnitude and its unit — "3 minutes" → 3.0 and "minutes".
     *
     * Null when there is no leading number to read, which is the only case the rest of this file cannot
     * reason about.
     */
    internal fun split(term: String): Pair<Double, String>? {
        val m = LEADING_NUMBER.find(term.trim()) ?: return null
        // Thousands separators are presentation, not value: "2,000 metres" is 2000.
        val value = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val unit = term.trim().substring(m.range.last + 1).trim()
        return value to unit
    }

    /** Same unit, case- and spacing-insensitive. A bare number's unit is the empty string. */
    internal fun sameUnit(a: String, b: String): Boolean =
        a.trim().replace(" ", "").equals(b.trim().replace(" ", ""), ignoreCase = true)

    /**
     * Whether two terms measure the same kind of thing, and so can appear in one list of options.
     *
     * ⚠️ Matching on the unit alone is not enough, and generating items from the real library is what
     * showed it. A **unitless** number carries no clue what it is: a year, a pH, a percentage, a water
     * activity and a plain count are all bare digits. The corpus duly produced "below pH ______ botulinum
     * cannot grow" offering **0.91 and 0.95** — water-activity figures from a neighbouring paragraph —
     * and "climbs above ______ percent" offering **92**, which was a kilojoule value from the sentence
     * before. Both are nonsense as options and both are eliminable by anyone who notices.
     *
     * So unitless terms additionally have to sit in the same magnitude band. A four-digit year only ever
     * competes with other four-digit years; a pH of 4.6 with other single digits. Units, where present,
     * already say what the quantity is, and the nearest-first ordering keeps those sensible.
     */
    internal fun sameQuantity(value: Double, unit: String, other: Double, otherUnit: String): Boolean {
        if (!sameUnit(unit, otherUnit)) return false
        if (unit.isNotBlank()) return true
        if (value <= 0.0 || other <= 0.0) return false
        val ratio = if (value > other) value / other else other / value
        return ratio <= UNITLESS_MAGNITUDE_FACTOR
    }

    /**
     * Candidate wrong answers for [answer], nearest-miss first.
     *
     * Real terms from the pool come first: a number another part of the corpus genuinely uses is a far
     * better distractor than an invented one, because it is the kind of thing a reader might actually
     * confuse it with. Perturbations of the answer are the fallback for when the pool is thin, and are
     * deliberately *multiplicative* — a reader who half-remembers "about ten minutes" should not be able
     * to pick it out by it being the only round number.
     */
    internal fun distractors(
        answer: String,
        pool: List<String>,
        want: Int,
        minSeparation: Double = 0.0,
    ): List<String> {
        val (value, unit) = split(answer) ?: return emptyList()
        val out = LinkedHashSet<String>()

        fun admissible(candidate: Double): Boolean =
            sameQuantity(value, unit, candidate, unit) &&
                !closeEnoughToBeTheSame(candidate, value) &&
                separation(candidate, value) >= minSeparation

        pool.asSequence()
            .mapNotNull { term -> split(term)?.let { term to it } }
            .filter { (_, parsed) -> sameQuantity(value, unit, parsed.first, parsed.second) }
            .filter { (_, parsed) -> admissible(parsed.first) }
            // Nearest first: the closest genuine value in the corpus is the hardest honest distractor.
            .sortedBy { (_, parsed) -> abs(parsed.first - value) }
            .forEach { (term, _) -> if (out.size < want) out += term.trim() }

        for (factor in PERTURBATIONS) {
            if (out.size >= want) break
            if (!admissible(value * factor)) continue
            val candidate = format(value * factor, unit)
            if (candidate != null && out.none { sameNumber(it, candidate) } && candidate != answer.trim()) {
                out += candidate
            }
        }
        return out.toList()
    }

    /** Relative distance between two values — 0.0 when identical, 1.0 when one is double the other. */
    private fun separation(a: Double, b: Double): Double {
        val base = maxOf(abs(a), abs(b))
        return if (base <= 0.0) 0.0 else abs(a - b) / base
    }

    /**
     * Two values that would render identically are the same answer wearing different clothes, and
     * offering both is the "two defensible answers" failure this is built to avoid.
     */
    private fun closeEnoughToBeTheSame(a: Double, b: Double): Boolean = abs(a - b) < SAME_VALUE_EPSILON

    private fun sameNumber(a: String, b: String): Boolean {
        val x = split(a) ?: return a == b
        val y = split(b) ?: return a == b
        return sameUnit(x.second, y.second) && closeEnoughToBeTheSame(x.first, y.first)
    }

    /** A value rendered the way the corpus writes them: whole numbers stay whole. Locale-independent. */
    internal fun format(value: Double, unit: String): String? {
        if (!value.isFinite() || value <= 0.0) return null
        val rounded = value.roundToLong()
        val text = if (abs(value - rounded) < 0.05 && rounded > 0) {
            rounded.toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
        return if (unit.isBlank()) text else "$text $unit"
    }

    // ---- building -----------------------------------------------------------------------------------

    /**
     * A multiple-choice item for a numeric [question], or null when it cannot be made fairly.
     *
     * Null rather than a degraded item on purpose: an item with one plausible distractor and two
     * obvious ones teaches nothing and scores as if it did. The caller falls back to the existing
     * self-graded question, which is honest about what it is.
     */
    fun build(
        question: StudyQuestions.Question,
        pool: List<String>,
        seed: Int,
        sourceSentence: String = "",
    ): QuizItem? {
        if (question.kind != StudyQuestions.QuestionKind.CLOZE) return null
        val answer = question.answer.trim()
        split(answer) ?: return null

        val format = formatFor(seed)
        val wanted = if (format == Format.DISCRIMINATE) DISCRIMINATE_CHOICES - 1 else STANDARD_CHOICES - 1
        val wrong = distractors(answer, pool, wanted)
        if (wrong.size < wanted) return null

        val explanation = sourceSentence.ifBlank {
            question.prompt.replace(StudyQuestions.GAP, answer)
        }

        val choices = when (format) {
            Format.NONE_OF_THESE -> {
                // The right answer is genuinely absent, so every listed value is wrong and the escape
                // hatch is the only correct choice. This is the sharpest format here: it defeats
                // recognition entirely and forces actual recall.
                // ⚠️ Withholding the answer is only fair if nothing on offer could reasonably be
                // mistaken for it. The corpus produced "below pH ______" with the true value 4.6 absent
                // and **4.0** listed — marking that reader wrong for not saying "none of these" would be
                // indefensible. Options for this form must be a clear distance from the truth.
                val extra = distractors(answer, pool, wanted + 1, minSeparation = NONE_OF_THESE_SEPARATION)
                if (extra.size < wanted + 1) return null
                shuffled(extra.map { Choice(it, correct = false) }, seed) + Choice(NONE_OF_THESE_TEXT, true)
            }
            else -> shuffled(wrong.map { Choice(it, false) } + Choice(answer, true), seed)
        }

        return QuizItem(
            questionId = question.id,
            prompt = promptFor(format, question.prompt),
            choices = choices,
            format = format,
            guideId = question.guideId,
            guideTitle = question.guideTitle,
            heading = question.heading,
            explanation = explanation,
        )
    }

    /**
     * A comprehension item: which statement is true of this material.
     *
     * This is the half that tests *understanding* rather than a remembered number. Distractors are real
     * sentences from elsewhere in the library — statements that are perfectly sensible in their own
     * context and simply are not what this section says. Telling them apart requires having understood
     * the section, which is the whole intent.
     *
     * ⚠️ [others] must come from material that does not overlap this heading. A sentence pulled from a
     * closely related section can easily *also* be true here, which would be the two-defensible-answers
     * failure — so the caller draws them from a different guide, and [build] refuses anything sharing a
     * distinctive word with the true statement.
     */
    fun statementItem(
        guideId: String,
        guideTitle: String,
        heading: String,
        trueStatements: List<String>,
        others: List<String>,
        seed: Int,
    ): QuizItem? {
        val truths = trueStatements.map { it.trim() }
            .filter { it.length in MIN_STATEMENT..MAX_STATEMENT }
            .distinct()
        val truth = truths.firstOrNull() ?: return null
        val truthWords = truths.flatMap { distinctiveWords(it) }.toSet()

        val foreign = others.asSequence()
            .map { it.trim() }
            .filter { it.length in MIN_STATEMENT..MAX_STATEMENT }
            .filter { it !in truths }
            .filter { candidate -> !aboutTheSameThing(distinctiveWords(candidate), truthWords) }
            .distinct()
            .toList()

        // ⚠️ The two forms need opposite things, and getting this backwards is the classic way an
        // auto-generated negative item ends up with several defensible answers. "Which is NOT said?"
        // needs MANY true statements and ONE foreign one — the foreign is the answer. "Which IS said?"
        // needs ONE true statement and many foreign ones.
        val negative = seed.mod(NEGATIVE_EVERY) == 0 && truths.size >= STANDARD_CHOICES - 1
        val choices = if (negative) {
            val odd = foreign.firstOrNull() ?: return null
            shuffled(
                listOf(Choice(odd, correct = true)) +
                    truths.take(STANDARD_CHOICES - 1).map { Choice(it, correct = false) },
                seed,
            )
        } else {
            if (foreign.size < STANDARD_CHOICES - 1) return null
            shuffled(
                listOf(Choice(truth, correct = true)) +
                    foreign.take(STANDARD_CHOICES - 1).map { Choice(it, correct = false) },
                seed,
            )
        }

        return QuizItem(
            questionId = "stmt:$guideId:$heading:${truth.take(40)}",
            prompt = if (negative) {
                "Three of these are from \"$guideTitle — $heading\". Which one is NOT?"
            } else {
                "Which of these does \"$guideTitle — $heading\" actually say?"
            },
            choices = choices,
            format = if (negative) Format.NEGATIVE else Format.STANDARD,
            guideId = guideId,
            guideTitle = guideTitle,
            heading = heading,
            // In the negative form the lesson is which statement was the impostor; in the positive form
            // it is the sentence itself.
            explanation = if (negative) "The odd one out is not from this section." else truth,
        )
    }

    /** Content words long enough to carry a subject, lowercased. */
    internal fun distinctiveWords(text: String): Set<String> =
        text.lowercase().split(NON_WORD).filter { it.length >= DISTINCTIVE_LEN }.toSet()

    /**
     * Whether a candidate distractor is about the same thing as the section — in which case it might
     * well also be true here, and offering it would be the two-defensible-answers failure.
     *
     * ⚠️ **One shared word is not a subject.** The first version rejected on any shared word of five
     * letters or more, and running it threw out "Frostbite is rewarmed in tepid **water**" from a
     * section on boiling **water**, and "sighting **through** the hole" from "filtered **through**
     * cloth". Those are ordinary English, not topic markers, and on the real corpus that rule would have
     * rejected nearly every legitimate distractor — leaving no items at all rather than unfair ones,
     * which is a quieter failure and just as bad.
     *
     * Two signals survive that: sharing *several* content words, or sharing one long one. Long words are
     * overwhelmingly the topic-bearing ones — "waterborne", "contamination" — where short ones are the
     * connective tissue every sentence has.
     */
    internal fun aboutTheSameThing(candidate: Set<String>, section: Set<String>): Boolean {
        val shared = candidate.filter { it in section }
        return shared.size >= SHARED_WORDS_MEANS_SAME_SUBJECT || shared.any { it.length >= TOPIC_WORD_LEN }
    }

    private fun promptFor(format: Format, prompt: String): String = when (format) {
        Format.DISCRIMINATE -> "$prompt\n\nOne of these is exactly right."
        Format.NONE_OF_THESE -> "$prompt\n\nCareful — it may not be listed."
        else -> prompt
    }

    /**
     * Whether this review should be asked **as written**, with no options at all.
     *
     * ⚠️ Found by generating over the real library rather than a fixture: between the numeric form and
     * the comprehension form, every single question drew a multiple choice and open recall vanished
     * entirely. That is a bad trade made silently — recognising the right answer among four is a
     * weaker act than producing it from nothing, and generation is the stronger practice even though
     * it is the one the app cannot mark. So a minority of reviews are rationed back to it deliberately.
     *
     * Rationed here rather than in either store, for the same reason the hard formats are: it is a
     * teaching decision, and the two platforms must not come to ration it differently.
     */
    fun asksOpenRecall(seed: Int): Boolean = seed.mod(OPEN_RECALL_EVERY) == 0

    private fun formatFor(seed: Int): Format = when {
        seed.mod(NONE_OF_THESE_EVERY) == 0 -> Format.NONE_OF_THESE
        seed.mod(DISCRIMINATE_EVERY) == 0 -> Format.DISCRIMINATE
        else -> Format.STANDARD
    }

    /**
     * Deterministic shuffle. Without it the correct answer sits wherever it was appended and becomes
     * findable by position alone, which would make every score meaningless.
     */
    internal fun <T> shuffled(items: List<T>, seed: Int): List<T> {
        if (items.size <= 1) return items
        val out = items.toMutableList()
        var state = (seed * 2_654_435_761L) xor 0x5DEECE66DL
        for (i in out.indices.reversed()) {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            val j = ((state ushr 33).toInt().mod(i + 1))
            val tmp = out[i]; out[i] = out[j]; out[j] = tmp
        }
        return out
    }

    // ---- shape --------------------------------------------------------------------------------------

    /** A number, possibly with thousands separators or a decimal part, at the start of a term. */
    private val LEADING_NUMBER = Regex("^(\\d[\\d,]*(?:\\.\\d+)?(?:/\\d+)?)")
    private val NON_WORD = Regex("[^a-z0-9]+")

    const val STANDARD_CHOICES = 4
    const val DISCRIMINATE_CHOICES = 2

    /** How often the sharpest formats appear. Used constantly they stop teaching and start needling. */
    /**
     * How often a review is asked with no options at all. Roughly one in five: enough that producing
     * an answer stays a habit, few enough that most reviews can still be marked objectively.
     */
    const val OPEN_RECALL_EVERY = 5

    const val NONE_OF_THESE_EVERY = 9
    const val DISCRIMINATE_EVERY = 4
    const val NEGATIVE_EVERY = 3

    const val NONE_OF_THESE_TEXT = "None of these"

    /** Values closer than this render the same and would be two spellings of one answer. */
    const val SAME_VALUE_EPSILON = 1e-9

    /** How far apart unitless quantities may be before they are plainly different kinds of thing. */
    const val UNITLESS_MAGNITUDE_FACTOR = 4.0

    /** Minimum relative gap between the withheld answer and anything offered beside it. */
    const val NONE_OF_THESE_SEPARATION = 0.25

    /** Multiplicative, so a half-remembered round number is not the only round option on offer. */
    private val PERTURBATIONS = doubleArrayOf(2.0, 0.5, 3.0, 10.0, 0.25, 5.0, 1.5)

    const val MIN_STATEMENT = 40
    const val MAX_STATEMENT = 240
    const val DISTINCTIVE_LEN = 5

    /** Two content words in common is a subject; one is a coincidence of ordinary English. */
    const val SHARED_WORDS_MEANS_SAME_SUBJECT = 2

    /** A shared word this long is almost always the topic itself, so one is enough. */
    const val TOPIC_WORD_LEN = 8
}
