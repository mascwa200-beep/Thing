package dev.mascwa.pulse.feature.shade

/**
 * The notification centre's arithmetic: what Android's shade holds, arranged the way the LCARS home
 * console shows it.
 *
 * Pure — no Android type anywhere in this file — so every rule below runs under JUnit. The platform
 * half, reading a `StatusBarNotification` into an [Entry], is `ShadeNotices`, and holding the live
 * intents is `ShadeStore`; neither decides anything this file decides.
 *
 * The rules, each of which has a plausible wrong answer:
 *
 * - **LCARS's own notifications are not shown.** The home console already draws the board those
 *   notices describe, and the rest are foreground-service lines ("Lock-screen board ready") that are
 *   chrome, not news. Showing them would put the console inside itself.
 * - **A group summary is dropped while any of its children is present**, and kept when it stands
 *   alone. A summary restates its children ("3 new messages"), so showing both counts every message
 *   twice; a summary whose children were all cleared is the only record left, so dropping it would
 *   lose the notice.
 * - **An entry with neither a title nor text is dropped.** There is nothing to show, and a blank row
 *   that opens something is a button with no label.
 * - **Newest first, within an app and across apps**, with the key as a tie-break so two notices
 *   posted in the same millisecond cannot swap places between one refresh and the next.
 */
object ShadeDigest {

    data class Entry(
        val key: String,
        val pkg: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val postedAtMs: Long,
        val clearable: Boolean,
        val groupKey: String,
        val isGroupSummary: Boolean,
    )

    data class Group(val pkg: String, val appLabel: String, val entries: List<Entry>) {
        /** Never called on an empty group: [digest] only builds groups that hold something. */
        val newestAtMs: Long get() = entries.maxOf { it.postedAtMs }
    }

    /** What the panel can honestly say about the reader behind it. */
    enum class Status {
        /** The owner has not switched notification access on for LCARS. */
        OFF,

        /** Switched on, and Android has not bound the reader yet. */
        CONNECTING,

        /** Reading the shade. */
        READY,
    }

    fun status(granted: Boolean, connected: Boolean): Status = when {
        connected -> Status.READY
        granted -> Status.CONNECTING
        else -> Status.OFF
    }

    fun digest(entries: List<Entry>, self: String): List<Group> {
        val shown = entries.filter { it.pkg != self && (it.title.isNotBlank() || it.text.isNotBlank()) }
        val withChildren = shown.asSequence().filter { !it.isGroupSummary }.map { it.groupKey }.toSet()
        val kept = shown.filterNot { it.isGroupSummary && it.groupKey in withChildren }
        return kept.groupBy { it.pkg }
            .map { (pkg, members) ->
                val sorted = members.sortedWith(NEWEST_FIRST)
                Group(pkg, sorted.first().appLabel, sorted)
            }
            .sortedWith(compareByDescending<Group> { it.newestAtMs }.thenBy { it.pkg })
    }

    /** How many notices the panel is showing in all. */
    fun count(groups: List<Group>): Int = groups.sumOf { it.entries.size }

    /** The keys "clear all" would clear — the ones Android itself would let the shade clear. */
    fun clearableKeys(groups: List<Group>): List<String> =
        groups.flatMap { g -> g.entries.filter { it.clearable }.map { it.key } }

    /**
     * What the LOCK screen may say: which apps, and how many from each — never a title or a word of
     * text, because anyone who picks the phone up can read the lock screen.
     */
    fun lockSummary(groups: List<Group>, limit: Int): List<Pair<String, Int>> =
        groups.take(limit).map { it.appLabel to it.entries.size }

    /**
     * How long ago, in the shade's own shorthand: "now", "5m", "2h", "3d".
     *
     * ⚠️ A post time in the future — a phone whose clock was corrected backwards, or an app that
     * stamps its own time — reads as "now", never as a negative age.
     */
    fun age(nowMs: Long, postedAtMs: Long): String {
        val seconds = (nowMs - postedAtMs) / 1_000
        return when {
            seconds < 60 -> "now"
            seconds < 3_600 -> "${seconds / 60}m"
            seconds < 86_400 -> "${seconds / 3_600}h"
            else -> "${seconds / 86_400}d"
        }
    }

    private val NEWEST_FIRST = compareByDescending<Entry> { it.postedAtMs }.thenBy { it.key }
}
