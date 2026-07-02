package dev.mascwa.pulse.feature.survive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.PipFrame
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.navigation.Routes
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun SurviveHubScreen(onOpenRoute: (String) -> Unit, onBack: (() -> Unit)? = null) {
    PulseScaffold(
        title = "Survive",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        SurviveBody(onOpenRoute, Modifier.padding(innerPadding))
    }
}

/** The scaffold-free SURVIVE hub grid — hosted standalone in [SurviveHubScreen] and as the SURVIVE
 *  sub-tab inside the PIP-BOY STATS page. Each tile deep-links to its survival tool. */
@Composable
fun SurviveBody(onOpenRoute: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PipHubTile("SOS", "Strobe, alarm, call & text for help", Icons.Filled.Sos, c.magenta) { onOpenRoute(Routes.SOS) } }
        item { PipHubTile("Nearest Help", "Hospitals, shelters, food banks, towers", Icons.Filled.LocalHospital, c.accent) { onOpenRoute(Routes.PLACES) } }
        item { PipHubTile("Nearby Safety", "Quakes, disasters & weather alerts near you", Icons.Filled.Warning, c.amber) { onOpenRoute(Routes.SAFETY) } }
        item { PipHubTile("Map", "Incidents & help on the live nav map", Icons.Filled.Map, c.accent) { onOpenRoute(Routes.NAV) } }
        item { PipHubTile("Survival Guides", "First aid, water, fire, signalling · offline", Icons.AutoMirrored.Filled.MenuBook, c.positive) { onOpenRoute(Routes.SURVIVAL) } }
        item { PipHubTile("Tools", "SOS strobe, alarm, morse · offline", Icons.Filled.Bolt, c.positive) { onOpenRoute(Routes.TOOLS) } }
    }
}

/** A Survive hub tile in the Pip-Boy terminal idiom: a flat corner-bracketed frame with an accent
 *  icon, title, and subtitle. */
@Composable
private fun PipHubTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
) {
    val c = Pulse.colors
    PipFrame(Modifier.fillMaxWidth().clickable { onClick() }, accent = accent) {
        Column {
            Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
            Text(
                title, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                color = c.ink, modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                subtitle, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
