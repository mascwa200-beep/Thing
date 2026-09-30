package dev.mascwa.pulse.feature.keyboard

import dev.mascwa.pulse.feature.keyboard.KeyboardGeometry.Placed
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Key
import kotlin.math.hypot
import kotlin.math.max

/**
 * Glide typing: which words a finger's path across the letters most likely spelled.
 *
 * The method is the one every glide keyboard descends from (SHARK², Kristensson and Zhai): each word
 * has an IDEAL path — straight lines through its letters' key centres — and the path drawn is compared
 * with it twice over, once for WHERE it went and once for its SHAPE wherever it went. The location
 * score is what tells "hello" from "jello"; the shape score is what forgives a thumb that drew the right
 * word a little to one side. A common word is preferred to a rare one that fits as well.
 *
 * ⚠️ **A word is only considered if the path could have spelled it.** It must start within
 * [END_REACH_KEYS] of the word's first key and end within that of its last, and pass within
 * [PASS_REACH_KEYS] of every letter between, in order. That is what keeps the comparison to a few
 * hundred words out of ninety thousand, and what stops a short path matching a long word it only
 * resembles in outline.
 *
 * Distances are in key widths throughout, so nothing here depends on the screen.
 */
object GlideDecoder {

    data class Point(val x: Float, val y: Float)

    data class Choice(val word: String, val score: Float)

    /**
     * A finished glide, as the keyboard hands it over: the [path] drawn, the letters' key [centres] it was
     * drawn over, one key's width ([unit]) in the same units, and the key the finger lifted on — typed
     * instead, as a plain release would type it, when the path spells no word at all.
     */
    data class Stroke(val path: List<Point>, val centres: Map<Char, Point>, val unit: Float, val fallback: Key?)

    /**
     * How far, in key widths, a finger must get from where it came down before it is gliding rather than
     * sliding onto the letter it meant. Under a key's width is the old correction — land on the edge of
     * r, slide into t, lift, get t — and a key's width and more is a word, as Gboard has it: w to e and
     * lift is "we".
     */
    const val START_KEYS = 1.0f

    /** A point is added to the path once the finger is this far past the last one, in key widths. */
    const val STEP_KEYS = 1f / 6f

    /** The most points one path keeps: far past any word, and a bound on a finger that never lifts. */
    const val MAX_POINTS = 1024

    /** Points each path is resampled to before comparing. More buys nothing a thumb can draw. */
    const val SAMPLES = 32

    /** How close the path's two ends must come to a word's first and last keys. */
    const val END_REACH_KEYS = 1.2f

    /** How close the path must pass to every letter between. */
    const val PASS_REACH_KEYS = 1.5f

    /** How much a word's shape counts beside where it was drawn; location decides most. */
    const val SHAPE_WEIGHT = 0.5f

    /** How much being common is worth: a full-frequency word gains this many key widths. */
    const val FREQ_WEIGHT = 0.6f

    /**
     * How much the path's two ends count on their own. A finger comes down and lifts deliberately, so
     * where it did is worth more than any point between — and a mean over the whole path cannot see one
     * key more or less at the end of a long word: "keyboard" and "keyboards" differ by a single key in
     * twenty-five.
     */
    const val END_WEIGHT = 0.5f

    /** Whether a finger coming down on [key] may be starting a glide: only a letter can. */
    fun startsOn(key: Key): Boolean {
        val c = (key as? Key.Text)?.value?.singleOrNull()?.lowercaseChar() ?: return false
        return c in 'a'..'z'
    }

    /** Whether a finger that came down at [from] and is now at [to] has become a glide. */
    fun isGlide(from: Point, to: Point, unit: Float): Boolean = unit > 0f && dist(from, to) >= START_KEYS * unit

    /**
     * Adds [p] to [path] when the finger has moved far enough since the last point to be worth one, and
     * the path is not already full. Returns whether it was added.
     */
    fun extend(path: MutableList<Point>, p: Point, unit: Float): Boolean {
        val last = path.lastOrNull()
        if (last != null && (path.size >= MAX_POINTS || dist(last, p) < STEP_KEYS * unit)) return false
        path += p
        return true
    }

    /** The letters' key centres, off whatever layout the keyboard is showing — empty if it shows none. */
    fun centres(placed: List<Placed>): Map<Char, Point> = placed.mapNotNull { p ->
        val c = (p.key as? Key.Text)?.value?.singleOrNull()?.lowercaseChar()
        if (c != null && c in 'a'..'z') c to Point(p.box.cx, p.box.cy) else null
    }.toMap()

    /**
     * The best [n] words for [path], best first. [unit] is one key's width in the path's own units;
     * [learned] are words this phone has learned, which count as though fairly common.
     */
    fun decode(
        path: List<Point>,
        centres: Map<Char, Point>,
        unit: Float,
        dict: Dictionary,
        learned: Collection<String> = emptyList(),
        n: Int = 3,
    ): List<Choice> {
        if (path.size < 2 || centres.isEmpty() || !(unit > 0f)) return emptyList()
        val drawn = resample(path, SAMPLES)
        val fine = resample(path, SAMPLES * 4)
        val start = path.first()
        val end = path.last()
        val firsts = centres.filterValues { dist(it, start) <= END_REACH_KEYS * unit }.keys
        val lasts = centres.filterValues { dist(it, end) <= END_REACH_KEYS * unit }.keys
        val best = HashMap<String, Choice>()
        fun consider(word: String, freq: Int) {
            val letters = letters(word) ?: return
            if (letters.size < 2 || letters.first() !in firsts || letters.last() !in lasts) return
            if (!passes(letters, centres, fine, PASS_REACH_KEYS * unit)) return
            val ideal = resample(letters.map { centres.getValue(it) }, SAMPLES)
            val ends = (dist(drawn.first(), ideal.first()) + dist(drawn.last(), ideal.last())) / unit
            val score = location(drawn, ideal) / unit + SHAPE_WEIGHT * shape(drawn, ideal) + END_WEIGHT * ends -
                FREQ_WEIGHT * freq / 255f
            val k = Dictionary.key(word)
            val have = best[k]
            if (have == null || score < have.score) best[k] = Choice(word, score)
        }
        for (f in firsts) for (i in dict.prefixRange(f.toString())) consider(dict.word(i), dict.frequency(i))
        learned.forEach { consider(it, Suggest.LEARNED_FREQ) }
        return best.values.sortedBy { it.score }.take(n)
    }

    /**
     * [word]'s letters as keys on the board, a letter typed twice in a row counting once (a finger does
     * not leave a key to press it again). Null when it has a letter with no key — an accented one; an
     * apostrophe or a hyphen is simply not drawn.
     */
    internal fun letters(word: String): List<Char>? {
        val out = ArrayList<Char>(word.length)
        for (ch in word) {
            val c = ch.lowercaseChar()
            when {
                c in 'a'..'z' -> if (out.lastOrNull() != c) out += c
                ch == '\'' || ch == '-' -> Unit
                else -> return null
            }
        }
        return out
    }

    /** Whether [points] pass within [reach] of every one of [letters]' keys, in order. */
    internal fun passes(letters: List<Char>, centres: Map<Char, Point>, points: List<Point>, reach: Float): Boolean {
        var i = 0
        for (c in letters) {
            val key = centres[c] ?: return false
            while (i < points.size && dist(points[i], key) > reach) i++
            if (i == points.size) return false
        }
        return true
    }

    /** [path] as [n] points evenly spaced along its length; a path that never moved is its one point, n times. */
    internal fun resample(path: List<Point>, n: Int): List<Point> {
        if (path.isEmpty() || n < 1) return emptyList()
        if (path.size == 1 || n == 1) return List(n) { path.first() }
        val lengths = FloatArray(path.size)
        for (i in 1 until path.size) lengths[i] = lengths[i - 1] + dist(path[i - 1], path[i])
        val total = lengths.last()
        if (!(total > 0f)) return List(n) { path.first() }
        val out = ArrayList<Point>(n)
        var seg = 1
        for (k in 0 until n) {
            val at = total * k / (n - 1)
            while (seg < path.size - 1 && lengths[seg] < at) seg++
            val a = path[seg - 1]
            val b = path[seg]
            val span = lengths[seg] - lengths[seg - 1]
            val t = if (span > 0f) ((at - lengths[seg - 1]) / span).coerceIn(0f, 1f) else 0f
            out += Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        }
        return out
    }

    /** Mean distance between corresponding points: where the path went. */
    internal fun location(a: List<Point>, b: List<Point>): Float {
        var sum = 0f
        for (i in a.indices) sum += dist(a[i], b[i])
        return sum / a.size
    }

    /**
     * Mean distance once both are moved to the same centre and scaled to the same size: the path's
     * shape, wherever it was drawn. Unitless — each is measured against its own size. Two paths that
     * never moved have the same shape.
     */
    internal fun shape(a: List<Point>, b: List<Point>): Float {
        val na = normalised(a)
        val nb = normalised(b)
        return location(na, nb)
    }

    private fun normalised(p: List<Point>): List<Point> {
        val cx = p.sumOf { it.x.toDouble() }.toFloat() / p.size
        val cy = p.sumOf { it.y.toDouble() }.toFloat() / p.size
        val size = max(p.maxOf { it.x } - p.minOf { it.x }, p.maxOf { it.y } - p.minOf { it.y })
        val s = if (size > 0f) 1f / size else 0f
        return p.map { Point((it.x - cx) * s, (it.y - cy) * s) }
    }

    private fun dist(a: Point, b: Point): Float = hypot(a.x - b.x, a.y - b.y)
}
