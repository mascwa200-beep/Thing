package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Closest point of approach — where two moving contacts will be nearest each other, and when.
 *
 * The scope already knows where every aircraft is and which way it is going. That is enough to
 * answer the question a plot cannot: *are those two converging?* Straight-line extrapolation only,
 * which is exactly what it claims to be — nobody's actual intentions are known, and a turn a
 * second from now invalidates the answer. It is a geometry readout, not a collision-avoidance
 * system, and nothing here should ever be presented as one.
 *
 * The maths runs in a local east-north tangent plane. Over the tens of kilometres a radar scope
 * covers, treating that patch as flat costs centimetres.
 */
object Cpa {

    private const val DEG = Math.PI / 180.0

    /** A contact with a velocity. Speeds are metres per second, angles degrees. */
    data class Track(
        val id: String,
        val label: String,
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        /** Null when the contact reports no altitude — vertical separation is then unknown. */
        val altitudeM: Double? = null,
        val groundSpeedMs: Double,
        /** Direction of travel, degrees clockwise from true north. */
        val trackDeg: Double,
        /** Positive is climbing. */
        val verticalRateMs: Double = 0.0,
    )

    /** Where and when two tracks come nearest, on their current headings. */
    data class Approach(
        val a: Track,
        val b: Track,
        /** Seconds until closest approach. Zero means they are separating already. */
        val secondsToClosest: Double,
        val closestHorizontalM: Double,
        /** Null when either contact reports no altitude. */
        val closestVerticalM: Double?,
        val currentHorizontalM: Double,
        val currentVerticalM: Double?,
    ) {
        /** True when the gap is shrinking rather than opening. */
        val converging: Boolean get() = secondsToClosest > 0.0

        /**
         * Close enough to be worth a second look — both horizontally and, when known, vertically.
         *
         * Vertical separation is decisive: aircraft pass directly over one another all day long
         * with a thousand feet between them, and flagging that would make the readout useless.
         * With no altitude reported the vertical test cannot be applied, so proximity alone
         * qualifies and the caller should say the vertical gap is unknown.
         */
        val isNotable: Boolean
            get() = converging &&
                closestHorizontalM <= NOTABLE_HORIZONTAL_M &&
                (closestVerticalM == null || closestVerticalM <= NOTABLE_VERTICAL_M)
    }

    /** Roughly three nautical miles. */
    const val NOTABLE_HORIZONTAL_M = 5_556.0

    /** Roughly a thousand feet, the standard vertical separation minimum. */
    const val NOTABLE_VERTICAL_M = 305.0

    /** Below this a contact is effectively parked and its heading means nothing. */
    private const val MOVING_MS = 5.0

    /**
     * Closest approach of [a] and [b] on their current headings, or null when neither is moving
     * relative to the other — parallel tracks at identical speed never converge, and reporting a
     * time for that would be meaningless.
     */
    fun approach(a: Track, b: Track): Approach? {
        if (a.id == b.id) return null
        if (!a.latitudeDeg.isFinite() || !b.latitudeDeg.isFinite()) return null

        // b's offset from a, in metres east and north.
        val range = Geodesy.distanceMeters(a.latitudeDeg, a.longitudeDeg, b.latitudeDeg, b.longitudeDeg)
        val bearing = Geodesy.initialBearing(a.latitudeDeg, a.longitudeDeg, b.latitudeDeg, b.longitudeDeg) * DEG
        val rEast = range * sin(bearing)
        val rNorth = range * cos(bearing)

        val (aEast, aNorth) = velocity(a)
        val (bEast, bNorth) = velocity(b)
        val vEast = bEast - aEast
        val vNorth = bNorth - aNorth
        val speedSq = vEast * vEast + vNorth * vNorth

        val verticalNow = if (a.altitudeM != null && b.altitudeM != null) {
            abs(b.altitudeM - a.altitudeM)
        } else {
            null
        }

        // No relative motion: the separation now is the separation forever.
        if (speedSq < 1e-6) {
            return Approach(a, b, 0.0, range, verticalNow, range, verticalNow)
        }

        // t that minimises |r + v t|. Negative means the minimum is behind them: they are already
        // opening, so the closest approach available from here is right now.
        val t = (-(rEast * vEast + rNorth * vNorth) / speedSq).coerceAtLeast(0.0)
        val closestEast = rEast + vEast * t
        val closestNorth = rNorth + vNorth * t
        val closest = sqrt(closestEast * closestEast + closestNorth * closestNorth)

        val verticalAt = if (a.altitudeM != null && b.altitudeM != null) {
            abs((b.altitudeM + b.verticalRateMs * t) - (a.altitudeM + a.verticalRateMs * t))
        } else {
            null
        }

        return Approach(a, b, t, closest, verticalAt, range, verticalNow)
    }

    /**
     * Every notable convergence among [tracks], nearest-in-time first.
     *
     * Stationary contacts are excluded: a heading reported by something that is not moving is
     * noise, and extrapolating it produces confident nonsense.
     */
    fun notableApproaches(
        tracks: List<Track>,
        withinSeconds: Double = 600.0,
        limit: Int = 12,
    ): List<Approach> {
        val moving = tracks.filter { it.groundSpeedMs >= MOVING_MS && it.trackDeg.isFinite() }
        if (moving.size < 2) return emptyList()
        val out = mutableListOf<Approach>()
        for (i in moving.indices) {
            for (j in i + 1 until moving.size) {
                val approach = approach(moving[i], moving[j]) ?: continue
                if (approach.isNotable && approach.secondsToClosest <= withinSeconds) out += approach
            }
        }
        return out.sortedBy { it.secondsToClosest }.take(limit)
    }

    /** Ground velocity as east and north components, metres per second. */
    private fun velocity(t: Track): Pair<Double, Double> {
        val heading = t.trackDeg * DEG
        return t.groundSpeedMs * sin(heading) to t.groundSpeedMs * cos(heading)
    }

    /** Knots to metres per second — ADS-B reports ground speed in knots. */
    fun knotsToMs(knots: Double): Double = knots * 0.514444

    /** Feet per minute to metres per second — ADS-B reports vertical rate in feet per minute. */
    fun feetPerMinuteToMs(fpm: Double): Double = fpm * 0.00508
}
