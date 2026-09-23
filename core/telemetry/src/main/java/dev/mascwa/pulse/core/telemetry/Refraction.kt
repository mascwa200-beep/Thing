package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Atmospheric refraction: how much higher the air lifts everything in the sky.
 *
 * ⚠️ **This is the largest single correction the star map ever lacked, and it is not small.** Light
 * arriving through the atmosphere is bent toward the zenith, so every object appears higher than it
 * is — by **34.5 arcminutes for something that APPEARS on the horizon** (more than the Moon's own
 * diameter, so a Moon truly half a diameter below the horizon is seen sitting on it), 28.9′ for
 * something truly at zero, 9.6′ at a true five degrees, 5.3′ at ten, 1.0′ at forty-five, and nothing
 * at the zenith. ⚠️ The two horizon figures differ because Bennett is stated in the APPARENT
 * altitude, and every number here was computed from the functions below rather than copied from a
 * table. A chart that draws true positions puts the rising Moon a whole diameter below where the eye
 * sees it, at every field of view, and the measured gap against Skyfield's apparent places before
 * this existed was a median of **884 arcseconds** in the lowest five degrees. Stellarium applies it
 * by default; so does this map, behind its ATMOSPHERE switch.
 *
 * ## The formula, and why the forward direction is an iteration
 *
 * Bennett's formula (Meeus, *Astronomical Algorithms*, 16.3) gives the refraction as a function of
 * the **apparent** altitude — the altitude the eye sees. That is the natural direction for a tap,
 * which asks "what true direction is under this drawn one": [trueOf] is one closed-form step. The
 * map, though, holds TRUE positions and wants the apparent one, which means solving
 * `apparent = true + R(apparent)` — a fixed point, found by iterating Bennett from the true altitude.
 * The map's derivative is about 0.2 near the horizon and far smaller above, so the iteration
 * contracts and converges in a dozen steps to well under a microarcsecond. Skyfield's `refract`
 * does exactly this, which is what makes the two comparable to the arcsecond.
 *
 * ⚠️ **Why not Sæmundsson's forward formula (Meeus 16.4) and Bennett's inverse?** Because the two
 * are not exact inverses of each other — Meeus notes they agree only to about 4″ — and the tap
 * depends on the inverse landing exactly on what was drawn. One formula, iterated one way and
 * inverted the other, is exact by construction.
 *
 * ## Below the horizon
 *
 * Bennett is fitted for the sky, not for the ground: at an apparent altitude of −4.4° its argument
 * has a pole and below that it returns nonsense. The map draws what is below the horizon, dimmed, so
 * it needs an answer there that is finite, continuous and monotonic rather than physically exact —
 * nobody can see a star through the ground. The bending is held at its value for a true altitude of
 * [TAPER_TOP_DEG] and tapered linearly to zero by [TAPER_BOTTOM_DEG], the same shape Stellarium's
 * own `Refraction` uses. ⚠️ Monotonicity is what matters and it is asserted: a fold anywhere would
 * draw two stars at one altitude and leave the inverse with two answers.
 *
 * ## Standard conditions
 *
 * 1010 hPa and 10 °C, which are Bennett's own and Stellarium's default. Refraction scales almost
 * linearly with pressure over temperature, so a hot day at altitude bends about a tenth less at the
 * horizon and the same nothing at the zenith. Not a parameter yet; the day it is, it goes through
 * [bennettDeg] and nowhere else.
 *
 * Pure and platform-free, so the whole thing is held by a test that runs here.
 */
object Refraction {

    /**
     * Above this TRUE altitude the bending is Bennett's; from here down to [TAPER_BOTTOM_DEG] it is
     * tapered linearly to zero.
     *
     * A degree below the horizon, where Bennett is still well-behaved (its argument is 1.15° there)
     * and where the last of the sky the map can honestly claim to bend has already set.
     */
    const val TAPER_TOP_DEG = -1.0

    /** At and below this TRUE altitude nothing is bent at all. */
    const val TAPER_BOTTOM_DEG = -4.0

    /**
     * The spacing of [fastBendingDeg]'s table, in degrees of TRUE altitude.
     *
     * ⚠️ Chosen so that both taper joints land exactly on a table node: −4 and −1 are whole multiples
     * of 0.05 from −4, so the one place the bending has a kink is never straddled by a linear
     * interpolation. Straddling it would cost about an arcminute of error at exactly the altitude of
     * the horizon's lower edge; on a node the error is the curvature term alone, measured at a tenth
     * of an arcsecond near the horizon and nothing at all higher up.
     */
    const val TABLE_STEP_DEG = 0.05

    private const val MAX_ITERATIONS = 40

    /** Convergence bound for the fixed point, in degrees: a thousandth of a milliarcsecond. */
    private const val CONVERGENCE_DEG = 1e-10

    /** How many halvings the inverse takes: one degree over 2^52 is far below double precision. */
    private const val BISECTIONS = 52

    /**
     * Bennett's refraction for an APPARENT altitude, in degrees, at 1010 hPa and 10 °C.
     *
     * ⚠️ Clamped at zero rather than allowed negative: the formula's argument passes 90° a little
     * before the altitude does, so at the zenith it answers about −5″. Refraction at the zenith is
     * zero, and a negative bend would break the monotonicity the whole file rests on.
     *
     * ⚠️ Answers zero below an apparent altitude of −4°, where the argument runs into its pole. No
     * caller reaches there — [bendingDeg] tapers on the TRUE altitude well before — but a formula
     * that returns infinity for an input somebody might one day pass it is worse than one that says
     * nothing.
     */
    fun bennettDeg(apparentAltDeg: Double): Double {
        if (apparentAltDeg <= -4.0) return 0.0
        val u = apparentAltDeg + 7.31 / (apparentAltDeg + 4.4)
        val arcmin = 1.0 / tan(Math.toRadians(u))
        return if (arcmin > 0.0) arcmin / 60.0 else 0.0
    }

    /**
     * How far the air lifts something at a TRUE altitude, in degrees: `apparent − true`.
     *
     * Bennett iterated to its fixed point above [TAPER_TOP_DEG]; tapered linearly to zero between
     * there and [TAPER_BOTTOM_DEG]; zero below. Never negative.
     */
    fun bendingDeg(trueAltDeg: Double): Double = when {
        trueAltDeg <= TAPER_BOTTOM_DEG -> 0.0
        trueAltDeg < TAPER_TOP_DEG ->
            converged(TAPER_TOP_DEG) *
                (trueAltDeg - TAPER_BOTTOM_DEG) / (TAPER_TOP_DEG - TAPER_BOTTOM_DEG)
        else -> converged(trueAltDeg)
    }

    /** The apparent altitude of something at a TRUE altitude. */
    fun apparentOf(trueAltDeg: Double): Double = trueAltDeg + bendingDeg(trueAltDeg)

    /**
     * The TRUE altitude under an APPARENT one — the exact inverse of [apparentOf].
     *
     * ⚠️ **Found by bisection on [apparentOf] rather than by evaluating Bennett at the apparent
     * altitude, and the reason is the taper.** Above [TAPER_TOP_DEG] the closed form
     * `apparent − bennett(apparent)` IS the exact inverse of the fixed point; below it the taper is
     * a different function with a different inverse, and the two have to agree to the last bit at
     * the joint or a tap there resolves to a star that is not drawn where the finger was. One
     * monotonic forward function and one inverse derived from it cannot disagree with themselves.
     * A tap happens once, so fifty-two evaluations of the forward form cost nothing.
     */
    fun trueOf(apparentAltDeg: Double): Double {
        if (apparentAltDeg <= TAPER_BOTTOM_DEG) return apparentAltDeg
        // The bend is never negative, so the true altitude is never above the apparent one — and it
        // is never more than a degree below it, at any altitude the taper reaches.
        var lo = maxOf(TAPER_BOTTOM_DEG, apparentAltDeg - 1.0)
        var hi = apparentAltDeg
        repeat(BISECTIONS) {
            val mid = 0.5 * (lo + hi)
            if (apparentOf(mid) < apparentAltDeg) lo = mid else hi = mid
        }
        return 0.5 * (lo + hi)
    }

    private val TABLE_ENTRIES: Int =
        ((90.0 - TAPER_BOTTOM_DEG) / TABLE_STEP_DEG).roundToInt() + 1

    /**
     * [bendingDeg] sampled every [TABLE_STEP_DEG] of true altitude from [TAPER_BOTTOM_DEG] to 90°,
     * built on first use. Fifteen kilobytes, once.
     */
    private val TABLE: DoubleArray by lazy {
        DoubleArray(TABLE_ENTRIES) { i -> bendingDeg(TAPER_BOTTOM_DEG + i * TABLE_STEP_DEG) }
    }

    /**
     * [bendingDeg] by table lookup and linear interpolation — the per-star path.
     *
     * ⚠️ The map bends every drawn star every frame, and there are up to nine thousand of them; a
     * dozen tangents each would be the frame. This is one division, a floor and two multiplications,
     * and it agrees with the exact form to within half an arcsecond over the whole range — asserted,
     * not assumed, and the kink is on a node (see [TABLE_STEP_DEG]).
     */
    fun fastBendingDeg(trueAltDeg: Double): Double {
        if (trueAltDeg <= TAPER_BOTTOM_DEG || trueAltDeg >= 90.0) return 0.0
        val x = (trueAltDeg - TAPER_BOTTOM_DEG) / TABLE_STEP_DEG
        val i = x.toInt()
        val f = x - i
        val t = TABLE
        val a = t[i]
        return a + (t[i + 1] - a) * f
    }

    /**
     * Rotate a unit direction toward the zenith by a bend, writing the result into [out].
     *
     * The geometry of refraction on the sphere: the apparent direction lies in the plane of the true
     * direction and the zenith, tilted toward the zenith by the bend. `u` is the unit vector in that
     * plane perpendicular to `v`, and `v cos δ + u sin δ` is the rotation.
     *
     * ⚠️ Undefined at the zenith itself, where `v` and the zenith coincide and there is no plane — and
     * exactly there the bend is zero, so the caller skips the rotation rather than dividing by zero.
     * [SIN_NO_BEND] is where that skip begins.
     *
     * ⚠️ `sin` and `cos` of the bend from their series rather than from the library: the bend is
     * never more than about 0.65° (0.011 rad), where two terms of the sine and three of the cosine
     * are exact to a hundred-billionth, and a frame that skips eighteen thousand trigonometric calls
     * is a frame that costs nothing extra for being refracted.
     *
     * @param sinAlt the direction's sine of altitude, `v · zenith`, which every caller already holds.
     * @param bendDeg how far to lift; negative lowers, which is what the inverse wants.
     */
    fun lift(
        vx: Double, vy: Double, vz: Double,
        zenithX: Double, zenithY: Double, zenithZ: Double,
        sinAlt: Double,
        bendDeg: Double,
        out: DoubleArray,
    ) {
        val inv = 1.0 / sqrt(1.0 - sinAlt * sinAlt)
        val ux = (zenithX - sinAlt * vx) * inv
        val uy = (zenithY - sinAlt * vy) * inv
        val uz = (zenithZ - sinAlt * vz) * inv
        val d = Math.toRadians(bendDeg)
        val d2 = d * d
        val c = 1.0 - d2 * (0.5 - d2 / 24.0)
        val s = d * (1.0 - d2 / 6.0)
        out[0] = vx * c + ux * s
        out[1] = vy * c + uy * s
        out[2] = vz * c + uz * s
    }

    /**
     * Above this sine of altitude (89.9°) nothing is bent: the bend there is a tenth of an arcsecond
     * and the rotation plane is about to vanish.
     */
    const val SIN_NO_BEND = 0.99999848

    /** The sine of [TAPER_BOTTOM_DEG]: below it a caller can skip the lookup outright. */
    val SIN_TAPER_BOTTOM: Double = kotlin.math.sin(Math.toRadians(TAPER_BOTTOM_DEG))

    /**
     * The largest bend anywhere — the bend at the top of the taper, about 0.655°. Above it the
     * bend only falls with altitude; below it the taper only falls toward zero.
     *
     * ⚠️ This is how far something can truly sit OUTSIDE a cone and still be lifted into it, so a
     * caller that culls by angle before projecting (a run of constellation lines against the
     * screen's corner cone) has to look this much further out than the cone itself, or a run whose
     * every vertex is just past the bottom corner is thrown away while the air would have drawn it.
     */
    val MAX_BEND_DEG: Double by lazy { converged(TAPER_TOP_DEG) }

    /**
     * The TRUE altitude of something that APPEARS exactly on the horizon: about −0.55°.
     *
     * ⚠️ This is the line the map's "above the horizon" test moves to when the atmosphere is on.
     * The horizon line is drawn at apparent zero, and a star truly half a degree below it is drawn
     * above it — dimming that star as "not up" would put a dimmed dot above the line it is supposed
     * to be under. What the eye can see is what counts, and the eye sees this far round the curve.
     */
    val TRUE_AT_APPARENT_HORIZON_DEG: Double by lazy { trueOf(0.0) }

    private fun converged(trueAltDeg: Double): Double {
        var apparent = trueAltDeg
        repeat(MAX_ITERATIONS) {
            val next = trueAltDeg + bennettDeg(apparent)
            val done = abs(next - apparent) < CONVERGENCE_DEG
            apparent = next
            if (done) return apparent - trueAltDeg
        }
        return apparent - trueAltDeg
    }
}
