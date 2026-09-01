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
data class MenuEntry(
    val label: String,
    val description: String,
    val route: String,
    /**
     * The words a person actually types when hunting for this — synonyms and concrete nouns the
     * label and description don't already carry ("planes" for the radar, "aurora" for space
     * weather). Lowercase; the menu's search matches label + description + these.
     */
    val searchTerms: List<String> = emptyList(),
)

data class MenuGroup(
    val label: String,
    val accent: (NightwirePalette) -> Color,
    val entries: List<MenuEntry>,
)

val GROUPS = listOf(
    MenuGroup("EMERGENCY", { it.negative }, listOf(
        MenuEntry("SOS", "Call, strobe and alarm for help", Routes.SOS,
            listOf("emergency", "help", "911", "rescue", "distress")),
        MenuEntry("Nearby Danger", "Earthquakes, disasters and severe weather near you", Routes.SAFETY,
            listOf("quake", "alerts", "warning", "crime", "incidents", "safety")),
        MenuEntry("Nearest Help", "Hospitals, shelters and food banks around you", Routes.PLACES,
            listOf("hospital", "pharmacy", "police", "shelter", "places")),
    )),
    MenuGroup("GUIDES", { it.accent }, listOf(
        // The one screen that searches ACROSS survival — every destination and every offline guide,
        // indexed down to section headings, so "knot" or "cpr" lands on the page rather than the
        // library. It was wired into the NavHost and reachable only by deep link, which meant the
        // fastest way into thousands of pages was the one route nothing linked to.
        MenuEntry("Search Survival", "Find any guide or tool by what you need — offline", Routes.SURVIVE,
            listOf("first aid", "cpr", "how to", "survive")),
        MenuEntry("Knowledge Library", "Thousands of pages on everything — works offline", Routes.SURVIVAL,
            listOf("guides", "wiki", "encyclopedia", "reference", "book", "read", "learn")),
        MenuEntry("Knowledge Packs", "Add more subjects to the library — downloads once, then offline", Routes.PACKS,
            listOf("expansion", "download", "more guides")),
        // Sits beside the library rather than under YOUR THINGS: it is what turns those pages from
        // something you can look up into something you are actually taught.
        MenuEntry("Study", "Learn the library a piece a day, and be asked again", Routes.STUDY,
            listOf("quiz", "practice", "flashcards", "course", "lessons", "teach")),
        MenuEntry("Wildlife Guide", "Animals in your region and what to do — offline", Routes.HABITAT,
            listOf("animals", "snakes", "bears", "insects", "bites", "species")),
        MenuEntry("Field Tools", "Flashlight, strobe, alarm and morse", Routes.TOOLS,
            listOf("torch", "light", "whistle", "signal", "utilities")),
        MenuEntry("Compass", "Offline heading, sun and moon", Routes.COMPASS,
            listOf("north", "direction", "bearing", "navigate")),
    )),
    MenuGroup("MAPS & SKY", { it.sky }, listOf(
        MenuEntry("Map", "A live 3D map with your saved places", Routes.NAV,
            listOf("navigation", "gps", "directions", "route", "terrain", "where am i")),
        MenuEntry("Aircraft Radar", "Planes flying near you, live", Routes.RADAR,
            listOf("flights", "airplanes", "adsb", "sky traffic")),
        MenuEntry("Space Weather", "Solar storms and aurora chances", Routes.SPACE_WX,
            listOf("aurora", "northern lights", "kp", "sun", "flare", "geomagnetic")),
        MenuEntry("Satellites & Asteroids", "The ISS, the Moon and close asteroid passes", Routes.ORBITAL,
            listOf("iss", "space station", "orbit", "sky tonight", "launches", "planets")),
        MenuEntry("Sky Map", "Point it at the sky — 8,404 stars, offline", Routes.SKYMAP,
            listOf("stars", "star chart", "constellation", "planetarium", "astronomy", "night sky",
                "meteor", "shower")),
    )),
    MenuGroup("SOUND", { it.amber }, listOf(
        MenuEntry("Radio", "Local and internet stations, plays in the background", Routes.RADIO,
            listOf("music", "fm", "streams", "listen")),
        MenuEntry("Music", "Your Spotify player", Routes.MUSIC,
            listOf("spotify", "songs", "player", "playlists")),
        MenuEntry("Theater", "Browse, search and watch — no links needed", Routes.VIEWSCREEN,
            listOf("video", "videos", "watch", "youtube", "tv", "movies", "viewscreen")),
    )),
    MenuGroup("YOUR THINGS", { it.violet }, listOf(
        MenuEntry("Advisories", "The Computer's best next moves for you", Routes.ORACLE,
            listOf("oracle", "insights", "suggestions", "recommendations", "advice")),
        MenuEntry("Environment Scanner", "What the ship's senses read around you right now", Routes.SENSORIUM,
            listOf("sensorium", "ambient", "sensors", "surroundings", "listening")),
        MenuEntry("Interrogator", "Listens, writes down what is said, and questions weak reasoning", Routes.INTERROGATOR,
            listOf("transcript", "fallacies", "arguments", "debate")),
        MenuEntry("Saved Places", "Places you track, plus calendar stops", Routes.OBJECTIVES,
            listOf("waypoints", "pins", "tracked", "bookmarks", "objectives")),
        MenuEntry("Notes", "Quick notes", Routes.NOTES,
            listOf("memo", "write", "jot")),
        MenuEntry("Diary", "Your daily log", Routes.DIARY,
            listOf("journal", "entries")),
    )),
    MenuGroup("INTERNET", { it.positive }, listOf(
        MenuEntry("Search", "This device first, then the web", Routes.SEARCH,
            listOf("web", "google", "find", "lookup")),
        MenuEntry("Social Feeds", "Lemmy, Mastodon and Hacker News", Routes.SOCIAL,
            listOf("forums", "posts", "trending", "hn")),
    )),
    MenuGroup("SYSTEM", { it.muted }, listOf(
        // ⚠️ The mail words earn their place here rather than in a second entry. These terms reach
        // three consumers, and every one of them identifies a single destination rather than
        // filtering a list, so widening them cannot bury anything: the MENU's own search,
        // DeviceSearchIndex's FEATURE record for this route, and the Computer's `open` tool.
        MenuEntry("Settings", "Every switch and preference", Routes.SETTINGS,
            listOf(
                "preferences", "options", "config", "toggles", "setup",
                "email", "mail", "texts", "sms", "inbox", "notifications",
            )),
        MenuEntry("Device Health", "Battery, sensors, memory and position", Routes.TELEMETRY,
            listOf("telemetry", "diagnostics", "status", "storage")),
        MenuEntry("Security Check", "A local scan of apps and permissions", Routes.SECURITY_AUDIT,
            listOf("permissions", "privacy", "audit", "scan")),
        MenuEntry("Crash Console", "Fault logs, shareable", Routes.CRASH_LOG,
            listOf("faults", "errors", "bugs", "reports")),
    )),
)

/**
 * The one route map derived from the directory rather than written out again.
 *
 * ⚠️ Before this existed, `PulseApp` kept a private hand-written twin of this set for launcher
 * shortcuts and notification deep-links, and the two had to be updated together by whoever
 * remembered. Deriving it from [GROUPS] is the only arrangement in which "everything the MENU lists
 * is deep-linkable" stays true by construction. Verified at the switch-over: the derived set and the
 * hand-written one were IDENTICAL (31 routes, empty diff both directions), so no shortcut's
 * behaviour changed.
 *
 * Economy and Fuel ride along explicitly: they are deliberately unlisted in the menu (the Markets
 * sub-tabs are their surface) but old launcher shortcuts and notifications still target them.
 */
val SHORTCUT_ROUTES: Set<String> =
    GROUPS.flatMapTo(mutableSetOf()) { g -> g.entries.map { it.route } } + setOf(Routes.ECONOMY, Routes.FUEL)

private val ENTRY_OF: Map<String, MenuEntry> = GROUPS.flatMap { it.entries }.associateBy { it.route }

/**
 * The directory's label for [route], or null when the directory does not list it.
 *
 * For any second surface that names a feature — a tile, a chip, a recommendation — so the same
 * feature cannot be called three different things in three places. One name per feature is what
 * lets a name become a habit.
 */
fun menuLabel(route: String): String? = ENTRY_OF[route]?.label

/** Bottom-nav destinations, which are their own top level rather than sitting under a menu group. */
private val TOP_LEVEL: Map<String, String> = mapOf(
    Routes.HOME to "MAIN",
    Routes.NEWS to "MAIN",
    Routes.MARKETS to "MAIN",
    Routes.WEATHER to "MAIN",
    Routes.JARVIS to "MAIN",
    Routes.HEALTH to "MAIN",
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

/** route → the group it is filed under, built once alongside [SECTION_OF]. */
private val GROUP_OF: Map<String, MenuGroup> =
    GROUPS.flatMap { g -> g.entries.map { it.route to g } }.toMap()

/**
 * The directory group a route belongs to, or null for a bottom-nav tab or an unlisted sub-screen.
 *
 * ⚠️ Matches on the base route for the same reason [sectionOf] does: `survival?guide=knots` is still
 * the Knowledge Library, and a column of siblings that vanished the moment you opened an actual page
 * would disappear exactly where it is most useful.
 */
fun groupOf(route: String?): MenuGroup? =
    route?.substringBefore('?')?.substringBefore('/')?.let { GROUP_OF[it] }

/** Which group is on screen, which of its entries you are looking at, and how to open another. */
data class SiblingRailContext(
    val group: MenuGroup,
    val route: String,
    val onOpen: (String) -> Unit,
)

/**
 * The siblings of the screen currently on top, or null when it has none.
 *
 * ⚠️ Provided ONCE around the NavHost, exactly as [LocalConsoleSection] is, and for the same reason:
 * it is how twenty-nine screens changed shape without one of them being edited. A screen does not
 * pass this, does not read it, and does not know it exists — the shared frame does.
 *
 * Null for the six bottom-nav tabs and for anything the directory does not list, so those screens
 * keep the decorative rail they have always had.
 */
val LocalSiblingRail = compositionLocalOf<SiblingRailContext?> { null }
