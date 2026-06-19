package dev.mascwa.pulse.feature.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * Retro-CRT terminal building blocks for the TOOLS / PIP-BOY feeds — a flat framed panel with drawn
 * corner brackets, a section header with a rule line, and a framed stat tile. They read their colours
 * from [Pulse.colors], so under the phosphor-green TOOLS palette they render as the green terminal
 * look (and stay on-theme everywhere else). Generic terminal chrome — no third-party art or marks.
 */
@Composable
fun PipFrame(
    modifier: Modifier = Modifier,
    accent: Color = Pulse.colors.accent,
    padding: PaddingValues = PaddingValues(13.dp),
    content: @Composable () -> Unit,
) {
    val c = Pulse.colors
    Box(
        modifier
            .background(c.panel)
            .drawWithContent {
                drawContent()
                val w = size.width
                val h = size.height
                val len = 12.dp.toPx()
                val sw = 1.5.dp.toPx()
                // Thin full border + bright L-brackets at each corner.
                drawRect(c.line, style = Stroke(1f))
                drawLine(accent, Offset(0f, 0f), Offset(len, 0f), sw)
                drawLine(accent, Offset(0f, 0f), Offset(0f, len), sw)
                drawLine(accent, Offset(w, 0f), Offset(w - len, 0f), sw)
                drawLine(accent, Offset(w, 0f), Offset(w, len), sw)
                drawLine(accent, Offset(0f, h), Offset(len, h), sw)
                drawLine(accent, Offset(0f, h), Offset(0f, h - len), sw)
                drawLine(accent, Offset(w, h), Offset(w - len, h), sw)
                drawLine(accent, Offset(w, h), Offset(w, h - len), sw)
            }
            .padding(padding),
    ) { content() }
}

/** A terminal section header: a solid lead block, the title, then a rule line out to the edge. */
@Composable
fun PipHeader(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    val c = Pulse.colors
    Row(
        modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(11.dp).background(c.accent))
        Text(
            title.uppercase(),
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp,
            color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Canvas(Modifier.weight(1f).height(2.dp)) {
            drawLine(c.line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5f)
        }
        if (trailing != null) {
            Text(trailing, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.muted)
        }
    }
}

/** A framed label/value tile (terminal version of StatTile) for the feeds. */
@Composable
fun PipStatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Pulse.colors.ink) {
    val c = Pulse.colors
    PipFrame(modifier, padding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
        Column {
            Text(label.uppercase(), fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.7.sp, color = c.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = valueColor,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}
