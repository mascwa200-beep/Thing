package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How large a solar-system body actually looks, and what shape it is when you get there.
 *
 * ## Why this exists at all
 *
 * The sky map drew the Sun as a nine-pixel circle, the Moon as eight and every planet as five —
 * fixed sizes, unrelated to anything. That was defensible while the field of view had a four-degree
 * floor, because at four degrees the Sun's half a degree is under an eighth of the screen's narrow
 * axis and a marker is all it could ever be. S1 dropped the floor to a quarter of a degree, and at
 * that field the solar disc spans **twice the screen**. A fixed nine pixels is now the one thing
 * standing between the map and actually looking at the Sun.
 *
 * ## ⚠️ The Sun's and the Moon's radii are copied to MATCH, not chosen independently
 *
 * [Eclipses] and [Occultations] each declare their own `SUN_RADIUS_KM` / `MOON_RADIUS_KM`, which is
 * the duplicated-definition drift this project has corrected several times. The values here are
 * taken **verbatim from those files** rather than from a fresher source, so the three agree by
 * construction rather than by luck. The IAU nominal solar radius is 695,700 km against the 696,000
 * used there; the difference is 0.04%, which is under an arcsecond on a half-degree disc and
 * invisible on any screen — but it is emphatically NOT invisible in an eclipse contact time, so
 * converging them is a job for whoever touches the eclipse maths, not for a rendering slice.
 *
 * ## What a caller has to supply, and why
 *
 * Everything here takes a distance or an angle rather than a date. Where a planet IS belongs to
 * [Ephemeris] and `PlanetCalc`; how big it LOOKS is a triangle, and keeping the triangle separate is
 * what makes every number below checkable against an external ephemeris without a clock anywhere in
 * the file.
 */
object PlanetDisc {

    private const val DEG = Math.PI / 180.0

    /** One astronomical unit, IAU 2012 definition — exact by convention. */
    const val AU_KM = 149_597_870.7

    /** The bodies this map draws as more than a point. */
    enum class Body { SUN, MOON, MERCURY, VENUS, MARS, JUPITER, SATURN }

    /**
     * Equatorial radius in kilometres.
     *
     * ⚠️ **Equatorial, not mean**, and for the two giants that is the visible difference: Jupiter is
     * 6.5% flatter through the poles and Saturn 9.8%, which at a narrow field is several pixels of a
     * disc that is plainly an oval through a small telescope. Drawing them round is the commonest
     * way a planetarium looks wrong to somebody who has actually looked.
     */
    fun equatorialRadiusKm(body: Body): Double = when (body) {
        Body.SUN -> SUN_RADIUS_KM
        Body.MOON -> MOON_RADIUS_KM
        Body.MERCURY -> 2439.7
        Body.VENUS -> 6051.8
        Body.MARS -> 3396.2
        Body.JUPITER -> 71_492.0
        Body.SATURN -> 60_268.0
    }

    /** Polar radius in kilometres — equal to the equatorial one except for Mars and the giants. */
    fun polarRadiusKm(body: Body): Double = when (body) {
        Body.MARS -> 3376.2
        Body.JUPITER -> 66_854.0
        Body.SATURN -> 54_364.0
        else -> equatorialRadiusKm(body)
    }

    /** How flat the disc is, 0 for a sphere. Saturn is the most oblate planet in the system. */
    fun flattening(body: Body): Double {
        val a = equatorialRadiusKm(body)
        if (a <= 0.0) return 0.0
        return (a - polarRadiusKm(body)) / a
    }

    /**
     * The full angular diameter in degrees.
     *
     * ⚠️ **`2 asin(r/d)`, not `2r/d`.** The small-angle form is right to a part in ten million for
     * everything in this table and would be perfectly fine — but the same function has to answer for
     * the Sun seen from Mercury's distance if anything ever asks, and an `asin` that refuses an
     * impossible ratio beats a linear form that silently returns nonsense. A body closer than its own
     * radius is not a view, so it answers zero rather than NaN.
     */
    fun apparentDiameterDeg(radiusKm: Double, distanceKm: Double): Double {
        if (!(radiusKm > 0.0) || !(distanceKm > 0.0)) return 0.0
        val s = radiusKm / distanceKm
        if (s >= 1.0) return 0.0
        return 2.0 * asin(s) / DEG
    }

    /** The same, for a distance in astronomical units. */
    fun apparentDiameterDegAu(radiusKm: Double, distanceAu: Double): Double =
        apparentDiameterDeg(radiusKm, distanceAu * AU_KM)

    // ---- phase -----------------------------------------------------------------------------------

    /**
     * The lit fraction of the disc, from the Sun-body-observer angle.
     *
     * Half at ninety degrees, full at zero, new at a hundred and eighty. This is the same
     * `(1 + cos i)/2` [Ephemeris.moonPhase] uses; it lives here as well so a planet does not have to
     * borrow the Moon's phase machinery to answer the same question about itself.
     */
    fun illuminatedFraction(phaseAngleDeg: Double): Double =
        ((1.0 + cos(phaseAngleDeg * DEG)) / 2.0).coerceIn(0.0, 1.0)

    /**
     * The terminator's semi-minor axis, as a **signed** fraction of the disc's radius.
     *
     * ⚠️ **The lit part of a phased disc is a half-circle joined to a half-ELLIPSE, not two
     * overlapping circles.** Drawing it as a circular bite is the classic wrong crescent: it is
     * wrong everywhere except exactly half phase, and near full it produces a shape no telescope has
     * ever shown. The correct boundary is the projection of the great circle dividing day from
     * night, which projects to an ellipse whose semi-minor axis is `r cos i` — so this returns
     * `cos i` and the renderer scales by the radius.
     *
     * The SIGN carries which way the terminator bulges, and it is the half that is easy to lose:
     * positive is gibbous (the ellipse bows away from the lit limb, so the lit part is more than
     * half), negative is crescent. At exactly half phase it is zero and the terminator is a straight
     * line, which is what "quarter Moon" means and why that phase looks like a semicircle.
     */
    fun terminatorFactor(phaseAngleDeg: Double): Double = cos(phaseAngleDeg * DEG)

    /**
     * Limb darkening: how bright the Sun's disc is at a given fraction of its radius from centre.
     *
     * The standard one-parameter law, `1 - u(1 - cos θ)` with `cos θ = sqrt(1 - x²)` — about 0.6 in
     * visible light, so the very edge is roughly 40% as bright as the centre. It is a real and
     * plainly visible effect, and it is the thing that makes a drawn Sun look like the Sun rather
     * than like a disc of paint.
     *
     * @param fractionOfRadius 0 at the centre, 1 at the limb. Outside that, zero.
     */
    fun limbDarkening(fractionOfRadius: Double): Double {
        if (fractionOfRadius < 0.0 || fractionOfRadius > 1.0) return 0.0
        val mu = sqrt(1.0 - fractionOfRadius * fractionOfRadius)
        return (1.0 - LIMB_DARKENING_U * (1.0 - mu)).coerceIn(0.0, 1.0)
    }

    /** The visible-light limb-darkening coefficient. */
    const val LIMB_DARKENING_U = 0.6

    // ---- Saturn's rings --------------------------------------------------------------------------

    /**
     * Ring radii in units of Saturn's own equatorial radius, so a renderer scales them with the disc.
     *
     * The C ring's inner edge is where the rings become visible at all; the A ring's outer edge is
     * where they end; and the Cassini division between the B and A rings is the one gap an ordinary
     * telescope shows, which is why it is the only one modelled.
     */
    const val RING_INNER = 74_658.0 / 60_268.0
    const val RING_OUTER = 136_780.0 / 60_268.0
    const val CASSINI_INNER = 117_580.0 / 60_268.0
    const val CASSINI_OUTER = 122_170.0 / 60_268.0

    /** Saturn's north pole in J2000 equatorial coordinates (IAU working group values). */
    const val SATURN_POLE_RA_DEG = 40.589
    const val SATURN_POLE_DEC_DEG = 83.537

    /** Jupiter's north pole, same source. Its equator is where the Galilean moons run. */
    const val JUPITER_POLE_RA_DEG = 268.057
    const val JUPITER_POLE_DEC_DEG = 64.495

    /**
     * Where a planet's north pole points on the sky, in degrees east of celestial north.
     *
     * ⚠️ **Two things need this and they used to be one function's private business.** Saturn's rings
     * lie in its equator and so do Jupiter's moons, so "which way is the planet's axis tilted from
     * here" is the same question twice — and answering it twice is the duplicated-definition drift
     * this project has corrected repeatedly. It is pure geometry: the pole vector's components along
     * local north and local east at the planet's own place, and an `atan2`. No series, nothing fitted,
     * and checkable against any ephemeris with nothing but a right ascension and a declination.
     *
     * The value is the position angle of the pole itself. The planet's **equator**, which is what
     * both callers actually draw along, lies ninety degrees from it.
     */
    fun axisPositionAngle(
        poleRaDeg: Double,
        poleDecDeg: Double,
        bodyRaDeg: Double,
        bodyDecDeg: Double,
    ): Double {
        val p = SkyProjection.equatorialVector(poleRaDeg, poleDecDeg)
        val dec = bodyDecDeg * DEG
        val ra = bodyRaDeg * DEG
        // d(unit)/d(dec) is exactly local north; d(unit)/d(ra) / cos(dec) is exactly local east.
        val north = p[0] * (-sin(dec) * cos(ra)) + p[1] * (-sin(dec) * sin(ra)) + p[2] * cos(dec)
        val east = p[0] * -sin(ra) + p[1] * cos(ra)
        return (atan2(east, north) / DEG + 360.0) % 360.0
    }

    /**
     * How open the rings are and which way they are tilted on the sky.
     *
     * @property openingDeg the Saturnicentric latitude of the observer — zero when the rings are
     *   edge-on and invisible, about 27 degrees at their widest. **Signed**: positive means the
     *   northern face is turned toward us.
     * @property positionAngleDeg where the ring plane's minor axis points, measured east of north,
     *   which is what tells a renderer how to rotate the ellipse.
     */
    class Rings(val openingDeg: Double, val positionAngleDeg: Double) {
        /** The ellipse's minor axis as a fraction of its major one. Zero exactly edge-on. */
        val squash: Double get() = abs(sin(openingDeg * DEG))
    }

    /**
     * Where the rings are pointing, from Saturn's apparent place and the fixed pole direction.
     *
     * ⚠️ **This is geometry, not a theory, and that is what makes it testable.** The opening angle is
     * just the angle between the pole and the line of sight, taken ninety degrees round; the position
     * angle is where the projected pole lands on the sky. Both come out of two dot products and an
     * `atan2` over unit vectors, so there is no series to get subtly wrong and the whole thing can be
     * checked against an external ephemeris with nothing but a right ascension and a declination.
     *
     * The pole is treated as fixed. It precesses, but by well under a degree per century, and the
     * rings' *appearance* is dominated by Saturn's 29-year orbit rather than by that.
     */
    fun rings(saturnRaDeg: Double, saturnDecDeg: Double): Rings {
        val p = SkyProjection.equatorialVector(SATURN_POLE_RA_DEG, SATURN_POLE_DEC_DEG)
        val s = SkyProjection.equatorialVector(saturnRaDeg, saturnDecDeg)
        // The observer's Saturnicentric latitude: the pole's angle from the line of sight, minus a
        // right angle. Negated because `s` points AWAY from the observer, toward Saturn.
        val cosPoleToLos = (p[0] * s[0] + p[1] * s[1] + p[2] * s[2]).coerceIn(-1.0, 1.0)
        val opening = -asin(cosPoleToLos) / DEG
        val pa = axisPositionAngle(SATURN_POLE_RA_DEG, SATURN_POLE_DEC_DEG, saturnRaDeg, saturnDecDeg)
        return Rings(opening, pa)
    }

    // ---- Jupiter's moons -------------------------------------------------------------------------

    /**
     * One Galilean satellite, offset from Jupiter's centre in units of Jupiter's equatorial radius.
     *
     * ⚠️ **These are Jupiter's coordinates, NOT the sky's, and the difference is large enough to see.**
     * The frame is Jupiter's own equator: [x] runs along it and [y] across it. To draw them, rotate
     * by [axisPositionAngle] for Jupiter — which over 2026 alone swings between 8 and 18 degrees, so
     * Callisto at 26 radii sits up to eight radii off horizontal. Treating these as celestial west
     * and north was this function's third defect and, unlike the other two, it produces a perfectly
     * plausible-looking line of moons in the wrong place.
     */
    class Moonlet(
        val name: String,
        /** Along Jupiter's equator, in Jovian radii — the axis the orbits string out along. */
        val x: Double,
        /**
         * Across Jupiter's equator, in Jovian radii. Small: measured over 2026–2074 the orbits are
         * never seen more than **3.64 degrees** from edge-on, so this stays within a **fifteenth** of
         * the moon's own orbital radius. (⚠️ A first draft of this line said a seventeenth, which is
         * 0.0588 against a measured 0.0635 — close enough to sound right and still wrong.)
         */
        val y: Double,
        /** True when the moon is on the far side of the planet from us. */
        val behind: Boolean,
    )

    /**
     * The four moons Galileo saw.
     *
     * Meeus's low-accuracy method (Astronomical Algorithms 2nd ed., ch. 44): four mean longitudes
     * linear in time, each corrected by one large periodic term, with matching swings in orbital
     * radius, projected onto the plane of Jupiter's equator. It is not an ephemeris — it carries one
     * perturbation per moon where the real system has hundreds.
     *
     * ## What that is worth, measured rather than claimed
     *
     * Run against **JPL Horizons over 2000–2049** (107 samples at 171-day steps, so every moon lands
     * at an effectively random phase each time), in Jovian radii:
     *
     * | | Io | Europa | Ganymede | Callisto |
     * |---|---|---|---|---|
     * | mean error | 0.020 | 0.056 | 0.057 | 0.119 |
     * | worst error | 0.066 | 0.148 | 0.183 | 0.361 |
     *
     * ⚠️ **No drift and no bias**: the same figures over one year and over half a century, and every
     * mean signed residual is under 0.004 radii. On screen the worst case is about seven pixels, and
     * only at the one field where the whole system just fits the narrow axis; at any wider field it
     * is under a pixel.
     *
     * ⚠️ **What remains is almost entirely ACROSS the orbits, which is where the model is knowingly
     * flat.** Splitting the residual per axis, the scatter along the orbit is 0.009 to 0.046 radii
     * while across it is 0.022 to 0.133 — and the across-track figure is a near-constant 0.5% of each
     * moon's own orbital radius, which is what a small orbital inclination looks like. This function
     * places all four orbits exactly in Jupiter's equator; they are inclined to it by a few tenths of
     * a degree. That is the method's own simplification rather than a further defect, and it is the
     * reason to stop here rather than to keep chasing constants.
     *
     * ⚠️ **The orbits are drawn as a straight line and that is correct, not a simplification.**
     * Jupiter's equator is tilted about three degrees to its orbit and the moons sit in that plane,
     * so they string out along one axis. Anyone who has looked through a telescope has seen the line.
     *
     * @param epochMs the instant, which is the only place a clock enters this file.
     */
    fun galileanMoons(epochMs: Long): List<Moonlet> {
        // Days since J2000.0 TT. ⚠️ EVERY constant below is stated on this epoch. An earlier draft
        // of this function mixed an 1899-epoch phase set with J2000-era rates, which is a mistake
        // that cannot be seen by reading — the moons still swing back and forth about Jupiter and
        // still keep roughly the right periods; they are simply in the wrong places. It took a real
        // ephemeris to catch, and that is the reason `scratchpad/sky/MoonProbe.kt` exists.
        val d = Ephemeris.julianDateTT(epochMs) - 2_451_545.0

        // Where Jupiter and the Earth are, which is all the geometry the rest of this needs.
        val v = (172.74 + 0.00111588 * d) * DEG
        val bigM = (357.529 + 0.9856003 * d) * DEG
        val bigN = ((20.020 + 0.0830853 * d) + 0.329 * sin(v)) * DEG
        val bigJ = ((66.115 + 0.9025179 * d) - 0.329 * sin(v)) * DEG
        val a = (1.915 * sin(bigM) + 0.020 * sin(2 * bigM)) * DEG
        val b = (5.555 * sin(bigN) + 0.168 * sin(2 * bigN)) * DEG
        val k = bigJ + a - b
        val bigR = 1.00014 - 0.01671 * cos(bigM) - 0.00014 * cos(2 * bigM)
        val r = 5.20872 - 0.25208 * cos(bigN) - 0.00611 * cos(2 * bigN)
        val delta = sqrt(r * r + bigR * bigR - 2 * r * bigR * cos(k))
        val psi = asin((bigR * sin(k) / delta).coerceIn(-1.0, 1.0)) / DEG
        val lam = 34.35 + 0.083091 * d + 0.329 * sin(v) + b / DEG

        // How far the moons' plane is tipped out of edge-on as seen from here. The first term is the
        // Sun's view; the other two swing it round to ours, and they matter — the difference between
        // the two reaches about a degree, which at Callisto is a quarter of a Jovian radius.
        val dSun = 3.12 * sin((lam + 42.8) * DEG)
        val dEarth = dSun -
            2.22 * sin(psi * DEG) * sin((lam + 22.0) * DEG) -
            1.30 * (r - delta) / delta * sin((lam - 100.5) * DEG)

        // Mean longitudes, measured from superior geocentric conjunction: u = 0 puts the moon
        // directly behind Jupiter. Corrected for light time (Δ/173 days), for the parallactic shift
        // ψ, and for B.
        //
        // ⚠️ The frame term is `- B`, the equation of the centre, and NOT `- J`. That was the earlier
        // draft's second defect and it is invisible in a single frame: J carries a secular rate of
        // 0.9025179 degrees a day, so subtracting it slowed Io from 203.4059 to 202.503 degrees a
        // day — a period of 1.7778 days against 1.7699, drifting a whole orbit in about seven
        // months. B is a bounded oscillation with no rate at all, which is exactly why it can be
        // subtracted from a mean longitude without changing how fast the moon goes.
        //
        // ⚠️ The rates are JUPITER-RELATIVE and the arithmetic says so plainly: each is Jupiter's own
        // mean motion (0.0830853 deg/day) below the moon's true sidereal rate. That is what makes
        // them measurable from here — they are angles seen against Jupiter, which is the only thing
        // a telescope can report.
        val dl = d - delta / 173.0
        val u1 = (163.8067 + 203.4058643 * dl + psi - b / DEG) * DEG
        val u2 = (358.4108 + 101.2916334 * dl + psi - b / DEG) * DEG
        val u3 = (5.7129 + 50.2345179 * dl + psi - b / DEG) * DEG
        val u4 = (224.8151 + 21.4879801 * dl + psi - b / DEG) * DEG
        val g = (331.18 + 50.310482 * d) * DEG
        val h = (87.45 + 21.569231 * d) * DEG

        // The two big periodic terms, and the matching swings in orbital radius. ⚠️ Both read the
        // UNCORRECTED longitudes: the corrections are perturbations OF those angles, so feeding a
        // corrected value back into its own argument would be solving a different equation.
        val io = 2 * (u1 - u2)
        val eu = 2 * (u2 - u3)
        return listOf(
            moonlet("Io", u1 + 0.473 * DEG * sin(io), 5.9057 - 0.0244 * cos(io), dEarth),
            moonlet("Europa", u2 + 1.065 * DEG * sin(eu), 9.3966 - 0.0882 * cos(eu), dEarth),
            moonlet("Ganymede", u3 + 0.174 * DEG * sin(g), 14.9883 - 0.0216 * cos(g), dEarth),
            moonlet("Callisto", u4 + 0.845 * DEG * sin(h), 26.3627 - 0.1939 * cos(h), dEarth),
        )
    }

    private fun moonlet(name: String, u: Double, radii: Double, dEarthDeg: Double) = Moonlet(
        name = name,
        x = radii * sin(u),
        y = -radii * cos(u) * sin(dEarthDeg * DEG),
        // ⚠️ `cos(u) < 0`, and the sign is the opposite of what it reads like. u is measured from
        // INFERIOR conjunction — the moon in front — so superior conjunction, where it is hidden, is
        // u near 180. Two independent lines of evidence, because this is easy to get backwards and
        // impossible to see: the vector geometry gives across-track = r·sin(theta)·sin(D_E) against
        // Meeus's -r·cos(u)·sin(D_E), so sin(theta) = -cos(u) and the far half is cos(u) < 0; and
        // comparing each moon's range from Earth against Jupiter's in JPL Horizons agrees at five of
        // five unambiguous cases. A first draft had `> 0.0`, which draws every moon on the wrong
        // side of the planet while putting all four in exactly the right places.
        behind = cos(u) < 0.0,
    )

    // ⚠️ Copied to match Eclipses and Occultations exactly — see the class note. Not independent.
    private const val SUN_RADIUS_KM = 696_000.0
    private const val MOON_RADIUS_KM = 1737.4
}
