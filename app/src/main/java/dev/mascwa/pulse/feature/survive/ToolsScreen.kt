package dev.mascwa.pulse.feature.survive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import dev.mascwa.pulse.feature.common.LcarsIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun ToolsScreen(vm: ToolsViewModel, onBack: (() -> Unit)? = null) {
    val strobe by vm.strobe.collectAsStateWithLifecycle()
    val alarm by vm.alarm.collectAsStateWithLifecycle()
    val torch by vm.torch.collectAsStateWithLifecycle()
    var flare by remember { mutableStateOf(false) }
    val c = Pulse.colors

    if (flare) {
        // Full-screen bright flare for signalling; tap to exit.
        Box(
            Modifier.fillMaxSize().background(Color.White).clickable { flare = false },
            contentAlignment = Alignment.Center,
        ) {
            Text("TAP TO EXIT", color = Color.Black, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold)
        }
        return
    }

    PulseScaffold(
        title = "Survival Tools",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(LcarsIcons.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        Column(
            Modifier.padding(innerPadding).padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LcarsHeaderBar("Signal")
            ToolButton(
                title = if (strobe) "SOS strobe — ON" else "SOS strobe",
                subtitle = if (vm.torchAvailable) "Flashes · · · — — — · · · in morse" else "No flashlight on this device",
                active = strobe, accent = c.amber, enabled = vm.torchAvailable,
            ) { vm.toggleStrobe() }
            ToolButton("Screen flare", "Full-bright white screen to signal", active = false, accent = c.sky) { flare = true }

            LcarsHeaderBar("Light")
            ToolButton(
                title = if (torch) "Flashlight — ON" else "Flashlight",
                subtitle = if (vm.torchAvailable) "Steady torch" else "No flashlight on this device",
                active = torch, accent = c.amber, enabled = vm.torchAvailable,
            ) { vm.toggleTorch() }

            LcarsHeaderBar("Alert")
            ToolButton(
                title = if (alarm) "Loud alarm — ON" else "Loud alarm",
                subtitle = "Maximum-volume attention tone",
                active = alarm, accent = c.magenta,
            ) { vm.toggleAlarm() }
            ToolButton("Vibrate SOS", "Pulse the SOS pattern", active = false, accent = c.accent) { vm.sosVibrate() }

            Text(
                "These tools run fully offline. The strobe and alarm keep running until you turn them off — mind your battery.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ToolButton(
    title: String,
    subtitle: String,
    active: Boolean,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Pulse.colors
    LcarsFrame(
        modifier = Modifier.fillMaxWidth()
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        accent = if (active) accent else c.line,
    ) {
        Column {
            Text(title, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                color = if (enabled) (if (active) accent else c.ink) else c.muted)
            Text(subtitle, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
        }
    }
}
