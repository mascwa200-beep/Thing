package dev.mascwa.pulse.core.telemetry

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where each planet is relative to the Sun: the VSOP87 planetary theory, evaluated over the
 * truncated tables in [Vsop87Tables.kt].
 *
 * VSOP87B (Bretagnon & Francou 1988) gives heliocentric spherical coordinates — longitude `L`,
 * latitude `B`, radius `R` — in the J2000 dynamical ecliptic and equinox, each as
 * `Σ_k T^k · Σ_i A_i cos(B_i + C_i T)` with `T` in Julian millennia of Terrestrial Time from
 * J2000. That is the whole of the arithmetic here; what makes it worth a file is the frame.
 *
 * ⚠️ **J2000 ecliptic, and that is the reason the B variant was chosen over the of-date ones.**
 * The star catalogue, [Comets] and [Ephemeris.earthHeliocentricJ2000Au] all speak J2000, so a
 * planet's geocentric vector is a subtraction with no frame fudge in it, and precession and
 * nutation are applied ONCE, at the end, by the same rotation the Sun and Moon go through. The
 * of-date variants would put precession inside the series where nothing else can see it.
 *
 * ⚠️ **What the truncation is bounded on is the GEOCENTRIC direction, not the coordinate.** A
 * heliocentric error of a thousand kilometres is a tenth of an arcsecond on Neptune and a full
 * second on Venus at inferior conjunction, so bounding `L`, `B` and `R` separately would either
 * over-keep the outer planets or under-keep the inner ones. The builder measures each planet's
 * error across the distance it is actually seen over — for the Earth, across the nearest thing
 * that is ever looked at from it — and the table's header carries the figures: under half an
 * arcsecond for every planet over 1900–2100, re-measured on instants the search never saw.
 * [Vsop87Test] holds the Kotlin evaluator to those same numbers against the FULL series.
 *
 * ⚠️ Validity is 1900–2100 by construction. Outside it the T-power terms the cut dropped grow
 * back, and the theory itself is fitted to ±4000 years for the inner planets and less for the
 * outer; a chart that scrubs to 1500 is drawing something plausible rather than something
 * measured, which is why the time control is bounded to the same span.
 */
object Vsop87 {

    /** The eight planets, in the order the tables were built. */
    enum class Body { MERCURY, VENUS, EARTH, MARS, JUPITER, SATURN, URANUS, NEPTUNE }

    /** Heliocentric spherical coordinates: radians, radians, astronomical units. */
    class Spherical(val longitudeRad: Double, val latitudeRad: Double, val radiusAu: Double)

    private const val J2000_JD = 2451545.0
    private const val DAYS_PER_MILLENNIUM = 365250.0
    private const val TWO_PI = 2.0 * Math.PI

    /** Half a day either side: far below the series' own smoothness, far above rounding. */
    private const val VELOCITY_STEP_DAYS = 0.05

    private fun tables(body: Body): Triple<Array<DoubleArray>, Array<DoubleArray>, Array<DoubleArray>> =
        when (body) {
            Body.MERCURY -> Triple(Vsop87Mercury.L, Vsop87Mercury.B, Vsop87Mercury.R)
            Body.VENUS -> Triple(Vsop87Venus.L, Vsop87Venus.B, Vsop87Venus.R)
            Body.EARTH -> Triple(Vsop87Earth.L, Vsop87Earth.B, Vsop87Earth.R)
            Body.MARS -> Triple(Vsop87Mars.L, Vsop87Mars.B, Vsop87Mars.R)
            Body.JUPITER -> Triple(Vsop87Jupiter.L, Vsop87Jupiter.B, Vsop87Jupiter.R)
            Body.SATURN -> Triple(Vsop87Saturn.L, Vsop87Saturn.B, Vsop87Saturn.R)
            Body.URANUS -> Triple(Vsop87Uranus.L, Vsop87Uranus.B, Vsop87Uranus.R)
            Body.NEPTUNE -> Triple(Vsop87Neptune.L, Vsop87Neptune.B, Vsop87Neptune.R)
        }

    /** How many terms the truncated table for [body] carries, for a test to pin. */
    fun termCount(body: Body): Int = when (body) {
        Body.MERCURY -> Vsop87Mercury.TERMS
        Body.VENUS -> Vsop87Venus.TERMS
        Body.EARTH -> Vsop87Earth.TERMS
        Body.MARS -> Vsop87Mars.TERMS
        Body.JUPITER -> Vsop87Jupiter.TERMS
        Body.SATURN -> Vsop87Saturn.TERMS
        Body.URANUS -> Vsop87Uranus.TERMS
        Body.NEPTUNE -> Vsop87Neptune.TERMS
    }

    /**
     * One coordinate's series at `t` millennia: the powers of T are accumulated as the loop
     * climbs, so nothing is raised to a power and nothing allocates.
     */
    private fun series(powers: Array<DoubleArray>, t: Double): Double {
        var total = 0.0
        var tk = 1.0
        for (k in powers.indices) {
            val a = powers[k]
            var s = 0.0
            var i = 0
            while (i < a.size) {
                s += a[i] * cos(a[i + 1] + a[i + 2] * t)
                i += 3
            }
            total += s * tk
            tk *= t
        }
        return total
    }

    /** Heliocentric spherical position of [body] at a Julian date on the TT scale. */
    fun spherical(body: Body, jdTT: Double): Spherical {
        val t = (jdTT - J2000_JD) / DAYS_PER_MILLENNIUM
        val (l, b, r) = tables(body)
        var lon = series(l, t) % TWO_PI
        if (lon < 0.0) lon += TWO_PI
        return Spherical(lon, series(b, t), series(r, t))
    }

    /**
     * Heliocentric rectangular position of [body] in the J2000 ecliptic, astronomical units,
     * written into [out] as x (toward the equinox), y, z (toward the ecliptic pole).
     */
    fun heliocentricJ2000Au(body: Body, jdTT: Double, out: DoubleArray) {
        val s = spherical(body, jdTT)
        val cb = cos(s.latitudeRad)
        out[0] = s.radiusAu * cb * cos(s.longitudeRad)
        out[1] = s.radiusAu * cb * sin(s.longitudeRad)
        out[2] = s.radiusAu * sin(s.latitudeRad)
    }

    /**
     * The Earth's heliocentric velocity in the J2000 ecliptic, AU per day, by central difference.
     *
     * ⚠️ This is the vector annual aberration needs — about 0.017 AU/day, and a ten-thousandth of
     * it is a thousandth of an arcsecond, so the difference step is nowhere near critical. It is
     * a difference rather than a differentiated series because the derivative of a 295-term sum is
     * a second table twice the size, for a quantity the chart cannot tell from this one.
     */
    fun earthVelocityAuPerDay(jdTT: Double, out: DoubleArray) {
        val before = DoubleArray(3)
        val after = DoubleArray(3)
        heliocentricJ2000Au(Body.EARTH, jdTT - VELOCITY_STEP_DAYS, before)
        heliocentricJ2000Au(Body.EARTH, jdTT + VELOCITY_STEP_DAYS, after)
        val scale = 1.0 / (2.0 * VELOCITY_STEP_DAYS)
        out[0] = (after[0] - before[0]) * scale
        out[1] = (after[1] - before[1]) * scale
        out[2] = (after[2] - before[2]) * scale
    }

    /** Length of a three-vector, for callers holding positions as arrays. */
    fun length(v: DoubleArray): Double = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
}
