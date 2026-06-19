package dev.mascwa.pulse.feature.grid

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.feature.common.hudCorners
import dev.mascwa.pulse.navigation.Routes
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

private data class GridEntry(
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val route: String,
    val offlineCapable: Boolean,
)

private data class GridSection(val title: String, val entries: List<GridEntry>)

@Composable
fun GridHubScreen(onOpenRoute: (String) -> Unit) {
    val context = LocalContext.current
    val online = remember(context) { isOnline(context) }
    val c = Pulse.colors

    val sections = listOf(
        GridSection("ASSISTANT", listOf(
            GridEntry("J.A.R.V.I.S.", "On-device assistant · private", Icons.Filled.SmartToy, Routes.JARVIS, true),
        )),
        GridSection("NAVIGATION & FIELD", listOf(
            GridEntry("Nav", "3D cyber-map · heading-up · trail", Icons.Filled.Map, Routes.NAV, false),
            GridEntry("Objectives", "Missions · waypoints · calendar", Icons.Filled.Flag, Routes.OBJECTIVES, true),
            GridEntry("Compass", "Heading · offline", Icons.Filled.Explore, Routes.COMPASS, true),
            GridEntry("Survive", "Nearest help · SOS · offline guides", Icons.Filled.HealthAndSafety, Routes.SURVIVE, true),
            GridEntry("Tacnet", "Live radar · flight telemetry · vitals", Icons.Filled.Radar, Routes.TACNET, true),
        )),
        GridSection("SPACE", listOf(
            GridEntry("Space Weather", "Kp · aurora · alerts", Icons.Filled.Bolt, Routes.SPACE_WX, false),
            GridEntry("Orbital", "ISS · sun · moon · NEOs", Icons.Filled.Public, Routes.ORBITAL, false),
        )),
        GridSection("MARKETS & ECONOMY", listOf(
            GridEntry("Economy", "Inflation · GDP · jobs", Icons.Filled.AccountBalance, Routes.ECONOMY, false),
            GridEntry("Inflation", "CPI history", Icons.Filled.Percent, Routes.INFLATION, false),
            GridEntry("Fuel & Energy", "Benchmarks · pump prices", Icons.Filled.LocalGasStation, Routes.FUEL, false),
        )),
        GridSection("INFO & MEDIA", listOf(
            GridEntry("Social", "Lemmy · Mastodon · Hacker News", Icons.Filled.Forum, Routes.SOCIAL, false),
            GridEntry("Search", "DuckDuckGo / Google / Brave", Icons.Filled.TravelExplore, Routes.SEARCH, false),
            GridEntry("Images", "Search the web or your own sites", Icons.Filled.Image, Routes.IMAGES, false),
        )),
    )

    PulseScaffold(title = "Tools") { innerPadding ->
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
            sections.forEach { section ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "hdr_${section.title}") {
                    PipSectionBar(section.title)
                }
                items(section.entries.size, key = { section.entries[it].route }) { i ->
                    val e = section.entries[i]
                    PipHubTile(
                        title = e.title, subtitle = e.subtitle, icon = e.icon,
                        onClick = { onOpenRoute(e.route) },
                        badge = if (!online && e.offlineCapable) "OFFLINE OK" else null,
                    )
                }
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

// ---- Fallout Pip-Boy phosphor-green styling for the Tools hub (matches the radar) ----

private object Pip {
    val bg = Color(0xFF04130A)
    val grid = Color(0xFF15462A)
    val dim = Color(0xFF2E8F52)
    val mid = Color(0xFF3FCB74)
    val bright = Color(0xFF5BFF9B)
    val glow = Color(0xFF9CFFC4)
}

@Composable
private fun PipSectionBar(title: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(Pip.bright))
        Text(
            title.uppercase(),
            fontFamily = JetBrainsMono, fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp, letterSpacing = 2.4.sp, color = Pip.mid,
            modifier = Modifier.padding(start = 9.dp),
        )
    }
}

@Composable
private fun PipHubTile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    badge: String? = null,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Pip.bg)
            .border(1.dp, Pip.grid, RoundedCornerShape(10.dp))
            .drawWithContent {
                drawContent()
                hudCorners(Pip.bright, 12.dp.toPx(), 1.6.dp.toPx(), 3.dp.toPx())
            }
            .clickable { onClick() }
            .padding(14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Pip.bright, modifier = Modifier.size(22.dp))
                if (badge != null) {
                    Box(
                        Modifier.padding(start = 8.dp).clip(RoundedCornerShape(5.dp))
                            .background(Pip.bright).padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(badge, fontFamily = JetBrainsMono, fontSize = 8.sp, color = Pip.bg)
                    }
                }
            }
            Text(
                title, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                color = Pip.glow, modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                subtitle, fontFamily = JetBrainsMono, fontSize = 9.sp, color = Pip.dim,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}
