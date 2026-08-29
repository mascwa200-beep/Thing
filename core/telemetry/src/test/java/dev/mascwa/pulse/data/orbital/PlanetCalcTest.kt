package dev.mascwa.pulse.data.orbital

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * The planets are checked against JPL, not against themselves.
 *
 * ⚠️ **This test exists because the file's own accuracy claim was wrong in both directions, and a
 * one-year sample would have said it was fine.** PlanetCalc implements Schlyter's low-precision
 * method and had left out section 10 of it — the perturbation terms for Jupiter and Saturn, whose
 * largest is the great Jupiter–Saturn inequality at 0.332 degrees for Jupiter and 0.812 for Saturn.
 * That term has a period of **918 years**, so across any twelve months it is very nearly a constant
 * offset and looks like nothing. Measured across fifty years it was **14.7 arcminutes** for Jupiter
 * and 13.3 for Saturn, against a Moon whose entire radius is 15.6.
 *
 * So the fixtures below are twelve instants spread over 2000 to 2045 rather than a year of them.
 * Sampling a period long enough for the term to move is the whole point; a tighter, denser sample
 * would have been more work and would have proved less.
 *
 * Expected values are DE421 read through Skyfield, **apparent, equinox of date** — which is the
 * convention Schlyter's method works in. Comparing against J2000 would show a spurious twenty
 * arcminutes of precession and comparing against astrometric would show twenty arcseconds of
 * aberration.
 */
class PlanetCalcTest {

    private class Jpl(val ms: Long, val name: String, val raDeg: Double, val decDeg: Double)

    private fun separationArcmin(ra1: Double, d1: Double, ra2: Double, d2: Double): Double {
        val r = Math.PI / 180.0
        val v = sin(d1 * r) * sin(d2 * r) + cos(d1 * r) * cos(d2 * r) * cos((ra1 - ra2) * r)
        return acos(v.coerceIn(-1.0, 1.0)) / r * 60.0
    }

    private val jpl = listOf(
        Jpl(946684800000L, "Mercury", 271.22050, -24.37866),
        Jpl(946684800000L, "Venus", 239.27232, -18.31362),
        Jpl(946684800000L, "Mars", 330.14521, -13.32237),
        Jpl(946684800000L, "Jupiter", 23.85001, 8.58459),
        Jpl(946684800000L, "Saturn", 38.77610, 12.61568),
        // 2000-01-01
        Jpl(1078012800000L, "Mercury", 338.75121, -11.07754),
        Jpl(1078012800000L, "Venus", 21.46475, 9.94748),
        Jpl(1078012800000L, "Mars", 43.58700, 17.62337),
        Jpl(1078012800000L, "Jupiter", 166.28965, 7.40907),
        Jpl(1078012800000L, "Saturn", 96.87854, 22.75605),
        // 2004-02-29
        Jpl(1209340800000L, "Mercury", 48.24938, 19.36796),
        Jpl(1209340800000L, "Venus", 25.38992, 9.10494),
        Jpl(1209340800000L, "Mars", 116.00585, 23.21707),
        Jpl(1209340800000L, "Jupiter", 293.94043, -21.63829),
        Jpl(1209340800000L, "Saturn", 154.37278, 12.55505),
        // 2008-04-28
        Jpl(1340668800000L, "Mercury", 122.28390, 21.16172),
        Jpl(1340668800000L, "Venus", 66.36948, 17.97132),
        Jpl(1340668800000L, "Mars", 176.65596, 2.01738),
        Jpl(1340668800000L, "Jupiter", 61.30683, 20.03610),
        Jpl(1340668800000L, "Saturn", 202.01806, -6.46138),
        // 2012-06-26
        Jpl(1471996800000L, "Mercury", 176.17082, -1.77964),
        Jpl(1471996800000L, "Venus", 173.64513, 4.12226),
        Jpl(1471996800000L, "Mars", 247.51002, -24.64811),
        Jpl(1471996800000L, "Jupiter", 177.27457, 2.38323),
        Jpl(1471996800000L, "Saturn", 248.46980, -20.37374),
        // 2016-08-24
        Jpl(1603324800000L, "Mercury", 214.28754, -15.94085),
        Jpl(1603324800000L, "Venus", 173.79367, 4.10973),
        Jpl(1603324800000L, "Mars", 18.14035, 5.03848),
        Jpl(1603324800000L, "Jupiter", 291.44962, -22.42074),
        Jpl(1603324800000L, "Saturn", 297.81416, -21.31424),
        // 2020-10-22
        Jpl(1734652800000L, "Mercury", 246.33334, -18.98565),
        Jpl(1734652800000L, "Venus", 317.62094, -18.44605),
        Jpl(1734652800000L, "Mars", 128.25211, 22.33069),
        Jpl(1734652800000L, "Jupiter", 73.40757, 21.91931),
        Jpl(1734652800000L, "Saturn", 345.77796, -8.25420),
        // 2024-12-20
        Jpl(1865980800000L, "Mercury", 306.49095, -19.85626),
        Jpl(1865980800000L, "Venus", 322.77116, -15.89954),
        Jpl(1865980800000L, "Mars", 194.05768, -2.42159),
        Jpl(1865980800000L, "Jupiter", 205.92036, -9.18312),
        Jpl(1865980800000L, "Saturn", 34.50082, 11.37699),
        // 2029-02-17
        Jpl(1997308800000L, "Mercury", 7.75943, 0.66297),
        Jpl(1997308800000L, "Venus", 352.29835, 0.48757),
        Jpl(1997308800000L, "Mars", 274.62741, -23.80429),
        Jpl(1997308800000L, "Jupiter", 332.71868, -12.05332),
        Jpl(1997308800000L, "Saturn", 92.30747, 22.75578),
        // 2033-04-17
        Jpl(2128636800000L, "Mercury", 80.47573, 23.77931),
        Jpl(2128636800000L, "Venus", 108.06342, 23.79601),
        Jpl(2128636800000L, "Mars", 7.71589, 1.01350),
        Jpl(2128636800000L, "Jupiter", 95.28389, 23.26970),
        Jpl(2128636800000L, "Saturn", 150.70795, 13.59323),
        // 2037-06-15
        Jpl(2259964800000L, "Mercury", 158.19020, 10.43068),
        Jpl(2259964800000L, "Venus", 107.36684, 21.69667),
        Jpl(2259964800000L, "Mars", 85.10590, 23.39341),
        Jpl(2259964800000L, "Jupiter", 204.61846, -9.04441),
        Jpl(2259964800000L, "Saturn", 199.36036, -5.62345),
        // 2041-08-13
        Jpl(2391292800000L, "Mercury", 215.99446, -16.10196),
        Jpl(2391292800000L, "Venus", 241.95017, -24.09852),
        Jpl(2391292800000L, "Mars", 153.11486, 12.54717),
        Jpl(2391292800000L, "Jupiter", 335.36763, -11.66692),
        Jpl(2391292800000L, "Saturn", 246.69781, -20.13787),
        // 2045-10-11
    )

    /**
     * ⚠️ The bar is three arcminutes because the measured worst is 2.9 and the median 0.6. A round
     * number well above what the code achieves is not a guard — it would let the perturbation terms
     * be deleted again and stay green, which is precisely how this defect survived being written.
     */
    @Test
    fun everyPlanetIsWithinThreeArcminutesOfJplAcrossFiftyYears() {
        var worst = 0.0
        var worstWho = ""
        for (j in jpl) {
            val p = PlanetCalc.planetsNow(51.5074, -0.1278, j.ms).first { it.name == j.name }
            val off = separationArcmin(p.rightAscensionDeg, p.declinationDeg, j.raDeg, j.decDeg)
            if (off > worst) { worst = off; worstWho = "${j.name} at ${j.ms}" }
        }
        assertTrue("worst was $worst arcmin, on $worstWho", worst < 3.0)
    }

    /**
     * The two outer planets specifically, because they are the ones the perturbations act on and
     * the ones that were wrong. Without section 10 this fails by a factor of five.
     */
    @Test
    fun theTwoPerturbedPlanetsAreNoWorseThanTheThreeThatNeedNoCorrection() {
        var outer = 0.0
        var inner = 0.0
        for (j in jpl) {
            val p = PlanetCalc.planetsNow(0.0, 0.0, j.ms).first { it.name == j.name }
            val off = separationArcmin(p.rightAscensionDeg, p.declinationDeg, j.raDeg, j.decDeg)
            if (j.name == "Jupiter" || j.name == "Saturn") outer = maxOf(outer, off)
            else inner = maxOf(inner, off)
        }
        // Not "outer < inner" -- that would be luck. Within a factor of two of each other is the
        // real claim: the outer planets are no longer a different class of wrong.
        assertTrue("outer $outer arcmin against inner $inner", outer < inner * 2.0)
    }

    /**
     * ⚠️ The observer's position must not move a geocentric coordinate at all. The perturbation
     * work sits between the orbit and the rectangular conversion, and getting it on the wrong side
     * of the horizon transform would make right ascension depend on where you are standing — which
     * would still look plausible on one screen and be nonsense on another.
     *
     * ⚠️ **Compared EXACTLY, and the first version of this compared through [separationArcmin] and
     * was worthless.** That helper is the standard cosine formula, and for two identical positions
     * `sin²d + cos²d` lands a bit either side of one, so `acos` of it returns up to 1.5e-8 radians
     * — three milliarcseconds of separation between a coordinate and itself. Whether it did so
     * depended on the declination's last bit, so the test passed or failed by luck. The claim here
     * is not "close": it is that the two numbers are the same number, so that is what is asserted.
     */
    @Test
    fun rightAscensionAndDeclinationDoNotDependOnWhereYouAreStanding() {
        val ms = 1734652800000L
        val london = PlanetCalc.planetsNow(51.5074, -0.1278, ms).associateBy { it.name }
        val sydney = PlanetCalc.planetsNow(-33.8688, 151.2093, ms).associateBy { it.name }
        for ((name, a) in london) {
            val b = sydney.getValue(name)
            assertEquals("$name right ascension", a.rightAscensionDeg, b.rightAscensionDeg, 0.0)
            assertEquals("$name declination", a.declinationDeg, b.declinationDeg, 0.0)
        }
    }
}
