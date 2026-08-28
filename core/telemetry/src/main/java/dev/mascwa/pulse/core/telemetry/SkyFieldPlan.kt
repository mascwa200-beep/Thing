package dev.mascwa.pulse.core.telemetry

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Deciding what part of a three-million-star catalogue to hold in memory.
 *
 * The old map converted every star it had to horizon coordinates once and kept them all. At eight
 * thousand stars that is the right design and it is why panning was free. At three million it is
 * neither: the objects alone would be hundreds of megabytes, and the conversion is millions of
 * trigonometric calls before anything can be drawn.
 *
 * So the pipeline inverts. The view says which direction it is looking; that direction is turned
 * back into a right ascension and declination ([Ephemeris.toEquatorial]); [SkyGrid.tilesInCone] says
 * which few thousandths of the file could be in it; and only those stars are read and converted.
 *
 * ## ⚠️ Reading on every frame would be worse than reading everything
 *
 * A drag emits pointer events far faster than a catalogue can be read, so the answer is a **cached
 * region**: load generously around the view, and re-read only when the view leaves what is held.
 * That makes this file's whole job one decision — *does what we hold still cover what is on
 * screen?* — and it has a dangerous direction. Reading too often costs frames, which is visible and
 * annoying. Reading too rarely means **stars quietly missing** from part of the view, which looks
 * like a rendering fault or, worse, like a sky that is simply wrong. Everything below leans the
 * safe way.
 *
 * ## What makes a constant amount of work possible at any zoom
 *
 * [SkyProjection.magnitudeLimit] deepens as the field narrows, and star counts rise steeply with
 * magnitude while the field's area falls as the square of its angle. Those two nearly cancel, so the
 * number of stars in view stays in the low thousands from the widest field to the narrowest — which
 * is the single reason a catalogue this size and an ordinary canvas are compatible at all. That is a
 * claim about measured data rather than a hope, and `SkyFieldPlanTest` checks it against the real
 * counts in the bundled file.
 */
object SkyFieldPlan {

    /** What is currently held in memory, in equatorial coordinates. */
    data class Loaded(
        val centreRaDeg: Double,
        val centreDecDeg: Double,
        val radiusDeg: Double,
        /** The faintest magnitude actually read — see [quantiseLimit]. */
        val magnitudeLimit: Double,
    )

    /** What to do about the current view. */
    sealed interface Plan {
        /** What is held already covers the view; draw from it. */
        data object Reuse : Plan

        /** Read these tiles to this depth, and remember it as [becomes]. */
        data class Read(
            val tiles: IntArray,
            val magnitudeLimit: Double,
            val becomes: Loaded,
        ) : Plan {
            // ⚠️ IntArray has identity equals, so a data class holding one does too. Spelled out
            // rather than left to surprise somebody comparing two plans in a test.
            override fun equals(other: Any?): Boolean =
                other is Read &&
                    tiles.contentEquals(other.tiles) &&
                    magnitudeLimit == other.magnitudeLimit &&
                    becomes == other.becomes

            override fun hashCode(): Int =
                31 * (31 * tiles.contentHashCode() + magnitudeLimit.hashCode()) + becomes.hashCode()
        }
    }

    /**
     * The angle from the centre of the view to its furthest corner.
     *
     * ⚠️ **Measured through the projection rather than derived from the field of view, and the
     * difference is not small.** `fovDeg` describes the *short* screen dimension; a tall phone shows
     * a good deal more sky than that up and down, and the stereographic projection is not linear in
     * angle — at a wide field the corner is very much further out than the arithmetic would suggest.
     * Taking half the field as the radius would leave the corners of the screen unloaded, which is
     * exactly the silent failure this file exists to avoid.
     *
     * Capped at 180°, which is the whole sky and the point past which a cone stops meaning anything.
     */
    fun fieldRadiusDeg(view: SkyProjection.View, viewport: SkyProjection.Viewport): Double {
        var worst = 0.0
        // The four corners, plus the four edge midpoints. The corners are furthest for any ordinary
        // viewport, but sampling the edges too costs nothing and removes the need to prove it.
        val xs = doubleArrayOf(-viewport.halfWidth, viewport.halfWidth, 0.0, 0.0,
            -viewport.halfWidth, viewport.halfWidth, -viewport.halfWidth, viewport.halfWidth)
        val ys = doubleArrayOf(0.0, 0.0, -viewport.halfHeight, viewport.halfHeight,
            -viewport.halfHeight, -viewport.halfHeight, viewport.halfHeight, viewport.halfHeight)
        for (i in xs.indices) {
            val (az, alt) = SkyProjection.unproject(xs[i], ys[i], view)
            val d = SkyProjection.separationDeg(view.azimuthDeg, view.altitudeDeg, az, alt)
            if (d.isFinite() && d > worst) worst = d
        }
        return min(worst, 180.0)
    }

    /**
     * How much to load around the view.
     *
     * ⚠️ **Measured, and the measurement changed the reasoning behind it.** The obvious argument is
     * "load generously so a drag never needs a re-read", which points at a large factor. It does not
     * survive arithmetic: the margin a factor buys is `(factor − 1)` of the field radius, so even
     * doubling the region only allows a pan of one field before re-reading — and a single flick pans
     * further than that. Re-reading is therefore ROUTINE rather than exceptional whatever is chosen
     * here, which inverts the trade: what matters is that each read is cheap, not that reads are
     * rare. Hence a modest factor.
     *
     * Swept against the real star counts, at the worst zoom for it:
     *
     *     factor   held    margin
     *      2.0    63,336   1.0 field
     *      1.6    42,164   0.6 field
     *      1.4    32,854   0.4 field   <- shipped
     *      1.25   26,520   0.25 field
     *
     * 1.4 holds about two and a half times what is drawn, which is a working set of roughly eight
     * hundred kilobytes and thirty thousand coordinate conversions per read — small enough to do off
     * the main thread between frames.
     *
     * ⚠️ The other half of why any of this is affordable: at a wide field the magnitude limit is
     * shallow, so a wide region is a few thousand stars; at a narrow field the limit is deep but the
     * region is tiny. A wide region read to a deep limit is the expensive combination, and it is one
     * the design cannot produce.
     */
    fun readRadiusDeg(fieldRadiusDeg: Double): Double =
        min(fieldRadiusDeg * REGION_FACTOR + REGION_PAD_DEG, 180.0)

    /**
     * The depth to actually read at, rounded to a step.
     *
     * ⚠️ **Without this a pinch re-reads the catalogue on every frame.** [SkyProjection.magnitudeLimit]
     * is continuous in the field of view, so any zoom at all changes it and a naive "is the loaded
     * depth enough" test is false forever during a gesture. Rounding UP to a step means the loaded
     * set is always at least as deep as the view needs — never less, which would hide stars — and
     * changes only when the zoom has moved a real distance.
     */
    fun quantiseLimit(magnitudeLimit: Double): Double =
        ceil(magnitudeLimit / LIMIT_STEP) * LIMIT_STEP

    /**
     * Decide what the view needs.
     *
     * @param loaded what is held now, or null on the first frame.
     * @param deepest the faintest magnitude the catalogue actually holds, so a view zoomed past the
     *   end of the data asks for what exists rather than for what it would like.
     */
    fun plan(
        view: SkyProjection.View,
        viewport: SkyProjection.Viewport,
        centreRaDeg: Double,
        centreDecDeg: Double,
        loaded: Loaded?,
        deepest: Double,
    ): Plan {
        val field = fieldRadiusDeg(view, viewport)
        val want = quantiseLimit(min(SkyProjection.magnitudeLimit(view.fovDeg, deepest), deepest))

        if (loaded != null && loaded.magnitudeLimit >= want - EPSILON) {
            val drift = angularSeparationDeg(
                loaded.centreRaDeg, loaded.centreDecDeg, centreRaDeg, centreDecDeg,
            )
            // ⚠️ The whole visible field has to be inside what is held, not merely its centre.
            // Comparing centres alone is the mistake that leaves a crescent of empty sky at the edge
            // of the view — and it would look like a rendering fault rather than a loading one.
            if (drift + field <= loaded.radiusDeg) return Plan.Reuse
        }

        val radius = readRadiusDeg(field)
        return Plan.Read(
            tiles = SkyGrid.tilesInCone(centreRaDeg, centreDecDeg, radius),
            magnitudeLimit = want,
            becomes = Loaded(centreRaDeg, centreDecDeg, radius, want),
        )
    }

    /**
     * Roughly how many stars a region holds down to a magnitude, over the whole sky's average.
     *
     * ⚠️ **Measured off the bundled catalogue rather than taken from a textbook.** Its cumulative
     * counts are 6,514 brighter than magnitude 6 and 3,087,821 brighter than 12, which is a factor
     * of 2.80 per magnitude — not the 2.512 of a uniform infinite universe, because the Galaxy is a
     * disc and we are inside it. This is only ever used to reason about the shape of the design, so
     * an average over the sky is the right resolution; the real count in a given direction varies by
     * an order of magnitude between the galactic plane and the poles.
     */
    fun estimateStars(radiusDeg: Double, magnitudeLimit: Double): Double {
        val r = radiusDeg.coerceIn(0.0, 180.0)
        val skyFraction = (1.0 - cos(Math.toRadians(r))) / 2.0
        val whole = STARS_AT_MAGNITUDE_SIX * PER_MAGNITUDE.pow(magnitudeLimit - 6.0)
        return max(0.0, whole * skyFraction)
    }

    /** Great-circle separation, in the same shape [SkyProjection] uses for the horizon. */
    fun angularSeparationDeg(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        // ⚠️ Right ascension increases eastward where azimuth increases clockwise, but a separation
        // does not care which way round the sphere is labelled — it is the same chord either way.
        // Reusing the tested implementation beats a second copy of the haversine.
        return SkyProjection.separationDeg(ra1, dec1, ra2, dec2)
    }

    /** How much wider than the view to load. See [readRadiusDeg] for the sweep behind it. */
    const val REGION_FACTOR = 1.4

    /** Plus a fixed pad, so a very narrow field still loads a usable neighbourhood. */
    const val REGION_PAD_DEG = 0.5

    /**
     * The depth is rounded up to this. Half a magnitude is about a 1.6× change in the number of
     * stars, which is a real step and not a jitter.
     */
    const val LIMIT_STEP = 0.5

    /** Cumulative count over the whole sky brighter than magnitude 6, from the bundled catalogue. */
    const val STARS_AT_MAGNITUDE_SIX = 6_514.0

    /** How much the count grows per magnitude, measured over the same file across six magnitudes. */
    const val PER_MAGNITUDE = 2.80

    private const val EPSILON = 1e-9
}
