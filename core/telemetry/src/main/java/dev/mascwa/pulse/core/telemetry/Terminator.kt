package dev.mascwa.pulse.core.telemetry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The day/night terminator — the line around the Earth where the Sun is setting right now.
 *
 * Everything here follows from [Ephemeris]: the subsolar point is just the Sun's declination and
 * the longitude where it is currently noon, and the terminator is the circle 90 degrees away from
 * it. Pure geometry, no network, and it draws the same at midnight in a tunnel as it does anywhere
 * else.
 *
 * Coordinates come back as (latitude, longitude) pairs, which is the order a human says them in;
 * GeoJSON wants the reverse, so a renderer has to swap deliberately rather than by accident.
 */
object Terminator {

    private const val DEG = PI / 180.0

    /** Where the Sun is directly overhead. */
    data class SubSolar(val latitudeDeg: Double, val longitudeDeg: Double)

    /**
     * The point the Sun is directly above.
     *
     * Its latitude is the Sun's declination — that is what declination means — and its longitude is
     * wherever local apparent noon currently is, which is Greenwich sidereal time reckoned against
     * the Sun's right ascension.
     */
    fun subSolarPoint(epochMs: Long): SubSolar {
        val sun = Ephemeris.sunEquatorial(epochMs)
        val gmst = Ephemeris.gmstDeg(Ephemeris.julianDate(epochMs))
        return SubSolar(
            latitudeDeg = sun.declinationDeg,
            longitudeDeg = Geodesy.normalizeLongitude(sun.rightAscensionDeg - gmst),
        )
    }

    /**
     * The terminator itself, sampled every [stepDeg] of longitude, west to east.
     *
     * At each longitude there is exactly one latitude where the Sun sits on the horizon, from
     * `sin(altitude) = sin(lat)sin(dec) + cos(lat)cos(dec)cos(H) = 0`.
     */
    fun curve(epochMs: Long, stepDeg: Double = 2.0): List<Pair<Double, Double>> {
        val step = abs(stepDeg).coerceIn(0.25, 30.0)
        val sun = subSolarPoint(epochMs)
        // Near an equinox the declination approaches zero and tan(dec) with it, which would send
        // the latitude to infinity. The terminator is then the meridian circle through the poles,
        // so clamp to a declination that still produces a finite, correct-signed answer.
        val dec = sun.latitudeDeg.let {
            if (abs(it) < MIN_DECLINATION_DEG) MIN_DECLINATION_DEG * (if (it < 0) -1.0 else 1.0) else it
        } * DEG
        val out = ArrayList<Pair<Double, Double>>()
        var lon = -180.0
        while (lon <= 180.0 + 1e-9) {
            val hourAngle = (lon - sun.longitudeDeg) * DEG
            val lat = atan(-cos(hourAngle) / tan(dec)) / DEG
            out += lat to Geodesy.normalizeLongitude(lon)
            lon += step
        }
        return out
    }

    /**
     * The night side as a closed ring, ready to be filled.
     *
     * The dark hemisphere is always the one containing the pole *opposite* the subsolar latitude,
     * so the ring runs along the terminator and then closes across that pole.
     */
    fun nightPolygon(epochMs: Long, stepDeg: Double = 2.0): List<Pair<Double, Double>> {
        val sun = subSolarPoint(epochMs)
        val darkPoleLat = if (sun.latitudeDeg >= 0) -90.0 else 90.0
        val line = curve(epochMs, stepDeg)
        if (line.isEmpty()) return emptyList()
        val ring = ArrayList<Pair<Double, Double>>(line.size + 3)
        ring.addAll(line)
        // Close across the dark pole, then back to the start.
        ring += darkPoleLat to line.last().second
        ring += darkPoleLat to line.first().second
        ring += line.first()
        return ring
    }

    /**
     * Whether the Sun is above the horizon at a point right now.
     *
     * Derived from the subsolar point rather than from a rise/set search: a place is in daylight
     * exactly when it is less than 90 degrees away from where the Sun is overhead.
     */
    fun isDaylight(latDeg: Double, lonDeg: Double, epochMs: Long): Boolean =
        sunAltitudeDeg(latDeg, lonDeg, epochMs) > 0.0

    /** The Sun's altitude at a point, from the subsolar geometry. */
    fun sunAltitudeDeg(latDeg: Double, lonDeg: Double, epochMs: Long): Double {
        val sun = subSolarPoint(epochMs)
        val lat = latDeg * DEG
        val dec = sun.latitudeDeg * DEG
        val hourAngle = (lonDeg - sun.longitudeDeg) * DEG
        val sinAlt = sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(hourAngle)
        return kotlin.math.asin(sinAlt.coerceIn(-1.0, 1.0)) / DEG
    }

    /** Fraction of the globe currently in daylight — always close to a half, and a decent smoke test. */
    fun daylightFraction(epochMs: Long, samples: Int = 60): Double {
        val n = samples.coerceIn(8, 360)
        var lit = 0
        var total = 0
        for (i in 0 until n) {
            val lat = -90.0 + 180.0 * (i + 0.5) / n
            // Weight by cos(latitude): rows near the poles cover far less surface than the equator.
            val weight = cos(lat * DEG).coerceAtLeast(0.0)
            for (j in 0 until n) {
                val lon = -180.0 + 360.0 * (j + 0.5) / n
                total += (weight * 1000).toInt()
                if (isDaylight(lat, lon, epochMs)) lit += (weight * 1000).toInt()
            }
        }
        return if (total == 0) 0.0 else lit.toDouble() / total
    }

    /** Below this the terminator degenerates towards the meridians and tan(dec) explodes. */
    private const val MIN_DECLINATION_DEG = 0.05
}
