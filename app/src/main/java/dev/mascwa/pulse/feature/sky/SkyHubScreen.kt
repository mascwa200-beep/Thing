package dev.mascwa.pulse.feature.sky

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import dev.mascwa.pulse.feature.common.LcarsIcons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mascwa.pulse.feature.common.HubTile
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.navigation.Routes

@Composable
fun SkyHubScreen(onOpenRoute: (String) -> Unit, onBack: (() -> Unit)? = null) {
    PulseScaffold(
        title = "Sky",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(LcarsIcons.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HubTile("Compass", "Heading, true north & coordinates · offline", Icons.Filled.Explore, { onOpenRoute(Routes.COMPASS) }) }
            item { HubTile("Space Weather", "Kp, solar wind, aurora & alerts", Icons.Filled.Bolt, { onOpenRoute(Routes.SPACE_WX) }) }
            item { HubTile("Orbital", "ISS, sun, moon & near-Earth objects", Icons.Filled.Public, { onOpenRoute(Routes.ORBITAL) }) }
        }
    }
}
