package dev.mascwa.pulse.feature.grid

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Rocket
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.HubTile
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.navigation.Routes
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

private data class GridEntry(
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val route: String,
    val offlineCapable: Boolean,
)

@Composable
fun GridHubScreen(onOpenRoute: (String) -> Unit) {
    val context = LocalContext.current
    val online = remember(context) { isOnline(context) }
    val c = Pulse.colors

    val entries = listOf(
        GridEntry("Sky", "Space wx · orbital · compass", Icons.Filled.Rocket, Routes.SKY, true),
        GridEntry("Compass", "Heading · offline", Icons.Filled.Explore, Routes.COMPASS, true),
        GridEntry("Space Weather", "Kp · aurora · alerts", Icons.Filled.Bolt, Routes.SPACE_WX, false),
        GridEntry("Orbital", "ISS · sun · moon · NEOs", Icons.Filled.Public, Routes.ORBITAL, false),
        GridEntry("Economy", "Inflation · GDP · jobs", Icons.Filled.AccountBalance, Routes.ECONOMY, false),
        GridEntry("Inflation", "CPI history", Icons.Filled.Percent, Routes.INFLATION, false),
        GridEntry("Fuel & Energy", "Benchmarks · pump prices", Icons.Filled.LocalGasStation, Routes.FUEL, false),
    )

    PulseScaffold(title = "Grid") { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!online) {
                item(span = { GridItemSpan(maxLineSpan) }) { StaleBanner(true) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "OFFLINE — survival tools, guides & compass below work with no signal.",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber,
                    )
                }
            }
            items(entries.size, key = { entries[it].route }) { i ->
                val e = entries[i]
                HubTile(
                    title = e.title, subtitle = e.subtitle, icon = e.icon,
                    onClick = { onOpenRoute(e.route) },
                    accent = if (!online && e.offlineCapable) c.positive else c.accent,
                    badge = if (!online && e.offlineCapable) "OFFLINE OK" else null,
                )
            }
        }
    }
}

private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
