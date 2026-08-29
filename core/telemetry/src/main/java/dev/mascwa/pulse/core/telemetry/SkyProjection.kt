package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Turning the sky into a map you can pan and zoom.
 *
 * The observatory's existing chart is a **whole-sky polar plot** — horizon at the rim, zenith at the
 * centre — which is the right shape for "what is up right now" and the wrong one for a map. It
 * cannot zoom, so everything below about magnitude 3 would be a smear; it cannot pan, because it
 * already shows everything; and it distorts savagely near the rim, which is exactly where a person
 * standing outside is looking. This is the other kind of chart: a window onto part of the sky,
 * pointed somewhere, with a field of view.
 *
 * ## Why stereographic
 *
 * ⚠️ **A gnomonic (tangent-plane) projection is the obvious choice and it is wrong here.** It maps
 * great circles to straight lines, which is lovely for a telescope's narrow field, and it sends
 * anything 90° from the centre to infinity — so a 120° field, which is roughly what somebody sweeping
 * a phone across the sky wants, tears apart at the edges. Stereographic is **conformal**: it keeps
 * shapes locally undistorted at every scale, so a constellation looks like itself whether it is in
 * the middle of the view or at the corner, and it stays finite right out to the point opposite the
 * one you are looking at. Scale grows toward the edge, which is the honest trade and the one every
 * planetarium makes.
 *
 * ## Conventions, stated once because getting one backwards is invisible
 *
 * - **Azimuth is degrees clockwise from true north**, matching [Ephemeris.Horizontal] and everything
 *   else in this app.
 * - The frame is **east-north-up**: x east, y north, z up.
 * - Screen coordinates are **+x right, +y DOWN**, because that is what a canvas wants. Turning to
 *   your right moves the sky left, so a star at a larger azimuth than the view centre sits to the
 *   right — which is what "looking at it" means, and the opposite of a paper star chart held over
 *   your head.
 * - A radius of 1.0 is the edge of the **declared** field of view, and the caller multiplies by half
 *   the smaller screen dimension. That guarantees the declared field is always fully on screen at
 *   any orientation — but see the warning below, because it is emphatically NOT a clip.
 *
 * ## ⚠️ The declared field is a circle; the screen is not
 *
 * ⚠️ **[Screen.inField] is a hit-testing predicate and using it to decide what to draw produces a
 * literal circle in a black rectangle.** That is exactly what this map did until it was reported:
 * the drawn sky was a disc inscribed in the narrow screen dimension with dead bands above and below.
 * A star at radius 1.4 directly above the view centre on a portrait phone is perfectly valid, on
 * screen, and was being thrown away.
 *
 * Ask [Screen.onScreen] with the real viewport instead — [viewportOf] turns a pixel size into the
 * half-extents it wants. `inField` keeps its meaning for "is this inside the field the user asked
 * for", which is what a tap tolerance is measured against; conflating the two is how the circle
 * happened, so they are deliberately two functions with two names.
 */
object SkyProjection {

    private const val DEG = Math.PI / 180.0

    /**
     * Where the viewer is pointed and how much sky is in the window.
     *
     * @param altitudeDeg clamped away from the poles by [MAX_ALTITUDE_DEG]; see [pan].
     * @param fovDeg the FULL field across the smaller screen dimension, not the half-angle.
     * @param rollDeg how far the sky is turned in the plane of the screen, clockwise positive.
     *   Zero for a chart you drag with a finger; in pointing mode it follows the phone, so tipping
     *   the handset sideways tips the sky with it. **Defaulted**, so every existing construction
     *   compiles and behaves exactly as before.
     */
    data class View(
        val azimuthDeg: Double,
        val altitudeDeg: Double,
        val fovDeg: Double,
        val rollDeg: Double = 0.0,
    )

    /**
     * The half-extents of the drawing surface, in the same units [Screen.x] and [Screen.y] use.
     *
     * ⚠️ **One of these is always exactly 1.0** — whichever axis is the smaller screen dimension,
     * because that is the axis the field of view is normalised against. The other is the aspect
     * ratio, and it is the half that the old circle-shaped clip was throwing away.
     */
    data class Viewport(val halfWidth: Double, val halfHeight: Double)

    /**
     * The viewport for a drawing surface of this pixel size.
     *
     * Takes pixels rather than an aspect ratio because the caller has the size to hand and the ratio
     * alone cannot say which axis is the smaller one. A degenerate size (a surface not yet measured,
     * which composition genuinely hands you on the first frame) answers a unit square rather than
     * dividing by zero.
     */
    fun viewportOf(widthPx: Double, heightPx: Double): Viewport {
        val minor = minOf(widthPx, heightPx)
        if (!(minor > 0.0) || !widthPx.isFinite() || !heightPx.isFinite()) return Viewport(1.0, 1.0)
        return Viewport(widthPx / minor, heightPx / minor)
    }

    /**
     * Where something landed.
     *
     * ⚠️ [visible] is about the projection, not about the screen: it is false only for a point so
     * far round the sky that it cannot be drawn at all. A point can be perfectly valid and still sit
     * outside the visible circle — the caller decides whether to clip on radius, because a label
     * just past the edge is often worth drawing and a star is not.
     */
    data class Screen(val x: Double, val y: Double, val visible: Boolean) {
        val radius: Double get() = sqrt(x * x + y * y)

        /**
         * Inside the field of view as declared, which is what [radius] is normalised against.
         *
         * ⚠️ **This is a question about the FIELD, not about the screen, and drawing decisions must
         * not be made with it** — see the warning on [SkyProjection]. Use it where "within the field
         * the user asked for" is the actual question: a tap tolerance, or a whole-sky summary.
         */
        val inField: Boolean get() = visible && radius <= 1.0

        /**
         * Inside the drawing surface, which is the question a renderer is asking.
         *
         * @param marginUnits how far past the edge still counts. A star drawn as a disc, or a label
         *   hanging off its right-hand side, is partly on screen while its centre is not; clipping
         *   hard at the edge makes things pop in and out as you drag.
         */
        fun onScreen(viewport: Viewport, marginUnits: Double = 0.0): Boolean =
            visible &&
                abs(x) <= viewport.halfWidth + marginUnits &&
                abs(y) <= viewport.halfHeight + marginUnits
    }

    /** Nothing may be drawn closer than this to the point opposite the view — it projects to infinity. */
    private const val MIN_FORWARD = -0.999

    /**
     * ⚠️ **The look direction is clamped away from the zenith and nadir, and that is not cosmetic.**
     * The screen's "up" is derived from the look direction crossed with the world's vertical, and at
     * exactly the zenith that cross product is the zero vector — the view has no defined orientation
     * and the map would spin or vanish. Every planetarium has this seam; clamping half a degree short
     * of it means a person can look as near straight up as makes any visual difference and never
     * reach the singularity.
     */
    const val MAX_ALTITUDE_DEG = 89.5

    /**
     * Wide enough to sweep, narrow enough to see a planet as a disc rather than a dot.
     *
     * ⚠️ **The floor is a quarter of a degree because the Sun and Moon are about half of one.** At
     * the old 4° floor the Sun could never be more than a speck however far you zoomed — the reason
     * "zoom in and actually see the Sun" was impossible rather than merely unimplemented. At 0.25°
     * the solar disc spans twice the screen, so the limb and its darkening are what you are looking
     * at. Saturn is 20 arcseconds at its best, so it is still a small feature at this floor; that is
     * the honest limit of pointing a phone, not of the arithmetic.
     */
    const val MIN_FOV_DEG = 0.25
    const val MAX_FOV_DEG = 150.0

    /**
     * Everything about a view that does not change from one star to the next.
     *
     * ⚠️ **The whole reason this exists is that [project] was rebuilding it per star.** The look
     * direction, the right and up axes, the stereographic scale and the roll's sine and cosine are
     * identical for every point in a frame, and recomputing them meant seven trigonometric calls, a
     * tangent and three array allocations apiece. Measured over twelve thousand stars — which is what
     * the widest zoom actually draws — that is 1.36 ms on this machine, and a weak phone is four to
     * six times slower again: a third of a sixty-frame budget spent before anything is drawn.
     *
     * Components are held as scalars rather than arrays because the arrays were the allocation.
     */
    class Basis internal constructor(
        forward: DoubleArray,
        upX: Double,
        upY: Double,
        upZ: Double,
        fovDeg: Double,
        rollDeg: Double,
    ) {
        internal val fx: Double
        internal val fy: Double
        internal val fz: Double
        internal val rx: Double
        internal val ry: Double
        internal val rz: Double
        internal val ux: Double
        internal val uy: Double
        internal val uz: Double
        internal val invScale: Double
        internal val cosRoll: Double
        internal val sinRoll: Double
        internal val rolled: Boolean

        /**
         * False when the view is looking straight up or down, where no right axis exists.
         *
         * ⚠️ **Unreachable as things stand, and the comment says so rather than implying otherwise.**
         * The length being tested is exactly `cos(altitude)`, and the view's altitude is clamped to
         * [MAX_ALTITUDE_DEG] — 89.5°, where the cosine is 0.0087, eight orders of magnitude above
         * the threshold. Removing the check fails no test, because nothing can produce the case.
         * It stays so that a future caller who lifts the clamp gets a blank frame instead of a
         * division by zero, which is the safe direction; it is not evidence of a live hazard.
         */
        val usable: Boolean

        init {
            fx = forward[0]; fy = forward[1]; fz = forward[2]
            // Right is the look direction crossed with whichever way is up for the viewer, which is
            // NOT a fixed axis: in horizon coordinates it is the world's vertical, and in equatorial
            // coordinates it is the observer's zenith, which moves as the Earth turns. Passing it in
            // is what lets one basis serve both.
            // ⚠️ forward x up, in that order. The other way round is the negative, which mirrors
            // the whole chart left-to-right — and it compiles, draws a perfectly plausible sky, and
            // is wrong. The existing projection tests caught it when I wrote it backwards.
            val nx = fy * upZ - fz * upY
            val ny = fz * upX - fx * upZ
            val nz = fx * upY - fy * upX
            val n = sqrt(nx * nx + ny * ny + nz * nz)
            usable = n >= 1e-9
            if (usable) {
                rx = nx / n
                ry = ny / n
                rz = nz / n
                // u = r x f.
                ux = ry * fz - rz * fy
                uy = rz * fx - rx * fz
                uz = rx * fy - ry * fx
            } else {
                rx = 0.0; ry = 0.0; rz = 0.0; ux = 0.0; uy = 0.0; uz = 0.0
            }
            invScale = 1.0 / edgeRadius(fovDeg)
            rolled = rollDeg != 0.0
            val a = rollDeg * DEG
            cosRoll = cos(a)
            sinRoll = sin(a)
        }
    }

    /** The per-frame constants of a view in horizon coordinates, computed once. */
    fun basisOf(view: View): Basis = Basis(
        unit(view.azimuthDeg, view.altitudeDeg.coerceIn(-MAX_ALTITUDE_DEG, MAX_ALTITUDE_DEG)),
        // In the horizon frame the viewer's up IS the world's vertical.
        0.0, 0.0, 1.0,
        view.fovDeg,
        view.rollDeg,
    )

    /**
     * A basis for stars held in some other frame — in practice, equatorial.
     *
     * ⚠️ **This is what stops a star map reloading its catalogue as the clock runs.** Held in horizon
     * coordinates, every star's position changes continuously as the Earth turns, so the loaded set
     * goes stale within seconds — and at a narrow field, where a pixel is a fraction of an
     * arcsecond, within a single frame. Held in equatorial coordinates nothing moves at all except
     * by proper motion, which is a matter of decades, and the whole of the Earth's rotation lives in
     * this basis instead: two vectors recomputed once per frame rather than tens of thousands.
     *
     * @param forward where the middle of the screen points, as a unit vector in the stars' own frame.
     * @param up which way is up for the viewer, in that same frame — for equatorial stars, the
     *   observer's zenith. Need not be perpendicular to [forward]; only its component across the
     *   look direction is used.
     */
    fun basisOf(
        forward: DoubleArray,
        upX: Double,
        upY: Double,
        upZ: Double,
        fovDeg: Double,
        rollDeg: Double = 0.0,
    ): Basis = Basis(forward, upX, upY, upZ, fovDeg, rollDeg)

    /**
     * A right ascension and declination as a unit vector.
     *
     * ⚠️ **NOT [unit] with different arguments, and getting that wrong mirrors the entire sky.** The
     * horizon frame measures azimuth CLOCKWISE from north, so its axes come out (east, north, up);
     * right ascension runs the other way, EASTWARD, so the standard equatorial axes are x toward
     * (0h, 0°), y toward (6h, 0°) and z toward the north celestial pole. Feeding a right ascension
     * into `unit` swaps x and y, which is a reflection — a left-handed frame.
     *
     * The consequence is not a crash or a blank screen. It is a complete, plausible, reflected sky,
     * and it was measured at ninety-two degrees of error before a test caught it. Hence its own
     * implementation rather than a delegate, and hence the test that requires a star drawn this way
     * to land exactly where the horizon path puts it.
     */
    fun equatorialVector(raDeg: Double, decDeg: Double): DoubleArray {
        val r = raDeg * DEG
        val d = decDeg * DEG
        val cd = cos(d)
        return doubleArrayOf(cd * cos(r), cd * sin(r), sin(d))
    }

    /**
     * A direction already held as a unit vector, projected into a prepared view.
     *
     * ⚠️ **No trigonometry at all**, which is the point: a caller that keeps its stars as unit
     * vectors — computed once when they are loaded, not once per frame — pays nine multiplications
     * and a divide each. [project] is the convenience form and does the same arithmetic with the
     * basis rebuilt and the vector derived on the spot.
     */
    fun projectUnit(vx: Double, vy: Double, vz: Double, basis: Basis): Screen {
        if (!basis.usable) return Screen(0.0, 0.0, visible = false)
        val z = vx * basis.fx + vy * basis.fy + vz * basis.fz
        if (z <= MIN_FORWARD) return Screen(0.0, 0.0, visible = false)
        val x = vx * basis.rx + vy * basis.ry + vz * basis.rz
        val y = vx * basis.ux + vy * basis.uy + vz * basis.uz

        // Stereographic from the antipode of the view centre.
        val k = 2.0 / (1.0 + z)
        // Screen y grows downward, so the sign flips here and nowhere else.
        val sx = x * k * basis.invScale
        val sy = -y * k * basis.invScale
        if (!basis.rolled) return Screen(sx, sy, visible = true)
        // Roll turns the whole picture in the plane of the screen, so it is applied last and to the
        // flat result — it has nothing to do with the sky and everything to do with the handset.
        return Screen(
            sx * basis.cosRoll - sy * basis.sinRoll,
            sx * basis.sinRoll + sy * basis.cosRoll,
            visible = true,
        )
    }

    /**
     * The exact inverse of [projectUnit]: a screen point back to a direction in the basis's frame.
     *
     * ⚠️ **[unproject] is NOT this function with a different signature, and the difference is the
     * whole reason this exists.** That one takes a [View], rebuilds the axes with four trigonometric
     * calls and two allocations, and answers in **horizon** coordinates — right for a tap, which
     * happens once. This takes the prepared [Basis], costs no trigonometry at all, allocates
     * nothing, and answers in whatever frame the basis was built in — which for [SkyFrame] is
     * equatorial. A caller sweeping a whole screen of points needs all three of those properties.
     *
     * The stereographic inverse is closed-form, so unlike the forward direction there is no
     * approximation anywhere: `z = (4 − r²)/(4 + r²)` falls straight out of `r² = 4(1 − z)/(1 + z)`.
     * That also means **it never fails for a finite input** — stereographic covers the entire sphere
     * bar the single antipodal point, which no finite screen coordinate can reach — so the only
     * false answer is a basis with no defined orientation.
     *
     * @param out three doubles, written with the unit vector. Supplied by the caller because the
     *   only reason to prefer this over [unproject] is to run it tens of thousands of times, and an
     *   allocation per call would give back most of what the prepared basis saves.
     * @return false if the basis is unusable, in which case [out] is untouched.
     */
    fun unprojectUnit(sx: Double, sy: Double, basis: Basis, out: DoubleArray): Boolean {
        if (!basis.usable) return false
        // ⚠️ Undo the roll FIRST and with the opposite sign — `projectUnit` applies it last. The
        // error from skipping it is exactly zero at the centre of the screen, which is where anybody
        // checking by eye would look.
        val rx: Double
        val ry: Double
        if (basis.rolled) {
            rx = sx * basis.cosRoll + sy * basis.sinRoll
            ry = -sx * basis.sinRoll + sy * basis.cosRoll
        } else {
            rx = sx
            ry = sy
        }
        val scale = 1.0 / basis.invScale
        val bigX = rx * scale
        // The forward direction flips y once, on the way out; this is the same flip on the way back.
        val bigY = -ry * scale
        val s = bigX * bigX + bigY * bigY
        val z = (4.0 - s) / (4.0 + s)
        val k = (4.0 + s) / 4.0
        val px = bigX / k
        val py = bigY / k
        out[0] = basis.rx * px + basis.ux * py + basis.fx * z
        out[1] = basis.ry * px + basis.uy * py + basis.fy * z
        out[2] = basis.rz * px + basis.uz * py + basis.fz * z
        return true
    }

    /**
     * A horizon direction as a unit vector, for a caller that wants to keep it.
     *
     * The star field converts a catalogue position to the horizon once per load and then projects it
     * on every frame, so holding the vector rather than the two angles removes the last four
     * trigonometric calls from the frame path.
     */
    fun unitVector(azimuthDeg: Double, altitudeDeg: Double): DoubleArray = unit(azimuthDeg, altitudeDeg)

    /**
     * Project a horizon position into the view.
     *
     * ⚠️ Kept as it was, and now a two-line delegate. Every existing caller draws a handful of things
     * — the horizon polyline, four compass letters, the planets — where rebuilding the basis costs
     * nothing and a simpler signature is worth more. Only the star field needs [projectUnit].
     */
    fun project(azimuthDeg: Double, altitudeDeg: Double, view: View): Screen {
        val v = unit(azimuthDeg, altitudeDeg)
        return projectUnit(v[0], v[1], v[2], basisOf(view))
    }

    /**
     * A screen point back to a horizon position — what a tap is asking about.
     *
     * ⚠️ Exact, not a search. The stereographic inverse is closed-form, so a tap resolves to the
     * direction it actually points at rather than to whichever catalogued object happened to be
     * nearest in screen space, and the caller can then hit-test in **angle** where a person's finger
     * covers a fixed number of degrees regardless of the zoom.
     */
    fun unproject(x: Double, y: Double, view: View): Pair<Double, Double> {
        // ⚠️ Undo the roll FIRST and with the opposite sign. `project` applies it last, so an inverse
        // that skipped it would answer a tap with whatever the sky held before the phone was tipped —
        // and the error is zero at the centre of the screen, which is exactly where a person testing
        // it would tap. Unrolled rather than branched, unlike `project`: cos(0) and sin(0) are exactly
        // 1 and 0 so the no-roll case is bit-identical, and this runs once per tap rather than once
        // per star per frame.
        val scale = edgeRadius(view.fovDeg)
        val ca = cos(-view.rollDeg * DEG)
        val sa = sin(-view.rollDeg * DEG)
        val bigX = (x * ca - y * sa) * scale
        val bigY = -(x * sa + y * ca) * scale
        val s = bigX * bigX + bigY * bigY
        val z = (4.0 - s) / (4.0 + s)
        val k = (4.0 + s) / 4.0
        val px = bigX / k
        val py = bigY / k

        val f = unit(view.azimuthDeg, view.altitudeDeg.coerceIn(-MAX_ALTITUDE_DEG, MAX_ALTITUDE_DEG))
        val rx = f[1]
        val ry = -f[0]
        val rn = sqrt(rx * rx + ry * ry)
        if (rn < 1e-9) return view.azimuthDeg to view.altitudeDeg
        val r = doubleArrayOf(rx / rn, ry / rn, 0.0)
        val u = cross(r, f)

        val vx = r[0] * px + u[0] * py + f[0] * z
        val vy = r[1] * px + u[1] * py + f[1] * z
        val vz = r[2] * px + u[2] * py + f[2] * z

        val alt = Math.toDegrees(Math.asin(vz.coerceIn(-1.0, 1.0)))
        val az = norm360(Math.toDegrees(Math.atan2(vx, vy)))
        return az to alt
    }

    /**
     * Drag the view.
     *
     * ⚠️ **The horizontal step is divided by the cosine of the altitude**, so a drag near the zenith
     * does not whip the sky round. Without it, panning up feels progressively faster sideways until
     * the map is unusable at the top — the same reason a globe's meridians converge.
     */
    fun pan(view: View, dAzimuthDeg: Double, dAltitudeDeg: Double): View {
        val alt = (view.altitudeDeg + dAltitudeDeg).coerceIn(-MAX_ALTITUDE_DEG, MAX_ALTITUDE_DEG)
        val shrink = cos(view.altitudeDeg.coerceIn(-MAX_ALTITUDE_DEG, MAX_ALTITUDE_DEG) * DEG)
            .coerceAtLeast(0.05)
        return view.copy(
            azimuthDeg = norm360(view.azimuthDeg + dAzimuthDeg / shrink),
            altitudeDeg = alt,
        )
    }

    /** Pinch. A factor above 1 zooms IN, which is the direction a pinch-out gesture reports. */
    fun zoom(view: View, factor: Double): View =
        view.copy(fovDeg = (view.fovDeg / factor).coerceIn(MIN_FOV_DEG, MAX_FOV_DEG))

    /**
     * How many degrees a screen distance is worth at the centre of the view.
     *
     * The point of it is hit-testing: a fingertip is about the same number of screen units whatever
     * the zoom, and it has to become a tolerance in degrees that shrinks as you zoom in.
     */
    fun degreesPerUnit(view: View): Double = view.fovDeg / 2.0

    /**
     * The angle from the middle of the screen to its furthest CORNER.
     *
     * This is what a renderer needs to throw away whole objects without projecting them: anything
     * further from the view direction than this cannot land on screen, whatever its shape.
     *
     * ⚠️ **Not `fovDeg / 2`, and the difference is the whole point.** The declared field is measured
     * across the smaller screen dimension, so the corner of a portrait phone is well outside it —
     * culling on the half-field would drop constellation lines that are plainly visible at the top
     * and bottom of the screen, which is the same mistake in a different place as drawing the sky
     * as a circle.
     *
     * Inverts the stereographic radius: a direction `θ` from the centre lands at `2·tan(θ/2)` before
     * scaling, so a corner at normalised radius `r` is at `θ = 2·atan(r · tan(fov/4))`.
     */
    fun coneRadiusDeg(fovDeg: Double, viewport: Viewport): Double {
        val r = sqrt(
            viewport.halfWidth * viewport.halfWidth + viewport.halfHeight * viewport.halfHeight,
        )
        return 2.0 * Math.toDegrees(Math.atan(r * edgeRadius(fovDeg) / 2.0))
    }

    /**
     * How faint a star is worth drawing at this zoom.
     *
     * ⚠️ **This is what makes a catalogue of any size drawable.** However many stars are on disk,
     * the number actually drawn stays in the low thousands at every zoom — so a renderer sized for
     * a few thousand points is sized for all of them, and the work per frame does not depend on how
     * deep the catalogue goes. Nothing else in the map has to know how big the file is.
     *
     * ## The law, and the arithmetic behind the two constants
     *
     * Star counts rise by a **measured factor of 2.63 per magnitude** at these depths — Gaia DR3
     * holds 9,100 stars brighter than 6.5 and 1,247,240 brighter than 11.0, which is 137× over 4.5
     * magnitudes. The area of the field falls as roughly the square of the angle. So keeping the
     * drawn count level while zooming means deepening the limit **linearly in the logarithm of the
     * field**, at 2 / log₁₀(2.63) ≈ 4.8 magnitudes per decade.
     *
     * [MAGNITUDES_PER_DECADE] is deliberately a little shallower than that. At 4.2 the implied count
     * drifts from about 1,540 at the widest field to about 710 at three degrees — a factor of two
     * over the whole range, which nobody will notice — and in exchange the limit keeps deepening
     * down to a field of under two degrees instead of exhausting a magnitude-14 catalogue at two and
     * a half. At the deepest zoom you want the faintest stars more than you want the most.
     *
     * ⚠️ **[WIDEST_LIMIT] was raised from 3.6, and it is a deliberate reversal of an earlier
     * judgement.** That number was chosen when the bundled catalogue held 8,404 stars drawn as flat
     * identical dots, on the reasoning that a full naked-eye sky would bury the shapes people
     * navigate by. The complaint that prompted this work was that the map is too sparse, and a real
     * dark sky *is* about magnitude 6. Drawing size by brightness is what keeps the constellations
     * legible now, rather than drawing fewer stars. It is one constant if it proves wrong on a phone.
     *
     * @param deepest how far the catalogue actually goes. The result is clamped to it, so a shallow
     *   catalogue stops deepening at its own floor instead of promising rows that are not there.
     *
     * ⚠️ **`deepest` HAS NO DEFAULT, and that is deliberate — it used to, and the omission it
     * invited was the defect that prompted this note.** The renderer called this without it, so it
     * silently cut at [NAKED_EYE_LIMIT] while the loader — which passed the real depth — read three
     * million stars down to magnitude 12. Measured over the real catalogue at a fifteen-degree
     * field: 31,529 loaded, **123 drawn**; at five degrees, ten. Zooming in made the sky emptier,
     * which is the complaint this whole body of work exists to answer.
     *
     * Every test passed a depth explicitly, so every test was green and none of them described what
     * the app did. This project has now shipped that shape three times — a default that quietly
     * means "do not do the thing" — so the parameter is required and the compiler is the guard.
     */
    fun magnitudeLimit(fovDeg: Double, deepest: Double): Double {
        val fov = fovDeg.coerceIn(MIN_FOV_DEG, MAX_FOV_DEG)
        val decades = log10(MAX_FOV_DEG / fov)
        return (WIDEST_LIMIT + MAGNITUDES_PER_DECADE * decades).coerceAtMost(deepest)
    }

    /** What the map draws at the widest field: roughly what a dark sky shows the naked eye. */
    const val WIDEST_LIMIT = 6.0

    /** See [magnitudeLimit] — 4.8 holds the drawn count level, 4.2 trades a little of that for depth. */
    const val MAGNITUDES_PER_DECADE = 4.2

    /** About as faint as an unaided eye reaches on a good night, and where the original bundle stops. */
    const val NAKED_EYE_LIMIT = 6.5

    // ---- running out of catalogue -------------------------------------------------------------

    /**
     * The field below which [magnitudeLimit] stops deepening, because the catalogue has run out.
     *
     * ⚠️ **This is not a corner case; it is half the zoom range.** Measured against the shipped
     * catalogue: G < 12 saturates at **5.59°**, and the map zooms to [MIN_FOV_DEG] — so over
     * **48.6% of the range** (in decades of field, which is how zoom is actually felt) not one new
     * star appears however far you pinch. The map said nothing about that, so the only reading
     * available to somebody using it was that the sky itself is empty there. It is not; the file is.
     *
     * ⚠️ **Every extra magnitude buys exactly the same amount of zoom and costs about 2.2× the
     * last, so there is no natural place to stop.** That falls straight out of the law: the limit is
     * linear in log-field, so one magnitude is always 1/[MAGNITUDES_PER_DECADE] = 0.238 decades.
     * Measured live against the Gaia DR3 archive, at [StarCatalogFormat.RECORD_BYTES] a star:
     *
     * | catalogue | stars | file | saturates | dead zoom |
     * |---|---|---|---|---|
     * | G < 12 (shipped) | 3,087,821 | 24.7 MB | 5.59° | 48.6% |
     * | G < 13 | 7,369,627 | 59.0 MB | 3.23° | 40.0% |
     * | G < 14 | 16,844,156 | 134.8 MB | 1.87° | 31.4% |
     *
     * Each step down that table buys 8.6 points of zoom range; the first costs 34 MB and the second
     * 76 MB. Nothing closes the gap — saturating at [MIN_FOV_DEG] would need magnitude 17.7, which
     * is on the order of a billion stars.
     *
     * @param deepest the depth the catalogue actually reaches — see [magnitudeLimit].
     */
    fun saturationFovDeg(deepest: Double): Double {
        val fov = MAX_FOV_DEG * 10.0.pow(-(deepest - WIDEST_LIMIT) / MAGNITUDES_PER_DECADE)
        return fov.coerceIn(MIN_FOV_DEG, MAX_FOV_DEG)
    }

    /**
     * Is the cut being made by the catalogue's depth rather than by the field?
     *
     * ⚠️ **Exact, with no tolerance, and that is a property of [magnitudeLimit] rather than luck.**
     * It ends in `coerceAtMost(deepest)`, which returns the very same `Double` when it clamps — so
     * `>=` is true precisely when it clamped. An epsilon here would report saturation just before it
     * happened, which is the one moment the reading is wrong in the direction that misleads.
     */
    fun isSaturated(fovDeg: Double, deepest: Double): Boolean =
        magnitudeLimit(fovDeg, deepest) >= deepest

    /**
     * The field width, said in a unit that has meaning at that width.
     *
     * ⚠️ **Both readouts printed `fovDeg.roundToInt()`, so below half a degree they read
     * "0° across".** Flatly wrong text on screen, and worst in exactly the range this map was
     * rebuilt to reach — [MIN_FOV_DEG] is 0.25°, so the deepest three quarters of a decade of zoom
     * all reported zero. Between 0.5° and 1.5° it was no better: everything read "1°", which is
     * where the useful detail lives.
     *
     * Arcminutes below a degree, because that is the unit the sky is measured in there and it gives
     * the reader something to hold on to — the Moon is 31′, so "30′ across" is a picture. Tenths of
     * a degree up to ten, whole degrees above.
     *
     * ⚠️ **Built from integers rather than `String.format`, so it carries no locale.** Three reasons,
     * and the third is the decisive one: a comma decimal would make the test pass or fail by the
     * machine it runs on; the rest of the readout is already locale-free integer interpolation; and
     * both applications ship `resourceConfigurations += listOf("en")`, so a comma decimal would be
     * the only one on an English screen. This is not the "numbers that are data use Locale.US" rule
     * — it is that a locale is not wanted here at all.
     */
    fun formatFieldWidth(fovDeg: Double): String {
        val fov = fovDeg.coerceIn(MIN_FOV_DEG, MAX_FOV_DEG)
        if (fov < 1.0) return "${(fov * 60.0).roundToInt()}′"
        if (fov < 10.0) return tenths(fov) + "°"
        return "${fov.roundToInt()}°"
    }

    /**
     * What to say when the map has run out of stars, or null when it has not.
     *
     * ⚠️ **Null is the ordinary answer and the note is the exception**, which is the only way it
     * stays worth reading. It says both numbers a reader needs to act on: how deep this catalogue
     * goes, and where the deepening stopped — so "would a bigger file help, and by how much" is
     * answerable from the screen rather than from this file.
     *
     * ⚠️ **It does not replace the note that says the catalogue failed to open.** Those are
     * different facts — one is "the file is not there", the other "you have zoomed past what is in
     * it" — and a surface may well want to show both at once.
     */
    fun depthNote(fovDeg: Double, deepest: Double): String? {
        if (!isSaturated(fovDeg, deepest)) return null
        val sat = saturationFovDeg(deepest)
        val tail =
            if (sat >= MAX_FOV_DEG) "Zooming shows no new stars."
            else "Below ${formatFieldWidth(sat)} across, zooming shows no new stars."
        return "Everything it holds down to magnitude ${tenths(deepest)} is on screen. $tail"
    }

    /**
     * One decimal place, without a locale and without `String.format`.
     *
     * ⚠️ **`roundToInt` is the right rounding here and `kotlin.math.round` would not be.** Measured
     * against the JDK rather than recalled, because a first draft of this file asserted the opposite:
     * `roundToInt` is `Math.round`, which takes 0.5 → 1, 2.5 → 3, 14.5 → 15; `kotlin.math.round` is
     * `Math.rint`, which is banker's and takes 0.5 → 0, 2.5 → 2, 14.5 → 14. Every value reaching here
     * is a field width or a catalogue depth, so positive, where half-up and half-away-from-zero agree.
     * [StarCatalogFormat] states the same rule for the same reason and reaches for `floor(v + 0.5)`
     * because it must match a Python builder, whose own `round` is banker's too.
     */
    private fun tenths(v: Double): String {
        val t = (v * 10.0).roundToInt()
        return "${t / 10}.${t % 10}"
    }

    /**
     * Angular separation between two horizon positions, degrees.
     *
     * ⚠️ Uses the half-angle form rather than `acos` of the dot product. At the small separations
     * that matter for hit-testing, `acos` near 1.0 loses most of its significant figures — a
     * textbook catastrophic cancellation, and it would make a tap on a star land a degree away.
     */
    fun separationDeg(az1: Double, alt1: Double, az2: Double, alt2: Double): Double {
        val a = unit(az1, alt1)
        val b = unit(az2, alt2)
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        val chord = sqrt(dx * dx + dy * dy + dz * dz)
        return Math.toDegrees(2.0 * Math.asin((chord / 2.0).coerceIn(0.0, 1.0)))
    }

    // ---- small helpers ----------------------------------------------------------------------

    /** East-north-up unit vector for a horizon position. */
    private fun unit(azimuthDeg: Double, altitudeDeg: Double): DoubleArray {
        val a = azimuthDeg * DEG
        val h = altitudeDeg * DEG
        val ch = cos(h)
        return doubleArrayOf(ch * sin(a), ch * cos(a), sin(h))
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    /** Projected radius of the field's edge, before normalising to 1.0. */
    private fun edgeRadius(fovDeg: Double): Double =
        2.0 * tan((fovDeg.coerceIn(MIN_FOV_DEG, MAX_FOV_DEG) / 4.0) * DEG)

    private fun norm360(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    /** True when two angles are the same direction, allowing for the wrap at 360. */
    internal fun sameAzimuth(a: Double, b: Double, toleranceDeg: Double): Boolean {
        val d = abs(norm360(a - b))
        return d <= toleranceDeg || d >= 360.0 - toleranceDeg
    }
}
