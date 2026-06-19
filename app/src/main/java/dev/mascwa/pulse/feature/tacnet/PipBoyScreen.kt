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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.sky.OrbitalBody
import dev.mascwa.pulse.feature.sky.OrbitalViewModel
import dev.mascwa.pulse.feature.sky.SpaceWeatherBody
import dev.mascwa.pulse.feature.sky.SpaceWeatherViewModel
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.LocalNightwire
import dev.mascwa.pulse.ui.theme.pipBoyPalette

/**
 * The combined PIP-BOY tab — radar, phone telemetry, orbital and space weather under one Fallout
 * Pip-Boy STAT screen. The four feeds are the literal Pip-Boy sections: STATUS (telemetry / the
 * Vault-Boy condition readout), DATA (orbital), MAP (RADSCOPE) and RADIO (space weather). Each feed's
 * scaffold-free *Body is hosted inside the green CRT chrome.
 */
private enum class PipTab(val label: String) {
    STATUS("STATUS"), DATA("DATA"), MAP("MAP"), RADIO("RADIO"),
}

@Composable
fun PipBoyScreen(
    radarVm: RadarViewModel,
    telemetryVm: TelemetryViewModel,
    orbitalVm: OrbitalViewModel,
    spaceWxVm: SpaceWeatherViewModel,
    onBack: (() -> Unit)? = null,
) {
    var tab by remember { mutableStateOf(PipTab.STATUS) }
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
                    PipTab.DATA -> orbitalVm.refresh()
                    PipTab.MAP -> radarVm.refresh()
                    PipTab.RADIO -> spaceWxVm.refresh()
                }
            }) { Icon(Icons.Filled.Refresh, "Refresh", tint = Pip.bright) }
        },
    ) { innerPadding ->
        // Re-theme the whole subtree to phosphor green so the four feeds read as one Pip-Boy unit
        // (also provided app-wide for the TOOLS section; kept here so PIP-BOY is self-contained).
        CompositionLocalProvider(LocalNightwire provides pipBoyPalette) {
            Column(Modifier.padding(innerPadding).fillMaxSize().background(Pip.bg)) {
                PipTabRail(tab) { tab = it }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        PipTab.STATUS -> TelemetryBody(telemetryVm)
                        PipTab.DATA -> OrbitalBody(orbitalVm)
                        PipTab.MAP -> RadarBody(radarVm)
                        PipTab.RADIO -> SpaceWeatherBody(spaceWxVm)
                    }
                }

                PipStatusBar(tab)
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

/** A thin Pip-Boy footer strip — the HP/AP-style status line, kept glanceable. */
@Composable
private fun PipStatusBar(tab: PipTab) {
    Row(
        Modifier.fillMaxWidth().background(Pip.bg).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "VAULT-TEC ⬢ NIGHTWIRE",
            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = Pip.dim,
        )
        Text(
            "▸ ${tab.label}",
            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = Pip.mid,
        )
    }
}
