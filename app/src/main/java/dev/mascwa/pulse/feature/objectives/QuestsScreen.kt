package dev.mascwa.pulse.feature.objectives

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import dev.mascwa.pulse.feature.common.LcarsIcons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.LocalNightwire
import dev.mascwa.pulse.ui.theme.Pulse
import dev.mascwa.pulse.ui.theme.lcarsPalette

/**
 * OBJECTIVES feed tab — the same objective log as the NAV map's OBJECTIVES sub-tab, in the LCARS
 * palette. Shares the one [ObjectivesViewModel]/`WaypointStore`, so tracking here updates the map
 * markers (and vice-versa) live. Reuses [ObjectivesPanel] (the shared objectives-tracker layout).
 */
@Composable
fun QuestsScreen(vm: ObjectivesViewModel, onBack: () -> Unit) {
    CompositionLocalProvider(LocalNightwire provides lcarsPalette) {
        val c = Pulse.colors
        PulseScaffold(
            title = "OBJECTIVES",
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(LcarsIcons.ArrowBack, "Back", tint = c.ink) }
            },
            actions = { IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh", tint = c.ink) } },
        ) { innerPadding ->
            ObjectivesPanel(vm, c, Modifier.fillMaxSize().padding(innerPadding))
        }
    }
}
