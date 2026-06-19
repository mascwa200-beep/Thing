package dev.mascwa.pulse.feature.objectives

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.LocalNightwire
import dev.mascwa.pulse.ui.theme.Pulse
import dev.mascwa.pulse.ui.theme.pipBoyPalette

/**
 * QUESTS feed tab — the same objectives/quest log as the NAV map's OBJECTIVES sub-tab, in the Pip-Boy
 * phosphor-green palette. Shares the one [ObjectivesViewModel]/`WaypointStore`, so tracking here updates
 * the map markers (and vice-versa) live. Reuses [ObjectivesPanel] (the Fallout quest-tracker layout).
 */
@Composable
fun QuestsScreen(vm: ObjectivesViewModel, onBack: () -> Unit) {
    CompositionLocalProvider(LocalNightwire provides pipBoyPalette) {
        val c = Pulse.colors
        PulseScaffold(
            title = "QUESTS",
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.ink) }
            },
            actions = { IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh", tint = c.ink) } },
        ) { innerPadding ->
            ObjectivesPanel(vm, c, Modifier.fillMaxSize().padding(innerPadding))
        }
    }
}
