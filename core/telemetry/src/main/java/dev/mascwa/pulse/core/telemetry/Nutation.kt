package dev.mascwa.pulse.core.telemetry

import kotlin.math.cos
import kotlin.math.sin

/**
 * Nutation of the Earth's axis: how far the TRUE equator and equinox of date sit from the MEAN
 * ones, in longitude (Δψ) and in obliquity (Δε). The IAU 2000B model, evaluated from the generated
 * [NutationTables].
 *
 * ⚠️ **This replaces a one-term shortcut that was half an arcsecond wrong on everything.** The
 * Moon's node term is the largest of the series by far, and Meeus ch. 22 offers it alone as a
 * quick form good to 0.5″. That was the whole of the nutation applied to the Sun, the Moon, every
 * planet and every star on the chart, and it was the ~1.5″ residual common to all seven planets
 * when the VSOP87 planets were measured against DE421. The full 77-term IAU 2000B model agrees with
 * the 1,365-term IAU 2000A to about a milliarcsecond over 1995–2050 — measured by `NutationTest`
 * against Skyfield's evaluation of both, not quoted.
 *
 * ⚠️ **The table is generated, and the fundamental arguments are deliberately LINEAR.** IAU 2000B
 * is defined that way (SOFA `iauNut00b`), and the reference values the test holds were computed
 * that way; adding the quadratic argument terms would look more careful and would make this
 * disagree with its own reference. `tools/sky/build_nutation.py` reads every number out of
 * Skyfield's bundled NOVAS table and re-evaluates its own output against `nutationlib.iau2000b`
 * before it will write a file.
 *
 * Cost: 77 sines and cosines, a few microseconds. `Ephemeris` evaluates it per body position and
 * per frame build, never per star.
 */
object Nutation {

    /** One turn, in arcseconds — the modulus the fundamental arguments are reduced by. */
    private const val ARCSEC_PER_TURN = 1_296_000.0

    /** The table's unit: tenths of a microarcsecond, to arcseconds. */
    private const val TENTH_MICROARCSEC = 1e-7

    /**
     * Δψ and Δε in **arcseconds** at `t` Julian centuries of Terrestrial Time from J2000.0, written
     * into [out] as `[Δψ, Δε]` and returned. Both at once because every consumer wants both, and
     * the seventy-seven arguments they share are most of the work.
     */
    fun arcsec(t: Double, out: DoubleArray = DoubleArray(2)): DoubleArray {
        val f = NutationTables.FUNDAMENTAL
        val a0 = Math.toRadians((f[0][1] * t + f[0][0]) % ARCSEC_PER_TURN / 3600.0)
        val a1 = Math.toRadians((f[1][1] * t + f[1][0]) % ARCSEC_PER_TURN / 3600.0)
        val a2 = Math.toRadians((f[2][1] * t + f[2][0]) % ARCSEC_PER_TURN / 3600.0)
        val a3 = Math.toRadians((f[3][1] * t + f[3][0]) % ARCSEC_PER_TURN / 3600.0)
        val a4 = Math.toRadians((f[4][1] * t + f[4][0]) % ARCSEC_PER_TURN / 3600.0)

        val m = NutationTables.MULTIPLIERS
        val lon = NutationTables.LONGITUDE
        val obl = NutationTables.OBLIQUITY
        var dPsi = 0.0
        var dEps = 0.0
        for (k in 0 until NutationTables.TERMS) {
            val b = k * 5
            val phi = m[b] * a0 + m[b + 1] * a1 + m[b + 2] * a2 + m[b + 3] * a3 + m[b + 4] * a4
            val s = sin(phi)
            val c = cos(phi)
            val l = k * 3
            dPsi += (lon[l] + lon[l + 1] * t) * s + lon[l + 2] * c
            dEps += (obl[l] + obl[l + 1] * t) * c + obl[l + 2] * s
        }
        out[0] = (dPsi + NutationTables.PLANETARY_LONGITUDE) * TENTH_MICROARCSEC
        out[1] = (dEps + NutationTables.PLANETARY_OBLIQUITY) * TENTH_MICROARCSEC
        return out
    }

    /** Δψ, the nutation in longitude, in degrees. */
    fun deltaPsiDeg(t: Double): Double = arcsec(t)[0] / 3600.0

    /** Δε, the nutation in obliquity, in degrees. */
    fun deltaEpsilonDeg(t: Double): Double = arcsec(t)[1] / 3600.0
}
