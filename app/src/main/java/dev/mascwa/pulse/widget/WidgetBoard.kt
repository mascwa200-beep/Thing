package dev.mascwa.pulse.widget

import android.app.PendingIntent
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.R

/**
 * The widget's tall form: a situation board rather than a list of lines.
 *
 * ## Why this exists at all
 *
 * ⚠️ `lock_widget_info.xml` allows `maxResizeHeight="640dp"` while the largest `SizeF` breakpoint
 * was **300×385**. Above 385dp the host had no richer variant to pick, so it stretched the same
 * twenty rows — which is exactly what "it is still very sparse" describes. The gap between those
 * two numbers was the defect; this is what fills it.
 *
 * ## What it is not
 *
 * It is **not** a replacement for [R.layout.widget_lock]. That layout and its root id are identity
 * — renaming either orphans every instance already placed on a home screen — so it keeps serving
 * the four small sizes byte-for-byte as it does today, and only the two new tall breakpoints draw
 * the board. A fault here cannot reach a widget somebody has already placed at a normal size.
 *
 * ## How it draws
 *
 * Every region is pre-declared in the XML and defaults to `GONE`; rendering is `setViewVisibility`
 * plus `setTextViewText` and nothing else in this slice. There is no `addView`, which matters for a
 * reason worth stating: `RemoteViews` caps NESTED RemoteViews objects at ten
 * (`MAX_NESTED_VIEWS`, verified against the platform, and it is enforced in `initializeFrom` — it
 * does **not** limit XML depth), and every nested object is parcelled in full. A pre-declared
 * skeleton costs a few kilobytes of actions instead.
 *
 * A region with nothing to say stays hidden, and the rule above it hides with it, so a feed that
 * failed costs its own block and leaves no gap where it used to be.
 */
internal object WidgetBoard {

    /**
     * One instrument in a strip: what it is, what it did, and how it got there.
     *
     * ⚠️ [chart] is a BUILT bitmap rather than the series it came from, and that is load-bearing.
     * The two size variants share one `BitmapCache` which de-duplicates on identity, so the same
     * instance reaching both costs one copy — but only if it IS the same instance. Drawing inside
     * the render pass would make two and pay twice. See [WidgetCharts].
     */
    internal data class Cell(
        val label: String,
        val value: String,
        val colorRes: Int,
        val series: List<Double> = emptyList(),
        val chart: android.graphics.Bitmap? = null,
    )

    /** One side of a two-column region. */
    internal data class Column(val label: String, val lines: List<String>)

    /**
     * Everything the board can draw. Every field is optional because every field is a feed that can
     * fail, and the board's whole contract is that a missing one costs its own block.
     */
    internal data class Board(
        val header: String,
        /**
         * Whether the header clock reads 24-hour. Follows [dev.mascwa.pulse.data.settings.AppSettings]
         * `use24HourClock`, NOT the system setting — see [render] for why that distinction is the
         * whole reason this field exists.
         */
        val clock24: Boolean = true,
        val subhead: String = "",
        val alert: String? = null,
        val alertRoute: String? = null,
        val lead: String? = null,
        val leadDetail: String? = null,
        val leadArgb: Int? = null,
        val leadRoute: String? = null,
        val sources: List<String> = emptyList(),
        val indices: List<Cell> = emptyList(),
        val indicesLabel: String = "",
        val stocks: List<Cell> = emptyList(),
        val stocksLabel: String = "",
        val breadth: String? = null,
        val breadthPct: Int? = null,
        val readouts: List<String> = emptyList(),
        val pairs: List<Pair<Column, Column>> = emptyList(),
        val foot: String? = null,
    )

    /**
     * Build the board.
     *
     * [full] is the difference between the two tall breakpoints: the compact one stops after the
     * readouts, the full one carries the two-column regions and the footer as well. Both draw the
     * same resource — a size shows *more*, never the same content with more air around it.
     */
    fun render(
        context: Context,
        board: Board,
        full: Boolean,
        intent: (route: String, requestCode: Int) -> PendingIntent,
    ): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_board)

        v.setTextViewText(R.id.widget_board_header, board.header)

        // ⚠️ BOTH formats are set to the SAME pattern, and that is not redundancy — it is the only
        // way to honour the app's own preference. A TextClock picks `format24Hour` when the DEVICE
        // is in 24-hour mode and `format12Hour` otherwise, so setting just one leaves the device
        // deciding. Writing both makes the choice ours, which matters because `use24HourClock` is a
        // switch the user can already flip in Settings and this is its only reader.
        //
        // ⚠️ `setCharSequence` reaches a view method only if it is @RemotableViewMethod, and a miss
        // throws ActionException ON THE DEVICE while compiling perfectly — nothing here would catch
        // it. Read out of the platform: TextClock has exactly three remotable methods, and
        // setFormat12Hour(CharSequence) and setFormat24Hour(CharSequence) are two of them.
        val clockPattern = if (board.clock24) CLOCK_24 else CLOCK_12
        v.setCharSequence(R.id.widget_board_clock, "setFormat12Hour", clockPattern)
        v.setCharSequence(R.id.widget_board_clock, "setFormat24Hour", clockPattern)

        text(v, R.id.widget_board_subhead, board.subhead)

        // ── what is happening ───────────────────────────────────────────────────────────────────
        val alertShown = text(v, R.id.widget_board_alert, board.alert)
        if (alertShown) {
            v.setTextColor(R.id.widget_board_alert, ContextCompat.getColor(context, R.color.nw_negative))
            board.alertRoute?.let { v.setOnClickPendingIntent(R.id.widget_board_alert, intent(it, RC_ALERT)) }
        }
        val leadShown = text(v, R.id.widget_board_lead, board.lead)
        if (leadShown) {
            board.leadArgb?.let { v.setTextColor(R.id.widget_board_lead, it) }
            board.leadRoute?.let { v.setOnClickPendingIntent(R.id.widget_board_lead, intent(it, RC_LEAD)) }
        }
        val detailShown = text(v, R.id.widget_board_lead_detail, board.leadDetail)
        var sourcesShown = false
        SOURCE_IDS.forEachIndexed { i, id ->
            if (text(v, id, board.sources.getOrNull(i))) sourcesShown = true
        }
        val topShown = alertShown || leadShown || detailShown || sourcesShown

        // ── the instruments ─────────────────────────────────────────────────────────────────────
        val idxShown = strip(
            context, v, board.indices, board.indicesLabel,
            R.id.widget_board_idx_strip, R.id.widget_board_sec_idx, INDEX_CELLS,
        )
        if (idxShown) v.setOnClickPendingIntent(R.id.widget_board_sec_idx, intent(MARKETS_ROUTE, RC_INDICES))
        val stkShown = strip(
            context, v, board.stocks, board.stocksLabel,
            R.id.widget_board_stk_strip, R.id.widget_board_sec_stk, STOCK_CELLS,
        )
        if (stkShown) v.setOnClickPendingIntent(R.id.widget_board_sec_stk, intent(MARKETS_ROUTE, RC_STOCKS))

        // ── breadth ─────────────────────────────────────────────────────────────────────────────
        val breadthShown = text(v, R.id.widget_board_breadth, board.breadth)
        val pct = board.breadthPct
        if (pct != null) {
            v.setViewVisibility(R.id.widget_board_meter, View.VISIBLE)
            v.setProgressBar(R.id.widget_board_meter, 100, pct.coerceIn(0, 100), false)
            // ⚠️ Tinted from a colour RESOURCE, not an int. `WidgetLinkageTest` fails the build on
            // any raw hex in a widget file, and the three-argument overload takes a resource id —
            // so the palette rule holds here for free rather than by remembering it.
            v.setColorStateList(
                R.id.widget_board_meter, "setProgressTintList",
                if (pct >= 50) R.color.nw_positive else R.color.nw_negative,
            )
            v.setColorStateList(R.id.widget_board_meter, "setProgressBackgroundTintList", R.color.nw_line)
        }
        val meterShown = breadthShown || pct != null
        if (meterShown) v.setOnClickPendingIntent(R.id.widget_board_breadth, intent(MARKETS_ROUTE, RC_BREADTH))

        // ── plain readouts ──────────────────────────────────────────────────────────────────────
        var readShown = false
        READ_IDS.forEachIndexed { i, id ->
            if (text(v, id, board.readouts.getOrNull(i))) readShown = true
        }

        // ── the two-column regions, full board only ─────────────────────────────────────────────
        var pairShown = false
        PAIR_SPECS.forEachIndexed { i, spec ->
            val p = if (full) board.pairs.getOrNull(i) else null
            val shown = column(v, spec.leftLabel, spec.leftLines, p?.first) or
                column(v, spec.rightLabel, spec.rightLines, p?.second)
            v.setViewVisibility(spec.container, if (shown) View.VISIBLE else View.GONE)
            if (shown) pairShown = true
        }
        val footShown = full && text(v, R.id.widget_board_foot, board.foot)

        // A rule earns its place only between two things that are both there. Otherwise it is a
        // line under nothing, which reads as a region that failed rather than one that is absent.
        rule(v, R.id.widget_board_rule_0, true, topShown || idxShown || stkShown || meterShown || readShown)
        rule(v, R.id.widget_board_rule_1, topShown, idxShown || stkShown || meterShown || readShown)
        rule(v, R.id.widget_board_rule_2, idxShown, stkShown || meterShown || readShown)
        rule(v, R.id.widget_board_rule_3, stkShown, meterShown || readShown)
        rule(v, R.id.widget_board_rule_4, meterShown, readShown || pairShown || footShown)
        rule(v, R.id.widget_board_rule_5, readShown, pairShown || footShown)
        rule(v, R.id.widget_board_rule_6, pairShown && full, footShown)
        return v
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /** Write a line, or hide it. Returns whether anything was drawn. */
    private fun text(v: RemoteViews, id: Int, value: String?): Boolean {
        if (value.isNullOrBlank()) {
            v.setViewVisibility(id, View.GONE)
            return false
        }
        v.setViewVisibility(id, View.VISIBLE)
        v.setTextViewText(id, value)
        return true
    }

    private fun rule(v: RemoteViews, id: Int, before: Boolean, after: Boolean) {
        v.setViewVisibility(id, if (before && after) View.VISIBLE else View.GONE)
    }

    private fun strip(
        context: Context,
        v: RemoteViews,
        cells: List<Cell>,
        label: String,
        stripId: Int,
        labelId: Int,
        slots: List<CellIds>,
    ): Boolean {
        slots.forEachIndexed { i, ids ->
            val cell = cells.getOrNull(i)
            if (cell == null) {
                v.setViewVisibility(ids.label, View.GONE)
                v.setViewVisibility(ids.value, View.GONE)
                v.setViewVisibility(ids.chart, View.GONE)
                return@forEachIndexed
            }
            v.setViewVisibility(ids.label, View.VISIBLE)
            v.setTextViewText(ids.label, cell.label)
            v.setViewVisibility(ids.value, View.VISIBLE)
            v.setTextViewText(ids.value, cell.value)
            v.setTextColor(ids.value, ContextCompat.getColor(context, cell.colorRes))
            val chart = cell.chart
            if (chart == null) {
                // No chart is not an empty chart: a blank strip of space under the value would read
                // as a feed that failed, where nothing at all reads as a cell reporting a number.
                v.setViewVisibility(ids.chart, View.GONE)
            } else {
                v.setViewVisibility(ids.chart, View.VISIBLE)
                v.setImageViewBitmap(ids.chart, chart)
            }
        }
        val any = cells.isNotEmpty()
        v.setViewVisibility(stripId, if (any) View.VISIBLE else View.GONE)
        // ⚠️ The label must be WRITTEN, not merely shown. The layout deliberately carries no
        // `android:text` — `WidgetLinkageTest` fails the build on static text nothing replaces —
        // so making it visible without setting it renders an empty row.
        if (any) {
            v.setViewVisibility(labelId, View.VISIBLE)
            v.setTextViewText(labelId, label)
        } else {
            v.setViewVisibility(labelId, View.GONE)
        }
        return any
    }

    private fun column(v: RemoteViews, labelId: Int, lineIds: List<Int>, col: Column?): Boolean {
        val shown = col != null && (col.label.isNotBlank() || col.lines.any { it.isNotBlank() })
        text(v, labelId, col?.label)
        lineIds.forEachIndexed { i, id -> text(v, id, col?.lines?.getOrNull(i)) }
        return shown
    }

    internal data class CellIds(val label: Int, val value: Int, val chart: Int)

    private data class PairSpec(
        val container: Int,
        val leftLabel: Int,
        val leftLines: List<Int>,
        val rightLabel: Int,
        val rightLines: List<Int>,
    )

    /**
     * Which route each region opens.
     *
     * ⚠️ Request codes start at 100 and are distinct per region. `Intent.filterEquals` ignores
     * extras, so two regions sharing a code would silently share ONE `PendingIntent` and every tap
     * would land wherever the last-built one pointed. The row slots use 0..19 and the fault card
     * uses 90, so this range cannot collide with either.
     */
    private const val RC_ALERT = 100
    private const val RC_LEAD = 101
    private const val RC_INDICES = 102
    private const val RC_STOCKS = 103
    private const val RC_BREADTH = 104

    private const val MARKETS_ROUTE = dev.mascwa.pulse.navigation.Routes.MARKETS

    private val SOURCE_IDS = listOf(
        R.id.widget_board_src_0, R.id.widget_board_src_1, R.id.widget_board_src_2,
    )

    /**
     * The header clock's patterns.
     *
     * ⚠️ These are `SimpleDateFormat` patterns, which is what TextClock takes — not `java.time`
     * ones. `HH` is 00-23 and `hh` would be 01-12 with a leading zero, so the 12-hour form uses a
     * bare `h`; `a` is the am/pm marker. No seconds, deliberately: a widget that redrew every second
     * would be a battery cost for a reading nobody takes to the second.
     */
    private const val CLOCK_24 = "HH:mm"
    private const val CLOCK_12 = "h:mm a"

    private val READ_IDS = listOf(
        R.id.widget_board_read_0, R.id.widget_board_read_1,
        R.id.widget_board_read_2, R.id.widget_board_read_3,
    )

    /**
     * How many plain readouts the board can draw.
     *
     * ⚠️ Public because the caller must TRIM rather than discover the limit by having a line
     * vanish. `getOrNull(i)` over a fixed list of slots drops anything past the end in silence —
     * exactly the defect the one notification already carries a guard against, and exactly what
     * happened here: the water line was added as a fourth readout and disappeared whenever the
     * weather detail, the air quality and space weather were all present. The region grew by one
     * and the caller now takes this many knowingly, shedding what it can most afford to lose.
     */
    const val MAX_READOUTS = 4

    /** How many two-column regions the full board can draw. Same reasoning as [MAX_READOUTS]. */
    const val MAX_PAIRS = 3

    /** The four cells of the index strip. */
    private val INDEX_CELLS = listOf(
        CellIds(R.id.widget_board_c0_label, R.id.widget_board_c0_value, R.id.widget_board_c0_chart),
        CellIds(R.id.widget_board_c1_label, R.id.widget_board_c1_value, R.id.widget_board_c1_chart),
        CellIds(R.id.widget_board_c2_label, R.id.widget_board_c2_value, R.id.widget_board_c2_chart),
        CellIds(R.id.widget_board_c3_label, R.id.widget_board_c3_value, R.id.widget_board_c3_chart),
    )

    /** The four cells of the stock strip. */
    private val STOCK_CELLS = listOf(
        CellIds(R.id.widget_board_c4_label, R.id.widget_board_c4_value, R.id.widget_board_c4_chart),
        CellIds(R.id.widget_board_c5_label, R.id.widget_board_c5_value, R.id.widget_board_c5_chart),
        CellIds(R.id.widget_board_c6_label, R.id.widget_board_c6_value, R.id.widget_board_c6_chart),
        CellIds(R.id.widget_board_c7_label, R.id.widget_board_c7_value, R.id.widget_board_c7_chart),
    )

    private val PAIR_SPECS = listOf(
        PairSpec(
            container = R.id.widget_board_pair_0,
            leftLabel = R.id.widget_board_p0l_label,
            leftLines = listOf(
                R.id.widget_board_p0l_0, R.id.widget_board_p0l_1,
                R.id.widget_board_p0l_2, R.id.widget_board_p0l_3,
            ),
            rightLabel = R.id.widget_board_p0r_label,
            rightLines = listOf(
                R.id.widget_board_p0r_0, R.id.widget_board_p0r_1,
                R.id.widget_board_p0r_2, R.id.widget_board_p0r_3,
            ),
        ),
        PairSpec(
            container = R.id.widget_board_pair_1,
            leftLabel = R.id.widget_board_p1l_label,
            leftLines = listOf(
                R.id.widget_board_p1l_0, R.id.widget_board_p1l_1,
                R.id.widget_board_p1l_2, R.id.widget_board_p1l_3,
            ),
            rightLabel = R.id.widget_board_p1r_label,
            rightLines = listOf(
                R.id.widget_board_p1r_0, R.id.widget_board_p1r_1,
                R.id.widget_board_p1r_2, R.id.widget_board_p1r_3,
            ),
        ),
        PairSpec(
            container = R.id.widget_board_pair_2,
            leftLabel = R.id.widget_board_p2l_label,
            leftLines = listOf(
                R.id.widget_board_p2l_0, R.id.widget_board_p2l_1,
                R.id.widget_board_p2l_2, R.id.widget_board_p2l_3,
            ),
            rightLabel = R.id.widget_board_p2r_label,
            rightLines = listOf(
                R.id.widget_board_p2r_0, R.id.widget_board_p2r_1,
                R.id.widget_board_p2r_2, R.id.widget_board_p2r_3,
            ),
        ),
    )
}
