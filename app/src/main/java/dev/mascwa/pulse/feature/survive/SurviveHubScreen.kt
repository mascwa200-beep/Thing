package dev.mascwa.pulse.feature.survive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mascwa.pulse.feature.common.HubTile
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.navigation.Routes
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun SurviveHubScreen(onOpenRoute: (String) -> Unit, onBack: (() -> Unit)? = null) {
    val c = Pulse.colors
    PulseScaffold(
        title = "Survive",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HubTile("SOS", "Strobe, alarm, call & text for help", Icons.Filled.Sos, { onOpenRoute(Routes.SOS) }, accent = c.magenta) }
            item { HubTile("Nearest Help", "Hospitals, shelters, food banks, towers", Icons.Filled.LocalHospital, { onOpenRoute(Routes.PLACES) }) }
            item { HubTile("Nearby Safety", "Quakes, disasters & weather alerts near you", Icons.Filled.Warning, { onOpenRoute(Routes.SAFETY) }, accent = c.amber) }
            item { HubTile("Map", "Incidents & help on the live nav map", Icons.Filled.Map, { onOpenRoute(Routes.NAV) }) }
            item { HubTile("Survival Guides", "First aid, water, fire, signalling · offline", Icons.AutoMirrored.Filled.MenuBook, { onOpenRoute(Routes.SURVIVAL) }, accent = c.positive) }
            item { HubTile("Tools", "SOS strobe, alarm, morse · offline", Icons.Filled.Bolt, { onOpenRoute(Routes.TOOLS) }, accent = c.positive) }
        }
    }
}
