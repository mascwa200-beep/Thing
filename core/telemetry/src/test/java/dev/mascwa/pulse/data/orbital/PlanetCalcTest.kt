package dev.mascwa.pulse.data.orbital

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * The planets are checked against JPL, not against themselves.
 *
 * Expected values are DE421 read through Skyfield: geocentric **apparent, true equinox of date**
 * right ascension and declination; **topocentric** altitude and azimuth WITHOUT refraction at
 * 42.7875 N, 86.1089 W (the owner's site); and the Mallama & Hilton (2018) magnitude. Twelve
 * instants spread over 2000–2045, because the previous method's largest omission — the great
 * Jupiter–Saturn inequality, period 918 years — looked like nothing across any single year, and a
 * sample that cannot see a slow term is how such a term survives.
 *
 * ⚠️ **The reference instants are built from a UTC calendar date, never `ts.utc(1970, 1, 1, 0, 0,
 * ms / 1000)`.** Skyfield looks the leap seconds up for the DATE it is handed and then adds the
 * seconds, so that form lands every instant 22–27 seconds early in both TT and UT1 — 400
 * arcseconds of rotation and a few arcseconds of Mercury — and a reference generated that way
 * disagreed with the one these fixtures replaced by up to 1.6 arcseconds for a day before the
 * cause was found. `scratchpad/sky/planets_ref.py` carries the warning at the line.
 *
 * ⚠️ **The magnitude fixtures are the Mallama formulae fed TRUE heliocentric vectors.** Skyfield's
 * own `planetary_magnitude` "shamelessly treats the Sun as sitting at the Solar System Barycenter"
 * (its comment), which is up to 0.01 AU wrong — a tenth of a magnitude on Mercury and nothing on
 * Neptune. The app uses the Sun, so the fixtures do too; against Skyfield's shortcut the worst
 * disagreement is 0.122 on Mercury, and that is the reference's error rather than this code's.
 */
class PlanetCalcTest {

    /** Site of the topocentric fixtures. */
    private val siteLat = 42.7875
    private val siteLon = -86.1089

    private class Ref(
        val ms: Long, val name: String,
        val raDeg: Double, val decDeg: Double,
        val altDeg: Double, val azDeg: Double,
        val mag: Double,
    )

    private fun separationArcsec(ra1: Double, d1: Double, ra2: Double, d2: Double): Double {
        val r = Math.PI / 180.0
        val v = sin(d1 * r) * sin(d2 * r) + cos(d1 * r) * cos(d2 * r) * cos((ra1 - ra2) * r)
        return acos(v.coerceIn(-1.0, 1.0)) / r * 3600.0
    }

    private val jpl = listOf(
        // 2000-01-01 00:00 UTC
        Ref(946684800000L, "Mercury", 271.22050, -24.37866, -25.25444, 259.32289, -0.722),
        Ref(946684800000L, "Venus", 239.27232, -18.31362, -44.62980, 288.18287, -4.069),
        Ref(946684800000L, "Mars", 330.14521, -13.32237, 21.07841, 226.10880, 1.033),
        Ref(946684800000L, "Jupiter", 23.85001, 8.58459, 54.69027, 162.73035, -2.525),
        Ref(946684800000L, "Saturn", 38.77610, 12.61568, 52.92501, 136.99721, 0.101),
        Ref(946684800000L, "Uranus", 317.44976, -17.02780, 10.91351, 234.20783, 5.943),
        Ref(946684800000L, "Neptune", 305.41452, -19.21722, 1.77884, 241.47954, 7.855),
        // 2004-02-29 00:00 UTC
        Ref(1078012800000L, "Mercury", 338.75121, -11.07754, -9.89493, 264.01128, -1.407),
        Ref(1078012800000L, "Venus", 21.46475, 9.94748, 35.19648, 248.60146, -4.217),
        Ref(1078012800000L, "Mars", 43.58700, 17.62337, 55.14581, 232.61019, 1.085),
        Ref(1078012800000L, "Jupiter", 166.28965, 7.40907, 1.92759, 81.68283, -2.495),
        Ref(1078012800000L, "Saturn", 96.87854, 22.75605, 61.26969, 126.30156, -0.121),
        Ref(1078012800000L, "Uranus", 335.38374, -11.01215, -12.31201, 266.30782, 6.001),
        Ref(1078012800000L, "Neptune", 316.32945, -16.70507, -30.01176, 274.72214, 7.845),
        // 2008-04-28 00:00 UTC
        Ref(1209340800000L, "Mercury", 48.24938, 19.36796, 18.77534, 279.35804, -1.224),
        Ref(1209340800000L, "Venus", 25.38992, 9.10494, -4.47922, 286.78444, -3.881),
        Ref(1209340800000L, "Mars", 116.00585, 23.21707, 67.15471, 215.55965, 1.169),
        Ref(1209340800000L, "Jupiter", 293.94043, -21.63829, -64.85955, 37.85117, -2.323),
        Ref(1209340800000L, "Saturn", 154.37278, 12.55505, 53.25606, 138.13238, 0.541),
        Ref(1209340800000L, "Uranus", 352.27983, -4.14292, -36.34041, 303.96710, 5.991),
        Ref(1209340800000L, "Neptune", 326.45086, -13.80777, -57.82368, 329.37137, 7.796),
        // 2012-06-26 00:00 UTC
        Ref(1340668800000L, "Mercury", 122.28390, 21.16172, 31.47664, 270.57599, 0.266),
        Ref(1340668800000L, "Venus", 66.36948, 17.97132, -9.25916, 305.22216, -4.595),
        Ref(1340668800000L, "Mars", 176.65596, 2.01738, 47.89664, 197.68071, 0.791),
        Ref(1340668800000L, "Jupiter", 61.30683, 20.03610, -10.55737, 310.34533, -2.041),
        Ref(1340668800000L, "Saturn", 202.01806, -6.46138, 39.22063, 162.43929, 0.609),
        Ref(1340668800000L, "Uranus", 8.01626, 2.67898, -44.53219, 0.55528, 5.903),
        Ref(1340668800000L, "Neptune", 335.19545, -10.95064, -47.03943, 52.11095, 7.731),
        // 2016-08-24 00:00 UTC
        Ref(1471996800000L, "Mercury", 176.17082, -1.77964, 12.98238, 255.11553, 0.567),
        Ref(1471996800000L, "Venus", 173.64513, 4.12226, 15.27515, 261.29940, -3.882),
        Ref(1471996800000L, "Mars", 247.51002, -24.64811, 22.55644, 179.09718, -0.449),
        Ref(1471996800000L, "Jupiter", 177.27457, 2.38323, 16.69075, 257.38588, -1.690),
        Ref(1471996800000L, "Saturn", 248.46980, -20.37374, 26.81484, 178.02827, 0.400),
        Ref(1471996800000L, "Uranus", 22.69528, 8.81789, -24.73614, 48.97253, 5.792),
        Ref(1471996800000L, "Neptune", 342.70208, -8.29087, -10.09261, 91.99576, 7.691),
        // 2020-10-22 00:00 UTC
        Ref(1603324800000L, "Mercury", 214.28754, -15.94085, -11.10498, 258.47745, 3.262),
        Ref(1603324800000L, "Venus", 173.79367, 4.10973, -25.55530, 303.42158, -4.010),
        Ref(1603324800000L, "Mars", 18.14035, 5.03848, 15.59420, 97.71107, -2.484),
        Ref(1603324800000L, "Jupiter", 291.44962, -22.42074, 23.64409, 193.44766, -2.203),
        Ref(1603324800000L, "Saturn", 297.81416, -21.31424, 25.57765, 187.18888, 0.558),
        Ref(1603324800000L, "Uranus", 36.84419, 14.07319, 8.01613, 78.21420, 5.700),
        Ref(1603324800000L, "Neptune", 349.91079, -5.55137, 26.71440, 127.83528, 7.705),
        // 2024-12-20 00:00 UTC
        Ref(1734652800000L, "Mercury", 246.33334, -18.98565, -32.14553, 273.32789, -0.132),
        Ref(1734652800000L, "Venus", 317.62094, -18.44605, 15.92280, 224.56364, -4.317),
        Ref(1734652800000L, "Mars", 128.25211, 22.33069, -7.70857, 49.63318, -0.930),
        Ref(1734652800000L, "Jupiter", 73.40757, 21.91931, 28.78032, 85.87639, -2.789),
        Ref(1734652800000L, "Saturn", 345.77796, -8.25420, 36.60735, 201.36136, 1.019),
        Ref(1734652800000L, "Uranus", 51.66790, 18.51745, 42.45202, 105.08294, 5.655),
        Ref(1734652800000L, "Neptune", 357.92010, -2.31033, 44.67297, 187.09448, 7.757),
        // 2029-02-17 00:00 UTC
        Ref(1865980800000L, "Mercury", 306.49095, -19.85626, -31.24905, 271.16005, -0.065),
        Ref(1865980800000L, "Venus", 322.77116, -15.89954, -16.79720, 263.65330, -3.899),
        Ref(1865980800000L, "Mars", 194.05768, -2.42159, -31.86450, 59.50267, -0.465),
        Ref(1865980800000L, "Jupiter", 205.92036, -9.18312, -44.44138, 52.90499, -2.226),
        Ref(1865980800000L, "Saturn", 34.50082, 11.37699, 50.98804, 224.31312, 0.366),
        Ref(1865980800000L, "Uranus", 68.58355, 21.96207, 68.27177, 161.09311, 5.649),
        Ref(1865980800000L, "Neptune", 7.12295, 1.51769, 26.67323, 244.87450, 7.806),
        // 2033-04-17 00:00 UTC
        Ref(1997308800000L, "Mercury", 7.75943, 0.66297, -15.18823, 285.52032, -0.361),
        Ref(1997308800000L, "Venus", 352.29835, 0.48757, -25.86810, 297.48935, -4.777),
        Ref(1997308800000L, "Mars", 274.62741, -23.80429, -62.14943, 54.93424, -0.412),
        Ref(1997308800000L, "Jupiter", 332.71868, -12.05332, -47.82655, 306.72099, -2.115),
        Ref(1997308800000L, "Saturn", 92.30747, 22.75578, 59.95215, 236.80459, 0.068),
        Ref(1997308800000L, "Uranus", 88.22172, 23.64122, 57.99375, 243.25920, 5.666),
        Ref(1997308800000L, "Neptune", 17.11659, 5.58738, -5.07211, 282.43573, 7.819),
        // 2037-06-15 00:00 UTC
        Ref(2128636800000L, "Mercury", 80.47573, 23.77931, 11.04754, 292.27556, -2.073),
        Ref(2128636800000L, "Venus", 108.06342, 23.79601, 30.65088, 275.19516, -3.888),
        Ref(2128636800000L, "Mars", 7.71589, 1.01350, -45.24757, 345.42638, 0.269),
        Ref(2128636800000L, "Jupiter", 95.28389, 23.26970, 21.07224, 282.72345, -1.887),
        Ref(2128636800000L, "Saturn", 150.70795, 13.59323, 52.78181, 226.43588, 0.671),
        Ref(2128636800000L, "Uranus", 109.83713, 22.61085, 31.23373, 272.94516, 5.673),
        Ref(2128636800000L, "Neptune", 27.10832, 9.38379, -31.25319, 325.24782, 7.786),
        // 2041-08-13 00:00 UTC
        Ref(2259964800000L, "Mercury", 158.19020, 10.43068, 16.20812, 269.30235, -0.619),
        Ref(2259964800000L, "Venus", 107.36684, 21.69667, -9.89096, 312.27675, -3.942),
        Ref(2259964800000L, "Mars", 85.10590, 23.39341, -18.48491, 331.62496, 1.136),
        Ref(2259964800000L, "Jupiter", 204.61846, -9.04441, 30.92786, 216.45798, -1.854),
        Ref(2259964800000L, "Saturn", 199.36036, -5.62345, 31.45251, 223.72357, 0.809),
        Ref(2259964800000L, "Uranus", 131.75913, 18.53739, 2.77434, 292.88376, 5.644),
        Ref(2259964800000L, "Neptune", 36.33254, 12.53477, -31.90070, 22.40728, 7.741),
        // 2045-10-11 00:00 UTC
        Ref(2391292800000L, "Mercury", 215.99446, -16.10196, -2.31841, 250.07511, -0.199),
        Ref(2391292800000L, "Venus", 241.95017, -24.09852, 7.79806, 226.49806, -4.376),
        Ref(2391292800000L, "Mars", 153.11486, 12.54717, -24.03540, 317.46744, 1.634),
        Ref(2391292800000L, "Jupiter", 335.36763, -11.66692, 23.64142, 134.90978, -2.727),
        Ref(2391292800000L, "Saturn", 246.69781, -20.13787, 13.55854, 225.10605, 0.535),
        Ref(2391292800000L, "Uranus", 152.56224, 12.06096, -24.72721, 317.70809, 5.586),
        Ref(2391292800000L, "Neptune", 44.50661, 15.00731, -4.23355, 65.01875, 7.679),
    )

    private fun ours(ms: Long, lat: Double = siteLat, lon: Double = siteLon): Map<String, Planet> =
        PlanetCalc.planetsNow(lat, lon, ms, includeOuter = true).associateBy { it.name }

    /**
     * ⚠️ **The bar is 4.5 arcseconds because the measured worst is 3.77 and the median under 1.5,
     * against a previous method whose worst was 2.9 arcMINUTES** — a sixtyfold gap that no round
     * number would have to sit inside. What remains is measured too: about 1.5" is common to every
     * planet and is the one-term nutation shortcut, which the star slice replaces; Uranus and
     * Neptune add up to 2" of VSOP87-against-DE421 disagreement in the outer solar system, which
     * no truncation setting can remove. Restoring the old method, or dropping the light-time or the
     * aberration (20" each way), fails this by a wide margin.
     */
    @Test
    fun everyPlanetIsWithinFourAndAHalfArcsecondsOfJplAcrossFortyFiveYears() {
        var worst = 0.0
        var worstWho = ""
        for (j in jpl) {
            val p = ours(j.ms).getValue(j.name)
            val off = separationArcsec(p.rightAscensionDeg, p.declinationDeg, j.raDeg, j.decDeg)
            if (off > worst) { worst = off; worstWho = "${j.name} at ${j.ms}" }
        }
        assertTrue("worst was $worst arcsec, on $worstWho", worst < 4.5)
    }

    /**
     * The horizon coordinates are TOPOCENTRIC — the observer's own parallax is applied — and are
     * compared against Skyfield's `altaz()` without refraction.
     *
     * ⚠️ The bar is 30" against a measured worst of 21.7", and both halves of the residual are
     * named: [Ephemeris.toHorizontal] runs on Greenwich MEAN sidereal time where the reference runs
     * on apparent, which is the equation of the equinoxes (up to 17"); and the reference carries
     * UT1 − UTC, which this app deliberately ignores as Stellarium does (13" inside the IERS table,
     * 21" in 2045 where Skyfield's ΔT extrapolation parts from the constant). Without the parallax
     * step Venus alone would miss this by 33".
     */
    @Test
    fun altitudeAndAzimuthAreTopocentricAndWithinHalfAnArcminuteOfJpl() {
        var worst = 0.0
        var worstWho = ""
        for (j in jpl) {
            val p = ours(j.ms).getValue(j.name)
            // A great-circle separation on the horizon sphere, so azimuth near the zenith is not
            // over-counted.
            val off = separationArcsec(p.azimuthDeg, p.altitudeDeg, j.azDeg, j.altDeg)
            if (off > worst) { worst = off; worstWho = "${j.name} at ${j.ms}" }
            assertEquals("${j.name} aboveHorizon at ${j.ms}", j.altDeg > 0.0, p.aboveHorizon)
        }
        assertTrue("worst alt/az was $worst arcsec, on $worstWho", worst < 30.0)
    }

    /**
     * Mallama & Hilton 2018 against the same formulae in Skyfield fed the true heliocentric
     * vectors: agreement to a few thousandths, which is the fixtures' own rounding. The old
     * magnitudes were a different, older fit and had none for Uranus or Neptune.
     */
    @Test
    fun magnitudesMatchMallamaAndHiltonToAHundredth() {
        var worst = 0.0
        var worstWho = ""
        for (j in jpl) {
            val p = ours(j.ms).getValue(j.name)
            val off = abs(p.magnitude - j.mag)
            if (off > worst) { worst = off; worstWho = "${j.name} at ${j.ms}" }
            assertTrue("${j.name} magnitude ${p.magnitude} is not finite", p.magnitude.isFinite())
        }
        assertTrue("worst magnitude error was $worst, on $worstWho", worst < 0.01)
    }

    /**
     * The naked-eye five are the default so that every consumer written against that list keeps
     * it; the star map asks for all seven, in the order the solar system has them.
     */
    @Test
    fun theOuterPlanetsAreOptInAndTheDefaultListIsTheNakedEyeFive() {
        val ms = 1734652800000L
        assertEquals(
            listOf("Mercury", "Venus", "Mars", "Jupiter", "Saturn"),
            PlanetCalc.planetsNow(siteLat, siteLon, ms).map { it.name },
        )
        assertEquals(
            listOf("Mercury", "Venus", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune"),
            PlanetCalc.planetsNow(siteLat, siteLon, ms, includeOuter = true).map { it.name },
        )
    }

    /**
     * ⚠️ The observer's position must not move a geocentric coordinate at all. The parallax step
     * sits between the published right ascension and the horizon conversion, and getting it on
     * the wrong side would make right ascension depend on where you are standing — which would
     * still look plausible on one screen and be nonsense on another, and would put a planet in the
     * wrong place against a star for the occultation search.
     *
     * ⚠️ **Compared EXACTLY, and the first version of this compared through a separation helper
     * and was worthless.** That helper is the standard cosine formula, and for two identical
     * positions `sin²d + cos²d` lands a bit either side of one, so `acos` of it returns up to
     * 1.5e-8 radians — three milliarcseconds of separation between a coordinate and itself. The
     * claim here is not "close": it is that the two numbers are the same number.
     */
    @Test
    fun rightAscensionAndDeclinationDoNotDependOnWhereYouAreStanding() {
        val ms = 1734652800000L
        val london = ours(ms, 51.5074, -0.1278)
        val sydney = ours(ms, -33.8688, 151.2093)
        assertEquals(7, london.size)
        for ((name, a) in london) {
            val b = sydney.getValue(name)
            assertEquals("$name right ascension", a.rightAscensionDeg, b.rightAscensionDeg, 0.0)
            assertEquals("$name declination", a.declinationDeg, b.declinationDeg, 0.0)
            assertEquals("$name distance", a.distanceAu, b.distanceAu, 0.0)
            assertEquals("$name phase angle", a.phaseAngleDeg, b.phaseAngleDeg, 0.0)
        }
    }

    /**
     * The horizon coordinates DO depend on where you are standing, by the parallax: at Venus's
     * nearest the shift between two antipodal observers is tens of arcseconds, which is exactly what
     * the exact-equality test above must never see in the published pair.
     */
    @Test
    fun theHorizonCoordinatesCarryTheParallaxThePublishedPairMustNot() {
        // Skyfield's topocentric altitude at the site against a GEOCENTRIC conversion of the
        // published pair: the difference is the parallax, and for Venus it is well above the
        // fixture's own resolution.
        var biggest = 0.0
        for (j in jpl.filter { it.name == "Venus" || it.name == "Mars" }) {
            val p = ours(j.ms).getValue(j.name)
            val geocentricAlt = dev.mascwa.pulse.core.telemetry.Ephemeris.toHorizontal(
                dev.mascwa.pulse.core.telemetry.Ephemeris.Equatorial(p.rightAscensionDeg, p.declinationDeg, 0.0),
                siteLat, siteLon, j.ms,
            ).altitudeDeg
            biggest = maxOf(biggest, abs(geocentricAlt - p.altitudeDeg) * 3600.0)
        }
        // Venus at 0.3 AU: a horizontal parallax of ~29", seen in full only low in the sky, so the
        // largest across twelve instants is somewhere between a few and thirty arcseconds.
        assertTrue("the largest parallax in the set was only $biggest arcsec", biggest > 5.0)
        assertTrue("$biggest arcsec is not a planetary parallax", biggest < 40.0)
    }
}
