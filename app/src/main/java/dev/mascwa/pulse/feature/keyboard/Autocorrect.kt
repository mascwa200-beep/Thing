package dev.mascwa.pulse.feature.keyboard

/**
 * Whether a word finished with a space or punctuation should be replaced, and by what.
 *
 * ⚠️ **When in doubt it does nothing.** A missed correction costs a tap on the strip; a wrong one
 * changes what somebody said without asking, and is the thing people hate most about phone keyboards.
 * So it corrects only when every one of these holds:
 * - the field allows it (never a password, an address, an email);
 * - the word is at least [MIN_LETTERS] long, and is not already a word — in the dictionary or learned;
 * - it is letters only, with no capital after the first (a name, an acronym, a code is left alone);
 * - the best correction is close ([limit]: two slips onto neighbouring keys, or one real mistake, in a
 *   short word) and clearly better than the next suggestion ([MARGIN]).
 *
 * Undoing a correction — delete straight after it — puts back what was typed, and the keyboard learns
 * that word, which is what stops it being corrected again.
 */
object Autocorrect {

    const val MIN_LETTERS = 2

    /**
     * The best correction must beat the next suggestion by at least this much of the score. Measured
     * over the real word list: at 0.2 "teh", "wgat", "recieve", "becuase", "dont" and "adn" are
     * corrected, while near-ties — "helo" between help and hello, "im" between in and I'm — are left
     * for the strip to offer rather than decided for somebody.
     */
    const val MARGIN = 0.2f

    /** How far a correction may be from what was typed, by its length. */
    fun limit(length: Int): Float = if (length < 6) 1f else 2f

    /**
     * The replacement for [typed], or null to leave it as it is. [candidates] are [Suggest]'s, best
     * first; [known] says whether a word is in the dictionary or learned.
     */
    fun decide(typed: String, candidates: List<Suggest.Candidate>, known: (String) -> Boolean, allowed: Boolean): String? {
        if (!allowed || typed.length < MIN_LETTERS) return null
        if (!typed.all { it.isLetter() || it == '\'' }) return null
        if (typed.drop(1).any { it.isUpperCase() }) return null
        if (known(typed)) return null
        val best = candidates.firstOrNull() ?: return null
        if (best.kind != Suggest.Kind.CORRECTION || best.cost > limit(typed.length)) return null
        val second = candidates.getOrNull(1)
        if (second != null && best.score - second.score < MARGIN) return null
        return best.word.takeIf { it != typed }
    }
}
