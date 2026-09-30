package dev.mascwa.pulse.feature.keyboard

/**
 * A word in the suggestion strip. [typed] marks the word exactly as it was typed, offered so it can be
 * kept when the keyboard is about to correct it; [primary] marks the correction the space bar will make.
 */
data class StripWord(val word: String, val typed: Boolean = false, val primary: Boolean = false)

/**
 * Which words go where in the strip's three places — left, middle, right — as Gboard lays them out.
 *
 * - **About to correct**: what was typed on the left, in quotes, so it can be kept with one tap; the
 *   correction in the middle, marked, because that is what the space bar will put in; the next
 *   suggestion on the right.
 * - **Otherwise**: the best suggestion in the middle, where the eye already is, the next two either side.
 *
 * ⚠️ **No word appears twice**, whatever its capitals: "the" beside "The" is one choice offered twice
 * and a slot wasted.
 */
object StripWords {

    fun arrange(typed: String, candidates: List<Suggest.Candidate>, correction: String?): List<StripWord?> {
        if (typed.isEmpty()) return listOf(null, null, null)
        if (correction != null) {
            val other = candidates.map { it.word }.firstOrNull { !same(it, correction) && !same(it, typed) }
            return listOf(StripWord(typed, typed = true), StripWord(correction, primary = true), other?.let { StripWord(it) })
        }
        val words = candidates.map { it.word }.fold(emptyList<String>()) { acc, w -> if (acc.any { same(it, w) }) acc else acc + w }
        return listOf(words.getOrNull(1), words.getOrNull(0), words.getOrNull(2)).map { w -> w?.let { StripWord(it) } }
    }

    /**
     * After a glide: the word put in, in the middle and marked, and the next two either side, so a tap
     * swaps it for what was meant. A path is often ambiguous — "to" and "too", "of" and "off" are drawn
     * the same — and this is where the other reading is one tap away.
     */
    fun glided(words: List<String>): List<StripWord?> {
        val distinct = words.fold(emptyList<String>()) { acc, w -> if (acc.any { same(it, w) }) acc else acc + w }
        val best = distinct.firstOrNull() ?: return listOf(null, null, null)
        return listOf(distinct.getOrNull(1)?.let { StripWord(it) }, StripWord(best, primary = true), distinct.getOrNull(2)?.let { StripWord(it) })
    }

    private fun same(a: String, b: String): Boolean = Dictionary.key(a) == Dictionary.key(b)
}
