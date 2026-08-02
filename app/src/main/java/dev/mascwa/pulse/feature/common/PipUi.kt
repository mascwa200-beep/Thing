package dev.mascwa.pulse.feature.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * LCARS-style building blocks for the TOOLS feeds — a swept-corner framed panel, a section header
 * with a rounded-cap lead block, a tabbed data row, and a framed stat tile. They read their colours
 * from [Pulse.colors], so they render in whatever palette is provided (LCARS under TOOLS, the default
 * cyberpunk palette elsewhere). Generic terminal chrome — no third-party art or marks.
 */
@Composable
fun PipFrame(
    modifier: Modifier = Modifier,
    accent: Color = Pulse.colors.accent,
    padding: PaddingValues = PaddingValues(13.dp),
    content: @Composable () -> Unit,
) {
    val c = Pulse.colors
    val shape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp, topEnd = 3.dp, bottomEnd = 3.dp)
    Box(
        modifier
            .clip(shape)
            .background(c.panel)
            .border(1.5.dp, accent, shape)
            .padding(padding),
    ) { content() }
}

/** A section header: a rounded-cap lead block, the title, then a rule line out to the edge. */
@Composable
fun PipHeader(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    val c = Pulse.colors
    Row(
        modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.height(11.dp).width(28.dp).clip(RoundedCornerShape(50)).background(c.accent))
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

/**
 * THE canonical LCARS list row: a rounded colour tab on the left edge, the [label] on the left and the
 * [value] on the right, with a hairline rule beneath. Stack these with NO gaps (a plain Column) so the
 * rules form a continuous list. Use this for every readout/stat list from here on.
 */
@Composable
fun PipDataRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Pulse.colors.ink) {
    val c = Pulse.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(4.dp).fillMaxHeight()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                    .background(c.accent),
            )
            Row(
                Modifier.weight(1f).padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label, fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = valueColor)
            }
        }
        Canvas(Modifier.fillMaxWidth().height(1.dp)) {
            drawLine(c.line, Offset(0f, 0f), Offset(size.width, 0f), 1f)
        }
    }
}

/**
 * THE canonical LCARS pick-one chip for horizontal rails (categories/tabs/engines) — a rounded pill:
 * the [selected] one is a solid accent-coloured pill with inverted (void) text, the rest are plain text
 * with a hairline pill outline.
 */
@Composable
fun PipChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Pulse.colors.accent,
) {
    val c = Pulse.colors
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) accent else Color.Transparent)
            .border(1.dp, if (selected) accent else c.line, shape)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text.uppercase(),
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp,
            color = if (selected) c.void else c.ink,
        )
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
