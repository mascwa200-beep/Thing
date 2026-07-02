package dev.mascwa.pulse.core.telemetry

/**
 * How the operator is getting around the wasteland, inferred from real GPS ground speed (and, when the
 * platform Activity-Recognition API offers one, a mode hint). Each way of travelling covers ground
 * differently, and the game counts them separately — boots earn the most respect per mile; a vehicle eats
 * distance but the wasteland doesn't hand out medals for couch miles. Pure + CI-tested: the on-device layer
 * samples speed (+ optional hint) and accrues per-mode distance, which feeds the achievement ladders.
 */
enum class TransportMode(val label: String) {
    STILL("Still"),
    WALK("On foot"),
    RUN("Running"),
    CYCLE("Cycling"),
    DRIVE("Driving"),
    ;

    /** Whether this mode counts as covering ground (STILL does not accrue travel). */
    val moving: Boolean get() = this != STILL
}

object Transport {
    // Ground-speed thresholds in m/s (approximate; documented in km/h). Overlaps between running and a slow
    // cyclist are broken by the platform hint when present.
    const val STILL_MAX = 0.4   // < ~1.4 km/h — sensor jitter / standing
    const val WALK_MAX = 2.2    // < ~8 km/h
    const val RUN_MAX = 4.5     // < ~16 km/h
    const val CYCLE_MAX = 8.3   // < ~30 km/h; above this is clearly vehicular

    /**
     * Classify the current [speedMps] into a [TransportMode], optionally disambiguated by a platform mode
     * [hint] (e.g. Activity Recognition's ON_BICYCLE / RUNNING). Rules, in order:
     *  - clearly still (below [STILL_MAX]) → [TransportMode.STILL] (a hint can't say you're moving when you're not),
     *  - clearly fast (at/above [CYCLE_MAX]) → [TransportMode.DRIVE] (you can't walk at 30 km/h, whatever the hint),
     *  - otherwise trust a moving hint if given (it disambiguates run-vs-cycle better than raw speed),
     *  - else fall back to the speed bucket.
     */
    fun classify(speedMps: Double, hint: TransportMode? = null): TransportMode {
        val s = speedMps.coerceAtLeast(0.0)
        if (s < STILL_MAX) return TransportMode.STILL
        if (s >= CYCLE_MAX) return TransportMode.DRIVE
        if (hint != null && hint.moving) return hint
        return when {
            s < WALK_MAX -> TransportMode.WALK
            s < RUN_MAX -> TransportMode.RUN
            else -> TransportMode.CYCLE
        }
    }

    /** Accrue [meters] of travel to [mode] in a per-mode totals map (STILL never accrues; negatives ignored). */
    fun accrue(totals: Map<TransportMode, Long>, mode: TransportMode, meters: Long): Map<TransportMode, Long> {
        if (!mode.moving || meters <= 0) return totals
        return totals + (mode to ((totals[mode] ?: 0L) + meters))
    }

    /** Metres travelled in [mode] so far. */
    fun distance(totals: Map<TransportMode, Long>, mode: TransportMode): Long = totals[mode] ?: 0L

    /** Metres on foot = walking + running (the wasteland's favoured way to travel). */
    fun onFoot(totals: Map<TransportMode, Long>): Long =
        distance(totals, TransportMode.WALK) + distance(totals, TransportMode.RUN)

    /** Total metres across every moving mode. */
    fun total(totals: Map<TransportMode, Long>): Long =
        TransportMode.entries.filter { it.moving }.sumOf { totals[it] ?: 0L }
}
