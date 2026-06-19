package dev.mascwa.pulse.feature.compass

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline solar azimuth (degrees from true north, clockwise) using Paul Schlyter's low-precision
 * method — the same approach as data/orbital/PlanetCalc, restricted to the Sun. Accurate to ~1°,
 * plenty for a compass sun marker. No network.
 */
object SunCalc {
    private fun rev(x: Double): Double { var r = x % 360.0; if (r < 0) r += 360.0; return r }
    private fun sind(d: Double) = sin(d * PI / 180.0)
    private fun cosd(d: Double) = cos(d * PI / 180.0)
    private fun atan2d(y: Double, x: Double) = atan2(y, x) * 180.0 / PI

    /** Sun azimuth in degrees (0 = N, 90 = E) for the observer at [lat]/[lon]. */
    fun azimuth(lat: Double, lon: Double, epochMs: Long = System.currentTimeMillis()): Double {
        val jd = epochMs / 86_400_000.0 + 2_440_587.5
        val d = jd - 2_451_543.5
        val ut = ((jd + 0.5) % 1.0) * 24.0
        val ecl = 23.4393 - 3.563e-7 * d

        val w = 282.9404 + 4.70935e-5 * d
        val e = 0.016709 - 1.151e-9 * d
        val m = rev(356.0470 + 0.9856002585 * d)
        val ea = m + e * (180.0 / PI) * sind(m) * (1 + e * cosd(m))
        val xv = cosd(ea) - e
        val yv = sqrt(1 - e * e) * sind(ea)
        val v = atan2d(yv, xv)
        val r = sqrt(xv * xv + yv * yv)
        val lonsun = rev(v + w)
        val xs = r * cosd(lonsun)
        val ys = r * sind(lonsun)

        // Ecliptic → equatorial (the Sun's ecliptic latitude is 0).
        val xe = xs
        val ye = ys * cosd(ecl)
        val ze = ys * sind(ecl)
        val ra = rev(atan2d(ye, xe))
        val dec = atan2d(ze, sqrt(xe * xe + ye * ye))

        // Local sidereal time (Schlyter), then hour angle → alt/az.
        val ls = rev(m + w)
        val lstHours = ls / 15.0 + 12.0 + ut + lon / 15.0
        val ha = rev(lstHours * 15.0 - ra)
        val x = cosd(ha) * cosd(dec)
        val y = sind(ha) * cosd(dec)
        val z = sind(dec)
        val xhor = x * sind(lat) - z * cosd(lat)
        return rev(atan2d(y, xhor) + 180.0)
    }
}
