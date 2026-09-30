package dev.mascwa.pulse.feature.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.LcarsFillRow
import dev.mascwa.pulse.feature.common.lcarsBlockShape
import dev.mascwa.pulse.feature.controls.ControlsState.Control
import dev.mascwa.pulse.feature.lcarsboard.ConsolePanel
import dev.mascwa.pulse.feature.lcarsboard.legibleOn
import dev.mascwa.pulse.feature.lcarsboard.textOnBlock
import dev.mascwa.pulse.ui.effects.HapticCue
import dev.mascwa.pulse.ui.effects.SoundCue
import dev.mascwa.pulse.ui.effects.rememberLcarsCue
import dev.mascwa.pulse.ui.theme.Antonio
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/** What the control panel can be asked to do. */
internal class ControlActions(
    val tap: (Control) -> Unit,
    val volume: (PhoneControls.Stream, Boolean) -> Unit,
)

/**
 * The home console's quick settings: a block for each control, lit when it is on, and a volume bar
 * for media, ring and alarm.
 *
 * A control LCARS cannot change says why under its name, and a tap on it opens Android's own control
 * for the same thing — never a switch that does nothing.
 */
@Composable
internal fun ControlsPanel(
    reading: ControlsState.Reading?,
    volumes: List<PhoneControls.Volume>,
    tab: Color,
    progress: () -> Float,
    index: Int,
    actions: ControlActions,
    modifier: Modifier = Modifier,
) {
    val c = Pulse.colors
    ConsolePanel(TITLE, tab, progress, index, modifier) {
        if (reading == null) {
            Text(
                READING_LINE,
                fontFamily = JetBrainsMono,
                fontSize = 12.sp,
                color = legibleOn(c.muted, c.void),
            )
        } else {
            ControlsState.tiles(reading).chunked(PER_ROW).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { tile -> ControlTile(tile, tab, actions, Modifier.weight(1f)) }
                    repeat(PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        volumes.forEach { v -> VolumeRow(v, tab, actions) }
    }
}

@Composable
private fun ControlTile(tile: ControlsState.Tile, tab: Color, actions: ControlActions, modifier: Modifier) {
    val c = Pulse.colors
    val cue = rememberLcarsCue()
    val lit = tile.on == true
    val block = if (lit) tab else c.raise
    Column(
        modifier
            .heightIn(min = 58.dp)
            .clip(lcarsBlockShape(10.dp, LcarsCorner.TopStart))
            .background(block)
            .clickable(onClickLabel = if (tile.canAct) "Change ${tile.label}" else "Open Android's ${tile.label} control") {
                cue(SoundCue.TAP, HapticCue.TAP_CRISP)
                actions.tap(tile.control)
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            tile.label.uppercase(),
            fontFamily = Antonio,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            color = textOnBlock(block),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            tile.state,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            color = textOnBlock(block),
            maxLines = 1,
        )
        if (tile.why != null) {
            Text(
                tile.why,
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                color = textOnBlock(block),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VolumeRow(v: PhoneControls.Volume, tab: Color, actions: ControlActions) {
    val c = Pulse.colors
    val cue = rememberLcarsCue()
    val lit = if (v.max > 0) v.level.toFloat() / v.max else 0f
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            v.stream.label.uppercase(),
            fontFamily = Antonio,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            color = legibleOn(tab, c.void),
            modifier = Modifier.width(56.dp),
        )
        StepButton("−", c.raise) { cue(SoundCue.TAP, HapticCue.TAP_CRISP); actions.volume(v.stream, false) }
        Spacer(Modifier.width(6.dp))
        LcarsFillRow(
            listOf(lit to tab, (1f - lit) to c.raise),
            Modifier.weight(1f).height(12.dp),
            gap = 1.5.dp,
        )
        Spacer(Modifier.width(6.dp))
        StepButton("+", c.raise) { cue(SoundCue.TAP, HapticCue.TAP_CRISP); actions.volume(v.stream, true) }
        Spacer(Modifier.width(6.dp))
        Text(
            "${v.level}/${v.max}",
            fontFamily = JetBrainsMono,
            fontSize = 10.sp,
            color = legibleOn(c.muted, c.void),
            modifier = Modifier.width(36.dp),
        )
    }
}

@Composable
private fun StepButton(label: String, block: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(lcarsBlockShape(6.dp, LcarsCorner.TopStart))
            .background(block)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = textOnBlock(block))
    }
}

private const val TITLE = "Controls"
private const val PER_ROW = 2
private const val READING_LINE = "Reading the phone's settings…"
