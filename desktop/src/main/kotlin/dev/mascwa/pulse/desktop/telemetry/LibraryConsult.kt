// MIRROR OF core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/LibraryConsult.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

/**
 * Deciding whether the bundled library actually has something to say, and how much of it to quote.
 *
 * Both the voice service and the chat console consult the library before asking a model. They used
 * to be one surface, so the logic lived privately in that one file; a second private copy is exactly
 * the duplicated-definition mistake this project has already corrected four times with palettes.
 *
 * The pure half lives here so it is decided once and CI can see it. The Android half — opening the
 * index and reading a shard — stays in `data/survival/LibraryConsult`.
 */
object LibraryConsult {

    /**
     * Whether a guide is genuinely about [key], the rarest word in the question.
     *
     * **The bar is deliberately strict, and this is the whole reason the file exists.**
     * [GuideSearch.rank] always returns its closest match — right for a search box, wrong for
     * grounding, where an unasked-for paragraph about association football injected confidently into
     * an answer about something else is worse than having no library at all. Sharing a common noun
     * with half the corpus is not enough to claim a page is about the question; the distinctive word
     * has to actually appear somewhere in it.
     *
     * ⚠️ **Word-aware, not a substring test.** This was `contains` until running it over the real
     * 581-guide library caught "car" matching **Newborn Care Basics for New Parents** — and "lease"
     * matches "release", "tap" matches "tape", "ion" matches "station". [GuideSearch.fieldMatch]
     * compares whole words and shares the ranker's own stem rule, so "knots" still finds "knot" and
     * "water" still finds "waterproofing" while "car" no longer finds "care".
     */
    fun isTopical(entry: GuideSearch.Entry, key: String): Boolean {
        // fieldMatch lowercases the text it scans but not the token, because in ranking the token
        // always arrives from GuideSearch.tokens. A key here can be any word a caller had to hand.
        val k = key.lowercase()
        return GuideSearch.fieldMatch(entry.title, k) > 0 ||
            GuideSearch.fieldMatch(entry.summary, k) > 0 ||
            GuideSearch.fieldMatch(entry.category, k) > 0 ||
            entry.headings.any { GuideSearch.fieldMatch(it, k) > 0 }
    }

    /**
     * The best section of a guide for [query], or null when the guide has no sections at all.
     *
     * Prefers a heading that shares a substantial word with the question — "Boiling" for "how do I
     * boil water" — and falls back to the opening section, which in this library is written as the
     * orientation for the rest.
     *
     * @param headings the guide's section headings, in order. Returns an index into that list.
     */
    fun bestSection(headings: List<String>, query: String): Int? {
        if (headings.isEmpty()) return null
        val words = query.split(' ').map { it.trim() }.filter { it.length > MIN_HEADING_WORD }
        if (words.isEmpty()) return 0
        var bestAt = -1
        var bestScore = 0
        headings.forEachIndexed { i, h ->
            val score = words.count { h.contains(it, true) }
            if (score > bestScore) {
                bestScore = score
                bestAt = i
            }
        }
        return if (bestAt >= 0) bestAt else 0
    }

    /**
     * The opening of a written section, cut at a sentence rather than mid-word.
     *
     * Used for the spoken and shown answer, so it has to end somewhere a person would stop reading.
     *
     * ⚠️ The empty-input guard is not defensive padding: splitting `""` yields one empty part, and
     * without it the loop below emits a lone full stop — which this did, and which was only found by
     * running it on real input rather than reading it.
     */
    fun firstSentences(body: String, sentences: Int = SPOKEN_SENTENCES, maxChars: Int = SPOKEN_CHARS): String {
        val flat = body.replace(WHITESPACE, " ").trim()
        if (flat.isEmpty()) return ""
        val out = StringBuilder()
        var taken = 0
        for (part in flat.split(". ")) {
            if (taken >= sentences || out.length + part.length > maxChars) break
            if (out.isNotEmpty()) out.append(' ')
            out.append(part.trimEnd('.')).append('.')
            taken++
        }
        // A single sentence longer than the cap has no break to cut at; give its opening rather than
        // nothing, since a truncated real answer beats silence.
        return if (out.isEmpty()) flat.take(maxChars) else out.toString()
    }

    /**
     * The answer a person hears or reads: the guide's warning, then the passage, then where it came
     * from.
     *
     * ⚠️ **The warning leads, and it is never trimmed.** 184 of the bundled guides carry one and this
     * path was dropping all of them — so the device would answer "someone is having a seizure" out
     * loud without "never put anything in their mouth and never hold them down", and "they've been
     * electrocuted" without "never touch someone who may still be in contact with electricity". Those
     * are the instructions that stop a bystander causing the injury or becoming the second casualty.
     *
     * Leading with it is not a new judgement: the reader already renders the safety note as a
     * highlighted card **above** the sections, so this makes the spoken answer agree with the written
     * page. It also fails in the right direction — somebody who stops listening early has heard the
     * part that matters most.
     *
     * The passage itself is still cut to a couple of sentences, because reading a five-hundred-word
     * section aloud is not an answer to a spoken question. The warning is not, because a warning cut
     * in half is where the "never do X" clause tends to live.
     */
    fun spokenAnswer(body: String, safety: String?, guideTitle: String): String {
        val warning = safety?.replace(WHITESPACE, " ")?.trim().orEmpty()
        val opening = if (warning.isEmpty()) "" else "First, the warning on this page: $warning "
        return opening + firstSentences(body) + citation(guideTitle)
    }

    /**
     * How the retrieved page is handed to a model.
     *
     * The guide's safety warning rides along in full when it has one, above the passage: a model
     * answering from this page must not be able to leave it out, and it is short enough that the
     * budget below still governs the prose.
     */
    fun groundingBlock(
        where: String,
        body: String,
        safety: String? = null,
        maxChars: Int = GROUNDING_CHARS,
    ): String {
        val warning = safety?.replace(WHITESPACE, " ")?.trim().orEmpty()
        return "\n\nFrom the device's bundled library, possibly relevant to what was just asked — \"" +
            where + "\":\n" +
            (if (warning.isEmpty()) "" else "SAFETY WARNING ON THIS PAGE, repeat it in your answer: $warning\n\n") +
            body.take(maxChars) +
            "\n\nIf it answers the question, answer from it and name the guide. If it does not, " +
            "ignore it entirely and never mention it."
    }

    /** Where a quoted passage came from, for the reader to go and check. */
    fun citation(guideTitle: String): String = " The full page is \"" + guideTitle + "\", in the library."

    private val WHITESPACE = Regex("\\s+")

    /** Shortest question word worth matching a heading on — below this it matches nearly any heading. */
    const val MIN_HEADING_WORD = 3

    // Reading a five-hundred-word section aloud is not an answer to a spoken question, and a wall of
    // retrieved text in a prompt crowds out everything else the model was given.
    const val SPOKEN_SENTENCES = 2
    const val SPOKEN_CHARS = 320
    const val GROUNDING_CHARS = 900
}
