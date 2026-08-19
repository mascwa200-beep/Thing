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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.ui.effects.HapticCue
import dev.mascwa.pulse.ui.effects.SoundCue
import dev.mascwa.pulse.ui.effects.rememberLcarsCue
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/** The chip/small-control shape — a stepped notch, matching [LcarsChip]'s exact silhouette so the two chip
 *  composables render identically shaped. */
val CyberChipCut: Shape = CutCornerShape(topStart = 0.dp, topEnd = 10.dp, bottomEnd = 0.dp, bottomStart = 10.dp)

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

/**
 * SHIM — the legacy panel name, now delegating to [LcarsFrame] so the app has ONE panel and not two
 * that render subtly differently on the same page (Weather carried both at once). ~96 call sites
 * stay untouched, which is what makes a change this wide verifiable.
 *
 * [corners] is accepted and ignored: the L-shaped HUD brackets were the CP2077 leftover, and keeping
 * a second decoration path alive would keep the second look alive. Delete the parameter with the
 * shim once the call sites are swept.
 */
@Composable
fun NeonPanel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(13.dp),
    borderColor: Color = Pulse.colors.lineSoft,
    background: Color = Pulse.colors.panel,
    @Suppress("UNUSED_PARAMETER") corners: Boolean = false,
    content: @Composable () -> Unit,
) {
    LcarsFrame(modifier, accent = borderColor, padding = padding, background = background) { content() }
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
            // A small swept-corner accent blade, matching the app-wide LCARS block silhouette.
            Box(Modifier.width(4.dp).height(15.dp).clip(lcarsBlockShape(sweep = 4.dp, corner = LcarsCorner.TopStart)).background(c.accent))
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

/**
 * SHIM — the legacy chip name, now delegating to [LcarsChip] so the app has ONE chip. The visual
 * change is deliberate and app-wide: a selected chip is a solid accent block with dark text (the
 * LCARS read) rather than a faint tint, so "which tab am I on" stops depending on which screen
 * happened to use which chip.
 */
@Composable
fun NeonChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LcarsChip(text, selected, onClick, modifier)
}

// StatTile, NeonDivider, HubTile, StatusDot and the CyberCut shape used to live here. All were
// re-grepped at deletion time and had ZERO call sites — dead since their consumers migrated onto
// the LCARS kit (LcarsStatBlock, LcarsDataRow's own rules, SurviveTileCard).
