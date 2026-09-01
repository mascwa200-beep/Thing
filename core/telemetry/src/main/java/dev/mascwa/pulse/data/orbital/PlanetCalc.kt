package dev.mascwa.pulse.data.orbital

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Naked-eye planet positions using Paul Schlyter's low-precision method
 * ("How to compute planetary positions"). Computes each planet's altitude/azimuth for the observer
 * and an approximate apparent magnitude. No network required.
 *
 * ## Measured accuracy, because the number that was here was wrong in both directions
 *
 * Against JPL DE421 read through Skyfield, at eighty instants spanning 2000 to 2050 — apparent
 * positions, equinox of date, which is the convention this method works in:
 *
 * | | median | worst |
 * |---|---|---|
 * | Mercury, Venus, Mars | 0.5' | 1.7' |
 * | Jupiter | 0.5' | 1.4' |
 * | Saturn | 0.7' | 2.9' |
 *
 * ⚠️ **Jupiter's worst was 14.7 arcminutes and Saturn's 13.3 until section 10 of Schlyter's own
 * method was applied**, which this had simply left out. The largest omitted term is the great
 * Jupiter–Saturn inequality — 0.332 degrees of longitude for Jupiter and 0.812 for Saturn, against
 * a Moon whose whole radius is 15.6 arcminutes. It is invisible in a one-year sample because its
 * period is 918 years, which is why measuring over five decades is what found it.
 *
 * ⚠️ The perturbations are added to the HELIOCENTRIC ECLIPTIC LONGITUDE (and, for Saturn, the
 * latitude) after the orbit is solved and before the position is turned back into rectangular
 * coordinates — not to the argument of latitude, where they would be a different quantity.
 *
 * ⚠️ The positions are for the equinox of DATE and carry no nutation and no aberration. That is
 * Schlyter's own convention and it is right for rise and set times and for pointing at something;
 * a chart drawn for a fixed epoch would need precession applied, which is
 * [dev.mascwa.pulse.core.telemetry.Ephemeris.precessFromJ2000] in the other direction.
 */
object PlanetCalc {

    private data class Elem(
        val name: String,
        val n0: Double, val nDot: Double,
        val i0: Double, val iDot: Double,
        val w0: Double, val wDot: Double,
        val a0: Double, val aDot: Double,
        val e0: Double, val eDot: Double,
        val m0: Double, val mDot: Double,
    )

    private val planets = listOf(
        Elem("Mercury", 48.3313, 3.24587e-5, 7.0047, 5.00e-8, 29.1241, 1.01444e-5, 0.387098, 0.0, 0.205635, 5.59e-10, 168.6562, 4.0923344368),
        Elem("Venus", 76.6799, 2.46590e-5, 3.3946, 2.75e-8, 54.8910, 1.38374e-5, 0.723330, 0.0, 0.006773, -1.302e-9, 48.0052, 1.6021302244),
        Elem("Mars", 49.5574, 2.11081e-5, 1.8497, -1.78e-8, 286.5016, 2.92961e-5, 1.523688, 0.0, 0.093405, 2.516e-9, 18.6021, 0.5240207766),
        Elem("Jupiter", 100.4542, 2.76854e-5, 1.3030, -1.557e-7, 273.8777, 1.64505e-5, 5.20256, 0.0, 0.048498, 4.469e-9, 19.8950, 0.0830853001),
        Elem("Saturn", 113.6634, 2.38980e-5, 2.4886, -1.081e-7, 339.3939, 2.97661e-5, 9.55475, 0.0, 0.055546, -9.499e-9, 316.9670, 0.0334442282),
    )

    private fun rev(x: Double): Double { var r = x % 360.0; if (r < 0) r += 360.0; return r }
    private fun sind(d: Double) = sin(d * PI / 180.0)
    private fun cosd(d: Double) = cos(d * PI / 180.0)
    private fun atan2d(y: Double, x: Double) = atan2(y, x) * 180.0 / PI
    private fun asind(x: Double) = atan2(x, sqrt(1 - x * x)) * 180.0 / PI
    private fun acosd(x: Double): Double { val c = x.coerceIn(-1.0, 1.0); return atan2(sqrt(1 - c * c), c) * 180.0 / PI }

    /** One planet's mean anomaly, degrees, [d] days from the element epoch. */
    private fun meanAnomaly(name: String, d: Double): Double {
        val p = planets.first { it.name == name }
        return rev(p.m0 + p.mDot * d)
    }

    fun planetsNow(lat: Double, lon: Double, epochMs: Long = System.currentTimeMillis()): List<Planet> {
        val jd = epochMs / 86_400_000.0 + 2_440_587.5
        val d = jd - 2_451_543.5
        val ecl = 23.4393 - 3.563e-7 * d
        val ut = ((jd + 0.5) % 1.0) * 24.0

        // Sun
        val ws = 282.9404 + 4.70935e-5 * d
        val es = 0.016709 - 1.151e-9 * d
        val ms = rev(356.0470 + 0.9856002585 * d)
        val esun = ms + es * (180.0 / PI) * sind(ms) * (1 + es * cosd(ms))
        val xvs = cosd(esun) - es
        val yvs = sqrt(1 - es * es) * sind(esun)
        val vs = atan2d(yvs, xvs)
        val rs = sqrt(xvs * xvs + yvs * yvs)
        val lonsun = rev(vs + ws)
        val xs = rs * cosd(lonsun)
        val ys = rs * sind(lonsun)
        val ls = rev(ms + ws)
        val lstHours = ls / 15.0 + 12.0 + ut + lon / 15.0

        // Mean anomalies of Jupiter and Saturn drive the perturbation terms below. Read out of the
        // same element table rather than retyped, so a correction there cannot leave these behind —
        // and BY NAME rather than by index, so reordering the list cannot silently swap them.
        val mj = meanAnomaly("Jupiter", d)
        val msat = meanAnomaly("Saturn", d)

        return planets.map { p ->
            val n = rev(p.n0 + p.nDot * d)
            val i = p.i0 + p.iDot * d
            val w = rev(p.w0 + p.wDot * d)
            val a = p.a0 + p.aDot * d
            val e = p.e0 + p.eDot * d
            val m = rev(p.m0 + p.mDot * d)

            var ea = m + e * (180.0 / PI) * sind(m) * (1 + e * cosd(m))
            repeat(4) { ea -= (ea - e * (180.0 / PI) * sind(ea) - m) / (1 - e * cosd(ea)) }

            val xv = a * (cosd(ea) - e)
            val yv = a * sqrt(1 - e * e) * sind(ea)
            val v = atan2d(yv, xv)
            val r = sqrt(xv * xv + yv * yv)
            val vw = v + w

            val xh0 = r * (cosd(n) * cosd(vw) - sind(n) * sind(vw) * cosd(i))
            val yh0 = r * (sind(n) * cosd(vw) + cosd(n) * sind(vw) * cosd(i))
            val zh0 = r * sind(vw) * sind(i)

            // Schlyter section 10. Mercury, Venus and Mars have nothing above 0.01 degrees.
            var lonecl = atan2d(yh0, xh0)
            var latecl = atan2d(zh0, sqrt(xh0 * xh0 + yh0 * yh0))
            when (p.name) {
                "Jupiter" -> lonecl +=
                    -0.332 * sind(2 * mj - 5 * msat - 67.6) -
                    0.056 * sind(2 * mj - 2 * msat + 21.0) +
                    0.042 * sind(3 * mj - 5 * msat + 21.0) -
                    0.036 * sind(mj - 2 * msat) +
                    0.022 * cosd(mj - msat) +
                    0.023 * sind(2 * mj - 3 * msat + 52.0) -
                    0.016 * sind(mj - 5 * msat - 69.0)

                "Saturn" -> {
                    lonecl +=
                        0.812 * sind(2 * mj - 5 * msat - 67.6) -
                        0.229 * cosd(2 * mj - 4 * msat - 2.0) +
                        0.119 * sind(mj - 2 * msat - 3.0) +
                        0.046 * sind(2 * mj - 6 * msat - 69.0) +
                        0.014 * sind(mj - 3 * msat + 32.0)
                    latecl +=
                        -0.020 * cosd(2 * mj - 4 * msat - 2.0) +
                        0.018 * sind(2 * mj - 6 * msat - 49.0)
                }
            }
            val xh = r * cosd(lonecl) * cosd(latecl)
            val yh = r * sind(lonecl) * cosd(latecl)
            val zh = r * sind(latecl)

            val xg = xh + xs
            val yg = yh + ys
            val zg = zh

            val xe = xg
            val ye = yg * cosd(ecl) - zg * sind(ecl)
            val ze = yg * sind(ecl) + zg * cosd(ecl)
            val ra = rev(atan2d(ye, xe))
            val dec = atan2d(ze, sqrt(xe * xe + ye * ye))

            val ha = rev(lstHours * 15.0 - ra)
            val x = cosd(ha) * cosd(dec)
            val y = sind(ha) * cosd(dec)
            val z = sind(dec)
            val xhor = x * sind(lat) - z * cosd(lat)
            val zhor = x * cosd(lat) + z * sind(lat)
            val az = rev(atan2d(y, xhor) + 180.0)
            val alt = asind(zhor)

            val bigR = sqrt(xg * xg + yg * yg + zg * zg)
            val fv = acosd((r * r + bigR * bigR - rs * rs) / (2 * r * bigR))
            val mag = when (p.name) {
                "Mercury" -> -0.36 + 5 * log10(r * bigR) + 0.027 * fv + 2.2e-13 * fv.pow(6)
                "Venus" -> -4.34 + 5 * log10(r * bigR) + 0.013 * fv + 4.2e-7 * fv.pow(3)
                "Mars" -> -1.51 + 5 * log10(r * bigR) + 0.016 * fv
                "Jupiter" -> -9.25 + 5 * log10(r * bigR) + 0.014 * fv
                "Saturn" -> -9.0 + 5 * log10(r * bigR) + 0.044 * fv
                else -> 0.0
            }
            // ⚠️ `bigR` and `fv` are the geocentric distance and the phase angle, both computed just
            // above because the magnitude needs them. They used to stop here. Carrying them out is
            // what lets the sky map draw a planet at the size it really is and with the phase it
            // really has, and it costs nothing — the arithmetic has already happened.
            Planet(p.name, alt, az, mag, alt > 0, ra, dec, distanceAu = bigR, phaseAngleDeg = fv)
        }
    }
}
