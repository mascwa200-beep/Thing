package dev.mascwa.pulse.desktop

import androidx.compose.ui.graphics.Color
import dev.mascwa.pulse.desktop.theme.NightwirePalette

/**
 * Every screen this machine has, grouped, in plain English.
 *
 * ⚠️ **Deliberately a separate list from the phone's `navigation/Directory.kt`, and not because
 * sharing was impossible.** The two machines genuinely hold different things: this machine has no
 * assistant, no SOS siren, no compass and no ambient sensors, because it has no microphone worth
 * listening through, no phone to dial with and nothing to point north. A shared directory would
 * have to list those and then hide them, and a directory that lies about what exists is worse than
 * two lists that agree about what they share.
 *
 * What IS shared is the vocabulary. The group names are the same words in the same order, so
 * "MAPS & SKY" means the same thing on both and someone who knows the phone knows this.
 *
 * ⚠️ Adding a screen is: one entry here, one branch in the `when`, and nothing else. That is the
 * whole point of the shape — the rest of the shell reads this list.
 */
data class DeskEntry(
    val screen: Screen,
    val label: String,
    val description: String,
    /** Words someone actually types hunting for this, beyond the label and description. Lowercase. */
    val searchTerms: List<String> = emptyList(),
)

data class DeskGroup(
    val label: String,
    val accent: (NightwirePalette) -> Color,
    val entries: List<DeskEntry>,
)

/**
 * The screens themselves.
 *
 * A bare identity — the label and the description live in [DESK_GROUPS], because a screen's name is
 * directory information rather than something the screen itself needs to know about.
 */
enum class Screen {
    HOME,
    REMOTE, ABOUT, SETTINGS,
    LIBRARY, SEARCH, STUDY, PACKS,
    ADVISORIES,
    NEWS, LIVE,
    SPACE_WEATHER, OBSERVATORY, RADAR, SAFETY, PLACES, WILDLIFE,
    MARKETS, WEATHER, ECONOMY, FUEL,
    RADIO,
    NOTES, DIARY,
}

val DESK_GROUPS: List<DeskGroup> = listOf(
    DeskGroup("OVERVIEW", { it.accent }, listOf(
        DeskEntry(Screen.HOME, "Home", "Everything worth knowing, on one page",
            listOf("dashboard", "overview", "start", "summary", "today")),
    )),
    DeskGroup("KNOWLEDGE", { it.accent }, listOf(
        DeskEntry(Screen.LIBRARY, "Library", "Every bundled page, by subject — works offline",
            listOf("guides", "wiki", "encyclopedia", "reference", "read", "book")),
        DeskEntry(Screen.SEARCH, "Search", "Find a page, or a study card, by what you need",
            listOf("find", "lookup")),
        DeskEntry(Screen.STUDY, "Study", "Learn the library a piece a day, and be asked again",
            listOf("quiz", "practice", "flashcards", "course", "teach")),
        DeskEntry(Screen.PACKS, "Packs", "Add more subjects — downloads once, then offline",
            listOf("expansion", "download", "more guides")),
    )),
    DeskGroup("THE WORLD", { it.sky }, listOf(
        DeskEntry(Screen.ADVISORIES, "Advisories", "What is worth knowing right now, across every feed at once",
            listOf("oracle", "insights", "alerts", "brief", "briefing", "what matters", "now")),
        DeskEntry(Screen.NEWS, "News", "Headlines, refreshed while you watch",
            listOf("headlines", "stories", "press", "wire", "lemmy", "mastodon", "hacker news")),
        DeskEntry(Screen.LIVE, "Live", "Television news, in a window of its own",
            listOf("tv", "channels", "broadcast", "watch")),
        DeskEntry(Screen.RADIO, "Radio", "Internet radio — near you, starred, and always on",
            listOf("music", "stream", "station", "listen", "fm", "somafm", "audio")),
        DeskEntry(Screen.SAFETY, "Nearby danger", "Earthquakes, disasters and official warnings near you",
            listOf("emergency", "alerts", "quake", "warning", "incident", "crime")),
        DeskEntry(Screen.PLACES, "Nearest help", "Hospitals, shelters and food banks, with how to reach them",
            listOf("hospital", "shelter", "clinic", "food bank", "a&e", "emergency room")),
        DeskEntry(Screen.WILDLIFE, "Wildlife", "What lives around here and what to do about it",
            listOf("animals", "snake", "bear", "bite", "sting", "fauna")),
        DeskEntry(Screen.MARKETS, "Markets", "Your watch list, and whether the market is even open",
            listOf("stocks", "shares", "crypto", "prices", "ticker", "forex", "commodities")),
        DeskEntry(Screen.WEATHER, "Weather", "The forecast, and what it will actually feel like",
            listOf("forecast", "rain", "temperature", "wind", "air quality", "uv")),
        DeskEntry(Screen.ECONOMY, "Economy", "A country's figures, each with how old it is",
            listOf("inflation", "gdp", "unemployment", "world bank", "indicators")),
        DeskEntry(Screen.FUEL, "Fuel & energy", "What crude, gas and petrol cost",
            listOf("oil", "petrol", "gasoline", "diesel", "crude", "brent", "wti", "natural gas", "pump")),
    )),
    DeskGroup("MAPS & SKY", { it.positive }, listOf(
        DeskEntry(Screen.SPACE_WEATHER, "Space weather", "What the Sun is doing, and what it is doing to us",
            listOf("solar", "aurora", "kp", "flare", "sunspot", "radio", "hf", "geomagnetic")),
        DeskEntry(Screen.OBSERVATORY, "Observatory", "The station, the Sun and Moon, what is passing, what is launching",
            listOf("iss", "satellite", "moon", "sunrise", "sunset", "planets", "asteroid", "launch", "rocket")),
        DeskEntry(Screen.RADAR, "Radar", "Aircraft and earthquakes within range",
            listOf("planes", "flights", "aviation", "adsb", "quake", "seismic")),
    )),
    DeskGroup("YOUR THINGS", { it.violet }, listOf(
        DeskEntry(Screen.NOTES, "Notes", "Filed snippets, by category",
            listOf("memo", "write", "jot", "library")),
        DeskEntry(Screen.DIARY, "Diary", "Your daily log",
            listOf("journal", "entries", "log")),
    )),
    DeskGroup("THIS MACHINE", { it.muted }, listOf(
        DeskEntry(Screen.REMOTE, "Remote", "Pair with your phone and control it over the local network",
            listOf("phone", "pair", "link", "control")),
        DeskEntry(Screen.SETTINGS, "Settings", "Every switch and preference",
            listOf("preferences", "options", "config", "units", "location")),
        DeskEntry(Screen.ABOUT, "About", "Which build you are on, and install a newer one",
            listOf("version", "update", "upgrade", "build")),
    )),
)

/** Flat lookup, derived rather than written out again. */
val DESK_ENTRIES: Map<Screen, DeskEntry> =
    DESK_GROUPS.flatMap { it.entries }.associateBy { it.screen }

/** Which group a screen belongs to, for the header's location readout. */
val DESK_SECTION: Map<Screen, String> =
    DESK_GROUPS.flatMap { g -> g.entries.map { it.screen to g.label } }.toMap()

/**
 * The phone's deep-link route for a screen this machine also has, or null.
 *
 * ⚠️ The direction matters: this reads a route the SHARED cores produce — [dev.mascwa.pulse.core.telemetry.Insight.actionRoute]
 * is written in the phone's vocabulary because the rules that set it live in a module that knows
 * nothing about either shell. Rather than teach the core a second vocabulary, the desktop translates
 * at the one place it consumes them.
 *
 * Null is the ordinary answer for a route this machine has no equivalent of, and the caller simply
 * offers no action — which is honest, and better than sending someone to an approximate screen.
 */
fun screenForRoute(route: String): Screen? = when (route) {
    "weather" -> Screen.WEATHER
    "markets" -> Screen.MARKETS
    "news" -> Screen.NEWS
    "space_wx" -> Screen.SPACE_WEATHER
    "study" -> Screen.STUDY
    "settings" -> Screen.SETTINGS
    // "nav", "jarvis", "objectives", "sensorium" — a map, the assistant, a task board and the ambient
    // sensors. None of them exist here, and none of the rules that emit them can fire on this machine
    // either, so this is a belt-and-braces null rather than a gap.
    else -> null
}

/** Case-insensitive contains over everything an entry says about itself — same rule as the phone. */
fun deskMatches(e: DeskEntry, q: String): Boolean =
    e.label.contains(q, ignoreCase = true) ||
        e.description.contains(q, ignoreCase = true) ||
        e.searchTerms.any { it.contains(q, ignoreCase = true) }
