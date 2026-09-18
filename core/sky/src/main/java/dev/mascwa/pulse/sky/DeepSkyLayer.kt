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
     */
    fun visible(i: Int, limit: Double, fovDeg: Double): Boolean =
        DeepSky.visibleAt(magnitude[i].toDouble(), majorArcmin[i].toDouble(), limit, fovDeg)
}
