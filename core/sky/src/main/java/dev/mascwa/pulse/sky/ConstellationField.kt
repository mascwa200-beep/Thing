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

    /**
     * The 88 constellation names, in the order of [data]'s figures, and where each one is written.
     *
     * A name goes at the figure's centroid — the normalised sum of the unit vectors of the stars its
     * lines join, each star counted once however many lines meet at it. That is where a printed atlas
     * puts the name, and it is a J2000 direction like every vertex here, so the chart projects it
     * through [SkyFrame] and the name rides with the figure through refraction and aberration.
     *
     * ⚠️ Built ONCE, in the constructor, and not in [update]: the centroid does not depend on how
     * finely the lines are cut, and rebuilding eighty-eight names on every zoom would be work spent on
     * a number that cannot change. `nameX`/`nameY`/`nameZ` are valid over `0 until names.size`.
     */
    val names: List<String> = data.figures.map { it.name }
    val nameX: DoubleArray = DoubleArray(names.size)
    val nameY: DoubleArray = DoubleArray(names.size)
    val nameZ: DoubleArray = DoubleArray(names.size)

    init {
        data.figures.forEachIndexed { index, figure ->
            var sx = 0.0
            var sy = 0.0
            var sz = 0.0
            val seen = HashSet<Int>()
            for (line in figure.lines) {
                for (star in line) {
                    if (!seen.add(star)) continue
                    val u = SkyProjection.equatorialVector(data.starRaDeg[star], data.starDecDeg[star])
                    sx += u[0]; sy += u[1]; sz += u[2]
                }
            }
            val n = kotlin.math.sqrt(sx * sx + sy * sy + sz * sz)
            // A figure whose stars cancel out has no centroid; the first star is a better answer
            // than a division by zero, and no real figure comes near it (the widest is ~60°).
            if (n < 1e-9) {
                val first = figure.lines.first().first()
                val u = SkyProjection.equatorialVector(data.starRaDeg[first], data.starDecDeg[first])
                nameX[index] = u[0]; nameY[index] = u[1]; nameZ[index] = u[2]
            } else {
                nameX[index] = sx / n; nameY[index] = sy / n; nameZ[index] = sz / n
            }
        }
    }

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
