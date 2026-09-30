package dev.mascwa.pulse.feature.dream

/**
 * Where the screensaver sits this minute, so an OLED screen showing it for hours on a charger does not
 * burn the console's blocks into the glass.
 *
 * The whole picture shifts a few density-independent pixels every minute, around a small square path,
 * so that:
 *
 * - **No pixel holds the same colour for more than a minute** while the path moves — every edge of
 *   every block crosses [STEP_DP] of glass each step.
 * - **It never wanders far.** The offset stays within ±[MAX_DP] in both directions, so the picture never
 *   drifts off the screen or leaves a band of black along one side.
 * - **It averages to the centre.** Over one lap the offsets sum to zero, so the wear, such as it is,
 *   is spread evenly rather than piling up on one side.
 *
 * Pure — a minute count in, an offset out — so the rules are tested rather than watched overnight.
 */
object DreamDrift {

    const val STEP_DP = 2

    /** The furthest the picture ever moves from centre, in either direction. */
    const val MAX_DP = 8

    /** One lap: out along the top edge of the square, down, back, up. */
    val PATH: List<Pair<Int, Int>> = buildList {
        val r = MAX_DP
        var x = -r
        var y = -r
        while (x < r) { add(x to y); x += STEP_DP }
        while (y < r) { add(x to y); y += STEP_DP }
        while (x > -r) { add(x to y); x -= STEP_DP }
        while (y > -r) { add(x to y); y -= STEP_DP }
    }

    /** The offset in dp for [minute] minutes since the screensaver started. Negative minutes count as 0. */
    fun offsetAt(minute: Long): Pair<Int, Int> {
        val i = Math.floorMod(minute.coerceAtLeast(0L), PATH.size.toLong()).toInt()
        return PATH[i]
    }
}
