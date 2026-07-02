package dev.mascwa.pulse.feature.survive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.PipFrame
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
    Box(Modifier.fillMaxSize().background(c.void)) {
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
                item { PipHubTile("SOS", "Strobe, alarm, call & text for help", Icons.Filled.Sos, { onOpenRoute(Routes.SOS) }, accent = c.magenta) }
                item { PipHubTile("Compass", "Heading & true north", Icons.Filled.Explore, { onOpenRoute(Routes.COMPASS) }, accent = c.positive) }
                item { PipHubTile("Survival Guides", "First aid, water, fire, signalling", Icons.AutoMirrored.Filled.MenuBook, { onOpenRoute(Routes.SURVIVAL) }, accent = c.positive) }
                item { PipHubTile("Tools", "SOS strobe, alarm, morse", Icons.Filled.Bolt, { onOpenRoute(Routes.TOOLS) }, accent = c.positive) }
                item { PipHubTile("Nearest Help", "Last cached hospitals & shelters", Icons.Filled.LocalHospital, { onOpenRoute(Routes.PLACES) }) }
                item { PipHubTile("Nearby Safety", "Last cached hazards", Icons.Filled.Warning, { onOpenRoute(Routes.SAFETY) }, accent = c.amber) }
            }
            Text(
                "▸ ENTER APP ANYWAY",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp,
                color = c.muted,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clickable { onDismiss() }.padding(vertical = 10.dp),
            )
        }
    }
}

/** A flat corner-bracketed Pip-Boy tile (matches the Survive hub): accent icon, title, subtitle. */
@Composable
private fun PipHubTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    accent: Color = Pulse.colors.accent,
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
