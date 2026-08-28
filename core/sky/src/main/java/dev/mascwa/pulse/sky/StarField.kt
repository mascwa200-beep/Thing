package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.SkyFieldPlan
import dev.mascwa.pulse.core.telemetry.SkyProjection
import dev.mascwa.pulse.core.telemetry.StarCatalogReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The stars the view can currently see, held as unit vectors ready to draw.
 *
 * This is where [SkyFieldPlan]'s decision, [StarCatalogReader]'s decoding and
 * [SkyProjection.projectUnit]'s arithmetic meet. Nothing here decides anything: the plan says what
 * to load, the reader gets it, and this converts it once into the form the draw pass wants.
 *
 * ## ⚠️ Why unit vectors and not the two angles
 *
 * Projecting a star from an azimuth and an altitude costs four trigonometric calls; projecting it
 * from a unit vector costs none. Measured over twelve thousand stars — which is what the widest zoom
 * draws — that is the difference between 1.35 ms and 0.05 ms a frame on this machine, and a weak
 * phone is four to six times slower again. The conversion happens once per load, where it is
 * affordable, rather than sixty times a second, where it is not.
 *
 * ## ⚠️ What is held is bigger than what is drawn, on purpose
 *
 * [SkyFieldPlan] loads a region wider than the view so an ordinary pan costs no reading. The draw
 * pass therefore sees stars behind it and beyond the screen edges, and drops them — which is far
 * cheaper than reloading. [count] is what is held; how many land on screen is the renderer's answer.
 *
 * Not thread-safe: one field belongs to one screen, and [update] is the only thing that writes.
 */
class StarField(private val reader: StarCatalogReader) {

    /** How many stars are currently held. The arrays below are valid over `0 until count`. */
    var count: Int = 0
        private set

    /**
     * EQUATORIAL unit vectors, x/y/z apart so the draw pass reads three primitive arrays.
     *
     * ⚠️ Equatorial and not horizon, which is the decision the whole class turns on — see [SkyFrame].
     * A horizon position changes continuously as the Earth turns, so a held set would go stale in
     * seconds and, at a narrow field, inside one frame. An equatorial position does not move at all
     * except by proper motion, which is a matter of decades.
     */
    var vx: DoubleArray = DoubleArray(INITIAL)
        private set
    var vy: DoubleArray = DoubleArray(INITIAL)
        private set
    var vz: DoubleArray = DoubleArray(INITIAL)
        private set

    /** Gaia G, for the size and the magnitude cut the renderer applies per frame. */
    var magnitude: FloatArray = FloatArray(INITIAL)
        private set

    /** Gaia bp_rp, or NaN where none was measured — about one star in three hundred. */
    var colour: FloatArray = FloatArray(INITIAL)
        private set

    /** What the last load covered, so the plan can tell whether it still does. */
    var loaded: SkyFieldPlan.Loaded? = null
        private set

    /**
     * How far the held positions were carried by proper motion, so a big jump in the drawn date
     * reloads.
     *
     * ⚠️ This is the ONLY way time invalidates the field, and it is the honest one: equatorial
     * positions are fixed, and the single thing that genuinely moves a star is its own motion
     * through the Galaxy. Even Barnard's Star, the fastest known, covers ten arcseconds a year — so
     * a tenth of a year is a fraction of an arcsecond on the very worst star in the sky, which is
     * far below anything a chart draws.
     */
    private var forYearsFromEpoch: Double = Double.NaN

    private val sink = StarCatalogReader.Sink(INITIAL)

    /** What [update] did, so a caller can tell a busy frame from an idle one. */
    enum class Outcome {
        /** Everything the view needs was already held. */
        UNCHANGED,

        /** The catalogue was read and the field rebuilt. */
        RELOADED,
    }

    /**
     * Bring the field up to date for this view, place and instant.
     *
     * ⚠️ **Reads and converts on [Dispatchers.Default], because both are far too slow for a frame.**
     * A reload decodes and transforms tens of thousands of stars; the caller keeps drawing what it
     * already holds until this returns, which is why the loaded region is generous — a fast pan
     * would otherwise show the edge of the held region as a boundary where stars stop.
     *
     * @param yearsFromEpoch how far to carry each star by its own proper motion, from the
     *   catalogue's epoch to the date being drawn.
     */
    suspend fun update(
        view: SkyProjection.View,
        viewport: SkyProjection.Viewport,
        latitudeDeg: Double,
        longitudeDeg: Double,
        epochMs: Long,
        yearsFromEpoch: Double = 0.0,
    ): Outcome {
        // Which way the middle of the screen is pointing, in the catalogue's own coordinates.
        val centre = SkyFrame.centreOf(view, latitudeDeg, longitudeDeg, epochMs)

        // ⚠️ Neither the clock nor the observer's position invalidates a load, and that is the point
        // of holding equatorial vectors: the region the plan reasons about is in the same frame the
        // stars are stored in, where nothing moves. Scrubbing the time by six hours or flying to the
        // other side of the world changes where the view POINTS, which the plan sees as drift and
        // handles already.
        val sameEpoch = forYearsFromEpoch.isFinite() &&
            kotlin.math.abs(yearsFromEpoch - forYearsFromEpoch) < PROPER_MOTION_TOLERANCE_YEARS

        val plan = SkyFieldPlan.plan(
            view = view,
            viewport = viewport,
            centreRaDeg = centre.rightAscensionDeg,
            centreDecDeg = centre.declinationDeg,
            loaded = if (sameEpoch) loaded else null,
            deepest = reader.deepestMagnitude,
        )
        if (plan is SkyFieldPlan.Plan.Reuse) return Outcome.UNCHANGED

        val read = plan as SkyFieldPlan.Plan.Read
        withContext(Dispatchers.Default) {
            sink.clear()
            reader.read(read.tiles, read.magnitudeLimit, sink, yearsFromEpoch)
            fill(sink)
        }
        loaded = read.becomes
        forYearsFromEpoch = yearsFromEpoch
        return Outcome.RELOADED
    }

    /** Forget everything, so the next [update] reloads. */
    fun clear() {
        count = 0
        loaded = null
        forYearsFromEpoch = Double.NaN
    }

    private fun fill(from: StarCatalogReader.Sink) {
        grow(from.count)
        for (i in 0 until from.count) {
            val u = SkyProjection.equatorialVector(
                from.rightAscensionDeg[i], from.declinationDeg[i],
            )
            vx[i] = u[0]
            vy[i] = u[1]
            vz[i] = u[2]
            magnitude[i] = from.magnitude[i]
            colour[i] = from.colourBpRp[i]
        }
        count = from.count
    }

    private fun grow(needed: Int) {
        if (needed <= vx.size) return
        var size = vx.size
        while (size < needed) size *= 2
        vx = DoubleArray(size)
        vy = DoubleArray(size)
        vz = DoubleArray(size)
        magnitude = FloatArray(size)
        colour = FloatArray(size)
    }

    private companion object {
        /**
         * Sized for the widest zoom's held count rather than grown into it.
         *
         * ⚠️ Measured: the plan holds up to about thirty-three thousand stars at its worst zoom, so
         * starting anywhere near zero means half a dozen reallocations and copies on the very first
         * load — and the copies are of arrays that are about to be overwritten anyway. `grow` throws
         * the old ones away rather than copying for exactly that reason: unlike the reader's sink,
         * nothing here accumulates across calls.
         */
        const val INITIAL = 16_384

        /**
         * How far the drawn date may move before the held positions are carried again.
         *
         * A tenth of a year. The fastest-moving star known covers ten arcseconds a year, so this is
         * a fraction of an arcsecond on the worst case in the sky and imperceptible on everything
         * else — while still meaning that scrubbing the chart to a different century reloads.
         */
        const val PROPER_MOTION_TOLERANCE_YEARS = 0.1
    }
}
