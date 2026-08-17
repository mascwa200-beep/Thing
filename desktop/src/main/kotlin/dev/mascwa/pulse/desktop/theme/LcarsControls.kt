package dev.mascwa.pulse.desktop.theme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The interactive half of the desktop LCARS kit.
 *
 * `LcarsGeometry.kt` is a verbatim port of the Android panel geometry, but the Android app gets its
 * buttons, fields and switches from Material 3 + its own `feature/common` composables, none of which came
 * across. Before this file the desktop kit had exactly one clickable primitive ([LcarsChip], a pick-one
 * chip with no hover affordance), so a real screen could not be built at all.
 *
 * These are written for the desktop specifically rather than ported: a mouse gets hover feedback, which is
 * meaningless on a phone and absent from the Android originals. Everything reads [Pulse.colors] so it
 * follows the palette rather than hardcoding, matching the convention the rest of the kit documents.
 */

/** A solid LCARS action button — swept on one corner, hover-lit, with a disabled state that reads as dim. */
@Composable
fun LcarsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = Pulse.colors.accent,
    corner: LcarsCorner = LcarsCorner.TopStart,
) {
    val c = Pulse.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = lcarsBlockShape(sweep = 14.dp, corner = corner)
    val fill = when {
        !enabled -> c.raise
        hovered -> c.ink
        else -> accent
    }
    Box(
        modifier
            .clip(shape)
            .background(fill)
            .hoverable(interaction, enabled = enabled)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 1.5.sp,
            color = if (enabled) c.void else c.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A quieter button: outline only, for secondary/destructive-adjacent actions like "forget this device". */
@Composable
fun LcarsGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = Pulse.colors.muted,
) {
    val c = Pulse.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = lcarsBlockShape(sweep = 14.dp, corner = LcarsCorner.TopStart)
    Box(
        modifier
            .clip(shape)
            .border(1.dp, if (enabled && hovered) accent else c.line, shape)
            .hoverable(interaction, enabled = enabled)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
            color = if (!enabled) c.faint else if (hovered) accent else c.ink2,
            maxLines = 1,
        )
    }
}

/**
 * A single-line text field. Compose Multiplatform ships `BasicTextField` but no styled desktop field, and
 * the Android app's fields are Material — so this supplies the LCARS chrome (label above, ruled underline,
 * monospace value) the rest of the kit implies.
 */
@Composable
fun LcarsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
) {
    val c = Pulse.colors
    Column(modifier) {
        Text(
            label.uppercase(),
            fontFamily = JetBrainsMono,
            fontSize = 9.sp,
            letterSpacing = 0.8.sp,
            color = c.muted,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 5.dp)
                .background(c.raise)
                .border(1.dp, c.line)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.faint)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                enabled = enabled,
                textStyle = TextStyle(
                    color = if (enabled) c.ink else c.muted,
                    fontFamily = JetBrainsMono,
                    fontSize = 13.sp,
                ),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A labelled on/off control. Rendered as a sliding block rather than a Material `Switch` so it matches the
 * kit's rectilinear language; the thumb animates so a remote toggle's state change is visible rather than
 * silently swapping.
 */
@Composable
fun LcarsSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subtitle: String? = null,
) {
    val c = Pulse.colors
    val thumbOffset by animateDpAsState(if (checked) 22.dp else 2.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                label,
                fontFamily = JetBrainsMono,
                fontSize = 13.sp,
                color = if (enabled) c.ink else c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontFamily = JetBrainsMono,
                    fontSize = 10.sp,
                    color = c.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Box(
            Modifier
                .width(44.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (checked && enabled) Pulse.colors.accent else c.raise)
                .border(1.dp, if (enabled) c.line else c.lineSoft, RoundedCornerShape(3.dp)),
        ) {
            Box(
                Modifier
                    .offset(x = thumbOffset, y = 2.dp)
                    .size(width = 18.dp, height = 16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (checked && enabled) c.void else c.muted),
            )
        }
    }
}

/** Connection/health indicator: a filled square plus a caption. Square, not a circle — this kit has no dots. */
@Composable
fun LcarsStatusDot(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val c = Pulse.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(1.dp)).background(color))
        Text(
            label.uppercase(),
            fontFamily = JetBrainsMono,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            color = c.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A left navigation rail entry. The selected item gets the accent bar; hovering previews the selection. */
@Composable
fun LcarsNavItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * What this destination is for, in plain English.
     *
     * The phone answers "how do I find anything" with a flat directory screen; on a desktop the rail
     * is always on screen, so the rail IS the directory and there is no reason to make someone open a
     * separate page to read the same list. A bare seven-word rail was navigable only if you already
     * knew what the seven words meant.
     */
    description: String? = null,
) {
    val c = Pulse.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(if (selected) 8.dp else 4.dp)
                .height(34.dp)
                .clip(lcarsBlockShape(sweep = 5.dp, corner = LcarsCorner.TopStart))
                .background(if (selected) c.accent else if (hovered) c.ink2 else c.line),
        )
        Column(Modifier.padding(start = 10.dp)) {
        Text(
            text.uppercase(),
            fontFamily = ChakraPetch,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 12.sp,
            letterSpacing = 1.5.sp,
            color = if (selected) c.ink else if (hovered) c.ink2 else c.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (description != null) {
            Text(
                description,
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
                color = if (selected) c.muted else c.faint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        }
    }
}

/** A thin progress/activity bar reusing [LcarsFillRow] so busy states look like the rest of the kit. */
@Composable
fun LcarsBusyBar(active: Boolean, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    LcarsFillRow(
        segments = if (active) listOf(0.5f to c.accent, 0.5f to c.raise) else listOf(1f to c.raise),
        modifier = modifier.height(3.dp),
        gap = 1.5.dp,
    )
}
