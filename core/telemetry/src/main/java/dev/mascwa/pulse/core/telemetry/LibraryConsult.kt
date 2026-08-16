package dev.mascwa.pulse.core.telemetry

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
     */
    fun isTopical(entry: GuideSearch.Entry, key: String): Boolean =
        entry.title.contains(key, true) ||
            entry.summary.contains(key, true) ||
            entry.category.contains(key, true) ||
            entry.headings.any { it.contains(key, true) }

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

    /** How the retrieved page is handed to a model. */
    fun groundingBlock(where: String, body: String, maxChars: Int = GROUNDING_CHARS): String =
        "\n\nFrom the device's bundled library, possibly relevant to what was just asked — \"" +
            where + "\":\n" + body.take(maxChars) +
            "\n\nIf it answers the question, answer from it and name the guide. If it does not, " +
            "ignore it entirely and never mention it."

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
