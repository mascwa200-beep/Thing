package dev.mascwa.pulse.feature.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.ui.theme.trendColor
import kotlin.math.max
import kotlin.math.min

/** Compact sparkline for a watchlist row. Colour reflects net trend. */
@Composable
fun Sparkline(
    values: List<Double>,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    if (values.size < 2) {
        Box(modifier)
        return
    }
    val positive = values.last() >= values.first()
    val lineColor = color ?: trendColor(positive)
    Canvas(modifier) {
        val minV = values.min()
        val maxV = values.max()
        val range = (maxV - minV).takeIf { it != 0.0 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - ((v - minV) / range * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 2.5f))
    }
}

/** Larger line chart for a quote detail or economic time series. */
@Composable
fun LineChart(
    values: List<Double>,
    modifier: Modifier = Modifier,
    color: Color? = null,
    showZeroBaseline: Boolean = false,
) {
    if (values.size < 2) {
        Box(modifier)
        return
    }
    val positive = values.last() >= values.first()
    val lineColor = color ?: trendColor(positive)
    val fill = lineColor.copy(alpha = 0.12f)
    Canvas(modifier) {
        val minV = min(values.min(), if (showZeroBaseline) 0.0 else values.min())
        val maxV = max(values.max(), if (showZeroBaseline) 0.0 else values.max())
        val range = (maxV - minV).takeIf { it != 0.0 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        fun py(v: Double) = size.height - ((v - minV) / range * size.height).toFloat()

        val line = Path()
        val area = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = py(v)
            if (i == 0) { line.moveTo(x, y); area.moveTo(x, size.height); area.lineTo(x, y) }
            else { line.lineTo(x, y); area.lineTo(x, y) }
        }
        area.lineTo((values.size - 1) * stepX, size.height)
        area.close()
        drawPath(area, fill)

        if (showZeroBaseline && minV < 0 && maxV > 0) {
            val zy = py(0.0)
            drawLine(
                color = lineColor.copy(alpha = 0.3f),
                start = Offset(0f, zy), end = Offset(size.width, zy), strokeWidth = 1f,
            )
        }
        drawPath(line, lineColor, style = Stroke(width = 3f))
    }
}

/** Coloured percent badge. */
@Composable
fun ChangePill(
    percent: Double?,
    modifier: Modifier = Modifier,
) {
    if (percent == null) {
        Text("—", style = MaterialTheme.typography.labelMedium, modifier = modifier)
        return
    }
    val positive = percent >= 0
    val c = trendColor(positive)
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .padding(0.dp),
    ) {
        Text(
            text = Formatters.signedPercent(percent),
            color = c,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
