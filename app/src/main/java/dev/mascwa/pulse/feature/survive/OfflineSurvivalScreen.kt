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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                items(offlineSurviveTiles(), key = { it.route }) { tile ->
                    SurviveTileCard(tile, onOpenRoute)
                }
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
