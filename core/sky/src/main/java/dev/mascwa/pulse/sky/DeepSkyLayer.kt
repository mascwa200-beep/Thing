package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.DeepSky
import dev.mascwa.pulse.core.telemetry.SkyProjection

/**
 * The deep sky in the form the draw pass wants.
 *
 * ## ⚠️ Why this is built once and the constellations are not
 *
 * [ConstellationField] re-cuts on zoom because a line has to be SUBDIVIDED, and how finely depends
 * on the field. A galaxy is a position and two axes: nothing about it changes when the view moves,
 * so this is filled once when the asset loads and then only read.
 *
 * ## What is a primitive array and what is not
 *
 * The cull loop touches a unit vector, a magnitude and a size, so those are primitives — no boxing,
 * no indirection, twelve and a half thousand times a frame. Everything else (the kind, the axes, the
 * position angle, the name) is read from [entries] only for the few dozen objects that survive, so
 * duplicating it into parallel arrays would cost memory to save nothing.
 *
 * ⚠️ **`Float.NaN` means not measured**, in both arrays, and it is chosen rather than a sentinel
 * number because every comparison against NaN is false — so an unmeasured magnitude cannot
 * accidentally pass a `<=` cut, whichever way the cut is written.
 */
class DeepSkyLayer(val entries: List<DeepSky.Entry>) {

    val count: Int = entries.size

    val vx = DoubleArray(count)
    val vy = DoubleArray(count)
    val vz = DoubleArray(count)

    /** Visual magnitude where measured, else NaN. See [DeepSky.Entry.band] for which system. */
    val magnitude = FloatArray(count)

    /** Long axis in arcminutes where measured, else NaN. */
    val majorArcmin = FloatArray(count)

    init {
        for (i in 0 until count) {
            val e = entries[i]
            val u = SkyProjection.equatorialVector(e.rightAscensionDeg, e.declinationDeg)
            vx[i] = u[0]
            vy[i] = u[1]
            vz[i] = u[2]
            magnitude[i] = e.magnitude?.toFloat() ?: Float.NaN
            majorArcmin[i] = e.majorAxisArcmin?.toFloat() ?: Float.NaN
        }
    }

    /**
     * Whether object [i] is drawn at this cut, without touching [entries].
     *
     * ⚠️ **Delegates rather than restating the rule.** The first version of this method was a second
     * copy of [DeepSky.visible]'s three clauses reading the flat arrays, which is the
     * duplicated-definition drift this project has corrected seven times — and here it would have
     * been invisible, since both copies would look right and could only disagree about the edge the
     * size clause exists for. [DeepSky.visibleAt] takes the primitives so the delegation costs
     * nothing: no boxing, and NaN carries "not measured" across.
     *
     * @param extinctionMag what the air takes at the object's altitude — [SkyFrame.extinctionMag],
     *   added to the catalogue magnitude before the cut exactly as `collectStars` adds it to a
     *   star's, so a galaxy dimmed past the cut is not drawn any more than a star is. Zero with the
     *   atmosphere off, so the cut is asked the catalogue magnitude to the bit. ⚠️ An UNMEASURED
     *   magnitude is NaN and `NaN + anything` is NaN, so the size clause and the narrow-field clause
     *   are untouched by the air: those objects are drawn for how big they are, not how bright.
     */
    fun visible(i: Int, limit: Double, fovDeg: Double, extinctionMag: Double = 0.0): Boolean =
        DeepSky.visibleAt(
            magnitude[i].toDouble() + extinctionMag, majorArcmin[i].toDouble(), limit, fovDeg,
        )

    /**
     * Whether object [i]'s name is drawn at this cut — [DeepSky.labels], with the air taken off
     * the headroom.
     *
     * ⚠️ The star label pass re-checks its headroom against `m0 + extinction` so a star the air
     * has dimmed to a speck loses the name its catalogue brightness earned; this is the same rule
     * for a galaxy, and it was missing — the marker went to half a percent alpha at the horizon
     * while the label stayed at full strength beside it. The subtraction is on the LIMIT rather
     * than the magnitude because the magnitude is nullable inside [DeepSky.labels], and an object
     * with no measured brightness is named for its size before the limit is read — so the air has
     * no say over it, exactly as it has none over [visible]'s size clause.
     *
     * @param extinctionMag [SkyFrame.extinctionMag] at the object's altitude; zero with the
     *   atmosphere off, so the answer is the catalogue rule to the bit.
     */
    fun labelled(i: Int, limit: Double, extinctionMag: Double = 0.0): Boolean =
        DeepSky.labels(entries[i], limit - extinctionMag)
}
