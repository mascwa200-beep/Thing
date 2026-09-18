package dev.mascwa.pulse.core.telemetry

/**
 * How big to draw a star, and which of the handful get more than a dot.
 *
 * ## ⚠️ Why this answers a BAND rather than a radius
 *
 * A million stars cannot be drawn one at a time. They are drawn in groups — every star of one
 * colour and one size in a single call — and a group is an array index, so the size has to be a
 * small integer before anything else can happen. Answering an exact radius per star would be more
 * precise and would put the renderer back to a draw call apiece, which is the whole problem.
 *
 * ⚠️ **The quantisation is invisible, and that is measured rather than assumed.** Adjacent bands
 * differ by [RADIUS_STEP_DP], which is half a density-independent pixel — one and a half real pixels
 * on an ordinary phone. So the error introduced by rounding a star into a band is at most three
 * quarters of a pixel on its radius, on an object drawn as a small disc, against a magnitude scale
 * nobody can read off a screen anyway.
 *
 * ## The law, and why it is relative rather than absolute
 *
 * ⚠️ **Size is measured DOWN from the current cut-off, not from a fixed zero point.** [SkyProjection
 * .magnitudeLimit] deepens as the field narrows, so at a wide field the brightest thing on screen is
 * Sirius and at a very narrow one it might be a tenth-magnitude star nobody has ever looked at. Both
 * should be the biggest thing in their own view: an absolute law would draw a deep field as a flat
 * wash of identical specks, which is exactly the "too sparse, no structure" complaint that started
 * this work. Brightness is relative because the eye's response is.
 */
object StarGlyph {

    /**
     * How many sizes a star can be drawn at.
     *
     * Eight, because the drawn range runs from a speck to about four and a half density-independent
     * pixels and there is no visible difference to be had past that. It also bounds the renderer's
     * bucket count: eight sizes times [StarNames.COLOUR_BANDS] colours is a few dozen draw calls
     * however many stars are on screen.
     */
    const val SIZE_BANDS = 8

    /** The faintest thing drawn: a speck, but a visible one on every density. */
    const val MIN_RADIUS_DP = 0.55f

    /** One band to the next. Half a dp is a pixel and a half on an ordinary phone. */
    const val RADIUS_STEP_DP = 0.50f

    /** How many magnitudes brighter than the cut-off one band is worth. */
    const val MAGNITUDES_PER_BAND = 1.0

    /**
     * Which size a star of this magnitude is drawn at, under this cut-off.
     *
     * A star at the very limit is band 0; each magnitude brighter is one band up, to the ceiling.
     * A star FAINTER than the limit answers band 0 rather than throwing — the renderer drops it on
     * the magnitude test, and a defensive answer here is better than a crash on a rounding edge.
     */
    fun sizeBand(magnitude: Double, limit: Double): Int {
        if (magnitude.isNaN() || limit.isNaN()) return 0
        val steps = (limit - magnitude) / MAGNITUDES_PER_BAND
        if (steps <= 0.0) return 0
        return steps.toInt().coerceAtMost(SIZE_BANDS - 1)
    }

    /** The radius of a band, in density-independent pixels. The renderer scales by its own density. */
    fun bandRadiusDp(band: Int): Float =
        MIN_RADIUS_DP + RADIUS_STEP_DP * band.coerceIn(0, SIZE_BANDS - 1)

    /**
     * How much brighter than the cut-off a star has to be before it earns a halo.
     *
     * ⚠️ **Measured, not chosen.** A halo is drawn one star at a time, so its cost is the count, and
     * the count was swept over the real catalogue across twenty-four views at four latitudes and the
     * whole zoom range. Star counts rise about 2.8-fold per magnitude, so every half magnitude of
     * headroom roughly halves the work:
     *
     * | headroom | widest field | busiest field |
     * |---|---|---|
     * | 4.0 | 35 | **191** |
     * | 4.5 | 13 | 111 |
     * | **5.0** | **8** | **64** |
     * | 5.5 | 6 | 34 |
     *
     * Five is where the two ends are both right: about eight haloes at the widest field, which is
     * very nearly the list of stars an ordinary person can name, and sixty at the busiest, which is
     * a per-frame cost a phone will not notice. Four was the first guess and it put a hundred and
     * ninety haloes on a fifteen-degree field, which is a smear rather than a sky.
     */
    const val GLOW_HEADROOM = 5.0

    /** True for the handful in view bright enough to be drawn as more than a disc. */
    fun glows(magnitude: Double, limit: Double): Boolean =
        !magnitude.isNaN() && limit - magnitude >= GLOW_HEADROOM

    /**
     * How much brighter than the cut-off a star has to be before its name is worth the room.
     *
     * Stricter than [GLOW_HEADROOM] on purpose: a halo costs a few pixels and a label costs a word,
     * and a chart with fifty words on it is unreadable in exactly the way a chart with fifty haloes
     * is not. Swept the same way: at 4.5 the busiest field carries about forty-eight names, which is
     * a page of text laid over a star chart; at 5.5 it is about seventeen, and the widest field
     * still names half a dozen.
     *
     * ⚠️ It is also only half the rule — the caller applies it to stars that actually HAVE a name,
     * and the deep catalogue has none at all, so this can never label a numbered star nobody has
     * ever called anything. That is why the counts above are far below the glow counts at the same
     * headroom: past a few degrees almost everything on screen comes from the deep set.
     */
    const val LABEL_HEADROOM = 5.5

    /** True when there is room to draw this star's name beside it, if it has one. */
    fun labels(magnitude: Double, limit: Double): Boolean =
        !magnitude.isNaN() && limit - magnitude >= LABEL_HEADROOM
}
