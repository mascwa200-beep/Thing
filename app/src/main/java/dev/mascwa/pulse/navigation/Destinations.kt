package dev.mascwa.pulse.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.GridView
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

    // Grid hub + Sky (Phase 1)
    const val GRID = "grid"
    const val SKY = "sky"
    const val COMPASS = "compass"
    const val SPACE_WX = "space_wx"
    const val ORBITAL = "orbital"

    // Survive (Phase 2)
    const val SURVIVE = "survive"
    const val SOS = "sos"
    const val PLACES = "places"
    const val SURVIVAL = "survival"
    const val TOOLS = "tools"
    const val SAFETY = "safety"
    const val MAP = "map"

    // Social & search (Phase 3)
    const val SOCIAL = "social"
    const val SEARCH = "search"
    const val IMAGES = "images"

    // Tacnet (real-time radar + telemetry)
    const val TACNET = "tacnet"
    const val RADAR = "radar"
    const val TELEMETRY = "telemetry"

    // J.A.R.V.I.S. Matrix (on-device assistant)
    const val JARVIS = "jarvis"
    const val JARVIS_SETUP = "jarvis_setup"

    // 3D cyberpunk navigation map
    const val NAV = "nav"

    // Diagnostics
    const val CRASH_LOG = "crash_log"
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
    TopDestination(Routes.GRID, "GRID", Icons.Filled.GridView, Icons.Outlined.GridView),
    TopDestination(Routes.SETTINGS, "SYS", Icons.Filled.Settings, Icons.Outlined.Settings),
)
