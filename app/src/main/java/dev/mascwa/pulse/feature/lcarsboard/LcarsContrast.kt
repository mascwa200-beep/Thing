package dev.mascwa.pulse.feature.lcarsboard

import kotlin.math.pow

/**
 * The console's "no text ever clashes" rule, as arithmetic a JVM test can hold.
 *
 * The lock console and the home screen draw text in two places only: on black panels, and on the
 * solid colour blocks that make LCARS look like LCARS. Both carry colours the app does not choose —
 * a calendar's own colour, a market cell's up or down, the palette under red alert — and a colour
 * that reads well on one ground can vanish on another. So every piece of text goes through here
 * rather than trusting that whoever picked the colour also checked it.
 *
 * The measure is WCAG 2's contrast ratio. [MIN_TEXT] is its bar for ordinary body text, and nothing
 * on either screen is drawn below it.
 *
 * Pure — ARGB ints in, ARGB ints out, no Android import — so it is tested rather than remembered.
 */
object LcarsContrast {

    /** WCAG AA for normal text. */
    const val MIN_TEXT = 4.5

    const val BLACK: Int = 0xFF000000.toInt()
    const val WHITE: Int = 0xFFFFFFFF.toInt()

    /** WCAG relative luminance of an sRGB colour, 0 (black) to 1 (white). Alpha is ignored. */
    fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb ushr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** WCAG contrast ratio between two colours: 1 (identical) to 21 (black on white). */
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * [fg] as it should be drawn on [bg]: unchanged when it already reads, otherwise moved toward
     * whichever of white or black contrasts more with [bg], only as far as it takes to reach [min].
     *
     * ⚠️ **Moved, not replaced.** A calendar coloured dark blue should still read as that calendar,
     * so the colour keeps its family and lightens just enough — the whole point of the calendar
     * colour is to tell two calendars apart, and snapping both to white would throw that away.
     *
     * ⚠️ **Alpha is forced opaque.** A provider colour with a zero alpha byte is a colour somebody
     * stored carelessly, not a request for invisible text.
     *
     * Always reaches [min] when [min] is at most [MIN_TEXT]: for ANY opaque ground, the better of
     * white and black clears 4.5 (the worst ground, a mid grey, still gives about 4.58).
     */
    fun legible(fg: Int, bg: Int, min: Double = MIN_TEXT): Int {
        val ground = opaque(bg)
        val start = opaque(fg)
        if (ratio(start, ground) >= min) return start
        val target = if (ratio(WHITE, ground) >= ratio(BLACK, ground)) WHITE else BLACK
        for (step in 1..STEPS) {
            val candidate = mix(start, target, step.toDouble() / STEPS)
            if (ratio(candidate, ground) >= min) return candidate
        }
        return target
    }

    /**
     * The text colour for a solid LCARS block: black or white, whichever contrasts more.
     *
     * LCARS letters its blocks in black, and every block in the ordinary palette is light enough for
     * that. The red-alert bank is not — it drains into dark reds — so this decides per block rather
     * than assuming black.
     */
    fun onBlock(block: Int): Int =
        if (ratio(BLACK, opaque(block)) >= ratio(WHITE, opaque(block))) BLACK else WHITE

    /** Linear blend of two opaque colours, channel by channel. */
    internal fun mix(a: Int, b: Int, t: Double): Int {
        fun ch(shift: Int): Int {
            val x = (a ushr shift) and 0xFF
            val y = (b ushr shift) and 0xFF
            return (x + (y - x) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun opaque(argb: Int): Int = argb or (0xFF shl 24)

    /** How finely [legible] walks toward the target: fine enough to stop just past [min]. */
    private const val STEPS = 64
}
