package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs

/**
 * Ranking a food search, over names written in a style nothing else in this app uses.
 *
 * ⚠️ **Why this is not [GuideSearch].** That ranker is IDF-weighted across a title, a category, a
 * summary and a list of headings, and it was tuned against a corpus of long prose documents. Two of
 * its properties are actively wrong here:
 *
 *  - **Rarity weighting inverts the signal.** "chicken" appears in roughly four hundred of these
 *    records, so IDF would give the head noun of the query a *low* weight — the opposite of what it
 *    deserves. A food corpus is a list of things, not a library of documents about things, and a
 *    common word in it is usually the subject rather than filler.
 *  - **Position within the name carries almost all the information**, and `GuideSearch` has no
 *    notion of it. USDA writes names inverted and comma-delimited, general to specific:
 *
 *        Chicken, broilers or fryers, breast, meat only, cooked, roasted
 *        Babyfood, fruit, bananas with tapioca, strained
 *
 *    The first segment is the food family; everything after it is a qualifier. A query word landing
 *    in segment 0 means "this is that food"; the same word in segment 4 means "this food mentions
 *    it". Scoring both the same is how "banana" returns strained baby food.
 *
 * Everything here is pure and injected, so CI holds the rules. The corpus is passed in — the caller
 * owns the reading, because on Android that is an asset scan and this module knows nothing of
 * Android.
 */
object FoodSearch {

    /**
     * One searchable food, flattened to just what ranking looks at.
     *
     * [name] is the whole descriptive name as its source wrote it, commas and all. It is deliberately
     * not tidied: the qualifiers are the distinction between four otherwise identical rows, and a
     * shortened "Chicken breast" collapses raw, roasted, with skin and without into one.
     */
    data class Entry(
        val id: String,
        val name: String,
        val brand: String = "",
        val category: String = "",
    )

    data class Hit(val entry: Entry, val score: Double)

    /** Below this a word is too short to stem safely — "oat" must not match "oatmeal" as a prefix. */
    private const val MIN_STEM = 4

    /**
     * Words that carry no discriminating power in a food name and appear in thousands of them.
     *
     * ⚠️ Deliberately short. A long stop list is how a ranker starts refusing legitimate queries:
     * "raw" and "cooked" look like noise and are the entire difference between two records whose
     * calories differ by a third.
     */
    private val STOP = setOf("and", "or", "with", "the", "of", "in", "a", "an", "as", "to", "from")

    fun tokens(query: String): List<String> {
        val all = query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
        val kept = all.filterNot { it in STOP }
        // ⚠️ Never return empty for a non-empty query. Somebody who typed "with" deserves whatever
        // that matches rather than a silent nothing — the same rule GuideSearch.tokens states.
        return kept.ifEmpty { all }
    }

    /**
     * How much longer than the word a query token may be and still count as the same word.
     *
     * ⚠️ **A compound word is not its first component, and the prefix rule could not tell.** The
     * principle two paragraphs down — "'bean' and 'beans' are one word, where 'corn' and 'cornbread'
     * are two" — was stated and then not enforced: `token.startsWith(word)` accepted `cornbread`
     * against `corn` exactly as readily as `cooked` against `cook`. Measured over the real corpus's
     * 2,936 distinct words, that one line was matching
     *
     *     milkshake -> milk        cheeseburger -> cheese      watermelon -> water
     *     meatballs -> meat        buttermilk   -> butter      grapefruit -> grape
     *     cornbread -> corn        beansprouts  -> bean        chickpeas  -> chick
     *     blueberry -> blue        strawberries -> straw
     *
     * so a search for a cheeseburger was ranking an antipasto that mentions cheese, and one for a
     * watermelon was ranking a whiskey sour prepared with water.
     *
     * An inflection is a short suffix; a different food is a long one. Three characters covers every
     * ending this corpus actually uses — cook/cooked/cooking, roast/roasted/roasting, bake/baked,
     * boil/boiled, grill/grilled, steam/steamed, smoke/smoked — and every pair above is four or
     * more. Both were measured over the whole corpus rather than reasoned about; the exceptions the
     * rule still lets through (cook/cookie, bake/bakery) are a stem match at [W_STEM], where an
     * exact match on the same query outranks them.
     */
    private const val MAX_STEM_GAP = 3

    /** Below this a singular is too short to strip an "s" from safely — "gas" must not become "ga". */
    private const val MIN_SINGULAR = 3

    /**
     * The word with a simple English plural removed, or null if it has none.
     *
     * ⚠️ **A prefix rule cannot express a plural, and measuring the real corpus is what proved it.**
     * The length guard on [MIN_STEM] exists to stop "oat" reaching "oatmeal", and it also silently
     * blocked every four-letter plural of a three-letter food: typing "yams" against a corpus
     * holding "Yam, raw" returned **literally nothing**, in the scorer *and* in the cheap reject.
     * That the same query works for "eggs" and "figs" is luck — the corpus happens to store those
     * pluralised, and relying on that is relying on somebody else's spelling.
     *
     * This is a separate, narrower rule and the two do not interfere: stripping one trailing "s"
     * can never turn "oat" into "oatmeal".
     */
    internal fun singular(word: String): String? =
        if (word.length > MIN_SINGULAR && word.endsWith("s") && !word.endsWith("ss")) {
            word.dropLast(1)
        } else {
            null
        }

    /**
     * How well one word answers one query token: 2 the same word, 1 a related one, 0 unrelated.
     *
     * ⚠️ **A plural counts as the SAME word, not a partial match**, and that distinction was worth
     * finding: "bean" against a corpus holding "Beans, NFS" was losing to "Bean cake" by two
     * hundredths of a point, because the plural was discounted to a stem match and that almost
     * exactly cancelled the head-coverage advantage of being a bean rather than a cake made of them.
     *
     * The principle is that "bean" and "beans" are one word, where "corn" and "cornbread" are two.
     * Prefix stemming stays a partial match, length-guarded in both directions so "oat" cannot reach
     * "oatmeal" — and measured over the real corpus, it does not misbehave: "butter" still answers
     * "Butter, NFS" rather than buttermilk, because an exact head match outweighs a stemmed one.
     */
    internal fun wordMatch(word: String, token: String): Int = when {
        word == token -> 2
        singular(word) == token || singular(token) == word -> 2
        word.length >= MIN_STEM && token.length >= MIN_STEM &&
            (word.startsWith(token) || token.startsWith(word)) &&
            abs(word.length - token.length) <= MAX_STEM_GAP -> 1
        else -> 0
    }

    /** A name split on its commas, each segment lowercased. */
    internal fun segments(name: String): List<String> =
        name.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /**
     * The best match for [token] anywhere in [segment], and 0 if it is not there.
     *
     * Word boundaries, not substrings. This project has corrected the substring trap five times —
     * "car" inside "Newborn Care", "time" inside "Maritime" — and a food corpus is full of the same
     * shape: "oil" inside "boiled", "ham" inside "graham".
     */
    internal fun segmentMatch(segment: String, token: String): Int {
        var best = 0
        var start = 0
        while (start <= segment.length) {
            var end = start
            while (end < segment.length && segment[end].isLetterOrDigit()) end++
            if (end > start) {
                best = maxOf(best, wordMatch(segment.substring(start, end), token))
                if (best == 2) return 2
            }
            start = if (end == start) start + 1 else end + 1
        }
        return best
    }

    /**
     * Weight for a match in segment [index].
     *
     * Steeply front-loaded, because the first segment is the food and the rest are adjectives. The
     * tail never reaches zero: a match in the last segment of a long name still beats no match, and
     * "roasted" genuinely is what somebody meant when they typed it.
     */
    internal fun positionWeight(index: Int): Double = when (index) {
        0 -> 1.0
        1 -> 0.45
        2 -> 0.28
        else -> 0.18
    }

    private const val W_EXACT = 1.0
    private const val W_STEM = 0.62
    private const val W_BRAND = 0.5

    /** Every query token must appear somewhere, or the row is not a result at all. */
    private const val W_COVERAGE = 4.0

    /** How much a fully-accounted-for head noun is worth. See [headCoverage]. */
    private const val W_HEAD = 3.0

    /**
     * What fraction of the name's **first segment** the query accounts for, 0..1.
     *
     * ⚠️ **This replaced a brevity bonus, and running the shipped ranker over the real 13,186-food
     * corpus is the only reason I know it had to.** Brevity looks like a proxy for "generic" and is
     * not one — it conflates *few characters* with *few qualifiers*. Measured, it put:
     *
     *     egg          -> Egg burrito          (over "Egg, whole, raw, fresh")
     *     rice         -> Dirty rice           (over "Rice, white, long-grain, regular, cooked")
     *     salmon       -> Lomi salmon
     *     milk         -> Goat milk
     *     ground beef  -> Spanish rice with ground beef
     *
     * Every one of those is short *and specific*: the query word is a modifier attached to some
     * other head noun. What actually separates them is whether the query accounts for the whole
     * head. "Egg" covers all of `egg`; it covers half of `egg burrito`. That distinction is the
     * difference between a food named this and a dish that mentions it.
     *
     * Only the first segment is considered, because in both source styles it is the food and every
     * later segment is an adjective on it.
     */
    internal fun headCoverage(name: String, terms: List<String>): Double {
        val head = segments(name).firstOrNull() ?: return 0.0
        val words = head.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() && it !in STOP }
        if (words.isEmpty()) return 0.0
        val matched = words.count { w -> terms.any { wordMatch(w, it) > 0 } }
        return matched.toDouble() / words.size
    }

    /**
     * A gentle preference for the shorter of two names, used only to break a tie.
     *
     * Worth far less than [headCoverage] and deliberately so — as the sole generic-ness signal it
     * produced the five wrong answers above. It still earns its place among candidates whose heads
     * are equally covered, where "Bananas, raw" should come before "Bananas, dehydrated, or banana
     * powder". Capped, so a long name can never be pushed below a non-match.
     */
    internal fun brevityBonus(name: String): Double {
        val n = name.length.coerceIn(10, 90)
        return 0.35 * (90 - n) / 80.0
    }

    /**
     * Score one entry against already-tokenised query terms, or 0 if any term is missing.
     *
     * Zero means "not a result", never "a bad result" — the caller filters on it, so a partial match
     * cannot be padded out to the bottom of the list where it looks like an answer.
     */
    fun score(entry: Entry, terms: List<String>): Double {
        if (terms.isEmpty()) return 0.0
        val segs = segments(entry.name)
        val brand = entry.brand.lowercase()
        var total = 0.0
        for (term in terms) {
            var best = 0.0
            segs.forEachIndexed { i, seg ->
                val m = segmentMatch(seg, term)
                if (m > 0) {
                    best = maxOf(best, (if (m == 2) W_EXACT else W_STEM) * positionWeight(i))
                }
            }
            if (brand.isNotEmpty()) {
                val m = segmentMatch(brand, term)
                if (m > 0) best = maxOf(best, (if (m == 2) W_EXACT else W_STEM) * W_BRAND)
            }
            // ⚠️ One missing term drops the row entirely. A search for "chicken breast" that returns
            // every chicken in the database because one word matched is not a search.
            if (best == 0.0) return 0.0
            total += best
        }
        return W_COVERAGE * total / terms.size +
            W_HEAD * headCoverage(entry.name, terms) +
            brevityBonus(entry.name)
    }

    /**
     * Best first, ties broken by name.
     *
     * ⚠️ Exposed so a caller that scores while streaming — which is what the Android seed reader has
     * to do to bound its memory — orders its results identically to [rank] instead of writing a
     * second comparator. A duplicated ordering is a mistake this project has corrected four times
     * with palettes, and here it would show as the same query returning a different first row
     * depending on which path answered it.
     */
    val ORDER: Comparator<Hit> = compareByDescending<Hit> { it.score }.thenBy { it.entry.name }

    /** The best [limit] entries for [query], best first. */
    fun rank(entries: Sequence<Entry>, query: String, limit: Int = 40): List<Hit> {
        val terms = tokens(query)
        if (terms.isEmpty()) return emptyList()
        return entries
            .map { Hit(it, score(it, terms)) }
            .filter { it.score > 0.0 }
            .sortedWith(ORDER)
            .take(limit)
            .toList()
    }

    /**
     * Does this line of the bundled corpus stand any chance of matching?
     *
     * ⚠️ The cheap reject in front of the real work. The seed is scanned as raw text and only the
     * survivors are parsed into an [Entry] — the same discipline `SurvivalContentRepository` uses for
     * guide bodies, which is what keeps memory at O(hits) rather than O(corpus) whatever the corpus
     * grows to. It over-admits on purpose: a substring hit here is refused properly by [score] a
     * moment later, whereas a miss is a food that can never be found.
     */
    fun couldMatch(line: String, terms: List<String>): Boolean {
        val lower = line.lowercase()
        return terms.all { term ->
            // ⚠️ Must admit everything [wordMatch] would accept, so the two use the same [singular].
            // An earlier version had its own length test here, and it was off by one from the
            // scorer's: "yams" was rejected before scoring even reached it, so a food the ranker
            // would gladly have returned was invisible with nothing to show it had been dropped.
            // ⚠️ And the OTHER prefix direction, which it did not admit. `wordMatch` accepts a
            // corpus word that the token starts with — "cooked" finds "cook" — but a substring test
            // for the whole token cannot see that, so those rows were refused before scoring. The
            // shortest word the gap rule can still reach is `term.take(len - MAX_STEM_GAP)`, so
            // that prefix is exactly what has to be admitted: tight enough to stay a cheap reject,
            // wide enough that nothing the scorer would return is invisible.
            lower.contains(term) || singular(term)?.let { lower.contains(it) } == true ||
                (
                    term.length >= MIN_STEM &&
                        lower.contains(term.take(maxOf(MIN_STEM, term.length - MAX_STEM_GAP)))
                    )
        }
    }
}
