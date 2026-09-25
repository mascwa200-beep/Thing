package dev.mascwa.pulse.sky

/**
 * Which star a fingertip landed on — restricted to the stars the chart is actually DRAWING.
 *
 * ## The defect this exists to remove
 *
 * `identify` used to search the whole layer within the tap tolerance and hand back the brightest
 * hit, and the layer is far deeper than the screen: a 15° field draws stars to about magnitude 8.5
 * from a file that goes to 15, and at midday with the atmosphere on the chart draws nothing fainter
 * than Venus. So a tap on empty sky could name an eleventh-magnitude star nobody could see, and the
 * card would describe, in confident detail, a dot that was not there. The rule for what is drawn
 * is `collectStars`' pair of cuts — the catalogue magnitude against the limit, then the magnitude
 * the air leaves against the same limit — and this applies exactly those two, in that order, to
 * the candidates within reach of the finger.
 *
 * ## Why it is a file of its own
 *
 * `identify` lives in the view model, which is Android-bound; this touches nothing but the layer,
 * the frame and arithmetic, so it runs under plain JUnit and CI holds the rule. The alternative — the
 * rule as a private function beside the tap — is what shipped, and it was wrong for two slices with
 * every test green, because no test could reach it.
 *
 * ⚠️ **Brighter wins, and "brighter" is the DRAWN magnitude, not the catalogue one.** Two stars
 * within a fingertip are both what was meant; the answer is the one that can actually be seen, and
 * at the horizon the air can reorder them. The card still reports the catalogue magnitude, which is
 * the star's, not the air's.
 */
object StarHitTest {

    /**
     * The brightest DRAWN star of [layer] within [cosTolerance] of [target], or null.
     *
     * @param target the touched direction in the layer's own frame — J2000 equatorial, aberration
     *   already taken back off, as `identify` prepares it.
     * @param cosTolerance the cosine of the tap tolerance, compared against a dot product so no
     *   angle is ever recovered from a cosine near one.
     * @param frame the frame the chart drew with; supplies the altitude sine and, with the
     *   atmosphere on, what the air takes at it. With the atmosphere off `extinctionMag` is 0.0 and
     *   the second cut is the first one repeated, which is how a chart with the switch off answers
     *   exactly as it always did.
     * @param limit the magnitude the chart is cutting at — the SAME number `collectStars` was given
     *   for this frame, derived once in the view model so the tap and the draw cannot disagree.
     */
    fun nearestDrawnStar(
        layer: StarLayer,
        target: DoubleArray,
        cosTolerance: Double,
        frame: SkyFrame,
        limit: Double,
    ): Int? {
        var best = -1
        var bestDrawn = Float.MAX_VALUE
        for (i in 0 until layer.count) {
            val m0 = layer.magnitude[i]
            // The catalogue magnitude first, as the draw pass does: the air only ever dims, so a
            // star past the cut here cannot come back through it — and a star no brighter than the
            // best so far cannot win, since its drawn magnitude is at least its catalogue one.
            // ⚠️ An EARLY-OUT, not the rule: the cut below subsumes it (m ≥ m0), which is why
            // deleting this line alone leaves every test green — measured, and the negative test
            // removes both cuts for that reason. What this line buys is the dot product it skips.
            if (m0 > limit || m0 >= bestDrawn) continue
            val x = layer.vx[i]
            val y = layer.vy[i]
            val z = layer.vz[i]
            val dot = target[0] * x + target[1] * y + target[2] * z
            if (dot < cosTolerance) continue
            val m = (m0 + frame.extinctionMag(frame.sinAltitude(x, y, z))).toFloat()
            if (m > limit || m >= bestDrawn) continue
            bestDrawn = m
            best = i
        }
        return if (best >= 0) best else null
    }
}
