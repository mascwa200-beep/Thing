package dev.mascwa.pulse.feature.survive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.HubTile
import dev.mascwa.pulse.navigation.Routes
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * Full-screen takeover shown automatically when the device has no connection.
 * Surfaces only tools that work with no signal; everything else stays reachable
 * once dismissed.
 */
@Composable
fun OfflineSurvivalScreen(onOpenRoute: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Pulse.colors
    Surface(Modifier.fillMaxSize(), color = c.void) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CloudOff, null, tint = c.amber, modifier = Modifier.size(26.dp))
                Text("OFFLINE SURVIVAL MODE", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                    fontSize = 20.sp, letterSpacing = 1.sp, color = c.amber,
                    modifier = Modifier.padding(start = 10.dp))
            }
            Text(
                "No WiFi or cellular connection. These tools work with no signal.",
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                modifier = Modifier.padding(top = 6.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                item { HubTile("SOS", "Strobe, alarm, call & text for help", Icons.Filled.Sos, { onOpenRoute(Routes.SOS) }, accent = c.magenta) }
                item { HubTile("Compass", "Heading & true north", Icons.Filled.Explore, { onOpenRoute(Routes.COMPASS) }, accent = c.positive) }
                item { HubTile("Survival Guides", "First aid, water, fire, signalling", Icons.AutoMirrored.Filled.MenuBook, { onOpenRoute(Routes.SURVIVAL) }, accent = c.positive) }
                item { HubTile("Tools", "SOS strobe, alarm, morse", Icons.Filled.Bolt, { onOpenRoute(Routes.TOOLS) }, accent = c.positive) }
                item { HubTile("Nearest Help", "Last cached hospitals & shelters", Icons.Filled.LocalHospital, { onOpenRoute(Routes.PLACES) }) }
                item { HubTile("Nearby Safety", "Last cached hazards", Icons.Filled.Warning, { onOpenRoute(Routes.SAFETY) }, accent = c.amber) }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Enter app anyway", color = c.muted)
            }
        }
    }
}
