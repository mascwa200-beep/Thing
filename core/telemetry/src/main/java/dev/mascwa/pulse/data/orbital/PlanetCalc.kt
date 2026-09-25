package dev.mascwa.pulse.data.orbital

import dev.mascwa.pulse.core.telemetry.Ephemeris
import dev.mascwa.pulse.core.telemetry.PlanetDisc
import dev.mascwa.pulse.core.telemetry.SkyProjection
import dev.mascwa.pulse.core.telemetry.Vsop87
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the planets are, as seen from the observer — VSOP87 positions carried through every
 * correction a planetarium applies, and Mallama & Hilton's magnitudes.
 *
 * ⚠️ **This replaced Paul Schlyter's low-precision method, whose measured worst was 2.9 arcminutes
 * against JPL, and the reason was not the arithmetic of the orbit alone.** That method carried no
 * light-time, no aberration, no nutation and no parallax, drew its Sun and its sidereal time from
 * its own approximations, and knew nothing of Uranus or Neptune — so a chart that could show a
 * magnitude-15 star could not show a magnitude-5.7 planet. A planet is now: the [Vsop87]
 * heliocentric position minus the Earth's, from ONE theory, then in order —
 *
 *  1. **light-time**: the planet is drawn where it WAS when the light left, `Δ/c` earlier, found by
 *     two corrected evaluations (the third-order change is a hundredth of an arcsecond);
 *  2. **annual aberration**, through [Ephemeris.aberrate], the same function the Sun goes through;
 *  3. **precession and nutation** to the true equinox of date, through [Ephemeris.j2000ToTrueOfDate]
 *     — again the Sun's own rotation, so a planet and the Sun beside it cannot sit in frames a few
 *     arcseconds apart;
 *  4. **parallax**, through [Ephemeris.topocentric], for the horizon coordinates only.
 *
 * ⚠️ **[Planet.rightAscensionDeg] and [Planet.declinationDeg] are GEOCENTRIC, apparent, true equinox
 * of date; [Planet.altitudeDeg] and [Planet.azimuthDeg] are TOPOCENTRIC.** The split is deliberate
 * and two callers depend on each half: the occultation search compares a planet against a star in
 * the geocentric frame, and a screen wants the horizon coordinates to say where to look. A
 * geocentric altitude would put Venus up to 33 arcseconds from where a telescope finds it; a
 * topocentric right ascension would make the coordinate depend on where you are standing, which
 * `PlanetCalcTest` asserts EXACTLY that it does not.
 *
 * Magnitudes are Mallama & Hilton (2018), *Computing Apparent Planetary Magnitudes for the
 * Astronomical Almanac* — the formulae Stellarium and Skyfield both use — transcribed from
 * Skyfield's `magnitudelib.py` so that the two disagree only where this file says so: Saturn past
 * a phase angle of 6.5° and Neptune before 2000 return NaN there, and here fall back to the
 * globe-only and phase-free forms respectively, because a chart cannot size a marker from NaN and
 * the Earth never sees Saturn from that angle anyway.
 *
 * ⚠️ The five naked-eye planets are the default and Uranus and Neptune are opt-in
 * ([includeOuter]), so every existing consumer — the radar scope, the observatory, the desktop —
 * keeps the list it was written against, and the star map, whose catalogue reaches far past
 * magnitude 8, asks for all seven.
 */
object PlanetCalc {

    private class Entry(val name: String, val body: Vsop87.Body, val nakedEye: Boolean)

    private val ALL = listOf(
        Entry("Mercury", Vsop87.Body.MERCURY, nakedEye = true),
        Entry("Venus", Vsop87.Body.VENUS, nakedEye = true),
        Entry("Mars", Vsop87.Body.MARS, nakedEye = true),
        Entry("Jupiter", Vsop87.Body.JUPITER, nakedEye = true),
        Entry("Saturn", Vsop87.Body.SATURN, nakedEye = true),
        Entry("Uranus", Vsop87.Body.URANUS, nakedEye = false),
        Entry("Neptune", Vsop87.Body.NEPTUNE, nakedEye = false),
    )

    /**
     * How many times the light-time is re-solved after the geometric first guess.
     *
     * The first correction moves Jupiter by about forty arcminutes of its orbit; the second by the
     * change in light-time THAT caused, of order a hundredth of an arcsecond; a third would be a
     * millionth. Two is where the arithmetic stops mattering, measured rather than assumed.
     */
    private const val LIGHT_TIME_ITERATIONS = 2

    private const val DEG = Math.PI / 180.0

    /**
     * Every planet as seen from ([lat], [lon]) at [epochMs].
     *
     * @param includeOuter Uranus and Neptune as well as the five naked-eye planets. Default off,
     *   so the naked-eye consumers keep the list they were written against.
     */
    fun planetsNow(
        lat: Double,
        lon: Double,
        epochMs: Long = System.currentTimeMillis(),
        includeOuter: Boolean = false,
    ): List<Planet> {
        val jdTT = Ephemeris.julianDateTT(epochMs)
        val earth = DoubleArray(3)
        Vsop87.heliocentricJ2000Au(Vsop87.Body.EARTH, jdTT, earth)
        val year = Ephemeris.julianYear(epochMs)
        return ALL
            .filter { includeOuter || it.nakedEye }
            .map { compute(it, jdTT, epochMs, earth, year, lat, lon) }
    }

    private fun compute(
        e: Entry,
        jdTT: Double,
        epochMs: Long,
        earth: DoubleArray,
        year: Double,
        lat: Double,
        lon: Double,
    ): Planet {
        val helio = DoubleArray(3)
        val geo = DoubleArray(3)
        // Light-time: start from the geometric position, then re-evaluate the planet where it was
        // when the light now arriving left it. Each pass uses the distance the previous one found.
        var tau = 0.0
        repeat(LIGHT_TIME_ITERATIONS + 1) {
            Vsop87.heliocentricJ2000Au(e.body, jdTT - tau, helio)
            geo[0] = helio[0] - earth[0]
            geo[1] = helio[1] - earth[1]
            geo[2] = helio[2] - earth[2]
            tau = Vsop87.length(geo) / Ephemeris.LIGHT_AU_PER_DAY
        }
        val delta = Vsop87.length(geo)
        val r = Vsop87.length(helio)

        // The Sun–planet–Earth angle, from the two vectors that meet at the planet. The angle
        // between `helio` (Sun → planet) and `geo` (Earth → planet) is the same angle as between
        // their negations, which point from the planet to the Sun and to the Earth.
        val phaseDeg = acos(
            ((helio[0] * geo[0] + helio[1] * geo[1] + helio[2] * geo[2]) / (r * delta)).coerceIn(-1.0, 1.0),
        ) / DEG

        // Direction, then aberration, then the frame: J2000 ecliptic → J2000 equator → true of date.
        val dir = doubleArrayOf(geo[0] / delta, geo[1] / delta, geo[2] / delta)
        Ephemeris.aberrate(dir, jdTT)
        val eqJ2000 = Ephemeris.eclipticJ2000ToEquatorialDeg(dir)
        val ofDate = Ephemeris.j2000ToTrueOfDate(eqJ2000[0], eqJ2000[1], epochMs)
        val ra = ofDate[0]
        val dec = ofDate[1]

        // Parallax for the horizon coordinates only; the geocentric pair above is what is published.
        val geocentric = Ephemeris.Equatorial(ra, dec, delta * Ephemeris.AU_KM)
        val seen = Ephemeris.topocentric(geocentric, lat, lon, epochMs)
        val horizon = Ephemeris.toHorizontal(seen, lat, lon, epochMs)

        val mag = magnitude(e.body, r, delta, phaseDeg, helio, geo, year)
        return Planet(
            e.name, horizon.altitudeDeg, horizon.azimuthDeg, mag, horizon.altitudeDeg > 0.0,
            ra, dec, distanceAu = delta, phaseAngleDeg = phaseDeg,
        )
    }

    // ---- magnitudes: Mallama & Hilton 2018 -------------------------------------------------------

    /**
     * Apparent visual magnitude from the heliocentric distance [r], the geocentric distance
     * [delta], the phase angle [ph] in degrees and, for the two tilted giants, the geometry of their
     * poles. [helio] and [geo] are the J2000 ECLIPTIC vectors the position was built from.
     */
    private fun magnitude(
        body: Vsop87.Body,
        r: Double,
        delta: Double,
        ph: Double,
        helio: DoubleArray,
        geo: DoubleArray,
        year: Double,
    ): Double {
        val dist = 5.0 * log10(r * delta)
        return when (body) {
            Vsop87.Body.MERCURY ->
                -0.613 + dist + ph * (6.3280e-2 + ph * (-1.6336e-3 + ph * (3.3644e-5 +
                    ph * (-3.4265e-7 + ph * (1.6893e-9 + ph * -3.0334e-12)))))

            Vsop87.Body.VENUS ->
                if (ph < 163.7) {
                    -4.384 + dist + ph * (-1.044e-3 + ph * (3.687e-4 + ph * (-2.814e-6 + ph * 8.938e-9)))
                } else {
                    // Past 163.7° the paper switches to a second fit; its constant term is stated
                    // relative to the -4.384 above, which is why both appear.
                    -4.384 + dist + (236.05828 + 4.384) + ph * (-2.81914 + ph * 8.39034e-3)
                }

            Vsop87.Body.MARS ->
                // ⚠️ Mars's rotational and orbital light-curve corrections (which face of the
                // planet is turned toward us) are omitted, as Skyfield omits them: ±0.06 mag.
                if (ph <= 50.0) -1.601 + dist + ph * (2.267e-2 + ph * -1.302e-4)
                else -0.367 + dist + ph * (-0.02573 + ph * 0.0003445)

            Vsop87.Body.JUPITER ->
                if (ph <= 12.0) {
                    -9.395 + dist + (6.16e-4 * ph - 3.7e-4) * ph
                } else {
                    val p = ph / 180.0
                    -9.428 + dist - 2.5 * log10(
                        ((((-1.876 * p + 2.809) * p - 0.062) * p - 0.363) * p - 1.507) * p + 1.0,
                    )
                }

            Vsop87.Body.SATURN -> {
                val sunLat = subLatitudeDeg(PlanetDisc.SATURN_POLE_RA_DEG, PlanetDisc.SATURN_POLE_DEC_DEG, helio)
                val earthLat = subLatitudeDeg(PlanetDisc.SATURN_POLE_RA_DEG, PlanetDisc.SATURN_POLE_DEC_DEG, geo)
                // The square root of the product of the two Saturnicentric latitudes, or zero when
                // the Sun and the Earth are on opposite sides of the ring plane (the paper's rule).
                val product = sunLat * earthLat
                val subLat = if (product >= 0.0) sqrt(product) else 0.0
                if (ph <= 6.5 && subLat <= 27.0) {
                    // Equation 10: globe plus rings, the only case the Earth ever sees.
                    val s = sin(subLat * DEG)
                    -8.914 - 1.825 * s + 0.026 * ph - 0.378 * s * exp(-2.25 * ph) + dist
                } else {
                    // ⚠️ Unreachable from the Earth (the phase angle tops out near 6.4°) and NaN in
                    // Skyfield; equation 12, the globe alone, is the honest number to hand a chart
                    // that has to size a marker from it.
                    -8.94 + dist + ph * (2.446e-4 + ph * (2.672e-4 + ph * (-1.506e-6 + ph * 4.767e-9)))
                }
            }

            Vsop87.Body.URANUS -> {
                val sunLat = subLatitudeDeg(PlanetDisc.URANUS_POLE_RA_DEG, PlanetDisc.URANUS_POLE_DEC_DEG, helio)
                val earthLat = subLatitudeDeg(PlanetDisc.URANUS_POLE_RA_DEG, PlanetDisc.URANUS_POLE_DEC_DEG, geo)
                val subLat = (abs(sunLat) + abs(earthLat)) / 2.0
                -7.110 + dist - 0.00084 * subLat +
                    if (ph > 3.1) (1.045e-4 * ph + 6.587e-3) * ph else 0.0
            }

            Vsop87.Body.NEPTUNE -> {
                // Equation 16: Neptune brightened through the 1980s and 90s, so the magnitude at unit
                // distance is a function of the year, clipped to the range the paper fitted.
                val base = (-6.89 - 0.0054 * (year - 1980.0)).coerceIn(-7.00, -6.89)
                // Equation 17 pertains only to years from 2000; before that Skyfield returns NaN
                // past 1.9°, and here the phase term is simply left off.
                base + dist + if (ph > 1.9 && year >= 2000.0) 7.944e-3 * ph + 9.617e-5 * ph * ph else 0.0
            }

            Vsop87.Body.EARTH -> Double.NaN
        }
    }

    /**
     * The latitude on a planet from which a vector's origin is seen overhead — the sub-solar
     * latitude for the Sun → planet vector, the sub-Earth latitude for the Earth → planet one.
     *
     * The pole is stated in J2000 equatorial coordinates (the IAU working-group frame) and the
     * vectors arrive in the J2000 ecliptic, so the vector is rotated to the equator first. The
     * angle between the pole and the vector, less a right angle, is the latitude — the same rule
     * [PlanetDisc.rings] uses for the ring opening, with the same sign convention as Skyfield's
     * `magnitudelib`, where the vectors point TOWARD the planet.
     */
    private fun subLatitudeDeg(poleRaDeg: Double, poleDecDeg: Double, eclipticVector: DoubleArray): Double {
        val pole = SkyProjection.equatorialVector(poleRaDeg, poleDecDeg)
        val eps0 = Ephemeris.obliquityJ2000Deg * DEG
        val x = eclipticVector[0]
        val y = eclipticVector[1] * cos(eps0) - eclipticVector[2] * sin(eps0)
        val z = eclipticVector[1] * sin(eps0) + eclipticVector[2] * cos(eps0)
        val n = sqrt(x * x + y * y + z * z)
        val cosAngle = ((pole[0] * x + pole[1] * y + pole[2] * z) / n).coerceIn(-1.0, 1.0)
        return acos(cosAngle) / DEG - 90.0
    }
}
