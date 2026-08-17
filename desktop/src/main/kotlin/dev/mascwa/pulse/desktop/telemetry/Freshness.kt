// MIRROR OF core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Freshness.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

/**
 * How much to trust what is on screen.
 *
 * Every aggregating screen in this app can show data it did not just fetch — a disk-cache hit, a
 * repository falling back to whatever it last stored, or a refresh that failed while the previous
 * numbers stayed put. Those look identical to a live reading unless something says otherwise, and a
 * price or a weather figure with nothing qualifying it is read as current. That is the same defect the
 * economic indicators had before they carried a vintage.
 *
 * This decides **what to say about it**; the caller decides how loudly to say it. Kept pure so both
 * platforms share one judgement rather than two hand-written vocabularies that drift — a mistake this
 * repo has already had to correct four times with palettes.
 *
 * Time is injected, so every case below is reachable in a test with no clock involved.
 */
object Freshness {

    /**
     * Why the thing on screen might not be current. Ordered from "say nothing" to "say the most".
     *
     * [OFFLINE] and [FAILED] are deliberately separate. Conflating them was the original defect: the
     * only notice this app had said "OFFLINE — SHOWING CACHED DATA", so a device that believed it was
     * online and had simply failed to refresh showed nothing at all.
     */
    enum class State {
        /** Fetched just now, or old enough to still count as current. Nothing to say. */
        LIVE,

        /** Stored data, and we know the device has no connection. */
        OFFLINE,

        /** A refresh was attempted and failed. The device thinks it is online, so this is not [OFFLINE]. */
        FAILED,

        /** Stored data with no error and a live connection — a source served its own cache, and it is old. */
        STORED,

        /** Stored data whose age we do not know. Never guess one; say so instead. */
        UNKNOWN,
    }

    /**
     * The verdict.
     *
     * [label] is empty exactly when [state] is [State.LIVE], so a caller can render unconditionally and
     * get nothing when there is nothing to report.
     */
    data class Reading(
        val state: State,
        val ageMs: Long,
        val label: String,
    ) {
        /** True when there is something worth putting on screen. */
        val worthShowing: Boolean get() = state != State.LIVE
    }

    /**
     * How recent still counts as current.
     *
     * Without this, a source that blips two seconds after a good fetch would announce that it could not
     * update, next to data that is two seconds old — technically true and worth nothing. A notice that
     * fires on every transient failure is one people learn to stop reading, which costs more than the
     * rare case it catches.
     */
    const val GRACE_MS: Long = 120_000L

    /**
     * @param lastUpdatedMs when the shown data was actually obtained; 0 or less when unrecorded.
     * @param servingStored true when this did not come from a successful live fetch.
     * @param refreshFailed true when the most recent attempt to refresh threw.
     */
    fun assess(
        lastUpdatedMs: Long,
        nowMs: Long,
        online: Boolean,
        servingStored: Boolean,
        refreshFailed: Boolean,
        graceMs: Long = GRACE_MS,
    ): Reading {
        // A clean live fetch, and no failure since. Nothing to qualify.
        if (!servingStored && !refreshFailed) return Reading(State.LIVE, 0L, "")

        if (lastUpdatedMs <= 0L) {
            return Reading(State.UNKNOWN, 0L, "Showing stored data — when it arrived wasn't recorded.")
        }

        // A clock that has moved backwards (a manual change, or a timestamp from a source ahead of us)
        // must not become a negative age and read as the future.
        val age = (nowMs - lastUpdatedMs).coerceAtLeast(0L)
        if (age < graceMs) return Reading(State.LIVE, age, "")

        val ago = ElapsedPhrase.describe(age)
        return when {
            // Offline outranks failed on purpose. With no connection the refresh has obviously failed,
            // and saying so explains nothing; "offline" names the cause and implies the remedy.
            !online -> Reading(State.OFFLINE, age, "Offline — showing what was last received, $ago.")
            refreshFailed -> Reading(State.FAILED, age, "Couldn't update — showing what was last received, $ago.")
            else -> Reading(State.STORED, age, "Showing stored data from $ago.")
        }
    }

    /**
     * The age to report for a screen fed by several sources: the **oldest** of them, because a screen is
     * only as current as its stalest part. Timestamps of 0 mean "unrecorded" and are ignored rather than
     * treated as 1970, which would otherwise make every mixed screen claim to be decades old.
     *
     * Returns 0 when nothing carries a timestamp, which [assess] reads as [State.UNKNOWN].
     */
    fun oldestOf(vararg lastUpdatedMs: Long): Long =
        lastUpdatedMs.filter { it > 0L }.minOrNull() ?: 0L
}
