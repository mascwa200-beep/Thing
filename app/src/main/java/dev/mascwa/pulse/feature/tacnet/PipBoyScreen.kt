package dev.mascwa.pulse.feature.tacnet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.data.sensors.Telemetry
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.sky.DataBody
import dev.mascwa.pulse.feature.sky.OrbitalViewModel
import dev.mascwa.pulse.feature.sky.SpaceWeatherViewModel
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.LocalNightwire
import dev.mascwa.pulse.ui.theme.Pulse
import dev.mascwa.pulse.ui.theme.pipBoyPalette
import kotlin.math.roundToInt

/**
 * The combined PIP-BOY tab — radar, phone telemetry, orbital and space weather under one Fallout
 * Pip-Boy STAT screen. The four feeds are the literal Pip-Boy sections: STATUS (telemetry / the
 * Vault-Boy condition readout), DATA (orbital), MAP (RADSCOPE) and RADIO (space weather). Each feed's
 * scaffold-free *Body is hosted inside the green CRT chrome.
 */
private enum class PipTab(val label: String) {
    STATUS("STATUS"), DATA("DATA"), MAP("MAP"), QUESTS("QUESTS"), NOTES("NOTES"), RADIO("RADIO"),
}

@Composable
fun PipBoyScreen(
    radarVm: RadarViewModel,
    telemetryVm: TelemetryViewModel,
    orbitalVm: OrbitalViewModel,
    spaceWxVm: SpaceWeatherViewModel,
    radioVm: RadioViewModel,
    notesVm: dev.mascwa.pulse.feature.notes.NotesViewModel,
    objectivesVm: dev.mascwa.pulse.feature.objectives.ObjectivesViewModel,
    onBack: (() -> Unit)? = null,
) {
    var tab by remember { mutableStateOf(PipTab.STATUS) }
    // Live device readouts for the persistent STAT strip (STATUS is the default tab, so telemetry
    // starts on open; values persist across sub-tabs).
    val telem by telemetryVm.telemetry.collectAsStateWithLifecycle()
    PulseScaffold(
        title = "PIP-BOY",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Pip.bright)
            }
        },
        actions = {
            IconButton(onClick = {
                when (tab) {
                    PipTab.STATUS -> Unit // telemetry is live; nothing to pull
                    PipTab.DATA -> { orbitalVm.refresh(); spaceWxVm.refresh() }
                    PipTab.MAP -> radarVm.refresh()
                    PipTab.QUESTS -> objectivesVm.refresh()
                    PipTab.NOTES -> Unit // the library is local; nothing to refresh
                    PipTab.RADIO -> Unit // the radio has its own tuner controls
                }
            }) { Icon(Icons.Filled.Refresh, "Refresh", tint = Pip.bright) }
        },
    ) { innerPadding ->
        // Re-theme the whole subtree to phosphor green so the four feeds read as one Pip-Boy unit
        // (also provided app-wide for the TOOLS section; kept here so PIP-BOY is self-contained).
        CompositionLocalProvider(LocalNightwire provides pipBoyPalette) {
            Column(Modifier.padding(innerPadding).fillMaxSize().background(Pip.bg)) {
                PipStatHeader(telem)
                PipTabRail(tab) { tab = it }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        PipTab.STATUS -> TelemetryBody(telemetryVm)
                        PipTab.DATA -> DataBody(orbitalVm, spaceWxVm)
                        PipTab.MAP -> RadarBody(radarVm)
                        PipTab.QUESTS -> dev.mascwa.pulse.feature.objectives.ObjectivesPanel(
                            objectivesVm, Pulse.colors, Modifier.fillMaxSize(),
                        )
                        PipTab.NOTES -> dev.mascwa.pulse.feature.notes.NotesBody(notesVm)
                        PipTab.RADIO -> RadioBody(radioVm)
                    }
                    // A faint CRT scanline tube over every feed — decorative, so it passes touches
                    // through (no pointer input) and stays low-alpha to keep text readable.
                    Canvas(Modifier.matchParentSize()) { crtScanlines(Color.Black.copy(alpha = 0.12f), gap = 3f) }
                }

                PipStatBar(telem, tab)
            }
        }
    }
}

/** Fallout-style section rail: STATUS · DATA · MAP · RADIO, the selected one lit + underlined. */
@Composable
private fun PipTabRail(current: PipTab, onSelect: (PipTab) -> Unit) {
    Box(Modifier.fillMaxWidth().background(Pip.bg)) {
        Canvas(Modifier.matchParentSize()) { crtScanlines(Pip.gridSoft, gap = 3f) }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            PipTab.entries.forEach { t ->
                val on = t == current
                Text(
                    t.label,
                    fontFamily = ChakraPetch,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 15.sp,
                    letterSpacing = 1.5.sp,
                    color = if (on) Pip.glow else Pip.dim,
                    textDecoration = if (on) TextDecoration.Underline else TextDecoration.None,
                    modifier = Modifier.clickable { onSelect(t) },
                )
            }
        }
    }
}

/** Free memory as a 0–100 %, used for the AP gauge. */
private fun freeMemPercent(t: Telemetry): Float =
    if (t.memTotalMb > 0) (((t.memTotalMb - t.memUsedMb) * 100f) / t.memTotalMb).coerceIn(0f, 100f) else 0f

/** The top STAT readout strip (persistent across sub-tabs) — live device data in Fallout STAT style. */
@Composable
private fun PipStatHeader(t: Telemetry) {
    val bat = (t.batteryPct ?: 0).coerceIn(0, 100)
    Row(
        Modifier.fillMaxWidth().background(Pip.bg).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("STAT", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp, color = Pip.glow)
        Text("HP $bat%${if (t.charging) " ⚡" else ""}", fontFamily = JetBrainsMono, fontSize = 10.sp, color = Pip.mid)
        Text("AP ${freeMemPercent(t).roundToInt()}%", fontFamily = JetBrainsMono, fontSize = 10.sp, color = Pip.mid)
        if (t.netType.isNotBlank()) Text(t.netType, fontFamily = JetBrainsMono, fontSize = 10.sp, color = Pip.dim)
    }
}

/** The bottom Pip-Boy status bar — HP (battery) and AP (free memory) gauges flanking the level. */
@Composable
private fun PipStatBar(t: Telemetry, tab: PipTab) {
    val bat = (t.batteryPct ?: 0).coerceIn(0, 100)
    val freeMem = freeMemPercent(t)
    val level = dev.mascwa.pulse.BuildConfig.VERSION_CODE
    Column(Modifier.fillMaxWidth().background(Pip.bg).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatGauge("HP", "$bat%", bat / 100f, Modifier.weight(1f))
            Text("LVL $level", fontFamily = JetBrainsMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Pip.glow)
            StatGauge("AP", "${freeMem.roundToInt()}%", freeMem / 100f, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("ARGUS DYNAMICS", fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = Pip.dim)
            Text("▸ ${tab.label}", fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = Pip.mid)
        }
    }
}

/** One labelled HP/AP-style gauge: label + value over a thin filled bar. */
@Composable
private fun StatGauge(label: String, value: String, fraction: Float, modifier: Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontFamily = JetBrainsMono, fontSize = 9.sp, color = Pip.dim)
            Text(value, fontFamily = JetBrainsMono, fontSize = 9.sp, color = Pip.mid)
        }
        Canvas(Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp)) {
            drawRect(Pip.gridSoft)
            drawRect(Pip.bright, size = androidx.compose.ui.geometry.Size(size.width * fraction.coerceIn(0f, 1f), size.height))
        }
    }
}
