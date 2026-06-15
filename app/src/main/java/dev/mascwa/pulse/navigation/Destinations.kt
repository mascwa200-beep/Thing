package dev.mascwa.pulse.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.filled.Article
import androidx.compose.ui.graphics.vector.ImageVector

object Routes {
    const val HOME = "home"
    const val NEWS = "news"
    const val MARKETS = "markets"
    const val WEATHER = "weather"
    const val SETTINGS = "settings"
    const val ECONOMY = "economy"
    const val INFLATION = "inflation"
    const val FUEL = "fuel"
}

data class TopDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

val TOP_DESTINATIONS = listOf(
    TopDestination(Routes.HOME, "PULSE", Icons.Filled.Home, Icons.Outlined.Home),
    TopDestination(Routes.NEWS, "WIRE", Icons.Filled.Article, Icons.Outlined.Article),
    TopDestination(Routes.MARKETS, "MARKETS", Icons.Filled.ShowChart, Icons.Outlined.ShowChart),
    TopDestination(Routes.WEATHER, "WX", Icons.Filled.WbSunny, Icons.Outlined.WbSunny),
    TopDestination(Routes.SETTINGS, "SYS", Icons.Filled.Settings, Icons.Outlined.Settings),
)
