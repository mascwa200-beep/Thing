package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs

/**
 * Deciding which GPS fixes belong on a recorded track.
 *
 * This looks like it should be trivial and is not. A phone sitting on a table produces a fix every
 * few seconds, each a few metres from the last, so a track that records everything wanders
 * hundreds of metres across an afternoon of standing still. A track that filters too hard cuts the
 * corners off every turn. And every so often a fix arrives from the other side of the county,
 * which is not a journey — it is the receiver guessing badly while it reacquires.
 *
 * So the rules are: refuse fixes the receiver itself says are vague, require real movement scaled
 * to how well the position is known, and refuse anything that would only be possible at
 * impossible speed.
 */
object TrackLog {

    /** One recorded point. Altitude is optional because plenty of fixes do not carry one. */
    data class TrackPoint(
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val epochMs: Long,
        val altitudeM: Double? = null,
    )

    /** Beyond this the fix is too vague to be worth a point at all. */
    const val MAX_ACCURACY_M = 50.0

    /** The smallest step that counts as having moved, when the fix is a good one. */
    const val MIN_STEP_M = 8.0

    /**
     * Above this a "move" is a receiver glitch rather than travel.
     *
     * Generous on purpose: a phone in an airliner genuinely does 250 m/s, and refusing to record
     * that would be a worse failure than admitting the occasional bad fix.
     */
    const val MAX_SPEED_MS = 320.0

    /**
     * Whether [candidate] should join the track after [previous].
     *
     * The first point of a track is accepted on accuracy alone — there is nothing to compare it to,
     * and a track has to start somewhere.
     */
    fun accept(previous: TrackPoint?, candidate: TrackPoint, accuracyM: Double? = null): Boolean {
        if (accuracyM != null && (accuracyM.isNaN() || accuracyM > MAX_ACCURACY_M)) return false
        if (!candidate.latitudeDeg.isFinite() || !candidate.longitudeDeg.isFinite()) return false
        if (abs(candidate.latitudeDeg) > 90.0 || abs(candidate.longitudeDeg) > 180.0) return false
        if (previous == null) return true

        val moved = Geodesy.distanceMeters(
            previous.latitudeDeg, previous.longitudeDeg,
            candidate.latitudeDeg, candidate.longitudeDeg,
        )
        // Scale the threshold to the fix: with a 30 m accuracy, a 10 m "move" is indistinguishable
        // from noise, and recording it draws a walk that never happened.
        val threshold = maxOf(MIN_STEP_M, accuracyM ?: 0.0)
        if (moved < threshold) return false

        val elapsedMs = candidate.epochMs - previous.epochMs
        // Out-of-order or identically-stamped fixes cannot be speed-checked, so let the distance
        // rule stand alone rather than dividing by zero or by a negative.
        if (elapsedMs <= 0L) return true
        return moved / (elapsedMs / 1000.0) <= MAX_SPEED_MS
    }

    /** Total ground distance along the track, metres. */
    fun distanceMeters(points: List<TrackPoint>): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            total += Geodesy.distanceMeters(
                points[i - 1].latitudeDeg, points[i - 1].longitudeDeg,
                points[i].latitudeDeg, points[i].longitudeDeg,
            )
        }
        return total
    }

    /** Wall-clock span from the first point to the last, milliseconds. */
    fun durationMs(points: List<TrackPoint>): Long =
        if (points.size < 2) 0L else (points.last().epochMs - points.first().epochMs).coerceAtLeast(0L)

    /**
     * Cumulative climb, metres, ignoring anything below [noiseFloorM].
     *
     * GPS altitude is far noisier than GPS position, so summing every upward difference would
     * report a mountain climbed on a flat walk.
     */
    fun ascentMeters(points: List<TrackPoint>, noiseFloorM: Double = 3.0): Double {
        var gain = 0.0
        var reference: Double? = null
        for (p in points) {
            val alt = p.altitudeM ?: continue
            val ref = reference
            if (ref == null) {
                reference = alt
                continue
            }
            val delta = alt - ref
            if (delta >= noiseFloorM) {
                gain += delta
                reference = alt
            } else if (delta <= -noiseFloorM) {
                reference = alt
            }
        }
        return gain
    }

    /** Keep the most recent [max] points; a track is not an archive. */
    fun capped(points: List<TrackPoint>, max: Int): List<TrackPoint> =
        if (points.size <= max) points else points.subList(points.size - max, points.size)
}
