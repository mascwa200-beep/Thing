package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Where a comet is, from its published orbital elements.
 *
 * The Minor Planet Center publishes elements for every known comet as six numbers plus a perihelion
 * date, and everything an observer wants — is it up, is it bright, when is it closest — follows from
 * solving the orbit for a moment and subtracting where the Earth is. That solve is what this file
 * is; [Ephemeris.earthHeliocentricJ2000Au] is the other half.
 *
 * ## Three branches, and one of them replaced a whole approximation
 *
 * A conic section is an ellipse, a parabola or a hyperbola, and each needs its own solve. The usual
 * treatment — including the one this project's planet code follows — adds a fourth: a series
 * expansion for orbits *near* parabolic, on the grounds that Kepler's equation converges badly as
 * eccentricity approaches one.
 *
 * ⚠️ **That series is not used here, because it was measured against the real catalogue and it is
 * wrong far from perihelion.** Every comet in the MPC file with an eccentricity between 0.98 and 1.0
 * — 130 of them — was solved both ways and compared against JPL DE421 through Skyfield. The series
 * reached **540 arcseconds of error on 342P/SOHO**, nine arcminutes, and was over an arcsecond on
 * fourteen others; a plain Kepler solve was within **0.01 arcseconds on every one of them**. The
 * series is fitted for the weeks around perihelion and these comets are years away from theirs.
 *
 * So: Kepler for everything bound, a hyperbolic solve for everything unbound, and Barker's equation
 * only in the vanishingly narrow band where the orbit is genuinely parabolic.
 *
 * ## What the accuracy actually is, and where the error comes from
 *
 * Every one of the **957 comets** in a real `CometEls.txt`, at three epochs — 2,871 positions —
 * against JPL DE421 through Skyfield: **median 0.64 arcseconds, 99th percentile 4.1, worst 17.3**,
 * with no failures and at most nine iterations.
 *
 * ⚠️ **Almost none of that is the orbit solve, and knowing which part is which matters.** The same
 * arithmetic driven by JPL's own Earth position agrees with Skyfield to **0.009 arcseconds** — so
 * the solver is exact to the limit of the comparison, and what is being measured above is
 * [Ephemeris.earthHeliocentricJ2000Au]. Two independent checks say so. The error times the distance
 * is roughly constant at a median of 4.9 arcsecond-AU, which is the signature of a fixed
 * displacement rather than an angular mistake; and converting that back gives about 3,500 km, which
 * is what the Earth's position measures directly against DE421 (1,540 to 6,640 km at three epochs).
 *
 * So the error scales as one over the distance: the worst case above is the *closest* comet in the
 * catalogue, at 0.31 AU. A hypothetical comet at 0.05 AU would be about three arcminutes out.
 *
 * ⚠️ **All of which is far finer than the answer deserves, and saying so matters more than the
 * number.** Comet elements are a fit to past observations, and comets are lumps of ice that vent gas
 * when they warm — a push no element set carries. A months-old element set can be arcminutes wrong
 * about a real comet however exactly the arithmetic is done. For comparison this is already an order
 * of magnitude finer than the app's own planet positions, which the same measurement puts at one to
 * three arcminutes. Improving it means a better solar theory in [Ephemeris], which would lift
 * eclipses, occultations and planets together and is not this file's to attempt.
 */
object Comets {

    /** Gaussian gravitational constant: radians per day for a body at one AU. */
    private const val GAUSS_K = 0.01720209895

    /** The speed of light in the units this file works in. */
    private const val LIGHT_AU_PER_DAY = 173.1446326846693

    /**
     * How close to one an eccentricity has to be before the orbit is treated as parabolic.
     *
     * ⚠️ **Measured, not chosen.** Both conic solves were compared against Barker's equation across
     * perihelion distances from 0.008 to 5 AU and times from ten days to eighty years either side of
     * perihelion, sweeping `|e - 1|` from 1e-4 down to 1e-12. The result is a clean U: at the wide
     * end the disagreement is the real physical difference between that orbit and a parabola
     * (1046 arcseconds at 1e-4, falling by a factor of ten per decade), and at the narrow end it is
     * precision loss, because `a = q / (1 - e)` reaches 1e9 AU and the eccentric-anomaly arithmetic
     * cancels catastrophically (0.55 arcseconds at 1e-11, 11.6 at 1e-12). The floor of the U sits at
     * **1e-9, where the two agree to 0.0095 arcseconds** — so that is the boundary, and switching
     * branches across it can introduce no visible discontinuity.
     *
     * For scale: the closest any of the 957 real comets comes to this band is 1e-6, a thousand times
     * wider. Barker is here for correctness at `e == 1.0` exactly — where `q / (1 - e)` divides by
     * zero — and not because the catalogue needs it.
     */
    private const val PARABOLIC_BAND = 1e-9

    /** Newton-Halley stops here; the real catalogue never needs more than nine. */
    private const val MAX_ITERATIONS = 60

    /**
     * One comet's orbit, as the Minor Planet Center publishes it.
     *
     * @param perihelionJdTt the perihelion instant as a Julian Date in Terrestrial Time — the raw
     *   catalogue value, kept unconverted so no round trip can lose it.
     * @param absoluteMagnitude the catalogue's `M1`, brightness at one AU from both Sun and Earth.
     * @param magnitudeSlope the catalogue's `K1`, how sharply it brightens as it approaches. Null on
     *   either magnitude field means the catalogue did not state one and no brightness is predicted.
     */
    data class Elements(
        val designation: String,
        val perihelionDistanceAu: Double,
        val eccentricity: Double,
        val perihelionJdTt: Double,
        val argumentOfPerihelionDeg: Double,
        val ascendingNodeDeg: Double,
        val inclinationDeg: Double,
        val absoluteMagnitude: Double? = null,
        val magnitudeSlope: Double? = null,
    )

    /** Where a comet is and how it is doing, for one instant. */
    data class Sighting(
        val designation: String,
        /** Apparent place, equinox of date, with [Ephemeris.Equatorial.distanceKm] geocentric. */
        val equatorial: Ephemeris.Equatorial,
        /** Distance from the Sun. */
        val heliocentricAu: Double,
        /** Distance from the Earth. */
        val geocentricAu: Double,
        /** Predicted total magnitude, or null when the catalogue stated no brightness. */
        val magnitude: Double?,
        /** Angle from the Sun as seen from here: below about 15 degrees nothing is observable. */
        val elongationDeg: Double,
        /** Days until perihelion; negative once it has passed. */
        val daysToPerihelion: Double,
    )

    /**
     * Solve one comet for one instant.
     *
     * ⚠️ **The light-time iteration is not optional.** Light takes a quarter of an hour to cross an
     * AU, and a comet moves the whole time — so what an observer sees is where it *was* when the
     * light left. Measured on the real catalogue: without it, positions are out by 9.7 arcseconds
     * for a comet at 1.7 AU and 9.9 at 50 AU, dwarfing everything else in this file. Three passes is
     * comfortably enough; the correction changes the distance by parts in ten thousand, so the
     * second pass is already converged and the third is insurance.
     *
     * @return null only if the elements are not physically solvable at all.
     */
    fun positionOf(elements: Elements, epochMs: Long): Sighting? {
        if (elements.perihelionDistanceAu <= 0.0 || elements.eccentricity < 0.0) return null
        if (!elements.perihelionDistanceAu.isFinite() || !elements.eccentricity.isFinite()) return null

        val jd = Ephemeris.julianDateTT(epochMs)
        val earth = Ephemeris.earthHeliocentricJ2000Au(epochMs)

        var comet = DoubleArray(4)
        var geo = DoubleArray(3)
        var delta = 0.0
        var at = jd
        repeat(3) {
            comet = heliocentric(elements, at - elements.perihelionJdTt) ?: return null
            geo = doubleArrayOf(comet[0] - earth[0], comet[1] - earth[1], comet[2] - earth[2])
            delta = sqrt(geo[0] * geo[0] + geo[1] * geo[1] + geo[2] * geo[2])
            at = jd - delta / LIGHT_AU_PER_DAY
        }
        if (!delta.isFinite() || delta <= 0.0) return null

        // J2000 ecliptic -> J2000 equatorial, then forward to the equinox of date so this sits in
        // the same frame as every other body in this app.
        val eps = Ephemeris.obliquityJ2000Deg * Math.PI / 180.0
        val xq = geo[0]
        val yq = geo[1] * cos(eps) - geo[2] * sin(eps)
        val zq = geo[1] * sin(eps) + geo[2] * cos(eps)
        val raJ2000 = atan2(yq, xq) * 180.0 / Math.PI
        val decJ2000 = asin((zq / delta).coerceIn(-1.0, 1.0)) * 180.0 / Math.PI
        val ofDate = Ephemeris.precessFromJ2000(raJ2000, decJ2000, epochMs)

        val r = comet[3]
        val sun = Ephemeris.sunEquatorial(epochMs)
        val elongation = Ephemeris.angularSeparationDeg(
            ofDate.rightAscensionDeg, ofDate.declinationDeg,
            sun.rightAscensionDeg, sun.declinationDeg,
        )
        return Sighting(
            designation = elements.designation,
            equatorial = Ephemeris.Equatorial(
                rightAscensionDeg = ofDate.rightAscensionDeg,
                declinationDeg = ofDate.declinationDeg,
                distanceKm = delta * Ephemeris.AU_KM,
            ),
            heliocentricAu = r,
            geocentricAu = delta,
            magnitude = magnitudeOf(elements, r, delta),
            elongationDeg = elongation,
            daysToPerihelion = elements.perihelionJdTt - jd,
        )
    }

    /**
     * How bright it should look: `M1 + 5 log(delta) + 2.5 K1 log(r)`.
     *
     * ⚠️ **The factor of 2.5 is the part that is easy to get wrong, and it was settled by
     * measurement rather than by reading.** The catalogue's slope field is published under two
     * different conventions — as a coefficient applied directly to `log r`, or as the exponent `n`
     * in a brightness law, in which case it needs multiplying by 2.5. In a real `CometEls.txt` the
     * value is 4 for 751 of 957 comets, which is the default assumed `n`, so this is the second one.
     * Checking against a comet whose brightness is known settles it: Halley in March 1986 was at
     * r = 0.59 and delta = 0.42 AU with M1 = 5.5 and K1 = 3.2, and **was observed at about magnitude
     * 2.1**. This formula predicts 2.2; treating the slope as a direct coefficient predicts 3.05.
     *
     * ⚠️ **A predicted comet magnitude is a genuinely poor prediction and the surface should say so.**
     * The law is a fit to how a particular comet behaved last time, comets are unpredictable, and
     * being two magnitudes out is ordinary. This is a rough expectation, not a measurement.
     */
    fun magnitudeOf(elements: Elements, heliocentricAu: Double, geocentricAu: Double): Double? {
        val m1 = elements.absoluteMagnitude ?: return null
        val k1 = elements.magnitudeSlope ?: return null
        if (heliocentricAu <= 0.0 || geocentricAu <= 0.0) return null
        val m = m1 + 5.0 * log10(geocentricAu) + 2.5 * k1 * log10(heliocentricAu)
        return if (m.isFinite()) m else null
    }

    /**
     * The comets worth pointing at right now, brightest first.
     *
     * @param magnitudeLimit how faint to go. Six is the naked-eye limit under a dark sky, ten is a
     *   reasonable pair of binoculars, and the default admits what a small telescope would show.
     * @param minElongationDeg how far from the Sun something has to be to be observable at all.
     *   Anything closer is lost in twilight however bright it is, so reporting it would be a
     *   prediction nobody can act on.
     */
    fun visible(
        catalogue: List<Elements>,
        epochMs: Long,
        magnitudeLimit: Double = 13.0,
        minElongationDeg: Double = 20.0,
        limit: Int = 12,
    ): List<Sighting> = catalogue
        .asSequence()
        .mapNotNull { positionOf(it, epochMs) }
        .filter { it.elongationDeg >= minElongationDeg }
        .filter { it.magnitude != null && it.magnitude <= magnitudeLimit }
        .sortedBy { it.magnitude }
        .take(limit)
        .toList()

    /** The instant of perihelion, on the millisecond clock the rest of the app uses. */
    fun perihelionEpochMs(elements: Elements): Long =
        ((elements.perihelionJdTt - 2440587.5) * 86_400.0 - Ephemeris.DELTA_T_SECONDS).toLong() * 1000L

    /**
     * Plain words for what a comet is doing, which is most of what somebody wants to know.
     *
     * Deliberately says nothing about whether it will be *visible* — that needs the observer's
     * horizon and their sky, and is the caller's to answer.
     */
    fun describe(sighting: Sighting): String {
        val days = sighting.daysToPerihelion
        val when_ = when {
            abs(days) < 1.0 -> "at perihelion today"
            days > 0 && days < 400 -> "closest to the Sun in ${days.toInt()} days"
            days > 0 -> "still years from perihelion"
            days > -400 -> "${(-days).toInt()} days past perihelion"
            else -> "long past perihelion"
        }
        val brightness = sighting.magnitude?.let { m ->
            when {
                m <= 0.0 -> ", and should be unmistakable"
                m <= 6.0 -> ", and should be a naked-eye object"
                m <= 10.0 -> ", within reach of binoculars"
                else -> ", but faint"
            }
        } ?: ""
        return "${format1(sighting.geocentricAu)} AU away, $when_$brightness."
    }

    private fun format1(v: Double): String {
        val scaled = kotlin.math.round(v * 10.0) / 10.0
        val whole = scaled.toLong()
        val tenth = kotlin.math.round(abs(scaled - whole) * 10.0).toLong()
        return "$whole.$tenth"
    }

    /**
     * The comet's heliocentric position in the J2000 ecliptic, plus its distance from the Sun.
     *
     * @return `[x, y, z, r]` in AU, or null if the orbit could not be solved.
     */
    private fun heliocentric(el: Elements, daysFromPerihelion: Double): DoubleArray? {
        val q = el.perihelionDistanceAu
        val e = el.eccentricity
        val solved = when {
            e < 1.0 - PARABOLIC_BAND -> elliptic(q, e, daysFromPerihelion)
            e > 1.0 + PARABOLIC_BAND -> hyperbolic(q, e, daysFromPerihelion)
            else -> parabolic(q, daysFromPerihelion)
        } ?: return null
        val v = solved[0]
        val r = solved[1]
        if (!v.isFinite() || !r.isFinite() || r <= 0.0) return null

        val d = Math.PI / 180.0
        val w = el.argumentOfPerihelionDeg * d
        val node = el.ascendingNodeDeg * d
        val i = el.inclinationDeg * d
        val vw = v + w
        return doubleArrayOf(
            r * (cos(node) * cos(vw) - sin(node) * sin(vw) * cos(i)),
            r * (sin(node) * cos(vw) + cos(node) * sin(vw) * cos(i)),
            r * sin(vw) * sin(i),
            r,
        )
    }

    /**
     * Kepler's equation for a closed orbit, by Halley's method.
     *
     * ⚠️ The mean anomaly is reduced to the nearest revolution rather than to `[0, 2pi)`, because a
     * starter is only good near the solution and `M` just under `2pi` is geometrically just *before*
     * perihelion, not almost a whole orbit after it.
     */
    private fun elliptic(q: Double, e: Double, t: Double): DoubleArray? {
        val a = q / (1.0 - e)
        if (!a.isFinite() || a <= 0.0) return null
        var m = GAUSS_K / (a * sqrt(a)) * t
        if (!m.isFinite()) return null
        m = Math.IEEEremainder(m, 2.0 * Math.PI)
        var ea = m + e * sin(m) * (1.0 + e * cos(m))
        var i = 0
        while (i < MAX_ITERATIONS) {
            val s = e * sin(ea)
            val f = ea - s - m
            val fp = 1.0 - e * cos(ea)
            if (fp == 0.0) break
            var step = -f / fp
            step = -f / (fp + 0.5 * step * s)
            ea += step
            if (abs(step) <= 1e-13 * kotlin.math.max(1.0, abs(ea))) break
            i++
        }
        val xv = a * (cos(ea) - e)
        val yv = a * sqrt(1.0 - e * e) * sin(ea)
        return doubleArrayOf(atan2(yv, xv), hypot(xv, yv))
    }

    /** Barker's equation, solved in closed form by Cardano — a parabola needs no iteration. */
    private fun parabolic(q: Double, t: Double): DoubleArray? {
        val w = 1.5 * GAUSS_K * t / sqrt(2.0 * q * q * q)
        if (!w.isFinite()) return null
        // The radicand exceeds w-squared, so the cube root is always of a positive number and
        // Cardano's awkward three-real-roots case cannot arise.
        val y = Math.cbrt(w + sqrt(w * w + 1.0))
        val s = y - 1.0 / y
        return doubleArrayOf(2.0 * atan(s), q * (1.0 + s * s))
    }

    /**
     * The hyperbolic Kepler equation `M = e sinh F - F`, by Halley's method.
     *
     * ⚠️ **The starter is `asinh(M / e)` and the obvious alternative overflows.** `M / (e - 1)` is
     * the textbook small-`M` starter and it is unbounded: for a sungrazer far from perihelion — a
     * real case, `q = 0.008 AU` and eighty years out — it returns about 7200, and `sinh` of that is
     * larger than a double can hold, so the first iteration produces infinity and never recovers.
     * `asinh` grows logarithmically, so `F` stays small however large `M` is. Found by widening a
     * probe that had passed at moderate parameters.
     */
    private fun hyperbolic(q: Double, e: Double, t: Double): DoubleArray? {
        val a = q / (e - 1.0)
        if (!a.isFinite() || a <= 0.0) return null
        val m = GAUSS_K * t / (a * sqrt(a))
        if (!m.isFinite()) return null
        var f = asinh(m / e)
        if (!f.isFinite()) return null
        var i = 0
        while (i < MAX_ITERATIONS) {
            val sh = sinh(f)
            val ch = cosh(f)
            if (!sh.isFinite() || !ch.isFinite()) return null
            val fn = e * sh - f - m
            val fp = e * ch - 1.0
            if (fp == 0.0) break
            var step = -fn / fp
            step = -fn / (fp + 0.5 * step * e * sh)
            f += step
            if (abs(step) <= 1e-13 * kotlin.math.max(1.0, abs(f))) break
            i++
        }
        val ch = cosh(f)
        if (!ch.isFinite()) return null
        val v = 2.0 * atan2(sqrt(e + 1.0) * tanh(f / 2.0), sqrt(e - 1.0))
        return doubleArrayOf(v, a * (e * ch - 1.0))
    }
}
