package dev.mascwa.pulse.feature.compass

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline lunar azimuth (degrees from true north, clockwise) using Paul Schlyter's low-precision
 * method, including the main lunar perturbations (evection, variation, yearly equation, …). Accuracy
 * is a couple of degrees — plenty for a compass moon marker. No network. Mirrors [SunCalc].
 */
object MoonCalc {
    private fun rev(x: Double): Double { var r = x % 360.0; if (r < 0) r += 360.0; return r }
    private fun sind(d: Double) = sin(d * PI / 180.0)
    private fun cosd(d: Double) = cos(d * PI / 180.0)
    private fun atan2d(y: Double, x: Double) = atan2(y, x) * 180.0 / PI

    /** Moon azimuth in degrees (0 = N, 90 = E) for the observer at [lat]/[lon]. */
    fun azimuth(lat: Double, lon: Double, epochMs: Long = System.currentTimeMillis()): Double {
        val jd = epochMs / 86_400_000.0 + 2_440_587.5
        val d = jd - 2_451_543.5
        val ut = ((jd + 0.5) % 1.0) * 24.0
        val ecl = 23.4393 - 3.563e-7 * d

        // Sun — needed for the lunar perturbations and for local sidereal time.
        val ws = 282.9404 + 4.70935e-5 * d
        val ms = rev(356.0470 + 0.9856002585 * d)
        val ls = rev(ms + ws) // Sun's mean longitude.

        // Moon's orbital elements.
        val nm = rev(125.1228 - 0.0529538083 * d) // Long. ascending node
        val im = 5.1454                            // Inclination
        val wm = rev(318.0634 + 0.1643573223 * d)  // Arg. of perigee
        val am = 60.2666                           // Mean distance (Earth radii)
        val em = 0.054900                          // Eccentricity
        val mm = rev(115.3654 + 13.0649929509 * d) // Mean anomaly

        // Eccentric anomaly — iterate, the Moon's eccentricity is sizeable.
        var ea = mm + em * (180.0 / PI) * sind(mm) * (1 + em * cosd(mm))
        repeat(3) { ea -= (ea - em * (180.0 / PI) * sind(ea) - mm) / (1 - em * cosd(ea)) }

        val xv = am * (cosd(ea) - em)
        val yv = am * sqrt(1 - em * em) * sind(ea)
        val v = rev(atan2d(yv, xv))
        val r = sqrt(xv * xv + yv * yv)

        // Geocentric position in the ecliptic plane.
        val xh = r * (cosd(nm) * cosd(v + wm) - sind(nm) * sind(v + wm) * cosd(im))
        val yh = r * (sind(nm) * cosd(v + wm) + cosd(nm) * sind(v + wm) * cosd(im))
        val zh = r * sind(v + wm) * sind(im)

        var lonEcl = atan2d(yh, xh)
        var latEcl = atan2d(zh, sqrt(xh * xh + yh * yh))

        // Main perturbations (Schlyter): without these the Moon can be off by a degree or more.
        val lm = rev(nm + wm + mm) // Moon's mean longitude
        val dEl = rev(lm - ls)     // Mean elongation
        val f = rev(lm - nm)       // Argument of latitude
        lonEcl += (-1.274 * sind(mm - 2 * dEl) +    // Evection
            0.658 * sind(2 * dEl) -                  // Variation
            0.186 * sind(ms) -                       // Yearly equation
            0.059 * sind(2 * mm - 2 * dEl) -
            0.057 * sind(mm - 2 * dEl + ms) +
            0.053 * sind(mm + 2 * dEl) +
            0.046 * sind(2 * dEl - ms) +
            0.041 * sind(mm - ms) -
            0.035 * sind(dEl) -                      // Parallactic equation
            0.031 * sind(mm + ms) -
            0.015 * sind(2 * f - 2 * dEl) +
            0.011 * sind(mm - 4 * dEl))
        latEcl += (-0.173 * sind(f - 2 * dEl) -
            0.055 * sind(mm - f - 2 * dEl) -
            0.046 * sind(mm + f - 2 * dEl) +
            0.033 * sind(f + 2 * dEl) +
            0.017 * sind(2 * mm + f))

        // Ecliptic → equatorial (unit vector — only direction matters for azimuth).
        val xe = cosd(lonEcl) * cosd(latEcl)
        val ye = sind(lonEcl) * cosd(latEcl)
        val ze = sind(latEcl)
        val xeq = xe
        val yeq = ye * cosd(ecl) - ze * sind(ecl)
        val zeq = ye * sind(ecl) + ze * cosd(ecl)
        val ra = rev(atan2d(yeq, xeq))
        val dec = atan2d(zeq, sqrt(xeq * xeq + yeq * yeq))

        // Local sidereal time → hour angle → azimuth (same convention as SunCalc).
        val lstHours = ls / 15.0 + 12.0 + ut + lon / 15.0
        val ha = rev(lstHours * 15.0 - ra)
        val x = cosd(ha) * cosd(dec)
        val y = sind(ha) * cosd(dec)
        val z = sind(dec)
        val xhor = x * sind(lat) - z * cosd(lat)
        return rev(atan2d(y, xhor) + 180.0)
    }
}
