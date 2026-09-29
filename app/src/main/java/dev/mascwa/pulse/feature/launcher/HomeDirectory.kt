package dev.mascwa.pulse.feature.launcher

import java.text.Normalizer
import java.util.Locale

/**
 * How the home screen orders, groups and finds apps — pure, so it is tested rather than eyeballed.
 *
 * Generic over the app type so the Android layer can keep its icons and components on the same
 * objects this sorts; all it needs is a label.
 */
object HomeDirectory {

    /** The heading an app files under: its first letter, or `#` for anything that does not start with one. */
    fun section(label: String): Char {
        val first = fold(label).firstOrNull { !it.isWhitespace() } ?: return OTHER
        return if (first in 'a'..'z') first.uppercaseChar() else OTHER
    }

    /**
     * Apps grouped under their letter, A to Z with `#` last, each group sorted by label.
     *
     * ⚠️ Sorted on the FOLDED label (lower case, accents removed), not the raw one — otherwise
     * "Élan" files after "Zoom" and "eBay" after every capitalised name, which is the order a string
     * compare gives and not one anybody expects from a directory.
     */
    fun <T> grouped(apps: List<T>, label: (T) -> String): List<Pair<Char, List<T>>> =
        apps.groupBy { section(label(it)) }
            .toSortedMap(compareBy<Char> { if (it == OTHER) 1 else 0 }.thenBy { it })
            .map { (letter, members) -> letter to members.sortedWith(compareBy({ fold(label(it)) }, { label(it) })) }

    /**
     * Whether [label] answers [query]: every word typed appears somewhere in the label.
     *
     * Deliberately a substring match per word, not a prefix one: people type "mail" for Gmail and
     * "maps" for Google Maps, and a launcher search that refused those would be the one app on the
     * phone that could not find its own apps. Case and accents are ignored.
     */
    fun matches(label: String, query: String): Boolean {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val haystack = fold(label)
        return words.all { it in haystack }
    }

    /**
     * The dock: [pins] in the order they were pinned, keeping only apps that still exist.
     *
     * An uninstalled app's pin is dropped from what is shown rather than drawn as a blank slot, and
     * is not deleted from the store here — reinstalling the app brings it back where it was.
     */
    fun <T> docked(pins: List<String>, apps: List<T>, key: (T) -> String): List<T> {
        val byKey = apps.associateBy(key)
        return pins.distinct().mapNotNull { byKey[it] }
    }

    /** Lower case, accents stripped, runs of whitespace collapsed. */
    internal fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(COMBINING, "")
            .lowercase(Locale.ROOT)
            .replace(SPACES, " ")
            .trim()

    const val OTHER = '#'
    private val COMBINING = Regex("\\p{Mn}+")
    private val SPACES = Regex("\\s+")
}
