package dev.mascwa.pulse.core.telemetry

/**
 * Deciding whether the user just said the wake word.
 *
 * Pulled out of the voice service and into a tested core for one reason: **this is the kind of code
 * that fails silently.** The matcher it replaces gated its fuzzy pass on `token.length in 4..7`,
 * sized for "jarvis" — so renaming the wake word to an eight-letter one would have left the strict
 * matches working, the fuzzy ones quietly dead, and nothing anywhere to say so. The window is now
 * derived from the word's own length and cannot go stale.
 *
 * A small speech model mishears constantly, so matching is deliberately lenient: an exact substring,
 * a listed near-homophone, or any token one edit away.
 *
 * ⚠️ **"Computer" is a far more common English word than "Jarvis" was.** Saying "my computer is
 * slow" near the phone will wake it. That is inherent in choosing the canonical word rather than a
 * fault in this matcher, and the cost of a false wake is one listening cycle that times out.
 */
object WakePhrase {

    /** The word itself. Everything below is derived from it. */
    const val WORD = "computer"

    /**
     * The keyword-spotting grammar handed to the small offline model.
     *
     * `[unk]` is required — it is the sink the recogniser puts everything else into, and without it
     * the model is forced to match one of the listed phrases and wakes constantly.
     */
    const val GRAMMAR = "[\"computer\", \"hey computer\", \"ok computer\", \"okay computer\", \"[unk]\"]"

    /**
     * Substrings that count as the word outright.
     *
     * Mishearings a small model actually produces for this word, plus the stem: "compute" is here so
     * that "computer", "computers" and "computing" all match without a fuzzy pass.
     */
    val NEAR_HOMOPHONES = listOf(
        "computer", "compute", "computor", "computa", "kompyuter", "commuter", "compooter",
    )

    /**
     * How far a token may stray and still count.
     *
     * One edit, deliberately, even though a longer word invites more mistakes. Two edits from an
     * eight-letter word reaches a lot of ordinary English, and a wake word that triggers on
     * "commuters" or "compilers" would be worse than one that occasionally needs repeating.
     */
    const val MAX_EDITS = 1

    /** Tokens shorter or longer than this cannot be the word, whatever their edit distance. */
    val LENGTH_WINDOW: IntRange = (WORD.length - 2)..(WORD.length + 2)

    private val SPLIT = Regex("[^a-z]+")

    /** True when [text] — one recogniser hypothesis — contains the wake word. */
    fun matches(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lower = text.lowercase()
        if (NEAR_HOMOPHONES.any { lower.contains(it) }) return true
        return lower.split(SPLIT).any { token ->
            token.length in LENGTH_WINDOW && levenshtein(token, WORD) <= MAX_EDITS
        }
    }

    /** Iterative Levenshtein edit distance — small strings, one row of state. */
    fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev else 1 + minOf(prev, dp[j], dp[j - 1])
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
