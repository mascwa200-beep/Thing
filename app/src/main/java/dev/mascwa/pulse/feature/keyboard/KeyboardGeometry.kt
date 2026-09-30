package dev.mascwa.pulse.feature.keyboard

import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Key
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Slot
import kotlin.math.abs

/**
 * Where every key is, in pixels — worked out here rather than read back from the layout, so the keys
 * that are drawn, the key a touch lands on, and (for glide typing) the path a word traces across the
 * keys all come from one set of numbers.
 *
 * ## A key's box and a key's cell
 *
 * Each slot owns a CELL: its share of the row by weight, and its share of the gaps around it. The key
 * is DRAWN inset by half a gap on every side, but a touch anywhere in its cell is that key. So a thumb
 * that lands in the gap between two keys types the nearer one, never nothing — which is how every
 * phone keyboard behaves, and why a gap is never a dead zone.
 *
 * ## Nothing outside the keys is dead either
 *
 * A touch above the top row is the top row, below the bottom row is the bottom row, and past either
 * end of a row is the key at that end. The half-key gaps that indent the middle row belong to their
 * neighbours. A keyboard where a slightly-off touch does nothing feels broken; one where it types the
 * nearest key feels forgiving.
 */
object KeyboardGeometry {

    /** Every size in pixels. [padTop] and [padBottom] are the space above the first row and below the last. */
    data class Metrics(
        val keyHeight: Float,
        val rowGap: Float,
        val keyGap: Float,
        val padTop: Float,
        val padBottom: Float,
        val padSide: Float,
    )

    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val cx: Float get() = (left + right) / 2f
        val cy: Float get() = (top + bottom) / 2f
    }

    /** One key as drawn ([box]) and as touched ([cell]), on row [row] counting from the top. */
    data class Placed(val key: Key, val box: Rect, val cell: Rect, val row: Int)

    /** The height of [rowCount] rows of keys with their padding. */
    fun height(rowCount: Int, m: Metrics): Float =
        if (rowCount <= 0) m.padTop + m.padBottom
        else m.padTop + rowCount * m.keyHeight + (rowCount - 1) * m.rowGap + m.padBottom

    /** Every key of [rows] laid across [width] pixels. A [Key.Gap] takes its room and is not placed. */
    fun place(rows: List<List<Slot>>, width: Float, m: Metrics): List<Placed> {
        val unit = (width - 2 * m.padSide) / KeyboardModel.ROW_WEIGHT
        val half = m.keyGap / 2f
        return rows.flatMapIndexed { r, row ->
            val top = m.padTop + r * (m.keyHeight + m.rowGap)
            val bottom = top + m.keyHeight
            // A cell reaches halfway into the row gap on each side. Above the first row and below the
            // last there is no cell at all — keyAt gives those touches to the nearest row instead.
            val cellTop = top - m.rowGap / 2f
            val cellBottom = bottom + m.rowGap / 2f
            var x = m.padSide
            row.mapNotNull { slot ->
                val left = x
                x += slot.weight * unit
                if (slot.key == Key.Gap) {
                    null
                } else {
                    Placed(
                        key = slot.key,
                        box = Rect(left + half, top, x - half, bottom),
                        cell = Rect(left, cellTop, x, cellBottom),
                        row = r,
                    )
                }
            }
        }
    }

    /**
     * The key a touch at ([x], [y]) is on: the row whose cells cover [y] — the nearest row when it is
     * above or below them all — and in that row the key whose cell covers [x], or the nearest key when
     * none does (past an end, or in a half-key gap). Null only when there are no keys at all.
     */
    fun keyAt(placed: List<Placed>, x: Float, y: Float): Placed? {
        if (placed.isEmpty()) return null
        val row = placed.minBy { p ->
            when {
                y < p.cell.top -> p.cell.top - y
                y > p.cell.bottom -> y - p.cell.bottom
                else -> 0f
            }
        }.row
        val inRow = placed.filter { it.row == row }
        return inRow.firstOrNull { x >= it.cell.left && x < it.cell.right }
            ?: inRow.minBy { abs(it.box.cx - x) }
    }
}
