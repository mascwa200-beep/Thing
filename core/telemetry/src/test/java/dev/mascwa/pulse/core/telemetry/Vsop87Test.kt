package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The evaluator is held to the builder's own numbers, in both of the ways a generated table can go
 * wrong.
 *
 * ⚠️ **Every figure here was written by `tools/sky/build_vsop87.py` into `pins.json`, not typed
 * from memory.** The `truncated` values are what the SAME truncated table evaluates to in Python,
 * so the Kotlin loop is checked for reading the triples in the right order with the right powers of
 * T; the `full` values are the untruncated 35,080-term theory, so the truncation is checked for the
 * geocentric bound the table's header claims. A regenerated table that silently reordered a series,
 * dropped a power, or was built to a looser target fails one or the other.
 */
class Vsop87Test {

    private class Pin(val body: Vsop87.Body, val terms: Int, val jdTT: DoubleArray, val full: Array<DoubleArray>, val truncated: Array<DoubleArray>)

    // jdTT: J2000, 1950-ish (T = -0.05 millennia) and 2075-ish (T = +0.075).
    private val jd = doubleArrayOf(2451545.0, 2433282.5, 2478938.75)

    private val pins = listOf(
        Pin(
            Vsop87.Body.MERCURY, 180, jd,
            arrayOf(
                doubleArrayOf(4.429348104272946, -0.05275734105402421, 0.46647147511719655),
                doubleArrayOf(0.29981538553816023, -0.0636200007992677, 0.3365448760141946),
                doubleArrayOf(0.31780539361756155, -0.0613705396979291, 0.33574481343773466),
            ),
            arrayOf(
                doubleArrayOf(4.429348317816383, -0.05275799813963612, 0.46647171924141273),
                doubleArrayOf(0.2998151606603656, -0.06362081252460806, 0.336544133409655),
                doubleArrayOf(0.31780671728205334, -0.061370701709029, 0.3357455432583729),
            ),
        ),
        Pin(
            Vsop87.Body.VENUS, 180, jd,
            arrayOf(
                doubleArrayOf(3.187022190992958, 0.056978284936708284, 0.7202129248467305),
                doubleArrayOf(1.439494097901239, 0.005845215949269752, 0.7200994802282035),
                doubleArrayOf(2.627794466969945, 0.056972827194091545, 0.7187333740267781),
            ),
            arrayOf(
                doubleArrayOf(3.187022022895912, 0.05697823573408036, 0.7202128786321136),
                doubleArrayOf(1.43949370491017, 0.005845200821608934, 0.7200993191365712),
                doubleArrayOf(2.627794467280083, 0.05697292684186657, 0.7187335761743231),
            ),
        ),
        Pin(
            Vsop87.Body.EARTH, 295, jd,
            arrayOf(
                doubleArrayOf(1.7519238636718284, -3.965571572162726e-06, 0.9833276823222945),
                doubleArrayOf(1.7577142821899514, 0.00011006070818915071, 0.9832436380520053),
                doubleArrayOf(1.743353252453737, -0.00016474899742944895, 0.9833246863529765),
            ),
            arrayOf(
                doubleArrayOf(1.751923915110192, -3.896454164678344e-06, 0.9833276321464995),
                doubleArrayOf(1.7577143067635888, 0.00011004514002923309, 0.9832437395290126),
                doubleArrayOf(1.7433532398064173, -0.00016474830987643207, 0.9833249502674654),
            ),
        ),
        Pin(
            Vsop87.Body.MARS, 826, jd,
            arrayOf(
                doubleArrayOf(6.273538987210828, -0.024777982364448628, 1.3912076937159727),
                doubleArrayOf(2.566586020901539, 0.032087377453148935, 1.6638366902099893),
                doubleArrayOf(5.331443799513693, -0.031239362206248122, 1.3980853605837127),
            ),
            arrayOf(
                doubleArrayOf(6.273539204620161, -0.024778003066698858, 1.3912078782928796),
                doubleArrayOf(2.5665860125051125, 0.03208733924759008, 1.6638367782221868),
                doubleArrayOf(5.331443778845653, -0.031239343578909114, 1.398085462989569),
            ),
        ),
        Pin(
            Vsop87.Body.JUPITER, 395, jd,
            arrayOf(
                doubleArrayOf(0.633461421663384, -0.020500103873171388, 4.965381280273754),
                doubleArrayOf(5.448446459374733, -0.011999925711745034, 5.074472510110298),
                doubleArrayOf(2.6902176206559645, 0.018273451744815778, 5.390965372653508),
            ),
            arrayOf(
                doubleArrayOf(0.6334630021223006, -0.020499567646411275, 4.965382295100237),
                doubleArrayOf(5.448447239292438, -0.011999844512824867, 5.074472059362516),
                doubleArrayOf(2.6902172051813267, 0.01827361358087879, 5.390964972138372),
            ),
        ),
        Pin(
            Vsop87.Body.SATURN, 851, jd,
            arrayOf(
                doubleArrayOf(0.7980038867457715, -0.04019841492227554, 9.183848288052612),
                doubleArrayOf(2.870813246627497, 0.03357658877453019, 9.35325265439381),
                doubleArrayOf(4.354054481467852, 0.030164247958603445, 10.01796258344474),
            ),
            arrayOf(
                doubleArrayOf(0.7980042986521199, -0.04019833718617551, 9.183848700851188),
                doubleArrayOf(2.8708134882211933, 0.03357625930093496, 9.353252718000023),
                doubleArrayOf(4.354054219149003, 0.0301642535143733, 10.017964153134425),
            ),
        ),
        Pin(
            Vsop87.Body.URANUS, 892, jd,
            arrayOf(
                doubleArrayOf(5.522548529693809, -0.011952787831287188, 19.92404789520859),
                doubleArrayOf(1.636370448604379, 0.004564579942961258, 18.945445604599545),
                doubleArrayOf(4.883646342036043, -0.005852312920678804, 19.44249369908732),
            ),
            arrayOf(
                doubleArrayOf(5.5225479615970166, -0.011952389172094959, 19.924048314890562),
                doubleArrayOf(1.6363705642228732, 0.004564457316291263, 18.945444995803275),
                doubleArrayOf(4.883647143338054, -0.005852110784295067, 19.442495368760166),
            ),
        ),
        Pin(
            Vsop87.Body.NEPTUNE, 262, jd,
            arrayOf(
                doubleArrayOf(5.304562928379009, 0.004223678979045335, 30.120532933188983),
                doubleArrayOf(3.422968018076232, 0.02784187174944554, 30.295264593195473),
                doubleArrayOf(1.913477285855965, -0.011650602699405604, 29.962978648075296),
            ),
            arrayOf(
                doubleArrayOf(5.304563326414055, 0.004222982188133416, 30.12053316602601),
                doubleArrayOf(3.4229679926087746, 0.027841873844993217, 30.29526730702082),
                doubleArrayOf(1.9134773949182637, -0.011650722662344262, 29.962979815965944),
            ),
        ),
    )

    private fun xyz(l: Double, b: Double, r: Double) =
        doubleArrayOf(r * cos(b) * cos(l), r * cos(b) * sin(l), r * sin(b))

    private fun angleArcsec(a: DoubleArray, b: DoubleArray): Double {
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        return Math.toDegrees(acos((dot / (na * nb)).coerceIn(-1.0, 1.0))) * 3600.0
    }

    @Test
    fun `the evaluator reads the truncated table exactly as the builder does`() {
        // Same table, same arithmetic, different summation order: agreement to a picometre in
        // radius and a nanoarcsecond in angle is floating point and nothing else. A dropped power
        // of T or a misread triple is orders of magnitude away from this.
        for (p in pins) {
            for (i in 0..2) {
                val s = Vsop87.spherical(p.body, p.jdTT[i])
                assertEquals("${p.body} L at ${p.jdTT[i]}", p.truncated[i][0], s.longitudeRad, 1e-10)
                assertEquals("${p.body} B at ${p.jdTT[i]}", p.truncated[i][1], s.latitudeRad, 1e-10)
                assertEquals("${p.body} R at ${p.jdTT[i]}", p.truncated[i][2], s.radiusAu, 1e-9)
            }
        }
    }

    @Test
    fun `the term counts are the ones the table was built with`() {
        // A regenerated table to a looser target would pass the pin test above with new pins; it
        // cannot pass this without the header changing too, which is the point of writing it down.
        for (p in pins) assertEquals("${p.body}", p.terms, Vsop87.termCount(p.body))
        // ⚠️ MERCURY and VENUS keep the same number of terms; that is a coincidence of the ladder,
        // not a shared table — their J2000 longitudes differ by 1.2 radians above.
    }

    @Test
    fun `the truncation moves no geocentric direction by more than the header's half arcsecond`() {
        // The builder measured this over a 2,001-point grid and 400 random instants; these three
        // are a spot check that the Kotlin side really is the same truncation. The Earth's own error
        // is inside every geocentric vector, so it is checked through the others rather than alone.
        val earth = pins.first { it.body == Vsop87.Body.EARTH }
        for (p in pins) {
            if (p.body == Vsop87.Body.EARTH) continue
            for (i in 0..2) {
                val pf = xyz(p.full[i][0], p.full[i][1], p.full[i][2])
                val ef = xyz(earth.full[i][0], earth.full[i][1], earth.full[i][2])
                val pk = DoubleArray(3).also { Vsop87.heliocentricJ2000Au(p.body, p.jdTT[i], it) }
                val ek = DoubleArray(3).also { Vsop87.heliocentricJ2000Au(Vsop87.Body.EARTH, p.jdTT[i], it) }
                val gf = doubleArrayOf(pf[0] - ef[0], pf[1] - ef[1], pf[2] - ef[2])
                val gk = doubleArrayOf(pk[0] - ek[0], pk[1] - ek[1], pk[2] - ek[2])
                val err = angleArcsec(gf, gk)
                assertTrue("${p.body} at ${p.jdTT[i]}: $err arcsec", err < 0.5)
            }
        }
    }

    @Test
    fun `longitude is normalised and the Earth's velocity is the orbital one`() {
        for (p in pins) {
            val s = Vsop87.spherical(p.body, 2460000.5)
            assertTrue("${p.body} longitude ${s.longitudeRad}", s.longitudeRad >= 0.0 && s.longitudeRad < 2 * Math.PI)
        }
        // 2π AU a year is 0.01720 AU/day for a circular orbit; the real speed varies by the
        // eccentricity, 1.7%, either side of it.
        val v = DoubleArray(3)
        val r = DoubleArray(3)
        for (jd in doubleArrayOf(2451545.0, 2455000.0, 2460500.0)) {
            Vsop87.earthVelocityAuPerDay(jd, v)
            Vsop87.heliocentricJ2000Au(Vsop87.Body.EARTH, jd, r)
            val speed = Vsop87.length(v)
            assertTrue("speed $speed at $jd", abs(speed - 0.017202) < 0.0004)
            // Nearly perpendicular to the position: the radial component is the eccentricity's.
            val cosAngle = (r[0] * v[0] + r[1] * v[1] + r[2] * v[2]) / (Vsop87.length(r) * speed)
            assertTrue("radial fraction $cosAngle at $jd", abs(cosAngle) < 0.02)
        }
    }
}
