package dev.mascwa.pulse.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import dev.mascwa.pulse.feature.common.LcarsIcons

object Routes {
    const val HOME = "home"
    const val NEWS = "news"
    const val MARKETS = "markets"
    const val WEATHER = "weather"
    const val SETTINGS = "settings"
    const val ECONOMY = "economy"
    const val INFLATION = "inflation"
    const val FUEL = "fuel"

    // Sky (Phase 1)
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
    const val HABITAT = "habitat" // offline animal-habitat / wildlife map

    // Social & search (Phase 3)
    const val SOCIAL = "social"
    const val SEARCH = "search"

    // Tacnet (real-time radar + telemetry)
    const val TACNET = "tacnet"
    const val RADAR = "radar"
    const val TELEMETRY = "telemetry"

    // ORACLE — J.A.R.V.I.S.'s cross-signal foresight HUD
    const val ORACLE = "oracle"

    // J.A.R.V.I.S. Matrix (on-device assistant)
    const val JARVIS = "jarvis"
    const val JARVIS_SETUP = "jarvis_setup"
    const val JARVIS_APPROVALS = "jarvis_approvals"
    const val JARVIS_MEMORY = "jarvis_memory"
    const val JARVIS_DOSSIER = "jarvis_dossier"

    // 3D cyberpunk navigation map
    const val NAV = "nav"

    // Objectives / waypoint tracker (calendar + manual)
    const val OBJECTIVES = "objectives"

    // Objectives — the objective log as its own LCARS feed tab
    const val QUESTS = "quests"

    // Diagnostics
    const val CRASH_LOG = "crash_log"

    // On-device security auditor (read-only, local-only)
    const val SECURITY_AUDIT = "security_audit"
}

// selectedIcon/unselectedIcon intentionally carry the SAME LcarsIcons glyph — bottom-nav selection
// state is conveyed entirely by icon color (PulseApp.kt's NavigationBarItemDefaults.colors), not by
// swapping shapes, so a single hand-drawn glyph per destination covers both states.
data class TopDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

val TOP_DESTINATIONS = listOf(
    TopDestination(Routes.HOME, "PULSE", LcarsIcons.Home, LcarsIcons.Home),
    TopDestination(Routes.NEWS, "NEWS", LcarsIcons.Article, LcarsIcons.Article),
    TopDestination(Routes.MARKETS, "MARKETS", LcarsIcons.ShowChart, LcarsIcons.ShowChart),
    TopDestination(Routes.WEATHER, "WX", LcarsIcons.WbSunny, LcarsIcons.WbSunny),
    TopDestination(Routes.JARVIS, "COMPUTER", LcarsIcons.AutoAwesome, LcarsIcons.AutoAwesome),
    // TOOLS is a pseudo-destination: it opens the LCARS feed tabs (FEED_HOME) and highlights on
    // any feed route — handled specially in PulseApp. TACNET (the LCARS screen) is the feed home.
    TopDestination(Routes.TACNET, "TOOLS", LcarsIcons.GridView, LcarsIcons.GridView),
    TopDestination(Routes.SETTINGS, "SYS", LcarsIcons.Settings, LcarsIcons.Settings),
)
