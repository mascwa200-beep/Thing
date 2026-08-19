package dev.mascwa.pulse.navigation

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.mascwa.pulse.ui.theme.NightwirePalette

/**
 * THE directory — every destination in the app, grouped, in plain English.
 *
 * This used to live privately inside `MenuScreen`, which was fine while the menu was the only thing
 * that needed to know the app's shape. It is shared now because a second consumer appeared: the
 * console header, which shows **where you are** on every screen. Both read this, so the section a
 * screen reports and the section the menu files it under cannot disagree — the duplicated-definition
 * mistake this project has already corrected four times over palettes.
 *
 * Nothing here is conditional and nothing is nested. A destination is one tap from the menu and its
 * section is stated on its own header, which together are what make it hard to be lost.
 */
data class MenuEntry(val label: String, val description: String, val route: String)

data class MenuGroup(
    val label: String,
    val accent: (NightwirePalette) -> Color,
    val entries: List<MenuEntry>,
)

val GROUPS = listOf(
    MenuGroup("EMERGENCY", { it.negative }, listOf(
        MenuEntry("SOS", "Call, strobe and alarm for help", Routes.SOS),
        MenuEntry("Nearby Danger", "Earthquakes, disasters and severe weather near you", Routes.SAFETY),
        MenuEntry("Nearest Help", "Hospitals, shelters and food banks around you", Routes.PLACES),
    )),
    MenuGroup("GUIDES", { it.accent }, listOf(
        // The one screen that searches ACROSS survival — every destination and every offline guide,
        // indexed down to section headings, so "knot" or "cpr" lands on the page rather than the
        // library. It was wired into the NavHost and reachable only by deep link, which meant the
        // fastest way into thousands of pages was the one route nothing linked to.
        MenuEntry("Search Survival", "Find any guide or tool by what you need — offline", Routes.SURVIVE),
        MenuEntry("Knowledge Library", "Thousands of pages on everything — works offline", Routes.SURVIVAL),
        MenuEntry("Knowledge Packs", "Add more subjects to the library — downloads once, then offline", Routes.PACKS),
        // Sits beside the library rather than under YOUR THINGS: it is what turns those pages from
        // something you can look up into something you are actually taught.
        MenuEntry("Study", "Learn the library a piece a day, and be asked again", Routes.STUDY),
        MenuEntry("Wildlife Guide", "Animals in your region and what to do — offline", Routes.HABITAT),
        MenuEntry("Field Tools", "Flashlight, strobe, alarm and morse", Routes.TOOLS),
        MenuEntry("Compass", "Offline heading, sun and moon", Routes.COMPASS),
    )),
    MenuGroup("MAPS & SKY", { it.sky }, listOf(
        MenuEntry("Map", "A live 3D map with your saved places", Routes.NAV),
        MenuEntry("Aircraft Radar", "Planes flying near you, live", Routes.RADAR),
        MenuEntry("Space Weather", "Solar storms and aurora chances", Routes.SPACE_WX),
        MenuEntry("Satellites & Asteroids", "The ISS, the Moon and close asteroid passes", Routes.ORBITAL),
    )),
    MenuGroup("SOUND", { it.amber }, listOf(
        MenuEntry("Radio", "Local and internet stations, plays in the background", Routes.RADIO),
        MenuEntry("Music", "Your Spotify player", Routes.MUSIC),
    )),
    MenuGroup("YOUR THINGS", { it.violet }, listOf(
        MenuEntry("Advisories", "The Computer's best next moves for you", Routes.ORACLE),
        MenuEntry("Environment Scanner", "What the ship's senses read around you right now", Routes.SENSORIUM),
        MenuEntry("Interrogator", "Listens, writes down what is said, and questions weak reasoning", Routes.INTERROGATOR),
        MenuEntry("Saved Places", "Places you track, plus calendar stops", Routes.OBJECTIVES),
        MenuEntry("Notes", "Quick notes", Routes.NOTES),
        MenuEntry("Diary", "Your daily log", Routes.DIARY),
    )),
    MenuGroup("INTERNET", { it.positive }, listOf(
        MenuEntry("Search", "This device first, then the web", Routes.SEARCH),
        MenuEntry("Social Feeds", "Lemmy, Mastodon and Hacker News", Routes.SOCIAL),
    )),
    MenuGroup("SYSTEM", { it.muted }, listOf(
        MenuEntry("Settings", "Every switch and preference", Routes.SETTINGS),
        MenuEntry("Device Health", "Battery, sensors, memory and position", Routes.TELEMETRY),
        MenuEntry("Security Check", "A local scan of apps and permissions", Routes.SECURITY_AUDIT),
        MenuEntry("Crash Console", "Fault logs, shareable", Routes.CRASH_LOG),
    )),
)

/** Bottom-nav destinations, which are their own top level rather than sitting under a menu group. */
private val TOP_LEVEL: Map<String, String> = mapOf(
    Routes.HOME to "MAIN",
    Routes.NEWS to "MAIN",
    Routes.MARKETS to "MAIN",
    Routes.WEATHER to "MAIN",
    Routes.JARVIS to "MAIN",
    Routes.MENU to "MAIN",
)

/**
 * Screens reached from inside another screen rather than from the menu.
 *
 * ⚠️ These are the ones most worth labelling, not the least. A destination you opened from the
 * directory, you chose; a sub-screen you arrived at by tapping a card two levels in is exactly where
 * "how did I get here" happens. They are absent from [GROUPS] because the menu should stay flat —
 * listing every sub-screen would make the directory the thing you get lost in instead.
 */
private val SUB_SCREENS: Map<String, String> = mapOf(
    Routes.ECONOMY to "MARKETS",
    Routes.FUEL to "MARKETS",
    Routes.JARVIS_SETUP to "COMPUTER",
    Routes.JARVIS_MEMORY to "COMPUTER",
    Routes.JARVIS_APPROVALS to "COMPUTER",
    Routes.JARVIS_DOSSIER to "COMPUTER",
)

/** route → section label, built once. */
private val SECTION_OF: Map<String, String> =
    TOP_LEVEL + SUB_SCREENS + GROUPS.flatMap { g -> g.entries.map { it.route to g.label } }

/**
 * Which part of the app a route belongs to, or null when it is somewhere the directory does not
 * list.
 *
 * ⚠️ Matches on the base route, before any argument. `survival?guide=knots` is still the Knowledge
 * Library, and a readout that went blank the moment you opened an actual page would be worse than
 * having none — it would disappear exactly when you were deepest in.
 */
fun sectionOf(route: String?): String? =
    route?.substringBefore('?')?.substringBefore('/')?.let { SECTION_OF[it] }

/**
 * The section of the screen currently on top, for the console header.
 *
 * Provided once around the NavHost. Screens do not pass it and do not know about it, which is why
 * adding the readout cost no per-screen edit across thirty-five of them.
 */
val LocalConsoleSection = compositionLocalOf<String?> { null }
