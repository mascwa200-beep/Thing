import dev.mascwa.pulse.core.telemetry.Ephemeris
import dev.mascwa.pulse.core.telemetry.SkyProjection
import kotlin.math.abs
import kotlin.math.acos

/**
 * How far the star map is out for not precessing, measured against the field it can now show.
 *
 * The `precessFromJ2000` KDoc says ignoring precession is "invisible on a chart", and that was true
 * when it was written: the field floor was 4 degrees. S1 of this arc took it to 0.25.
 */
object PrecessProbe {
    private fun sep(aRa: Double, aDec: Double, bRa: Double, bDec: Double): Double {
        val u = SkyProjection.equatorialVector(aRa, aDec)
        val v = SkyProjection.equatorialVector(bRa, bDec)
        return Math.toDegrees(acos((u[0]*v[0] + u[1]*v[1] + u[2]*v[2]).coerceIn(-1.0, 1.0)))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // 2026-08-29, the day this is measured.
        val now = 1_787_000_000_000L
        val stars = listOf(
            Triple("Sirius", 101.28715533, -16.71611586),
            Triple("Polaris", 37.95456067, 89.26410897),
            Triple("Betelgeuse", 88.79293899, 7.40706400),
            Triple("Vega", 279.23473479, 38.78368896),
            Triple("Rigel", 78.63446707, -8.20163836),
            Triple("Antares", 247.35191542, -26.43200261),
            Triple("Alpha Cen", 219.90205833, -60.83397468),
        )
        println("=== how far a J2000 position is from where the star actually is, %s ===".format("2026"))
        var worst = 0.0; var best = 1e9
        for ((name, ra, dec) in stars) {
            val ofDate = Ephemeris.precessFromJ2000(ra, dec, now)
            val d = sep(ra, dec, ofDate.rightAscensionDeg, ofDate.declinationDeg)
            worst = maxOf(worst, d); best = minOf(best, d)
            println("  %-12s %7.3f deg = %5.1f arcmin".format(name, d, d * 60))
        }
        println("  range %.1f to %.1f arcmin".format(best * 60, worst * 60))

        println()
        println("=== against the field of view the map can now show ===")
        for (fov in doubleArrayOf(150.0, 80.0, 20.0, 4.0, 1.0, SkyProjection.MIN_FOV_DEG)) {
            // Fraction of the narrow screen axis the error spans, and pixels on a 1080-wide phone.
            val frac = worst / fov
            println("  fov %6.2f deg  ->  error is %6.1f%% of the field, %7.0f px on 1080".format(
                fov, frac * 100, frac * 1080))
        }
        println()
        println("  MIN_FOV_DEG is ${SkyProjection.MIN_FOV_DEG}; the old floor the KDoc was written under was 4.0.")

        println()
        println("=== drift per year, so the number above is not a one-off ===")
        for (y in intArrayOf(2026, 2036, 2050, 2100)) {
            val ms = (y - 1970).toLong() * 31_556_952_000L
            val d = sep(stars[0].second, stars[0].third,
                Ephemeris.precessFromJ2000(stars[0].second, stars[0].third, ms).rightAscensionDeg,
                Ephemeris.precessFromJ2000(stars[0].second, stars[0].third, ms).declinationDeg)
            println("  Sirius in %d: %5.1f arcmin".format(y, d * 60))
        }
    }
}
