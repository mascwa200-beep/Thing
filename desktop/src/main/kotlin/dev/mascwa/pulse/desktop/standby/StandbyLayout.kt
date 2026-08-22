package dev.mascwa.pulse.desktop.standby

import kotlin.math.min
import kotlin.math.pow

/**
 * How much of the standby display fits, and how big it is drawn.
 *
 * ## The mistake this exists to fix
 *
 * ⚠️ The first version scaled everything **linearly** with the canvas width, so a 1280 px window
 * rendered the 460 px HUD layout at 2.8× — the same content, larger, with two of the five panels
 * clipped off the bottom and every title ellipsised. That is exactly backwards: **a bigger surface
 * should show MORE, not the same thing bigger.** It is the same rule the phone's widget follows,
 * where the row count comes first and the breakpoint height is derived from it, rather than a size
 * being picked and the content squeezed to suit.
 *
 * So scale grows **sub-linearly** ([SCALE_CURVE]) while the item counts grow with the room. A lock
 * screen at 2560×1440 gets type about twice the HUD's on a canvas five times the area, and spends
 * the difference on content.
 *
 * Pure and testable: no Compose, no rendering. The display reads it and lays out accordingly.
 */
data class StandbyLayout(
    /** Multiplier on every type size and gap. */
    val scale: Float,
    /** Insights below the headline one. */
    val insights: Int,
    val headlines: Int,
    val movers: Int,
    /** Lines in the STANDING panel — the ISS, space weather, the deck, what the radio is playing. */
    val standing: Int,
    val showWire: Boolean,
    val showMarkets: Boolean,
    val showStanding: Boolean,
    val showSparkline: Boolean,
    /** One column stacks; two puts the measured world beside what the Computer thinks. */
    val twoColumn: Boolean,
) {
    companion object {

        /**
         * The canvas the display was laid out at — the HUD panel.
         *
         * Everything else is this arrangement given more room, which is why there is no second
         * layout to keep in step.
         */
        const val REFERENCE_MIN_PX = 460f

        /**
         * ⚠️ Sub-linear on purpose, and the exponent is the whole point. At 1.0 a 4K wallpaper
         * would draw a clock a third of the screen tall and fit three panels; at 0.0 it would draw
         * HUD-sized type across two metres of monitor. 0.55 lands about 2× type on a 5× canvas.
         */
        const val SCALE_CURVE = 0.55

        const val MIN_SCALE = 0.75f
        const val MAX_SCALE = 3.2f

        /** Panel rows the right-hand column holds per reference canvas height. Measured, not chosen. */
        const val ROWS_PER_UNIT = 11f

        /** What the weather panel costs before either list gets a line: header, big temperature,
         *  the detail line and the trace. */
        const val WEATHER_ROWS = 6

        /** Narrower than this and two columns cannot each hold a headline. */
        const val TWO_COLUMN_MIN = 1.25f

        /**
         * How much more height the same content needs when it stacks instead of sitting side by side.
         *
         * ⚠️ **Measured off a real render, not reasoned about.** At 2.0 the 460×560 HUD emitted a
         * STANDING panel that had room for its header and neither of its two lines — a panel drawn
         * as an empty box, which is worse than the panel being absent because it reads as a feed
         * that answered with nothing. Rendering it and looking is the only way that shows up; no
         * assertion about the arithmetic would have.
         */
        const val STACK_COST = 2.4f

        /**
         * ⚠️ Keyed on the SMALLER side, not the width. A standby display spanning two monitors is
         * enormously wide and no taller than one screen; scaling from width would make the type
         * unreadably large for the height it has to fit in.
         */
        fun forCanvas(widthPx: Int, heightPx: Int): StandbyLayout {
            val w = widthPx.coerceAtLeast(1)
            val h = heightPx.coerceAtLeast(1)
            val scale = ((min(w, h) / REFERENCE_MIN_PX).toDouble().pow(SCALE_CURVE))
                .toFloat().coerceIn(MIN_SCALE, MAX_SCALE)

            // How much room there is once the type is that big, expressed in reference units so the
            // thresholds below read as "how many HUDs would fit".
            val rawH = h / (scale * REFERENCE_MIN_PX)
            val roomW = w / (scale * REFERENCE_MIN_PX)

            // Below this the two columns are each too narrow to hold a headline, so stacking reads
            // better than ellipsising both.
            val twoColumn = roomW >= TWO_COLUMN_MIN

            // ⚠️ **Stacking costs height, and forgetting that clipped the HUD.** Every threshold
            // below was calibrated against the two-column arrangement, where the measured world sits
            // BESIDE what the Computer thinks. In one column the same panels queue up instead, so
            // the vertical demand roughly doubles — and a 460×560 HUD claiming room for five panels
            // fitted two and a sliver of a third. The room a stacked layout actually has is half
            // what it looks like.
            val roomH = if (twoColumn) rawH else rawH / STACK_COST

            // ⚠️ The right-hand panels are budgeted TOGETHER, not each on its own guess.
            //
            // Conditions, Markets and Standing share one column, and a Column clips whatever
            // overflows it **silently** — so three independent thresholds that each look reasonable
            // still add up to one line falling off the bottom of the last panel, which is
            // indistinguishable from a feed that had nothing to say. One budget, split, is the only
            // arrangement where the arithmetic can be checked at all.
            //
            // The numbers are measured rather than chosen: a rendered panel row is about a
            // fifteenth of the reference canvas, and the weather panel's headline block costs
            // [WEATHER_ROWS] of them before either list gets a line.
            val rightRows = (roomH * ROWS_PER_UNIT).toInt()
            val spare = (rightRows - WEATHER_ROWS).coerceAtLeast(4)
            val moverRows = (spare * 6 / 10).coerceIn(2, 6)

            return StandbyLayout(
                scale = scale,
                insights = when {
                    roomH >= 1.5f -> 5
                    roomH >= 1.1f -> 3
                    roomH >= 0.8f -> 2
                    else -> 1
                },
                headlines = when {
                    roomH >= 1.5f -> 6
                    roomH >= 1.1f -> 4
                    else -> 2
                },
                movers = moverRows,
                standing = (spare - moverRows).coerceIn(2, 6),
                // The order things are given up in, tightest first. The Oracle and the clock are
                // never on this list: they are what the display is for.
                showWire = roomH >= 0.95f,
                showMarkets = roomH >= 0.75f,
                showStanding = roomH >= 0.6f,
                showSparkline = roomH >= 0.85f,
                twoColumn = twoColumn,
            )
        }
    }
}
