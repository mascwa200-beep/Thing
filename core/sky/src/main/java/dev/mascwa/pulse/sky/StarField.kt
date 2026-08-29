package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.Ephemeris
import dev.mascwa.pulse.core.telemetry.SkyFieldPlan
import dev.mascwa.pulse.core.telemetry.SkyProjection
import dev.mascwa.pulse.core.telemetry.StarCatalogReader
import dev.mascwa.pulse.core.telemetry.StarNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The deep catalogue's stars for the region the view can currently see.
 *
 * This is where [SkyFieldPlan]'s decision, [StarCatalogReader]'s decoding and the renderer's
 * [StarLayer] meet. Nothing here decides anything: the plan says what to load, the reader gets it,
 * and this converts it once into the form the draw pass wants.
 *
 * ## ⚠️ What is held is bigger than what is drawn, on purpose
 *
 * [SkyFieldPlan] loads a region wider than the view so an ordinary pan costs no reading. The draw
 * pass therefore sees stars behind it and beyond the screen edges, and drops them — which is far
 * cheaper than reloading. [StarLayer.count] is what is held; how many land on screen is the
 * renderer's answer.
 *
 * ## ⚠️ This is only half the sky
 *
 * The deep catalogue is blind to bright stars — see [StarLayer], where the measurement is. The
 * bright set is a second layer, always resident, drawn through the same renderer.
 *
 * Not thread-safe: one field belongs to one screen, and [update] is the only thing that writes.
 */
class StarField(private val reader: StarCatalogReader) {

    /** The stars, ready to project. Valid over `0 until layer.count`. */
    val layer = StarLayer(INITIAL)

    /**
     * The faintest magnitude this catalogue holds, so the RENDERER can cut where the data stops.
     *
     * ⚠️ **This exists because the drawing side was cutting somewhere else entirely, and the
     * symptom was the complaint that started this work.** [SkyProjection.magnitudeLimit] takes a
     * `deepest` that defaults to [SkyProjection.NAKED_EYE_LIMIT]; [update] passes the real one and
     * the screen was passing nothing. So the loader read three million stars down to magnitude 12
     * and the renderer threw away everything fainter than 6.5 — measured over the real catalogue, a
     * fifteen-degree field drew 123 stars where it should draw thousands, and zooming in made the
     * sky EMPTIER. Both sides have to agree on where the data stops, and this is where it is known.
     */
    val deepestMagnitude: Double get() = reader.deepestMagnitude

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
     * @param centreOverride where the middle of the screen really points, when the caller knows it
     *   more precisely than [view] can say. ⚠️ **Pointing mode has to pass this.** Its view comes
     *   from `SkyPointing.equivalentView`, whose altitude is clamped to
     *   [SkyProjection.MAX_ALTITUDE_DEG] — harmless for the magnitude cut and the field radius,
     *   which is all [view] is otherwise read for, but up to half a degree of error in the centre
     *   within half a degree of the zenith. At the quarter-degree field floor that is twice the
     *   whole view, so the cone read here would not contain what is drawn.
     */
    suspend fun update(
        view: SkyProjection.View,
        viewport: SkyProjection.Viewport,
        latitudeDeg: Double,
        longitudeDeg: Double,
        epochMs: Long,
        yearsFromEpoch: Double = 0.0,
        centreOverride: Ephemeris.Equatorial? = null,
    ): Outcome {
        // Which way the middle of the screen is pointing, in the catalogue's own coordinates.
        val centre = centreOverride ?: SkyFrame.centreOf(view, latitudeDeg, longitudeDeg, epochMs)

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
            // ⚠️ **Inside the block, and that is the whole reason it is written this way.** There is
            // no suspension point in this body, so once it starts it runs to completion; but
            // `withContext` itself checks for cancellation on the way out. Assigning these AFTER it
            // means a cancelled update leaves the arrays holding the NEW region while `loaded` still
            // describes the OLD one — and the next plan, comparing against a region the field no
            // longer holds, can answer Reuse and draw the wrong patch of sky. Nothing would throw.
            loaded = read.becomes
            forYearsFromEpoch = yearsFromEpoch
        }
        return Outcome.RELOADED
    }

    /** Forget everything, so the next [update] reloads. */
    fun clear() {
        layer.clear()
        loaded = null
        forYearsFromEpoch = Double.NaN
    }

    private fun fill(from: StarCatalogReader.Sink) {
        layer.ensure(from.count)
        for (i in 0 until from.count) {
            val u = SkyProjection.equatorialVector(
                from.rightAscensionDeg[i], from.declinationDeg[i],
            )
            layer.vx[i] = u[0]
            layer.vy[i] = u[1]
            layer.vz[i] = u[2]
            layer.magnitude[i] = from.magnitude[i]
            // ⚠️ Resolved HERE rather than per frame. It is a comparison chain against five edges,
            // and it is the same answer every frame for the life of a load.
            layer.colourBand[i] = StarNames.bandFromBpRp(from.colourBpRp[i].toDouble())
        }
        layer.published(from.count)
    }

    private companion object {
        /**
         * Sized for the widest zoom's held count rather than grown into it.
         *
         * ⚠️ Measured: the plan holds up to about sixty-three thousand stars at its busiest zoom, so
         * starting anywhere near zero means half a dozen reallocations on the very first load — and
         * the arrays being thrown away are about to be overwritten anyway, which is why
         * [StarLayer.ensure] does not copy them.
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
