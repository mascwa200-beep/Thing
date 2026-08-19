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
    const val FUEL = "fuel"

    // THE flat directory — every feature, one tap, plain English (feature/menu/MenuScreen).
    const val MENU = "menu"

    // Sky
    const val COMPASS = "compass"
    const val SPACE_WX = "space_wx"
    const val ORBITAL = "orbital"

    // Survive
    const val SURVIVE = "survive"
    const val SOS = "sos"
    const val PLACES = "places"
    const val SURVIVAL = "survival"
    const val TOOLS = "tools"
    const val SAFETY = "safety"
    const val HABITAT = "habitat" // offline animal-habitat / wildlife map

    // Social & search
    const val SOCIAL = "social"
    const val SEARCH = "search"

    // Live radar + device telemetry
    const val RADAR = "radar"
    const val TELEMETRY = "telemetry"

    // Sound
    const val RADIO = "radio"
    const val MUSIC = "music"

    // Personal logs
    const val NOTES = "notes"
    const val DIARY = "diary"

    // ORACLE — the Computer's cross-signal foresight HUD ("Advisories")
    const val ORACLE = "oracle"

    // SENSORIUM — the ambient environment scanner (the ship's senses)
    const val SENSORIUM = "sensorium"

    // The acoustic interrogator — continuous speech capture, transcription and fallacy screening.
    const val INTERROGATOR = "interrogator"

    // STUDY — the bundled library taught rather than browsed
    const val STUDY = "study"

    // PACKS — subject packs that join the library once fetched, and read offline thereafter
    const val PACKS = "packs"

    // The Computer (on-device assistant)
    const val JARVIS = "jarvis"
    const val JARVIS_SETUP = "jarvis_setup"
    const val JARVIS_APPROVALS = "jarvis_approvals"
    const val JARVIS_MEMORY = "jarvis_memory"
    const val JARVIS_DOSSIER = "jarvis_dossier"

    // 3D navigation map
    const val NAV = "nav"

    // Saved places / waypoint tracker (calendar + manual)
    const val OBJECTIVES = "objectives"

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

// Six plain-English tabs, most-used first. Settings lives in MENU (and behind Home's gear) — everything
// that used to hide inside the old console's nested sub-tabs is now one tap from MENU.
val TOP_DESTINATIONS = listOf(
    TopDestination(Routes.HOME, "HOME", LcarsIcons.Home, LcarsIcons.Home),
    TopDestination(Routes.NEWS, "NEWS", LcarsIcons.Article, LcarsIcons.Article),
    TopDestination(Routes.MARKETS, "MARKETS", LcarsIcons.ShowChart, LcarsIcons.ShowChart),
    TopDestination(Routes.WEATHER, "WEATHER", LcarsIcons.WbSunny, LcarsIcons.WbSunny),
    TopDestination(Routes.JARVIS, "COMPUTER", LcarsIcons.AutoAwesome, LcarsIcons.AutoAwesome),
    TopDestination(Routes.MENU, "MENU", LcarsIcons.GridView, LcarsIcons.GridView),
)
