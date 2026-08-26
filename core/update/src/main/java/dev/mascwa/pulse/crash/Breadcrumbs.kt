package dev.mascwa.pulse.crash

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One thing that happened, shortly before something went wrong. */
data class Crumb(val atMs: Long, val category: String, val label: String)

/**
 * A short, bounded memory of what the app was doing, written into every fault report.
 *
 * A stack trace says where the process died and almost never says what led there. A screen opened, a
 * lookup started, a database was asked for, a permission was refused — half a dozen of those in front
 * of the trace usually turn "an exception in a coroutine" into an obvious sequence.
 *
 * ⚠️ **In memory and nowhere else.** It has to be: the process is about to be torn down, so anything
 * that had to reach the disk on its own schedule would not survive to be read. [CrashReporter] copies
 * the ring into the report file at record time, which is the one moment it is still there.
 *
 * ## The content rule, and it is load-bearing
 *
 * ⚠️ **Categories, routes and identifiers only. Never a food name, never a weight, never a note,
 * never anything typed.** A fault report is the single thing in either application that leaves the
 * phone, and a breadcrumb trail is exactly the shape that would smuggle a food diary into it one
 * entry at a time. `"log" / "add:barcode"` is a useful crumb; `"log" / "add:cheese sandwich"` is a
 * privacy leak wearing a diagnostic's clothes. The scrubber catches credentials, not groceries.
 *
 * Every call site is written to that rule and the reviewer of a new one should apply it.
 */
object Breadcrumbs {

    /**
     * ⚠️ Bounded, and small on purpose. This is held for the life of the process whether or not
     * anything ever goes wrong, so it is sized to be free — two hundred crumbs is a few tens of
     * kilobytes — and to cover the recent past rather than the whole session. What matters for a
     * fault is the last minute, not the last hour.
     */
    const val CAP = 200

    private val ring = ArrayDeque<Crumb>(CAP)

    /**
     * Record something that happened. Cheap, thread-safe, and never throws.
     *
     * @param category a coarse area — `nav`, `log`, `food`, `body`, `update`, `sync`.
     * @param label what happened within it, from the content rule above.
     */
    fun drop(category: String, label: String) {
        val crumb = Crumb(System.currentTimeMillis(), category, label)
        synchronized(ring) {
            if (ring.size >= CAP) ring.removeFirst()
            ring.addLast(crumb)
        }
    }

    /** Oldest first, which is the order they read in. A copy, so the caller cannot see it change. */
    fun recent(): List<Crumb> = synchronized(ring) { ring.toList() }

    /** Wipe the trail — for a "clear diagnostics" control, so clearing is honest about what it clears. */
    fun clear() {
        synchronized(ring) { ring.clear() }
    }

    /**
     * The trail as it appears in a report.
     *
     * ⚠️ Times are relative to [now] rather than absolute, and that is deliberate: "0.4s before the
     * fault" is the fact worth reading, where a wall-clock stamp makes you subtract. Absolute time is
     * already in the report header once.
     */
    fun render(now: Long = System.currentTimeMillis()): String {
        val crumbs = recent()
        if (crumbs.isEmpty()) return "(no breadcrumbs — nothing was recorded before this)"
        return buildString {
            for (c in crumbs) {
                val ago = now - c.atMs
                append(String.format(Locale.US, "%8.1fs  ", ago / 1000.0))
                append(c.category).append("  ").append(c.label).append('\n')
            }
        }
    }

    /** Absolute stamp, used only where a crumb is shown on its own rather than beside a fault. */
    fun stampOf(crumb: Crumb): String = STAMP.format(Date(crumb.atMs))

    private val STAMP = SimpleDateFormat("HH:mm:ss", Locale.US)
}
