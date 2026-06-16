package dev.mascwa.pulse.feature.tacnet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mascwa.pulse.feature.common.HubTile
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.navigation.Routes

@Composable
fun TacnetHubScreen(onOpenRoute: (String) -> Unit, onBack: (() -> Unit)? = null) {
    PulseScaffold(
        title = "Tacnet",
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
            item { HubTile("Radar", "Live aircraft, ISS & quakes on a sweeping scope", Icons.Filled.Radar, { onOpenRoute(Routes.RADAR) }) }
            item { HubTile("Telemetry", "On-device sensors, system & GPS · offline", Icons.Filled.Sensors, { onOpenRoute(Routes.TELEMETRY) }) }
        }
    }
}
