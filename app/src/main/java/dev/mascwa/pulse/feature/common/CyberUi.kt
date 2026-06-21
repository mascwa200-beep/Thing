package dev.mascwa.pulse.feature.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/* ============================================================
   Cyberpunk-2077 HUD building blocks for the core NIGHTWIRE
   screens (Home / Markets / Weather / Economy / …). Angular,
   crimson, technical — every piece reads its colours from
   [Pulse.colors] so it tracks the accent. No third-party art.
   ============================================================ */

/** A deterministic CP2077-style technical micro-label for a section (e.g. `TRN.0x3F2A`). Stable per
 *  [seed] so it doesn't churn between recompositions. */
fun cyberTag(seed: String): String {
    val hex = Integer.toHexString(seed.hashCode() and 0xFFFF).uppercase().padStart(4, '0')
    return "TRN.0x$hex"
}

/**
 * The signature CP2077 section header: a sharp skewed accent blade, the uppercase display title, a thin
 * rule line out to the edge, and a mono technical [tag] code stamp on the right. Drop-in replacement for
 * plain section labels — gives every list the angular HUD feel. When [onToggle] is set it becomes a
 * collapsible header: the whole row is tappable and a ▾/▸ chevron reflects [collapsed].
 */
@Composable
fun CyberHeader(
    title: String,
    modifier: Modifier = Modifier,
    tag: String? = null,
    accent: Color = Pulse.colors.accent,
    collapsed: Boolean? = null,
    onToggle: (() -> Unit)? = null,
) {
    val c = Pulse.colors
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onToggle != null) Modifier.clickable { onToggle() } else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // Skewed accent blade (chamfered, not rounded).
        Box(
            Modifier.size(width = 5.dp, height = 16.dp)
                .clip(CutCornerShape(topStart = 0.dp, topEnd = 5.dp, bottomEnd = 0.dp, bottomStart = 5.dp))
                .background(accent),
        )
        Text(
            title.uppercase(),
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp,
            color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Canvas(Modifier.weight(1f).height(2.dp)) {
            drawLine(c.line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5f)
        }
        Text(
            tag ?: cyberTag(title),
            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = accent.copy(alpha = 0.7f),
        )
        if (collapsed != null && onToggle != null) {
            Text(
                if (collapsed) "▸" else "▾",
                fontFamily = JetBrainsMono, fontSize = 12.sp, color = accent,
            )
        }
    }
}

/**
 * Wraps a dense list row in the CP2077 idiom: a thin accent blade down the leading edge and a hairline
 * along the bottom, tappable, with the chamfered notch implied by the shared panels. The row [content]
 * keeps its own internal padding. [selected] makes the blade solid + a faint accent band (the inventory
 * "highlighted entry" look); otherwise the blade is a quiet tick.
 */
@Composable
fun CyberRowFrame(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    accent: Color = Pulse.colors.accent,
    content: @Composable () -> Unit,
) {
    val c = Pulse.colors
    Box(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .drawBehind {
                if (selected) drawRect(accent.copy(alpha = 0.10f))
                // Leading accent blade.
                drawRect(
                    if (selected) accent else accent.copy(alpha = 0.5f),
                    size = Size((if (selected) 3.5.dp else 2.5.dp).toPx(), size.height),
                )
                // Bottom hairline.
                drawLine(c.lineSoft, Offset(0f, size.height), Offset(size.width, size.height), 1f)
            },
    ) { content() }
}
