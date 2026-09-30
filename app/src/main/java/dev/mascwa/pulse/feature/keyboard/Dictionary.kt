package dev.mascwa.pulse.feature.keyboard

import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Key
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Layer
import kotlin.math.hypot
import kotlin.math.min

/**
 * The keyboard's English word list: AOSP LatinIME's dictionary source (Apache-2.0), cut to the 91,541
 * words at frequency 40 and up by `tools/keyboard/build_wordlist.py`, with nothing flagged offensive.
 *
 * Held as three parallel arrays sorted by [ORDER] — the lowercased word, then the word — so every
 * question is a binary search over one contiguous run: "is this a word" is one lookup, "what starts
 * with this" is one range, and "what starts with this letter" is one range too.
 *
 * ⚠️ **Nothing typed ever goes into it.** It is read from the APK and never written; the words this
 * phone has learned are held apart, encrypted, by `LearnedWords`, and only ever passed in.
 */
class Dictionary private constructor(
    private val words: Array<String>,
    private val keys: Array<String>,
    private val freqs: IntArray,
) {
    val size: Int get() = words.size

    fun word(i: Int): String = words[i]
    fun frequency(i: Int): Int = freqs[i]

    /** Entry [i]'s lowercased word, held rather than recomputed: suggestions compare tens of thousands a keystroke. */
    fun keyAt(i: Int): String = keys[i]

    /** The highest frequency any casing of [word] has, or null when it is not a word at all. */
    fun frequency(word: String): Int? {
        val k = key(word)
        var i = lowerBound(k)
        var best: Int? = null
        while (i < keys.size && keys[i] == k) {
            best = maxOf(best ?: 0, freqs[i])
            i++
        }
        return best
    }

    fun contains(word: String): Boolean = frequency(word) != null

    /** The indexes of every entry whose lowercased word starts with [prefix] (itself lowercased). */
    fun prefixRange(prefix: String): IntRange {
        val p = key(prefix)
        val from = lowerBound(p)
        // Everything from `from` that starts with p is contiguous; binary search its end.
        var lo = from
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid].startsWith(p)) lo = mid + 1 else hi = mid
        }
        return from until lo
    }

    private fun lowerBound(k: String): Int {
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < k) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** The key every lookup is made on. `Locale.ROOT` underneath, so a Turkish phone agrees. */
        fun key(word: String): String = word.lowercase()

        /** The order the asset is written in and the arrays are held in. */
        val ORDER: Comparator<String> = compareBy<String>({ key(it) }, { it })

        /** One `word<TAB>freq` per line; anything malformed is skipped rather than trusted. */
        fun parse(lines: Sequence<String>): Dictionary {
            val entries = ArrayList<Pair<String, Int>>(100_000)
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val freq = line.substring(tab + 1).trim().toIntOrNull() ?: continue
                entries += line.substring(0, tab) to freq.coerceIn(0, 255)
            }
            return of(entries)
        }

        /**
         * A dictionary of [entries]. Sorted here whatever order they come in, so a hand-edited or
         * reordered asset cannot silently break the binary search — the asset test checks the file is
         * already in order, so this sort is normally a pass over sorted data.
         */
        fun of(entries: List<Pair<String, Int>>): Dictionary {
            val sorted = entries.sortedWith(compareBy(ORDER) { it.first })
            return Dictionary(
                words = Array(sorted.size) { sorted[it].first },
                keys = Array(sorted.size) { key(sorted[it].first) },
                freqs = IntArray(sorted.size) { sorted[it].second },
            )
        }
    }
}

/**
 * Which letters sit next to which on the keyboard, worked out from [KeyboardGeometry] rather than typed
 * in: two letters are neighbours when their keys' centres are within 1.5 key widths — side by side, or
 * diagonally in the row above or below. Hitting a neighbour is the commonest mistake a thumb makes, so a
 * slip onto one costs half what an unrelated letter does.
 */
object KeyNeighbours {

    /** How close two key centres must be, in key widths, to count as neighbours. */
    const val REACH_KEYS = 1.5f

    private val table: Map<Char, Set<Char>> by lazy {
        val m = KeyboardGeometry.Metrics(keyHeight = 1f, rowGap = 0f, keyGap = 0f, padTop = 0f, padBottom = 0f, padSide = 0f)
        val centres = KeyboardGeometry.place(KeyboardModel.rows(Layer.LETTERS, ",", false), KeyboardModel.ROW_WEIGHT, m)
            .mapNotNull { p -> (p.key as? Key.Text)?.value?.singleOrNull()?.takeIf { it.isLetter() }?.let { it to (p.box.cx to p.box.cy) } }
            .toMap()
        centres.mapValues { (c, me) ->
            centres.filter { (k, at) -> k != c && hypot(at.first - me.first, at.second - me.second) <= REACH_KEYS }.keys
        }
    }

    // The same answer as a flat table over a..z, because the edit distance asks it for every pair of
    // letters it compares — tens of thousands of times a keystroke — and a map lookup boxes each Char.
    private val grid: BooleanArray by lazy {
        BooleanArray(26 * 26).also { g ->
            table.forEach { (a, ns) -> if (a in 'a'..'z') ns.forEach { b -> if (b in 'a'..'z') g[(a - 'a') * 26 + (b - 'a')] = true } }
        }
    }

    /** The letters whose keys are next to [c]'s, [c] itself not included. Empty for anything not a letter key. */
    fun of(c: Char): Set<Char> = table[c.lowercaseChar()].orEmpty()

    fun adjacent(a: Char, b: Char): Boolean {
        val x = a.lowercaseChar() - 'a'
        val y = b.lowercaseChar() - 'a'
        return x in 0..25 && y in 0..25 && grid[x * 26 + y]
    }
}

/**
 * The words to offer for what has been typed so far, best first.
 *
 * Three kinds, ranked on one score so they compete fairly:
 * - **the word itself**, when it is one — always first;
 * - **completions**: words that start with it, ranked by how common they are;
 * - **corrections**: words a slip away from it, by a keyboard-aware edit distance ([cost]) where hitting
 *   a neighbouring key, or swapping two letters, costs half an ordinary mistake.
 */
object Suggest {

    enum class Kind { EXACT, COMPLETION, CORRECTION }

    data class Candidate(val word: String, val kind: Kind, val cost: Float, val score: Float)

    /** A learned word ranks as though it were this common: commoner than most, never above "the". */
    const val LEARNED_FREQ = 180

    /** How far a correction may be from what was typed and still be offered at all. */
    const val MAX_OFFER_COST = 2f

    /** Completions cost this much, plus [COMPLETION_PER_LETTER] for each letter they add. */
    const val COMPLETION_COST = 0.35f
    const val COMPLETION_PER_LETTER = 0.05f

    /**
     * Keyboard-aware edit costs. An apostrophe is nearly free: "dont" is one small step from "don't".
     * Doubling a letter, or missing its double, is the other slip thumbs make constantly — "helo",
     * "tommorow" — so it costs half.
     */
    const val NEIGHBOUR_COST = 0.5f
    const val SWAP_COST = 0.5f
    const val APOSTROPHE_COST = 0.25f
    const val DOUBLE_COST = 0.5f

    /**
     * Somebody typing in lower case is rarely after an acronym or a name: "hte" means "the", not "GTE".
     * Offered all the same, but behind the ordinary words. Nothing is added when they typed a capital.
     */
    const val ACRONYM_PENALTY = 0.5f
    const val NAME_PENALTY = 0.25f

    /**
     * The best [n] words for [typed], or empty when nothing is typed. [learned] are the words this phone
     * has learned; each counts as a word at [LEARNED_FREQ].
     */
    fun candidates(typed: String, dict: Dictionary, learned: Collection<String> = emptyList(), n: Int = 3): List<Candidate> {
        if (typed.isEmpty()) return emptyList()
        val t = Dictionary.key(typed)
        val best = HashMap<String, Candidate>()
        val lowerTyping = typed.none { it.isUpperCase() }
        fun offer(word: String, k: String, kind: Kind, cost: Float, freq: Int) {
            val score = when (kind) {
                Kind.EXACT -> 2f + freq / 255f
                else -> freq / 255f - cost - if (lowerTyping) casePenalty(word) else 0f
            }
            val have = best[k]
            if (have == null || score > have.score) best[k] = Candidate(word, kind, cost, score)
        }
        fun consider(word: String, k: String, freq: Int) {
            when {
                k == t -> offer(word, k, Kind.EXACT, 0f, freq)
                k.startsWith(t) -> offer(word, k, Kind.COMPLETION, COMPLETION_COST + COMPLETION_PER_LETTER * (k.length - t.length), freq)
                kotlin.math.abs(k.length - t.length) <= 2 -> {
                    val c = cost(t, k, MAX_OFFER_COST)
                    if (c <= MAX_OFFER_COST) offer(word, k, Kind.CORRECTION, c, freq)
                }
            }
        }
        // Completions: the whole run of words starting with what was typed.
        for (i in dict.prefixRange(t)) consider(dict.word(i), dict.keyAt(i), dict.frequency(i))
        // Corrections: words starting with the typed first letter or one of its neighbours — or with
        // its SECOND letter, which is where "hte" finds "the": two letters swapped at the start.
        val firsts = buildSet {
            add(t[0])
            addAll(KeyNeighbours.of(t[0]))
            t.getOrNull(1)?.let { add(it) }
        }
        for (f in firsts) for (i in dict.prefixRange(f.toString())) {
            val k = dict.keyAt(i)
            if (k.startsWith(t)) continue // already considered as a completion
            if (kotlin.math.abs(k.length - t.length) <= 2) consider(dict.word(i), k, dict.frequency(i))
        }
        learned.forEach { consider(it, Dictionary.key(it), LEARNED_FREQ) }
        return best.values.sortedByDescending { it.score }.take(n).map { it.copy(word = cased(it.word, typed)) }
    }

    /**
     * The keyboard-aware edit distance from [a] to [b] (both lowercased): substitutions, insertions,
     * deletions and swaps of two neighbouring letters, with a slip onto a neighbouring key and a swap
     * costing half. Gives up and answers something over [limit] as soon as it cannot come in under it.
     */
    fun cost(a: String, b: String, limit: Float = Float.MAX_VALUE): Float {
        val n = a.length
        val m = b.length
        var prev2 = FloatArray(m + 1)
        var prev = FloatArray(m + 1)
        for (j in 1..m) prev[j] = prev[j - 1] + indel(b[j - 1])
        var cur = FloatArray(m + 1)
        var prevMin = 0f
        for (i in 1..n) {
            cur[0] = prev[0] + indel(a[i - 1])
            var rowMin = cur[0]
            for (j in 1..m) {
                val ca = a[i - 1]
                val cb = b[j - 1]
                val sub = when {
                    ca == cb -> 0f
                    KeyNeighbours.adjacent(ca, cb) -> NEIGHBOUR_COST
                    else -> 1f
                }
                val drop = if (i > 1 && ca == a[i - 2]) DOUBLE_COST else indel(ca)
                val add = if (j > 1 && cb == b[j - 2]) DOUBLE_COST else indel(cb)
                var v = min(prev[j - 1] + sub, min(prev[j] + drop, cur[j - 1] + add))
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb && ca != cb) {
                    v = min(v, prev2[j - 2] + SWAP_COST)
                }
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            // ⚠️ Not just this row: a swap reaches back two rows, so the row before can still bring a
            // later one under the limit. Only when neither can is it safe to stop.
            if (rowMin > limit && prevMin + SWAP_COST > limit) return rowMin
            prevMin = rowMin
            val t = prev2
            prev2 = prev
            prev = cur
            cur = t
        }
        return prev[m]
    }

    private fun indel(c: Char): Float = if (c == '\'') APOSTROPHE_COST else 1f

    /**
     * How far [word] is from what somebody typing in lower case is likely after. ⚠️ "I" and its
     * contractions are capitals by rule, not names: "im" is after "I'm", and penalising it hands the
     * correction to "in".
     */
    private fun casePenalty(word: String): Float = when {
        word == "I" || word.startsWith("I'") -> 0f
        word.drop(1).any { it.isUpperCase() } -> ACRONYM_PENALTY
        word.firstOrNull()?.isUpperCase() == true -> NAME_PENALTY
        else -> 0f
    }

    /**
     * [word] as the person is typing it: all capitals if they are typing in capitals, a capital first
     * letter if theirs has one, and otherwise as the dictionary spells it — so "London" and "I" keep
     * their capitals however they were typed.
     */
    fun cased(word: String, typed: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } && typed.any { it.isLetter() } ->
            word.uppercase()
        typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercaseChar() }
        else -> word
    }
}
