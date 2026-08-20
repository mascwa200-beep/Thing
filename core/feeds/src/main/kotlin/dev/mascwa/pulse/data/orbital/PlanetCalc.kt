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
 * ("How to compute planetary positions"). Accuracy ~1–2 arcminutes — labelled
 * approximate in the UI. Computes each planet's altitude/azimuth for the
 * observer and an approximate apparent magnitude. No network required.
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

            val xh = r * (cosd(n) * cosd(vw) - sind(n) * sind(vw) * cosd(i))
            val yh = r * (sind(n) * cosd(vw) + cosd(n) * sind(vw) * cosd(i))
            val zh = r * sind(vw) * sind(i)

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
            Planet(p.name, alt, az, mag, alt > 0)
        }
    }
}
