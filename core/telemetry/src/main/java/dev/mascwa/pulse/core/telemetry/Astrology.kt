package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * The zodiac, computed properly and labelled for what it is.
 *
 * ## What is measurement here and what is tradition
 *
 * ⚠️ **The positions are real; the meanings are not.** Every number this file produces comes from
 * the shipped [Ephemeris], which agrees with JPL DE421 to a few arcseconds — so where the Sun, the
 * Moon and the planets are, which sign they fall in, what angles they make and what degree of the
 * ecliptic is rising in the east are all straightforwardly true and checkable. What tradition
 * assigns to those positions is a system of correspondences some three thousand years old that has
 * been tested repeatedly and does not predict anything. Both halves are worth having; conflating
 * them is not, so this file computes the first and never asserts the second.
 *
 * The surface that renders this is expected to say so once, plainly and without sneering. Doing the
 * tradition properly and being honest about its status are not in tension.
 *
 * ## The tropical zodiac is not the constellations, and that is the interesting part
 *
 * ⚠️ Western astrology's signs are anchored to the **March equinox**, not to the stars. The equinox
 * drifts westward against the constellations by about 50 arcseconds a year, so in the two millennia
 * since the signs were named the two systems have come apart by roughly 24 degrees — very nearly a
 * whole sign. Somebody told their Sun is in Aries almost certainly had it standing in front of
 * Pisces.
 *
 * That is not a gotcha, it is a fact about two different conventions, and it is checkable. So
 * [siderealSignOf] gives the constellation-anchored answer beside the tropical one, using the Lahiri
 * ayanamsa that Indian astrology uses — and the app can show both and name the difference rather
 * than picking a side.
 *
 * ## What is deliberately absent
 *
 * ⚠️ **Placidus houses.** They are the commonest system in Western practice and they are undefined
 * above about 66 degrees of latitude, where some degrees of the ecliptic never rise at all — the
 * standard implementations either fail or return something meaningless there. [equalHouses] and
 * [wholeSignHouses] are exact everywhere, so they are what this ships. A house system that quietly
 * breaks for anyone inside the Arctic Circle is worse than one fewer choice.
 */
object Astrology {

    private const val DEG = Math.PI / 180.0

    private fun norm360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    // ---- the signs -------------------------------------------------------------------------------

    /** One of the twelve thirty-degree divisions, in order from the March equinox. */
    enum class Sign(
        val label: String,
        val symbol: String,
        val element: Element,
        val mode: Mode,
    ) {
        ARIES("Aries", "♈", Element.FIRE, Mode.CARDINAL),
        TAURUS("Taurus", "♉", Element.EARTH, Mode.FIXED),
        GEMINI("Gemini", "♊", Element.AIR, Mode.MUTABLE),
        CANCER("Cancer", "♋", Element.WATER, Mode.CARDINAL),
        LEO("Leo", "♌", Element.FIRE, Mode.FIXED),
        VIRGO("Virgo", "♍", Element.EARTH, Mode.MUTABLE),
        LIBRA("Libra", "♎", Element.AIR, Mode.CARDINAL),
        SCORPIO("Scorpio", "♏", Element.WATER, Mode.FIXED),
        SAGITTARIUS("Sagittarius", "♐", Element.FIRE, Mode.MUTABLE),
        CAPRICORN("Capricorn", "♑", Element.EARTH, Mode.CARDINAL),
        AQUARIUS("Aquarius", "♒", Element.AIR, Mode.FIXED),
        PISCES("Pisces", "♓", Element.WATER, Mode.MUTABLE),
        ;

        /** Where this sign starts along the ecliptic, degrees from the March equinox. */
        val startDeg: Double get() = ordinal * 30.0
    }

    enum class Element(val label: String) { FIRE("Fire"), EARTH("Earth"), AIR("Air"), WATER("Water") }

    enum class Mode(val label: String) {
        CARDINAL("Cardinal"), FIXED("Fixed"), MUTABLE("Mutable"),
    }

    /** The tropical sign an ecliptic longitude falls in. */
    fun signOf(eclipticLongitudeDeg: Double): Sign =
        Sign.entries[(floor(norm360(eclipticLongitudeDeg) / 30.0).toInt()) % 12]

    /** How far into that sign, 0 up to 30. */
    fun degreeInSign(eclipticLongitudeDeg: Double): Double = norm360(eclipticLongitudeDeg) % 30.0

    /**
     * "14°32′ Taurus" — the traditional way of writing a position, degrees and arcminutes within
     * the sign rather than a longitude out of 360.
     *
     * ⚠️ Locale-independent by construction: the two numbers are integers and the separators are
     * literal, so there is no decimal point to be rendered as a comma. Every other numeric string
     * in this project that reached a user has had to learn that.
     */
    fun format(eclipticLongitudeDeg: Double): String {
        val within = degreeInSign(eclipticLongitudeDeg)
        val d = floor(within).toInt()
        // ⚠️ Rounded to the arcminute AFTER splitting, and carried rather than clamped: 29.9999
        // degrees must read 30°00′ of one sign becoming 0°00′ of the next, not "29°60′".
        var m = Math.round((within - d) * 60.0).toInt()
        var deg = d
        if (m == 60) { m = 0; deg += 1 }
        return if (deg == 30) {
            val next = Sign.entries[(signOf(eclipticLongitudeDeg).ordinal + 1) % 12]
            "0°00′ ${next.label}"
        } else {
            "$deg°${m.toString().padStart(2, '0')}′ ${signOf(eclipticLongitudeDeg).label}"
        }
    }

    // ---- tropical against sidereal ---------------------------------------------------------------

    /**
     * The Lahiri ayanamsa in degrees — how far the constellations have slipped behind the signs.
     *
     * The value at J2000.0 is 23.8531 degrees (23°51′11″) and it grows with the general precession
     * of about 50.29 arcseconds a year.
     *
     * ⚠️ **A straight line, and that is honest at the precision this is used for.** Precession is
     * not quite linear, so this drifts from the rigorous value by a few arcseconds a decade — which
     * against a sign thirty degrees wide is nothing at all, and the difference this quantity exists
     * to describe is twenty-four degrees. A polynomial here would be false precision on top of a
     * convention that different schools disagree about by more than the error.
     */
    fun ayanamsaDeg(epochMs: Long): Double {
        val yearsFromJ2000 = (Ephemeris.julianDate(epochMs) - 2451545.0) / 365.25
        return LAHIRI_J2000_DEG + PRECESSION_DEG_PER_YEAR * yearsFromJ2000
    }

    /** The constellation-anchored sign, which is usually one behind the tropical one. */
    fun siderealSignOf(eclipticLongitudeDeg: Double, epochMs: Long): Sign =
        signOf(eclipticLongitudeDeg - ayanamsaDeg(epochMs))

    // ---- aspects ---------------------------------------------------------------------------------

    /**
     * The five Ptolemaic aspects, with the orbs in common use.
     *
     * An orb is how far from exact the angle may be and still count. They are wider for the
     * conjunction and opposition because tradition treats those as the strongest; that is a
     * convention rather than a measurement, like everything else in this half of the file.
     */
    enum class AspectKind(val label: String, val symbol: String, val exactDeg: Double, val orbDeg: Double) {
        CONJUNCTION("Conjunction", "☌", 0.0, 8.0),
        SEXTILE("Sextile", "⚹", 60.0, 4.0),
        SQUARE("Square", "□", 90.0, 6.0),
        TRINE("Trine", "△", 120.0, 6.0),
        OPPOSITION("Opposition", "☍", 180.0, 8.0),
    }

    /** Two bodies at a traditional angle, and how far from exact they are. */
    data class Aspect(
        val a: String,
        val b: String,
        val kind: AspectKind,
        /** Degrees away from the exact angle. Smaller is "tighter". */
        val orbDeg: Double,
    ) {
        /** Within a degree of exact — tradition's own word for it. */
        val exact: Boolean get() = orbDeg < 1.0
    }

    /**
     * Every aspect among the given bodies, tightest first.
     *
     * ⚠️ The separation is the SHORTER way round the circle, so 350 degrees apart is ten and not
     * three hundred and fifty. Getting that wrong turns every conjunction into an opposition, which
     * is the single easiest mistake to make here and looks entirely plausible on screen.
     *
     * Each pair yields at most one aspect: the orbs do not overlap, so the first match is the only
     * one, but taking the tightest makes that independent of the enum's declaration order.
     */
    fun aspects(longitudesByBody: Map<String, Double>): List<Aspect> {
        val names = longitudesByBody.keys.toList()
        val out = ArrayList<Aspect>()
        for (i in names.indices) {
            for (j in i + 1 until names.size) {
                val a = names[i]
                val b = names[j]
                val separation = separationDeg(longitudesByBody.getValue(a), longitudesByBody.getValue(b))
                AspectKind.entries
                    .map { it to abs(separation - it.exactDeg) }
                    .filter { (kind, orb) -> orb <= kind.orbDeg }
                    .minByOrNull { it.second }
                    ?.let { (kind, orb) -> out += Aspect(a, b, kind, orb) }
            }
        }
        return out.sortedBy { it.orbDeg }
    }

    /** The shorter angle between two ecliptic longitudes, 0 to 180. */
    fun separationDeg(aDeg: Double, bDeg: Double): Double {
        val d = norm360(aDeg - bDeg)
        return if (d > 180.0) 360.0 - d else d
    }

    // ---- the angles ------------------------------------------------------------------------------

    /**
     * The degree of the ecliptic rising on the eastern horizon — the ascendant.
     *
     * This is real astronomy and nothing else in this file depends on tradition: it is where the
     * ecliptic crosses the horizon in the east, which follows from the observer's latitude, the
     * obliquity, and how far the Earth has turned.
     *
     * ⚠️ **The tangent has two roots, 180 degrees apart, and one of them is the DESCENDANT.**
     * Choosing the wrong one puts every chart exactly half a circle out, which is not obviously
     * wrong on screen — every sign is still a plausible sign. The root is pinned by the geometry
     * rather than by a sign test: the ascendant always leads the midheaven around the ecliptic, so
     * the answer is the root lying in the semicircle after [midheavenDeg].
     *
     * ⚠️ Undefined exactly at the poles, where `tan` of the latitude runs away; returns null there
     * rather than a large meaningless number.
     */
    fun ascendantDeg(epochMs: Long, latDeg: Double, lonDeg: Double): Double? {
        if (abs(latDeg) > POLE_LIMIT_DEG) return null
        val theta = localSiderealDeg(epochMs, lonDeg) * DEG
        val eps = Ephemeris.trueObliquityDeg(epochMs) * DEG
        val phi = latDeg * DEG
        val y = cos(theta)
        val x = -(sin(theta) * cos(eps) + tan(phi) * sin(eps))
        val raw = norm360(atan2(y, x) / DEG)
        val mc = midheavenDeg(epochMs, lonDeg)
        return if (norm360(raw - mc) > 180.0) norm360(raw + 180.0) else raw
    }

    /**
     * The degree of the ecliptic crossing the meridian — the midheaven.
     *
     * ⚠️ **On the meridian, which is NOT the same as the highest point of the ecliptic**, and the
     * first version of this said "due south and highest". The ecliptic's highest point is the
     * nonagesimal, ninety degrees from the ascendant, and the two coincide only when the ecliptic
     * happens to meet the meridian at a right angle. The test that caught it now asserts the thing
     * that is exactly true instead: this point's right ascension IS the local sidereal time.
     *
     * ⚠️ It is due south from most inhabited places and due NORTH wherever the observer's latitude
     * is below the point's declination — inside the tropics, routinely. So an azimuth assertion
     * would be right in London and wrong in Quito.
     *
     * Well defined at every latitude including the poles, unlike the ascendant, because it does not
     * involve the observer's latitude at all.
     */
    fun midheavenDeg(epochMs: Long, lonDeg: Double): Double {
        val theta = localSiderealDeg(epochMs, lonDeg) * DEG
        val eps = Ephemeris.trueObliquityDeg(epochMs) * DEG
        return norm360(atan2(sin(theta), cos(theta) * cos(eps)) / DEG)
    }

    /** Greenwich sidereal time carried round to the observer's own meridian, in degrees. */
    fun localSiderealDeg(epochMs: Long, lonDeg: Double): Double =
        norm360(Ephemeris.gmstDeg(Ephemeris.julianDate(epochMs)) + lonDeg)

    // ---- houses ----------------------------------------------------------------------------------

    /** One of the twelve divisions of the sky, by whichever rule was used to cut them. */
    data class House(val number: Int, val cuspDeg: Double) {
        val sign: Sign get() = signOf(cuspDeg)
    }

    /**
     * Twelve equal thirty-degree houses starting at the ascendant.
     *
     * Exact at every latitude, which is why it is here — see the note on Placidus at the top.
     */
    fun equalHouses(ascendantDeg: Double): List<House> =
        (0 until 12).map { House(it + 1, norm360(ascendantDeg + it * 30.0)) }

    /**
     * Twelve houses each exactly one sign, the first being the sign the ascendant falls in.
     *
     * The oldest system, and the one that needs no arithmetic beyond finding the rising sign — so
     * it is also the one that cannot go subtly wrong.
     */
    fun wholeSignHouses(ascendantDeg: Double): List<House> {
        val first = signOf(ascendantDeg).startDeg
        return (0 until 12).map { House(it + 1, norm360(first + it * 30.0)) }
    }

    /** Which house a longitude falls in, given cusps in order. */
    fun houseOf(eclipticLongitudeDeg: Double, houses: List<House>): Int? {
        if (houses.size != 12) return null
        val lon = norm360(eclipticLongitudeDeg)
        for (i in 0 until 12) {
            val from = houses[i].cuspDeg
            val to = houses[(i + 1) % 12].cuspDeg
            val span = norm360(to - from)
            if (norm360(lon - from) < span) return houses[i].number
        }
        return null
    }

    // ---- retrograde ------------------------------------------------------------------------------

    /**
     * Whether a body's longitude is decreasing — the apparent backwards motion.
     *
     * ⚠️ Real, and the one piece of astrological vocabulary that describes something a telescope can
     * see: an outer planet really does appear to reverse for a few weeks each year as the Earth
     * overtakes it on the inside. It means nothing beyond that, but it happens.
     *
     * Takes two sampled longitudes rather than reaching for a clock, so the caller decides the
     * baseline and the core stays testable. The comparison is around the circle, so a body crossing
     * from 359 to 1 degree reads as moving forwards rather than as a huge leap backwards.
     */
    fun isRetrograde(longitudeEarlierDeg: Double, longitudeLaterDeg: Double): Boolean {
        val advance = norm360(longitudeLaterDeg - longitudeEarlierDeg)
        return advance > 180.0
    }

    /** A sensible baseline for [isRetrograde]: a day is long enough to see any planet's drift. */
    const val RETROGRADE_BASELINE_MS = 86_400_000L

    private const val LAHIRI_J2000_DEG = 23.8531
    private const val PRECESSION_DEG_PER_YEAR = 50.29 / 3600.0

    /**
     * Beyond this latitude the ascendant's `tan(latitude)` term dominates everything else and the
     * answer stops meaning anything. Just inside the poles rather than at the Arctic Circle: the
     * formula itself is fine at 80 degrees, it is Placidus that is not.
     */
    private const val POLE_LIMIT_DEG = 89.5
}
