package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.Constellations
import dev.mascwa.pulse.core.telemetry.SkyProjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The constellations, cut fine enough for the zoom they are being drawn at.
 *
 * ## ⚠️ Why this is rebuilt on zoom rather than held once
 *
 * A line's error on screen is the sagitta of drawing each interpolated piece as a chord, and it
 * scales as `step² / fov` — measured, in [Constellations]. Over a map that zooms six hundred fold
 * that means no single subdivision works: a step that is invisible at the widest field is a hundred
 * pixels wrong at the narrowest, and a step fine enough for the narrowest is seven times the
 * vertices the widest needs.
 *
 * So [update] re-cuts when the field has moved far enough to matter, and does it off the main thread
 * — the whole set at the finest step is about ninety thousand vertices, each of which costs a
 * precession or a spherical interpolation.
 *
 * ⚠️ **Nothing here depends on the clock, the observer or where the view is pointing**, which is why
 * a rebuild is rare: the vertices are equatorial, and the Earth's rotation lives in [SkyFrame].
 * Only the FIELD invalidates them.
 *
 * Not thread-safe: one field belongs to one screen, and [update] is the only writer.
 */
class ConstellationField(private val data: Constellations.Data) {

    /** The 88 IAU stick figures. */
    val figures = SkyLines(initialVertices = 16_384, initialLines = 256)

    /** The popular shapes that are not constellations — the Plough, the Summer Triangle. */
    val asterisms = SkyLines(initialVertices = 8_192, initialLines = 128)

    /** The IAU borders, already carried from B1875 to J2000. */
    val boundaries = SkyLines(initialVertices = 32_768, initialLines = 1_024)

    /** The step the held vertices were cut at, or NaN before the first build. */
    var stepDeg: Double = Double.NaN
        private set

    /** What [update] did, so a caller can tell a busy frame from an idle one. */
    enum class Outcome { UNCHANGED, REBUILT }

    /**
     * Re-cut for this field of view, if it has moved far enough to be worth it.
     *
     * ⚠️ **The threshold is what stops a pinch gesture rebuilding ninety thousand vertices on every
     * frame of the animation.** A tenth of the step is well below the tolerance the step was chosen
     * for in the first place, so the picture never visibly coarsens while the gesture is running.
     */
    suspend fun update(fovDeg: Double): Outcome {
        val want = Constellations.stepDegFor(fovDeg)
        if (stepDeg.isFinite() && abs(want - stepDeg) < stepDeg * REBUILD_FRACTION) {
            return Outcome.UNCHANGED
        }
        withContext(Dispatchers.Default) {
            fill(figures, data.figures, want)
            fill(asterisms, data.asterisms, want)
            fillBoundaries(want)
            // ⚠️ Assigned INSIDE the block and last. `withContext` checks for cancellation on the way
            // out; recording the step before the arrays are filled would leave a cancelled rebuild
            // claiming a subdivision it does not hold, and the next update would answer UNCHANGED.
            stepDeg = want
        }
        return Outcome.REBUILT
    }

    private fun fill(into: SkyLines, groups: List<Constellations.Figure>, step: Double) {
        into.clear()
        for (group in groups) {
            for (line in group.lines) {
                into.beginLine()
                for (i in 0 until line.size - 1) {
                    val a = line[i]
                    val b = line[i + 1]
                    var first = true
                    Constellations.walkGreatCircle(
                        data.starRaDeg[a], data.starDecDeg[a],
                        data.starRaDeg[b], data.starDecDeg[b],
                        step,
                    ) { ra, dec ->
                        // ⚠️ Each leg re-emits the star it starts from, which is the previous leg's
                        // last vertex. Adding it again would put a zero-length segment at every
                        // joint — invisible, and a wasted projection at each of a few thousand.
                        if (!first || i == 0) {
                            val u = SkyProjection.equatorialVector(ra, dec)
                            into.add(u[0], u[1], u[2])
                        }
                        first = false
                    }
                }
                into.endLine()
            }
        }
    }

    private fun fillBoundaries(step: Double) {
        boundaries.clear()
        for (edge in data.boundaries) {
            boundaries.beginLine()
            Constellations.walkEdge(edge, step) { ra, dec ->
                val u = SkyProjection.equatorialVector(ra, dec)
                boundaries.add(u[0], u[1], u[2])
            }
            boundaries.endLine()
        }
    }

    private companion object {
        /**
         * How far the step may drift before a rebuild, as a fraction of itself.
         *
         * A tenth. The step is chosen so the drawn line sits within about a pixel of the true one,
         * and a tenth of a step changes that by a fifth of a pixel — so the picture is never visibly
         * stale, while a pinch that changes the field by a factor of two rebuilds about seven times
         * rather than sixty.
         */
        const val REBUILD_FRACTION = 0.1
    }
}
