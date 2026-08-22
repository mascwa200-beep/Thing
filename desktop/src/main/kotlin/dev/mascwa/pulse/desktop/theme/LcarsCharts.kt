package dev.mascwa.pulse.desktop.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.util.Formatters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The LCARS instrument kit for the desktop — the same charts the phone's MAPS & SKY consoles are
 * built from, so a space-weather page or a forecast looks like itself on both machines.
 *
 * ⚠️ **This is a real port rather than a copy, and the reason is text.** The Android original draws
 * every label through `android.graphics.Paint` on `nativeCanvas`, which positions text by an
 * ANCHOR (left/centre/right) at a BASELINE. Compose's own `drawText` positions by the TOP-LEFT
 * corner. Ported naively, every label would sit half its own width to the right and most of its
 * height too low — the chart would draw perfectly and be unreadable. [drawLabel] below does the
 * measuring the anchor used to do for free, which is why every call site here passes an alignment
 * instead of setting one on a shared paint object.
 *
 * The geometry — the gutters, the band shading, the tick counts, the 240° gauge sweep, the polar
 * plot's north-up east-right convention — is deliberately identical to the phone's, so the two are
 * the same instrument rather than two instruments that resemble each other.
 *
 * Every composable degrades to an empty [Box] rather than throwing when there is nothing to draw.
 */

/** One line on a time chart. [points] are (epochMillis, value), any order. */
data class ChartSeries(
    val label: String,
    val points: List<Pair<Long, Double>>,
    val color: Color,
    val filled: Boolean = false,
)

/** A shaded horizontal band, e.g. "G1 storm" across a Kp chart. */
data class ChartBand(
    val from: Double,
    val to: Double,
    val color: Color,
    val label: String? = null,
)

/** A body plotted on the sky: azimuth clockwise from true north, altitude 0 (horizon)..90 (zenith). */
data class SkyPoint(
    val azimuthDeg: Double,
    val altitudeDeg: Double,
    val label: String? = null,
    val color: Color,
    val radiusDp: Dp = 3.dp,
    val glyph: String? = null,
)

/** Where a label's given x sits within it. The replacement for `Paint.Align`. */
enum class LabelAlign { Start, Center, End }

/** Where a label's given y sits within it. `Paint` had only a baseline; this is more honest. */
enum class LabelVAlign { Top, Middle, Bottom }

/**
 * Draw one short label, positioned the way a chart wants to think about it.
 *
 * ⚠️ The measure is the whole point. Compose can only place text by its top-left corner, so
 * "centred on this tick" and "right-aligned against this gutter" have to be computed from the
 * text's own size — which is exactly the arithmetic `Paint.Align` used to hide.
 */
private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    color: Color,
    sizeSp: Float,
    align: LabelAlign = LabelAlign.Center,
    vAlign: LabelVAlign = LabelVAlign.Middle,
) {
    if (text.isEmpty()) return
    val style = TextStyle(color = color, fontSize = sizeSp.sp, fontFamily = JetBrainsMono)
    val laid = measurer.measure(text, style)
    val w = laid.size.width.toFloat()
    val h = laid.size.height.toFloat()
    val left = when (align) {
        LabelAlign.Start -> x
        LabelAlign.Center -> x - w / 2f
        LabelAlign.End -> x - w
    }
    val top = when (vAlign) {
        LabelVAlign.Top -> y
        LabelVAlign.Middle -> y - h / 2f
        LabelVAlign.Bottom -> y - h
    }
    drawText(measurer, text, topLeft = Offset(left, top), style = style)
}

/**
 * A time-series chart with labelled axes: value gridlines on the left, clock times along the
 * bottom, optional shaded [bands], and any number of overlaid [series]. X is real time, so an
 * irregular feed (SWPC posts every 5 minutes but drops rows) is spaced honestly rather than evenly.
 */
@Composable
fun LcarsTimeChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    bands: List<ChartBand> = emptyList(),
    yTicks: Int = 4,
    xTicks: Int = 4,
    valueFormat: (Double) -> String = { Formatters.axisLabel(it) },
    forceMin: Double? = null,
    forceMax: Double? = null,
    /**
     * Label the horizontal axis with something other than a clock.
     *
     * The axis is a Long because it was written for real time, and that is still what it usually is.
     * But the same chart draws a route's elevation against distance, where the number is metres and
     * a clock label would be nonsense.
     */
    xFormat: ((Long) -> String)? = null,
) {
    val c = Pulse.colors
    val measurer = rememberTextMeasurer()
    val usable = series.filter { it.points.size >= 2 }
    if (usable.isEmpty()) {
        Box(modifier)
        return
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.US) }
    val dayFormat = remember { SimpleDateFormat("d MMM", Locale.US) }

    val all = usable.flatMap { it.points }
    val tMin = all.minOf { it.first }
    val tMax = all.maxOf { it.first }
    val tSpan = (tMax - tMin).takeIf { it > 0L } ?: 1L
    // A run longer than about a day reads better as dates than as clock times.
    val labelFormat = if (tSpan > 36L * 3600_000L) dayFormat else timeFormat

    var vMin = forceMin ?: minOf(all.minOf { it.second }, bands.minOfOrNull { it.from } ?: Double.MAX_VALUE)
    var vMax = forceMax ?: maxOf(all.maxOf { it.second }, bands.maxOfOrNull { it.to } ?: -Double.MAX_VALUE)
    // ⚠️ Order before widening. A caller pinning one end (forceMin = 0 against an all-negative
    // series — which is exactly what IMF Bz is) would otherwise leave vMax below vMin, and every
    // coerceIn(vMin, vMax) below throws on an inverted range.
    if (vMax < vMin) { val swap = vMin; vMin = vMax; vMax = swap }
    if (vMax - vMin < 1e-9) { vMin -= 0.5; vMax += 0.5 } // a flat line still needs a box to sit in
    val vSpan = vMax - vMin
    val ySteps = yTicks.coerceAtLeast(1)
    val xSteps = xTicks.coerceAtLeast(1)
    // Sort once per data change, not once per frame: this runs inside the draw lambda otherwise.
    val prepared = remember(usable) { usable.map { s -> s to s.points.sortedBy { it.first } } }

    Canvas(modifier) {
        val gutter = 34.dp.toPx()   // left, for value labels
        val footer = 14.dp.toPx()   // bottom, for time labels
        val plotW = (size.width - gutter).coerceAtLeast(1f)
        val plotH = (size.height - footer).coerceAtLeast(1f)

        fun px(t: Long): Float = gutter + ((t - tMin).toDouble() / tSpan * plotW).toFloat()
        fun py(v: Double): Float = (plotH - ((v - vMin) / vSpan * plotH)).toFloat()

        // Shaded threshold bands sit behind everything.
        bands.forEach { band ->
            val top = py(band.to.coerceIn(vMin, vMax))
            val bottom = py(band.from.coerceIn(vMin, vMax))
            if (bottom - top > 0.5f) {
                drawRect(
                    color = band.color.copy(alpha = 0.16f),
                    topLeft = Offset(gutter, top),
                    size = Size(plotW, bottom - top),
                )
            }
        }

        // Value gridlines + labels.
        for (i in 0..ySteps) {
            val v = vMin + vSpan * i / ySteps
            val y = py(v)
            drawLine(
                color = c.lineSoft.copy(alpha = 0.7f),
                start = Offset(gutter, y), end = Offset(size.width, y), strokeWidth = 1f,
            )
            drawLabel(
                measurer, valueFormat(v), gutter - 4.dp.toPx(), y, c.faint, 8f,
                align = LabelAlign.End, vAlign = LabelVAlign.Middle,
            )
        }

        // Time ticks along the bottom.
        // ⚠️ Keep the label bounds ordered: in a container narrower than the gutter plus both margins
        // — including the zero-width first layout pass — the upper bound falls below the lower one
        // and coerceIn throws on the empty range.
        val loX = gutter + 10.dp.toPx()
        val hiX = (size.width - 10.dp.toPx()).coerceAtLeast(loX)
        for (i in 0..xSteps) {
            val t = tMin + tSpan * i / xSteps
            val x = px(t).coerceIn(loX, hiX)
            drawLine(
                color = c.lineSoft.copy(alpha = 0.5f),
                start = Offset(x, 0f), end = Offset(x, plotH), strokeWidth = 1f,
            )
            drawLabel(
                measurer, xFormat?.invoke(t) ?: labelFormat.format(Date(t)),
                x, size.height, c.faint, 8f,
                align = LabelAlign.Center, vAlign = LabelVAlign.Bottom,
            )
        }

        // The axes themselves.
        drawLine(c.line, Offset(gutter, 0f), Offset(gutter, plotH), strokeWidth = 1.5f)
        drawLine(c.line, Offset(gutter, plotH), Offset(size.width, plotH), strokeWidth = 1.5f)

        // Each series, oldest point first so the path runs left to right.
        prepared.forEach { (s, pts) ->
            val line = Path()
            val area = Path()
            pts.forEachIndexed { i, (t, v) ->
                val x = px(t)
                val y = py(v.coerceIn(vMin, vMax))
                if (i == 0) {
                    line.moveTo(x, y); area.moveTo(x, plotH); area.lineTo(x, y)
                } else {
                    line.lineTo(x, y); area.lineTo(x, y)
                }
            }
            if (s.filled) {
                area.lineTo(px(pts.last().first), plotH)
                area.close()
                drawPath(area, s.color.copy(alpha = 0.14f))
            }
            drawPath(line, s.color, style = Stroke(width = 2.5f))
        }
    }
}

/**
 * A radial gauge for a bounded index — Kp, a storm scale, a percentage. Draws a 240° arc with
 * coloured [bands], a needle at [value], and the value written across the middle.
 */
@Composable
fun LcarsGauge(
    value: Double?,
    min: Double,
    max: Double,
    modifier: Modifier = Modifier,
    bands: List<ChartBand> = emptyList(),
    label: String? = null,
    unit: String = "",
    valueColor: Color? = null,
) {
    val c = Pulse.colors
    val measurer = rememberTextMeasurer()
    val span = (max - min).takeIf { abs(it) > 1e-9 } ?: 1.0
    val sweepTotal = 240f
    val startAngle = 150f // 150° -> 30°, i.e. a dial opening downward

    Canvas(modifier) {
        val stroke = 9.dp.toPx()
        val inset = stroke / 2 + 2.dp.toPx()
        val arcSize = Size(size.width - inset * 2, size.width - inset * 2)
        val topLeft = Offset(inset, inset)
        val cx = size.width / 2
        val cy = inset + arcSize.height / 2

        // Track.
        drawArc(
            color = c.lineSoft, startAngle = startAngle, sweepAngle = sweepTotal, useCenter = false,
            topLeft = topLeft, size = arcSize, style = Stroke(width = stroke),
        )
        // Bands over the track.
        bands.forEach { band ->
            val from = ((band.from - min) / span).coerceIn(0.0, 1.0)
            val to = ((band.to - min) / span).coerceIn(0.0, 1.0)
            if (to > from) {
                drawArc(
                    color = band.color.copy(alpha = 0.85f),
                    startAngle = startAngle + (from * sweepTotal).toFloat(),
                    sweepAngle = ((to - from) * sweepTotal).toFloat(),
                    useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke),
                )
            }
        }

        if (value != null) {
            val frac = ((value - min) / span).coerceIn(0.0, 1.0)
            val angle = Math.toRadians(startAngle + frac * sweepTotal)
            val r = arcSize.width / 2
            drawLine(
                color = valueColor ?: c.ink,
                start = Offset(cx, cy),
                end = Offset(cx + (r * 0.82f * cos(angle)).toFloat(), cy + (r * 0.82f * sin(angle)).toFloat()),
                strokeWidth = 2.5f,
            )
            drawCircle(valueColor ?: c.ink, radius = 3.5.dp.toPx(), center = Offset(cx, cy))
        }

        drawLabel(
            measurer,
            if (value == null) "—" else Formatters.axisLabel(value) + unit,
            cx, cy, valueColor ?: c.ink, 20f,
        )
        if (label != null) {
            drawLabel(
                measurer, label.uppercase(Locale.US),
                cx, cy + arcSize.height / 2, c.muted, 8f,
                align = LabelAlign.Center, vAlign = LabelVAlign.Bottom,
            )
        }
    }
}

/** A labelled bar chart — flare counts by class, passes per night, hours of rain. */
@Composable
fun LcarsHistogram(
    bars: List<Pair<String, Double>>,
    modifier: Modifier = Modifier,
    color: Color? = null,
    barColors: List<Color>? = null,
) {
    val c = Pulse.colors
    val measurer = rememberTextMeasurer()
    if (bars.isEmpty()) {
        Box(modifier)
        return
    }
    val fill = color ?: c.accent
    val peak = bars.maxOf { it.second }.takeIf { it > 0.0 } ?: 1.0

    Canvas(modifier) {
        val footer = 12.dp.toPx()
        val plotH = (size.height - footer).coerceAtLeast(1f)
        val slot = size.width / bars.size
        val barW = slot * 0.62f
        bars.forEachIndexed { i, (name, v) ->
            val h = ((v / peak) * plotH).toFloat().coerceAtLeast(if (v > 0) 1.5f else 0f)
            val x = slot * i + (slot - barW) / 2
            drawRect(
                color = barColors?.getOrNull(i) ?: fill,
                topLeft = Offset(x, plotH - h),
                size = Size(barW, h),
            )
            drawLabel(
                measurer, name, slot * i + slot / 2, size.height, c.muted, 8f,
                align = LabelAlign.Center, vAlign = LabelVAlign.Bottom,
            )
        }
        drawLine(c.line, Offset(0f, plotH), Offset(size.width, plotH), strokeWidth = 1.5f)
    }
}

/**
 * A polar alt-azimuth plot — the sky as seen from where you stand. North is up, east is right
 * (the mirror of a paper star chart, because you are looking *up*), the horizon is the rim and the
 * zenith is the centre. Altitude rings are drawn at 30° and 60°.
 */
@Composable
fun LcarsSkyPlot(
    points: List<SkyPoint>,
    modifier: Modifier = Modifier,
    showBelowHorizon: Boolean = false,
    rings: List<Int> = listOf(30, 60),
) {
    val c = Pulse.colors
    val measurer = rememberTextMeasurer()

    Canvas(modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val r = size.minDimension / 2 * 0.88f

        drawCircle(c.panel, radius = r, center = Offset(cx, cy))
        drawCircle(c.line, radius = r, center = Offset(cx, cy), style = Stroke(width = 1.5f))
        rings.forEach { alt ->
            val rr = r * (1f - alt / 90f)
            drawCircle(c.lineSoft, radius = rr, center = Offset(cx, cy), style = Stroke(width = 1f))
        }
        // Azimuth spokes every 45°.
        for (a in 0 until 360 step 45) {
            val rad = Math.toRadians(a.toDouble())
            drawLine(
                color = c.lineSoft.copy(alpha = 0.6f),
                start = Offset(cx, cy),
                end = Offset(cx + (r * sin(rad)).toFloat(), cy - (r * cos(rad)).toFloat()),
                strokeWidth = 1f,
            )
        }
        // Cardinals on the rim.
        listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (name, az) ->
            val rad = Math.toRadians(az)
            drawLabel(
                measurer, name,
                cx + (r * 1.07f * sin(rad)).toFloat(),
                cy - (r * 1.07f * cos(rad)).toFloat(),
                if (name == "N") c.accent else c.muted, 9f,
            )
        }

        points.forEach { p ->
            if (!showBelowHorizon && p.altitudeDeg < 0) return@forEach
            val pr = r * (1f - (p.altitudeDeg.coerceIn(-90.0, 90.0) / 90f).toFloat())
            val rad = Math.toRadians(p.azimuthDeg)
            val x = cx + (pr * sin(rad)).toFloat()
            val y = cy - (pr * cos(rad)).toFloat()
            if (p.glyph != null) {
                // ⚠️ The glyph is CENTRED on the body's position, both ways. The Android original
                // nudged the baseline down by the radius to achieve that with a centre-anchored
                // paint; here the vertical centring is stated instead of arrived at.
                drawLabel(measurer, p.glyph, x, y, p.color, p.radiusDp.value * 3f)
            } else {
                drawCircle(p.color, radius = p.radiusDp.toPx(), center = Offset(x, y))
            }
            if (p.label != null) {
                drawLabel(
                    measurer, p.label,
                    x + p.radiusDp.toPx() + 3.dp.toPx(), y, c.muted, 8f,
                    align = LabelAlign.Start, vAlign = LabelVAlign.Middle,
                )
            }
        }
    }
}

/**
 * A horizontal magnitude bar with a marker at [value] — the compact readout for a single number
 * inside a dense row, where a gauge would be too tall.
 */
@Composable
fun LcarsMeter(
    value: Double?,
    min: Double,
    max: Double,
    modifier: Modifier = Modifier,
    bands: List<ChartBand> = emptyList(),
    markerColor: Color? = null,
) {
    val c = Pulse.colors
    val span = (max - min).takeIf { abs(it) > 1e-9 } ?: 1.0
    Canvas(modifier) {
        drawRect(c.lineSoft, topLeft = Offset(0f, 0f), size = size)
        bands.forEach { band ->
            val from = ((band.from - min) / span).coerceIn(0.0, 1.0).toFloat()
            val to = ((band.to - min) / span).coerceIn(0.0, 1.0).toFloat()
            if (to > from) {
                drawRect(
                    color = band.color.copy(alpha = 0.75f),
                    topLeft = Offset(size.width * from, 0f),
                    size = Size(size.width * (to - from), size.height),
                )
            }
        }
        if (value != null) {
            val x = (size.width * ((value - min) / span).coerceIn(0.0, 1.0)).toFloat()
            drawLine(
                color = markerColor ?: c.ink,
                start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 2.5f,
            )
        }
    }
}

/**
 * A compact trace with no axis at all — the shape of a series, at a size where a label would be
 * most of the ink.
 *
 * ⚠️ Deliberately **not** [LcarsTimeChart]. That one is built for a full screen and draws a labelled
 * time axis; on a HUD strip or inside a list row the axis is most of the ink and none of the
 * information. The trace is normalised to its own range because what a reader wants at this size is
 * the shape, not the value — which whatever draws it states in words alongside.
 *
 * Promoted out of the standby display, which had the only copy. A second hand-rolled one for the
 * anomaly wall would have been the duplicated-definition mistake this project has corrected five
 * times with palettes.
 */
@Composable
fun LcarsSparkline(
    values: List<Double>,
    colour: Color,
    modifier: Modifier,
    background: Color? = null,
) {
    Canvas(if (background != null) modifier.background(background) else modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val span = (hi - lo).takeIf { it > 0.0001 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        var previous: Offset? = null
        values.forEachIndexed { i, v ->
            val point = Offset(
                x = stepX * i,
                y = (size.height - (((v - lo) / span) * size.height)).toFloat(),
            )
            previous?.let { drawLine(colour, it, point, strokeWidth = size.height * 0.08f) }
            previous = point
        }
    }
}
