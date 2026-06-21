package dev.mascwa.pulse.feature.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * The NIGHTWIRE Cyberpunk-2077 panel silhouette — a chamfered (cut-corner) box: the top-end and
 * bottom-start corners are sliced off on a diagonal, the signature CP2077 HUD parallelogram-notch.
 * Shared by every core surface so the whole app reads as one angular terminal.
 */
val CyberCut = CutCornerShape(topStart = 0.dp, topEnd = 11.dp, bottomEnd = 0.dp, bottomStart = 11.dp)

/** A tighter chamfer for chips / small controls. */
val CyberChipCut = CutCornerShape(topStart = 0.dp, topEnd = 8.dp, bottomEnd = 0.dp, bottomStart = 8.dp)

/** Draws Cyberpunk-style L-shaped HUD corner brackets inside a draw scope. */
fun DrawScope.hudCorners(color: Color, lenPx: Float, strokePx: Float, marginPx: Float) {
    val w = size.width; val h = size.height; val m = marginPx
    // Top-left
    drawLine(color, Offset(m, m), Offset(m + lenPx, m), strokePx)
    drawLine(color, Offset(m, m), Offset(m, m + lenPx), strokePx)
    // Top-right
    drawLine(color, Offset(w - m, m), Offset(w - m - lenPx, m), strokePx)
    drawLine(color, Offset(w - m, m), Offset(w - m, m + lenPx), strokePx)
    // Bottom-left
    drawLine(color, Offset(m, h - m), Offset(m + lenPx, h - m), strokePx)
    drawLine(color, Offset(m, h - m), Offset(m, h - m - lenPx), strokePx)
    // Bottom-right
    drawLine(color, Offset(w - m, h - m), Offset(w - m - lenPx, h - m), strokePx)
    drawLine(color, Offset(w - m, h - m), Offset(w - m, h - m - lenPx), strokePx)
}

/** Bordered carbon/panel container — the core NIGHTWIRE surface. */
@Composable
fun NeonPanel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(13.dp),
    borderColor: Color = Pulse.colors.lineSoft,
    background: Color = Pulse.colors.panel,
    corners: Boolean = false,
    content: @Composable () -> Unit,
) {
    val accent = Pulse.colors.accent
    Box(
        modifier
            .clip(CyberCut)
            .background(background)
            .border(BorderStroke(1.dp, borderColor), CyberCut)
            .then(
                if (corners) Modifier.drawWithContent {
                    drawContent()
                    hudCorners(accent, 12.dp.toPx(), 1.5.dp.toPx(), 3.dp.toPx())
                } else Modifier,
            )
            .padding(padding),
    ) { content() }
}

/** Section header: accent bar + uppercase display title, optional mono action. */
@Composable
fun SectionBar(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onTrailing: (() -> Unit)? = null,
) {
    val c = Pulse.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Sharp angular accent marker (a thin skewed blade) — CP2077, not a rounded bar.
            Box(Modifier.width(4.dp).height(15.dp).clip(CutCornerShape(topStart = 0.dp, topEnd = 4.dp, bottomEnd = 0.dp, bottomStart = 4.dp)).background(c.accent))
            dev.mascwa.pulse.ui.effects.DecryptText(
                title.uppercase(),
                fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp, letterSpacing = 2.4.sp, color = c.ink,
                modifier = Modifier.padding(start = 9.dp),
            )
        }
        if (trailing != null) {
            Text(
                trailing,
                fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.muted,
                modifier = if (onTrailing != null) Modifier.clickable { onTrailing() } else Modifier,
            )
        }
    }
}

/** Pill chip for category filters / ranges. */
@Composable
fun NeonChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    Box(
        modifier
            .clip(CyberChipCut)
            .background(if (selected) c.accent.copy(alpha = 0.13f) else c.panel)
            .border(
                BorderStroke(1.dp, if (selected) c.accent else c.line),
                CyberChipCut,
            )
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(
            text.uppercase(),
            fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 0.6.sp,
            color = if (selected) c.accent else c.ink2,
        )
    }
}

/** Label/number/sub stat tile (inflation, fuel, econ, weather stats). */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    valueColor: Color = Pulse.colors.ink,
    subColor: Color = Pulse.colors.muted,
) {
    val c = Pulse.colors
    NeonPanel(modifier, padding = PaddingValues(start = 12.dp, end = 12.dp, top = 11.dp, bottom = 11.dp)) {
        Column {
            Text(
                label.uppercase(),
                fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.7.sp, color = c.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                value,
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                color = valueColor, modifier = Modifier.padding(top = 7.dp),
            )
            if (sub != null) {
                Text(
                    sub, fontFamily = JetBrainsMono, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    color = subColor, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** Thin divider in the line-soft colour. */
@Composable
fun NeonDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Pulse.colors.lineSoft))
}

/** Launcher tile for hub screens (GRID / SKY / SURVIVE). */
@Composable
fun HubTile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Pulse.colors.accent,
    badge: String? = null,
) {
    val c = Pulse.colors
    Box(
        modifier
            .clip(CyberCut)
            .background(c.panel)
            .border(BorderStroke(1.dp, c.lineSoft), CyberCut)
            .drawWithContent {
                drawContent()
                hudCorners(accent.copy(alpha = 0.7f), 11.dp.toPx(), 1.5.dp.toPx(), 3.dp.toPx())
            }
            .clickable { onClick() }
            .padding(14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(
                    icon, null, tint = accent,
                    modifier = Modifier.size(22.dp),
                )
                if (badge != null) {
                    Box(
                        Modifier.padding(start = 8.dp).clip(RoundedCornerShape(5.dp))
                            .background(c.magenta).padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(badge, fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.void)
                    }
                }
            }
            Text(
                title, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                color = c.ink, modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                subtitle, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** Small status dot with glow. */
@Composable
fun StatusDot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(50)).background(color))
}
