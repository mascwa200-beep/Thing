package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.StarGlyph
import dev.mascwa.pulse.core.telemetry.StarNames

/**
 * Screen positions sorted into one bucket per (colour, size), so a whole sky is a few dozen calls.
 *
 * ## ⚠️ The counted form of drawPoints is what makes this work
 *
 * `android.graphics.Canvas.drawPoints(float[], offset, count, Paint)` takes a length, so a bucket's
 * buffer can be larger than what is in it. Compose's own `Canvas.drawRawPoints` does NOT — it draws
 * the entire array — so a partly-filled buffer would put every unused slot at the origin, which is
 * a pile of stars in the top-left corner of the screen that nothing would explain. Copying each
 * bucket to its exact length every frame would work and would allocate about forty kilobytes a
 * frame for nothing.
 *
 * ⚠️ **`count` in that call is a number of VALUES, not of points** — two floats per point — which is
 * why [forEachBucket] hands out `count * 2` rather than the star count.
 *
 * ## Sizing
 *
 * ⚠️ Seven colour slots, not six: the extra one is "no measured colour", which is about one star in
 * three hundred and is drawn in the surface's own ink rather than guessed at. Times eight sizes,
 * that is 56 buckets — an upper bound on the draw calls for any number of stars.
 *
 * Buffers start small and grow, and they are kept between frames. In practice the faint bands hold
 * nearly everything and the bright ones stay tiny, so the total is a few tens of kilobytes.
 */
class StarBatches {

    /** The colour slot used for a star with no measured colour. */
    val noColourSlot: Int get() = StarNames.COLOUR_BANDS

    private val buffers = Array(BUCKETS) { FloatArray(INITIAL) }
    private val counts = IntArray(BUCKETS)

    /** Empty every bucket, keeping the buffers. Called once per frame before filling. */
    fun reset() {
        counts.fill(0)
    }

    /**
     * File one star's screen position.
     *
     * @param colourBand [StarNames.NO_COLOUR_BAND] is accepted and lands in [noColourSlot]; anything
     *   else out of range is clamped rather than throwing, because this runs per star per frame and
     *   a crash there would take the whole screen down for one bad row.
     */
    fun add(colourBand: Int, sizeBand: Int, x: Float, y: Float) {
        val slot = if (colourBand in 0 until StarNames.COLOUR_BANDS) colourBand else noColourSlot
        val size = sizeBand.coerceIn(0, StarGlyph.SIZE_BANDS - 1)
        val bucket = slot * StarGlyph.SIZE_BANDS + size
        var buffer = buffers[bucket]
        val at = counts[bucket] * 2
        if (at + 2 > buffer.size) {
            buffer = buffer.copyOf(buffer.size * 2)
            buffers[bucket] = buffer
        }
        buffer[at] = x
        buffer[at + 1] = y
        counts[bucket]++
    }

    /**
     * Visit every non-empty bucket.
     *
     * @param action receives the colour slot, the size band, the shared buffer, and how many FLOATS
     *   of it are live — which is what the platform's counted `drawPoints` wants.
     */
    inline fun forEachBucket(action: (colourSlot: Int, sizeBand: Int, points: FloatArray, values: Int) -> Unit) {
        for (slot in 0..StarNames.COLOUR_BANDS) {
            for (size in 0 until StarGlyph.SIZE_BANDS) {
                val bucket = slot * StarGlyph.SIZE_BANDS + size
                val n = countOf(bucket)
                if (n > 0) action(slot, size, bufferOf(bucket), n * 2)
            }
        }
    }

    /**
     * How many stars were filed in total this frame.
     *
     * ⚠️ No caller yet — the sky map has no diagnostic readout to hang it on. Kept because it
     * costs nothing and the pointing-mode work will want it; the KDoc used to claim a readout
     * that does not exist, which is a smaller version of the same mistake.
     */
    val filed: Int get() = counts.sum()

    // ⚠️ Published only so `forEachBucket` can be inline, which is what keeps the visit free of a
    // lambda allocation on the frame path. Not part of the intended surface.
    @PublishedApi
    internal fun countOf(bucket: Int): Int = counts[bucket]

    @PublishedApi
    internal fun bufferOf(bucket: Int): FloatArray = buffers[bucket]

    private companion object {
        /** Seven colour slots — six measured plus "unmeasured" — times eight sizes. */
        val BUCKETS = (StarNames.COLOUR_BANDS + 1) * StarGlyph.SIZE_BANDS

        /** Small, because most buckets stay nearly empty and the busy ones double a few times. */
        const val INITIAL = 128
    }
}
