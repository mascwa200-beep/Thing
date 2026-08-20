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
    REMOTE, ABOUT, SETTINGS,
    LIBRARY, SEARCH, STUDY, PACKS,
    NEWS, LIVE,
}

val DESK_GROUPS: List<DeskGroup> = listOf(
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
        DeskEntry(Screen.NEWS, "News", "Headlines, refreshed while you watch",
            listOf("headlines", "stories", "press", "wire")),
        DeskEntry(Screen.LIVE, "Live", "Television news, in a window of its own",
            listOf("tv", "channels", "broadcast", "watch")),
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

/** Case-insensitive contains over everything an entry says about itself — same rule as the phone. */
fun deskMatches(e: DeskEntry, q: String): Boolean =
    e.label.contains(q, ignoreCase = true) ||
        e.description.contains(q, ignoreCase = true) ||
        e.searchTerms.any { it.contains(q, ignoreCase = true) }
