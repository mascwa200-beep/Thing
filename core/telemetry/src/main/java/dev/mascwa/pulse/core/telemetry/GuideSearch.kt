package dev.mascwa.pulse.core.telemetry

/**
 * Finding the right guide in the bundled library from a question asked in plain words.
 *
 * The library is hundreds of guides across dozens of categories, and the assistant reaches it by
 * passing whatever the user actually said. The repository's own body search is a single
 * case-insensitive `contains` over the raw shard text, which is the right tool for one distinctive
 * term and useless for a sentence: *"how do I purify water"* appears verbatim in no guide ever
 * written. This ranks the **index** instead — which is resident, carries a title, category, summary
 * and the section headings for every guide, and therefore answers most questions instantly, offline,
 * without opening a single shard.
 *
 * Pure and deterministic, so CI holds the ranking rules.
 */
object GuideSearch {

    /** The fields of one library entry this can rank on. Mirrors the app's index entry, minus its file. */
    data class Entry(
        val id: String,
        val title: String,
        val category: String,
        val summary: String,
        val headings: List<String> = emptyList(),
    )

    data class Hit(val entry: Entry, val score: Double, val matched: Int)

    // ---- tokenising ------------------------------------------------------------------------------

    /**
     * Words carrying no discriminating power in a library query.
     *
     * Deliberately a question-shaped list rather than a general English one: people ask the assistant
     * "how do I…", "what should I do if…", and every one of those words matches thousands of guides.
     * Domain words that merely *feel* generic — water, fire, food — are kept, because in this library
     * they are the subject.
     */
    val STOPWORDS: Set<String> = setOf(
        "how", "what", "when", "where", "why", "who", "which", "whats",
        "do", "does", "did", "doing", "done", "is", "are", "was", "were", "be", "been", "being",
        "can", "could", "should", "would", "will", "shall", "may", "might", "must",
        "the", "a", "an", "and", "or", "but", "if", "then", "than", "that", "this", "these", "those",
        "i", "me", "my", "mine", "you", "your", "yours", "we", "our", "it", "its", "they", "them",
        "to", "of", "in", "on", "at", "by", "for", "with", "from", "into", "about", "as", "so",
        "get", "got", "make", "made", "need", "want", "use", "using", "help", "please", "tell",
        "there", "here", "some", "any", "all", "one", "way", "ways", "thing", "things",
        // Pronoun-ish and connective words that survived the first pass and were seen adding pure
        // noise when the ranker was run over the real library.
        "someone", "somebody", "something", "anyone", "anybody", "anything", "everyone", "everything",
        "having", "without", "before", "after", "during", "while", "again", "still", "just", "very",
        "more", "most", "less", "least", "other", "another", "much", "many", "also", "even", "only",
    )

    /** Shortest token worth keeping — below this a word matches far too much to mean anything. */
    const val MIN_TOKEN = 3

    /**
     * A query reduced to the words worth matching on: lowercased, punctuation stripped, stopwords and
     * very short words dropped, duplicates removed but order kept.
     *
     * Falls back to the raw words when stripping would leave nothing, so a query that is *only*
     * stopwords still searches for something rather than silently returning the whole library.
     */
    fun tokens(query: String): List<String> {
        val raw = query.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }
        val kept = raw.filter { it.length >= MIN_TOKEN && it !in STOPWORDS }.distinct()
        return kept.ifEmpty { raw.distinct() }
    }

    // ---- matching --------------------------------------------------------------------------------

    /** Shortest word length at which a prefix relation is evidence rather than coincidence. */
    private const val MIN_STEM = 4

    /**
     * How well one word answers one token: 2 for the same word, 1 for a shared stem, 0 otherwise.
     *
     * The stem case is checked in **both directions** on purpose — it is what makes "knots" find
     * "knot" and "knot" find "knots" without carrying a stemmer or a plural dictionary.
     */
    internal fun wordMatch(word: String, token: String): Int = when {
        word == token -> 2
        word.length >= MIN_STEM && token.length >= MIN_STEM &&
            (word.startsWith(token) || token.startsWith(word)) -> 1
        else -> 0
    }

    /** The best match any word of [text] makes against [token]. */
    internal fun fieldMatch(text: String, token: String): Int {
        var best = 0
        var start = 0
        val s = text.lowercase()
        while (start <= s.length) {
            var end = start
            while (end < s.length && s[end].isLetterOrDigit()) end++
            if (end > start) {
                best = maxOf(best, wordMatch(s.substring(start, end), token))
                if (best == 2) return 2
            }
            start = if (end == start) start + 1 else end + 1
        }
        return best
    }

    // Field weights. A title is what the guide IS; a summary mentions many things in passing.
    private const val W_TITLE = 10
    private const val W_HEADING = 5
    private const val W_CATEGORY = 4
    private const val W_SUMMARY = 2

    /**
     * Bonus for matching a token at all, before rarity weighting.
     *
     * Answering more of the question beats repeating one word of it: for *"purify water"* a guide
     * about both must beat one that merely says "water" three times.
     */
    private const val W_COVERAGE = 12

    /** The un-weighted field score of one token against one entry — each field counted once, at its best. */
    internal fun fieldScore(entry: Entry, token: String): Int {
        var total = 0
        fieldMatch(entry.title, token).let { if (it > 0) total += it * W_TITLE }
        (entry.headings.maxOfOrNull { fieldMatch(it, token) } ?: 0).let { if (it > 0) total += it * W_HEADING }
        fieldMatch(entry.category, token).let { if (it > 0) total += it * W_CATEGORY }
        fieldMatch(entry.summary, token).let { if (it > 0) total += it * W_SUMMARY }
        return total
    }

    /** How many entries mention [token] anywhere. */
    fun documentFrequency(entries: List<Entry>, token: String): Int =
        entries.count { fieldScore(it, token) > 0 }

    /**
     * How much one word is worth, from how rare it is across the library.
     *
     * ⚠️ **This is what makes the ranking usable, and it was added after watching the field weights
     * alone fail on real questions.** Without it, "treating a snake bite" returns *Depression:
     * Understanding and Treating It* ahead of *Wildlife & Insects*, because a common verb sitting in
     * a title outscores the actual subject noun sitting in a summary; and "tie a bowline" puts
     * *Association Football Rules* level with *Knots & Cordage*. Weighting each word by its rarity
     * fixes both: "snake" and "bowline" are worth several times "treating" and "tie".
     *
     * Smoothed so a word in every guide still scores slightly above zero rather than being erased.
     */
    fun idf(entries: List<Entry>, token: String): Double {
        if (entries.isEmpty()) return 0.0
        val df = documentFrequency(entries, token)
        return kotlin.math.ln(1.0 + entries.size.toDouble() / (1.0 + df))
    }

    /** How well an entry answers a tokenised query, given each token's rarity weight. */
    fun score(entry: Entry, tokens: List<String>, weights: Map<String, Double>): Double {
        if (tokens.isEmpty()) return 0.0
        var total = 0.0
        for (t in tokens) {
            val field = fieldScore(entry, t)
            if (field <= 0) continue
            total += (field + W_COVERAGE) * (weights[t] ?: 1.0)
        }
        return total
    }

    /** How many of [tokens] the entry matches in any field. */
    fun matchCount(entry: Entry, tokens: List<String>): Int = tokens.count { fieldScore(entry, it) > 0 }

    /**
     * The best guides for [query], strongest first.
     *
     * Ties break on title so the same question always returns the same order — a list that reshuffles
     * between identical queries reads as a malfunction.
     */
    fun rank(entries: List<Entry>, query: String, limit: Int = 6): List<Hit> {
        val t = tokens(query)
        if (t.isEmpty() || entries.isEmpty()) return emptyList()
        val weights = t.associateWith { idf(entries, it) }
        return entries.asSequence()
            .map { Hit(it, score(it, t, weights), matchCount(it, t)) }
            .filter { it.score > 0.0 }
            .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.entry.title })
            .take(limit.coerceAtLeast(1))
            .toList()
    }

    /**
     * The one token worth paying a full-text scan for, or null.
     *
     * A body scan reads every shard, so it is only worth doing for the **rarest** word of the query,
     * and only when that word is rare enough to be a real subject rather than one sprinkled through
     * half the library. Null means the index already knows as much as a scan would, and the caller
     * should not open a single file.
     */
    fun distinctiveToken(entries: List<Entry>, query: String, maxEntries: Int = 12): String? {
        val t = tokens(query)
        if (t.isEmpty() || entries.isEmpty()) return null
        return t
            .map { token -> token to documentFrequency(entries, token) }
            .filter { (_, df) -> df <= maxEntries }
            // Rarest first; among equally rare words the longer one is the more specific subject.
            .minByOrNull { (token, df) -> df * 1000 - token.length }
            ?.first
    }
}
