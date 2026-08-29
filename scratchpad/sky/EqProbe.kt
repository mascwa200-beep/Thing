import dev.mascwa.pulse.core.telemetry.Ephemeris
import dev.mascwa.pulse.core.telemetry.SkyPointing
import dev.mascwa.pulse.core.telemetry.SkyProjection
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Measure what `SkyPointing.toEquatorialVector` actually does, before any assertion is written.
 *
 * The recurring habit this exists to break: an expectation reasoned about in my head has been wrong
 * where the shipped code was right roughly eighteen times in this project. The declination of the
 * celestial pole below is the immediate example — I first wrote "aim north at 90 minus the latitude",
 * which is out by twice the latitude.
 */
object EqProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val lat = 51.5074
        val lon = -0.1278
        val epochs = longArrayOf(1_700_000_000_000L, 1_711_000_000_000L, 1_735_689_600_000L)

        println("=== the pair stays orthonormal through the rotation ===")
        var worstDot = 0.0
        var worstLen = 0.0
        val f = DoubleArray(3); val u = DoubleArray(3)
        val ef = DoubleArray(3); val eu = DoubleArray(3)
        for (e in epochs) for (az in 0 until 360 step 37) for (alt in -80..90 step 17) for (roll in -180 until 180 step 47) {
            val a = SkyPointing.Attitude(az.toDouble(), alt.toDouble(), roll.toDouble())
            SkyPointing.forward(a, f); SkyPointing.screenUp(a, u)
            SkyPointing.toEquatorialVector(f, lat, lon, e, ef)
            SkyPointing.toEquatorialVector(u, lat, lon, e, eu)
            worstDot = maxOf(worstDot, abs(ef[0]*eu[0] + ef[1]*eu[1] + ef[2]*eu[2]))
            worstLen = maxOf(worstLen, abs(sqrt(ef[0]*ef[0]+ef[1]*ef[1]+ef[2]*ef[2]) - 1.0))
            worstLen = maxOf(worstLen, abs(sqrt(eu[0]*eu[0]+eu[1]*eu[1]+eu[2]*eu[2]) - 1.0))
        }
        println("worst |f.u| = $worstDot   worst |len-1| = $worstLen")

        println()
        println("=== the zenith's declination is the observer's latitude ===")
        for (e in epochs) {
            val z = Ephemeris.toEquatorial(Ephemeris.Horizontal(90.0, 0.0, 0.0), lat, lon, e)
            println("  epoch $e  dec=${"%.9f".format(z.declinationDeg)}  (lat $lat)")
        }

        println()
        println("=== due north at altitude = latitude is the celestial pole ===")
        for (e in epochs) {
            val p = Ephemeris.toEquatorial(Ephemeris.Horizontal(lat, 0.0, 0.0), lat, lon, e)
            println("  epoch $e  dec=${"%.9f".format(p.declinationDeg)}")
        }
        println("  and at 90 - lat (the version I first wrote):")
        for (e in epochs.take(1)) {
            val p = Ephemeris.toEquatorial(Ephemeris.Horizontal(90.0 - lat, 0.0, 0.0), lat, lon, e)
            println("  epoch $e  dec=${"%.9f".format(p.declinationDeg)}  <- NOT 90")
        }

        println()
        println("=== aimed at the zenith, which basis survives the rotation ===")
        val up = SkyPointing.Attitude(37.0, 90.0, 0.0)
        SkyPointing.forward(up, f); SkyPointing.screenUp(up, u)
        val e0 = epochs[0]
        SkyPointing.toEquatorialVector(f, lat, lon, e0, ef)
        SkyPointing.toEquatorialVector(u, lat, lon, e0, eu)
        val zen = Ephemeris.toEquatorial(Ephemeris.Horizontal(90.0, 0.0, 0.0), lat, lon, e0)
        val zv = SkyProjection.equatorialVector(zen.rightAscensionDeg, zen.declinationDeg)
        println("  screen-up basis usable = ${SkyProjection.basisOf(ef, eu[0], eu[1], eu[2], 60.0, 0.0).usable}")
        println("  zenith   basis usable = ${SkyProjection.basisOf(ef, zv[0], zv[1], zv[2], 60.0, 0.0).usable}")

        println()
        println("=== a swapped Horizontal(alt, az) would show up here ===")
        val a = SkyPointing.Attitude(120.0, 30.0, 0.0)
        SkyPointing.forward(a, f)
        SkyPointing.toEquatorialVector(f, lat, lon, e0, ef)
        val right = Ephemeris.toEquatorial(Ephemeris.Horizontal(30.0, 120.0, 0.0), lat, lon, e0)
        val wrong = Ephemeris.toEquatorial(Ephemeris.Horizontal(120.0, 30.0, 0.0), lat, lon, e0)
        val rv = SkyProjection.equatorialVector(right.rightAscensionDeg, right.declinationDeg)
        val wv = SkyProjection.equatorialVector(wrong.rightAscensionDeg, wrong.declinationDeg)
        fun sep(p: DoubleArray, q: DoubleArray) =
            Math.toDegrees(Math.acos((p[0]*q[0]+p[1]*q[1]+p[2]*q[2]).coerceIn(-1.0, 1.0)))
        println("  shipped vs correct order: ${"%.2e".format(sep(ef, rv))} deg")
        println("  shipped vs swapped order: ${"%.4f".format(sep(ef, wv))} deg")
    }
}
