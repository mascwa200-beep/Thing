// MIRROR OF core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/NewsSummary.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

/**
 * What is worth printing under a headline, and where to stop printing it.
 *
 * A feed's `<description>` is supposed to be a summary. Aggregators routinely make it something
 * else — Google News fills it with a cluster of other outlets' coverage of the same story, opening
 * with the article's own headline — and once the markup is stripped out, the line under the title
 * is the title again, followed by a run-on of fragments.
 *
 * ⚠️ **The rule here does not depend on any particular feed's shape**, which is deliberate: the
 * question it answers is "does this text tell the reader anything the headline above it did not",
 * and that is answerable from the two strings alone. A feed whose summary is real prose keeps it
 * untouched.
 *
 * Pure and CI-tested. Both platforms render the same card, so it lives here rather than in either.
 */
object NewsSummary {

    /**
     * The summary to show beneath [title], or null when it adds nothing.
     *
     * Null when the summary *is* the headline, or the headline followed only by the outlet's name.
     * When it opens with the headline and then continues, the opening is dropped and the rest is
     * returned — that part carries real text, and repeating the title before it does not.
     *
     * Comparison ignores punctuation, case and spacing, because an aggregator's copy of a headline
     * is rarely byte-identical to the one it files beside it: quotes get straightened, ampersands
     * get spelt out, an em dash becomes a hyphen.
     */
    fun subtitle(title: String, summary: String, source: String = ""): String? {
        val text = summary.trim()
        if (text.isEmpty()) return null
        val titleKey = key(title)
        if (titleKey.isEmpty()) return text
        val summaryKey = key(text)
        if (!summaryKey.startsWith(titleKey)) return text

        // The headline is a prefix. Find where it ends in the ORIGINAL text — the key has had
        // characters removed, so its length is not an offset into the string it came from.
        val rest = afterKeyPrefix(text, titleKey.length).trim().trimStart(*LEADING)
        val restKey = key(rest)
        if (restKey.isEmpty()) return null
        // "…headline. Reuters" is the headline and its byline, which the card already shows.
        if (restKey == key(source)) return null
        return rest.trim()
    }

    /**
     * [text] shortened to at most [maxChars], ending at a word.
     *
     * A hard character cut is what a feed parser reaches for and it lands mid-word about as often
     * as not. Backing up to the last space costs a few characters and stops the card ending in a
     * half-typed one.
     */
    fun clip(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        val cut = text.take(maxChars)
        val space = cut.lastIndexOf(' ')
        // A single token longer than the whole budget has no word boundary to back up to, and
        // giving up half the line to find one would be worse than the hard cut.
        val body = if (space >= maxChars / 2) cut.take(space) else cut
        return body.trimEnd().trimEnd(*TRAILING) + "…"
    }

    /** Letters and digits only, lowercased — what two strings have in common when neither is tidy. */
    private fun key(s: String): String = buildString(s.length) {
        for (c in s) if (c.isLetterOrDigit()) append(c.lowercaseChar())
    }

    /** The part of [text] that follows its first [keyChars] letters-and-digits. */
    private fun afterKeyPrefix(text: String, keyChars: Int): String {
        var seen = 0
        for ((i, c) in text.withIndex()) {
            if (c.isLetterOrDigit()) {
                seen++
                if (seen == keyChars) return text.substring(i + 1)
            }
        }
        return ""
    }

    private val LEADING = charArrayOf(' ', '-', '–', '—', ':', '·', '|', ',', '.', ';')
    private val TRAILING = charArrayOf(' ', ',', ';', ':', '-', '–', '—', '·', '|')
}
