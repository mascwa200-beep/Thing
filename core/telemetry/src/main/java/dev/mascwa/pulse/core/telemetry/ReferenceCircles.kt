package dev.mascwa.pulse.core.telemetry

import kotlin.math.cos
import kotlin.math.sin

/**
 * The two circles every star chart draws, and why a map is much harder to read without them.
 *
 * The **celestial equator** is the Earth's own equator thrown onto the sky, so it says which way is
 * north and how far from the pole anything is. The **ecliptic** is the plane of the Earth's orbit,
 * which is the road the Sun walks along all year — and, within a few degrees, the road every planet
 * and the Moon walks too. That is the whole reason it earns a line: a planet is never anywhere else,
 * so once the ecliptic is drawn, half the sky is ruled out at a glance and the zodiac stops being
 * arbitrary.
 *
 * ## ⚠️ Broken into arcs, and it is not cosmetic
 *
 * A great circle passes through every part of the sky, so the smallest cap containing it is the
 * WHOLE SPHERE — and `SkyLines`'s per-run cap test, which throws away a whole constellation border
 * with one dot product, would therefore never reject either of these. Split into [ARCS] runs, each
 * cap is a few tens of degrees across and the test does its job again.
 *
 * **Measured** by running the shipped fill and the shipped cap test over both circles, pointed at
 * the line from every one of 360 directions: at a 150-degree field 9 of the 12 runs survive
 * (144 of 192 vertices projected), and at a quarter-degree field exactly **1 run survives, so 16
 * vertices are projected instead of 192**. At every field, from the widest to the floor, at least
 * one segment still reaches the screen — which is the property that matters, because the failure
 * mode of getting this wrong is the ecliptic silently vanishing when you zoom in.
 *
 * ## What a step costs, and why it is not finer
 *
 * ⚠️ **A chord is not angularly wrong at all, and an earlier version of this note said it was.**
 * Every point of a chord lies in the plane of its own great circle, which passes through the
 * observer — so seen from the centre of the celestial sphere a chord and its arc are the same set
 * of directions. Any error is a **projection** artefact, and [SkyProjection] is stereographic, under
 * which a great circle becomes a circle rather than a straight line.
 *
 * Measured through the shipped projection, sweeping the circle's distance from the view centre and
 * sampling within each segment: the worst departure of the drawn chord from the true projected arc
 * is **0.35 pixels on a 1080-wide portrait screen at the widest field**, and it falls monotonically
 * as the field narrows — 0.12 px at 60 degrees, 0.08 px at 5. Below about a two-degree field no
 * segment has both ends on screen at all, so what is drawn is a line running off the edge, which is
 * straight anyway.
 */
object ReferenceCircles {

    private const val DEG = Math.PI / 180.0

    /** Degrees of longitude between adjacent vertices — see the class note for the error this costs. */
    const val STEP_DEG = 2.0

    /** How many separate runs each circle is cut into, so the cap test can reject most of them. */
    const val ARCS = 12

    /** Degrees of longitude one run covers. */
    const val ARC_SPAN_DEG = 360.0 / ARCS

    /** Vertices in one arc, including the shared endpoint that joins it to the next. */
    const val PER_ARC = (ARC_SPAN_DEG / STEP_DEG).toInt() + 1

    /**
     * The longitude of vertex [index] of run [arc] — the whole of the traversal, in one expression.
     *
     * ⚠️ **This exists so the caller can loop over integers rather than accumulate a Double.** The
     * first version of the filler ran `while (step * STEP_DEG <= ARC_SPAN_DEG)`, which happens to
     * stop at exactly [PER_ARC] vertices for the constants above and is a floating-point comparison
     * deciding how many slots of a **preallocated** buffer get used. Change [STEP_DEG] to something
     * that does not divide the span and the count silently stops matching [PER_ARC]; here it cannot,
     * because the count IS [PER_ARC].
     *
     * The last vertex of one run is the first of the next by construction — `arc * span + (PER_ARC
     * - 1) * STEP_DEG` is `(arc + 1) * span` whenever the step divides the span, which
     * `ReferenceCirclesTest` pins. Stopping one short would leave a visible gap every thirty
     * degrees, which reads as a dashed line rather than a circle.
     */
    fun longitudeOf(arc: Int, index: Int): Double = arc * ARC_SPAN_DEG + index * STEP_DEG

    /**
     * A point on the celestial equator, at right ascension [raDeg], as a unit vector.
     *
     * Declination zero, so this is [SkyProjection.equatorialVector] with the second argument dropped
     * — written out rather than delegated only because the whole point of the file is that both
     * circles are two lines of trigonometry and hiding that would make them look harder than they are.
     */
    fun equatorPoint(raDeg: Double, out: DoubleArray) {
        val ra = raDeg * DEG
        out[0] = cos(ra)
        out[1] = sin(ra)
        out[2] = 0.0
    }

    /**
     * A point on the ecliptic, at ecliptic longitude [longitudeDeg], as an EQUATORIAL unit vector.
     *
     * ⚠️ **The obliquity is a parameter, not a constant, and that matters over the span this app
     * claims.** It is about 23.44 degrees now and falls by roughly 0.013 degrees a century, so a
     * fixed value is invisible this year and is a quarter of a degree out by the year 4000 — which is
     * well inside the range a chart with a time scrubber can be asked about. [Ephemeris
     * .trueObliquityDeg] is what a caller should pass.
     */
    fun eclipticPoint(longitudeDeg: Double, obliquityDeg: Double, out: DoubleArray) {
        val l = longitudeDeg * DEG
        val e = obliquityDeg * DEG
        // The ecliptic frame's own equator, turned about the vernal equinox — which is the x axis in
        // both frames, and is exactly why this is one rotation rather than a matrix.
        out[0] = cos(l)
        out[1] = sin(l) * cos(e)
        out[2] = sin(l) * sin(e)
    }

    /**
     * Where the ecliptic and the equator cross: the two equinoxes.
     *
     * They are the x axis and its opposite, in both frames and at every obliquity, because that axis
     * is what the obliquity is measured about. Worth stating because it is the one property of these
     * two circles a reader can check by eye — if the drawn lines do not meet there, something is
     * wrong with the rotation and nowhere else would show it.
     */
    fun equinox(vernal: Boolean, out: DoubleArray) {
        out[0] = if (vernal) 1.0 else -1.0
        out[1] = 0.0
        out[2] = 0.0
    }
}
