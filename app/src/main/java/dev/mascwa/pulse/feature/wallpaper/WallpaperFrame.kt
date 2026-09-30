package dev.mascwa.pulse.feature.wallpaper

import dev.mascwa.pulse.feature.lcarsboard.LcarsContrast

/**
 * The LCARS wallpaper, as geometry and colour: a frame of blocks around a black middle, for the
 * places the LCARS consoles cannot reach — Android's PIN pad, the app switcher, the notification
 * shade's scrim and the moment between two apps.
 *
 * Pure, so the two rules that matter are held by tests rather than by eye:
 *
 * - **Android's text stays legible over every block.** The wallpaper sits under text this app does
 *   not draw and cannot move — the lock-screen clock, the PIN pad's digits, the status bar — and
 *   all of it is white. So every block is dimmed ([dimmed]) until white on it clears WCAG's 4.5:1.
 *   That is what lets the frame run under any of it on any phone's layout, rather than guessing
 *   where each maker puts its clock.
 * - **The middle is black and empty.** The frame is a frame: nothing is drawn inside [inner], which
 *   is where the PIN pad, the notifications and the app cards go.
 *
 * Sizes are in pixels, computed from the screen's size and density so one layout serves a small
 * phone and a tablet.
 */
object WallpaperFrame {

    /** A solid block. [outerRadius] rounds its outside corner where it is an elbow's corner piece. */
    data class Block(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val argb: Int,
        val outer: Corner? = null,
        val outerRadius: Float = 0f,
    ) {
        fun overlaps(o: Block): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    }

    enum class Corner { TOP_START, BOTTOM_START }

    /**
     * The concave curve where an elbow's rail turns into its bar: a square of [argb] at ([x], [y])
     * with a quarter circle of [radius] cut out of its far corner. Drawn by the renderer; listed here
     * so its bounds are tested with everything else.
     */
    data class Fillet(val x: Float, val y: Float, val radius: Float, val argb: Int, val corner: Corner)

    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    data class Frame(val blocks: List<Block>, val fillets: List<Fillet>, val inner: Rect)

    /**
     * The brightest a block may be: white text on it still clears [LcarsContrast.MIN_TEXT].
     * (1.0 + 0.05) / (0.18 + 0.05) = 4.57.
     */
    const val MAX_BLOCK_LUMINANCE = 0.18

    /**
     * The console's own blocks, minus signal red — a red wallpaper under every screen of the phone
     * would read as an alarm that never stops.
     */
    val PALETTE: List<Int> = listOf(
        0xFFFFB000.toInt(), // command gold
        0xFF22B8E0.toInt(), // electric cyan
        0xFF8A5CE0.toInt(), // violet
        0xFFFF7A1A.toInt(), // orange
        0xFFE0409A.toInt(), // magenta
        0xFFFFC61A.toInt(), // amber
        0xFF24C04A.toInt(), // scanner green
    )

    /**
     * [argb] scaled toward black — every channel by the same factor, so it keeps its hue — until its
     * luminance is at most [MAX_BLOCK_LUMINANCE]. A colour already that dark is returned as it is.
     */
    fun dimmed(argb: Int): Int {
        if (LcarsContrast.luminance(argb) <= MAX_BLOCK_LUMINANCE) return argb or (0xFF shl 24)
        var lo = 0.0
        var hi = 1.0
        repeat(DIM_STEPS) {
            val mid = (lo + hi) / 2
            if (LcarsContrast.luminance(scale(argb, mid)) <= MAX_BLOCK_LUMINANCE) lo = mid else hi = mid
        }
        return scale(argb, lo)
    }

    /**
     * The frame for a [widthPx] × [heightPx] screen at [density] pixels per dp.
     *
     * An elbow top-left and bottom-left, joined by a segmented rail down the left edge, with a
     * segmented bar running right from each elbow. Nothing sits in the top [TOP_INSET_DP] or bottom
     * [BOTTOM_INSET_DP] — the status bar and the gesture bar — and nothing inside [Frame.inner].
     */
    fun layout(widthPx: Int, heightPx: Int, density: Float): Frame {
        val w = widthPx.toFloat()
        val h = heightPx.toFloat()
        val d = density.coerceAtLeast(0.5f)
        val top = TOP_INSET_DP * d
        val bottom = h - BOTTOM_INSET_DP * d
        val rail = (w * RAIL_SHARE).coerceIn(RAIL_MIN_DP * d, RAIL_MAX_DP * d)
        val bar = BAR_DP * d
        val gap = GAP_DP * d
        val elbowH = rail * ELBOW_ASPECT
        val fillet = bar
        val right = w - SIDE_MARGIN_DP * d

        val colors = PALETTE.map(::dimmed)
        fun color(i: Int) = colors[i % colors.size]

        val blocks = mutableListOf<Block>()
        val fillets = mutableListOf<Fillet>()

        // Top elbow: the corner piece, the bar running right, and the curve between them.
        blocks += Block(0f, top, rail, top + elbowH, color(0), Corner.TOP_START, rail)
        fillets += Fillet(rail, top + bar, fillet, color(0), Corner.TOP_START)
        barSegments(rail, right, top, top + bar, gap, TOP_BAR_WEIGHTS) { l, r, i ->
            blocks += Block(l, top, r, top + bar, if (i == 0) color(0) else color(i + 1))
        }

        // Bottom elbow, mirrored.
        blocks += Block(0f, bottom - elbowH, rail, bottom, color(3), Corner.BOTTOM_START, rail)
        fillets += Fillet(rail, bottom - bar - fillet, fillet, color(3), Corner.BOTTOM_START)
        barSegments(rail, right, bottom - bar, bottom, gap, BOTTOM_BAR_WEIGHTS) { l, r, i ->
            blocks += Block(l, bottom - bar, r, bottom, if (i == 0) color(3) else color(i + 4))
        }

        // The rail between them, in segments of differing heights.
        val railTop = top + elbowH + gap
        val railBottom = bottom - elbowH - gap
        if (railBottom > railTop) {
            val total = RAIL_WEIGHTS.sum()
            val usable = railBottom - railTop - gap * (RAIL_WEIGHTS.size - 1)
            var y = railTop
            RAIL_WEIGHTS.forEachIndexed { i, weight ->
                val next = if (i == RAIL_WEIGHTS.lastIndex) railBottom else y + usable * weight / total
                blocks += Block(0f, y, rail, next, color(i + 1))
                y = next + gap
            }
        }

        val inner = Rect(rail + gap + fillet, top + bar + gap + fillet, right, bottom - bar - gap - fillet)
        return Frame(blocks, fillets, inner)
    }

    /** Splits the span from [start] to [end] into segments by [weights], [gap] apart. */
    private inline fun barSegments(
        start: Float,
        end: Float,
        top: Float,
        bottom: Float,
        gap: Float,
        weights: List<Float>,
        each: (Float, Float, Int) -> Unit,
    ) {
        if (end <= start || bottom <= top) return
        val usable = end - start - gap * (weights.size - 1)
        if (usable <= 0f) return
        val total = weights.sum()
        var x = start
        weights.forEachIndexed { i, weight ->
            val next = if (i == weights.lastIndex) end else x + usable * weight / total
            each(x, next, i)
            x = next + gap
        }
    }

    private fun scale(argb: Int, f: Double): Int {
        fun ch(shift: Int): Int = (((argb ushr shift) and 0xFF) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** The status bar's height on any phone this app runs on, with room to spare. */
    const val TOP_INSET_DP = 56f

    /** The gesture bar's, likewise. */
    const val BOTTOM_INSET_DP = 56f

    const val RAIL_SHARE = 0.11f
    const val RAIL_MIN_DP = 36f
    const val RAIL_MAX_DP = 88f
    const val BAR_DP = 16f
    const val GAP_DP = 4f
    const val SIDE_MARGIN_DP = 12f

    /** An elbow's corner piece is this many rail-widths tall. */
    const val ELBOW_ASPECT = 1.6f

    private val TOP_BAR_WEIGHTS = listOf(5f, 2f, 1f)
    private val BOTTOM_BAR_WEIGHTS = listOf(3f, 1f, 4f)
    private val RAIL_WEIGHTS = listOf(3f, 1f, 2f, 5f, 1f, 3f)

    private const val DIM_STEPS = 24
}
