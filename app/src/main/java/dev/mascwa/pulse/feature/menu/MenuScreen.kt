package dev.mascwa.pulse.feature.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.lcarsBlockShape
import dev.mascwa.pulse.navigation.Routes
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * THE flat directory — the fix for "hidden as sub tabs, hard to find": every feature in the app is a big,
 * plain-English, always-in-the-same-place block, grouped, ONE tap from here. Nothing on this page is
 * conditional and nothing is nested — an Okudagram directory panel is exactly what a real LCARS screen is.
 */
private data class MenuEntry(val label: String, val description: String, val route: String)

private data class MenuGroup(
    val label: String,
    val accent: (NightwirePalette) -> Color,
    val entries: List<MenuEntry>,
)

private val GROUPS = listOf(
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

@Composable
fun MenuScreen(onOpen: (String) -> Unit) {
    val c = Pulse.colors
    PulseScaffold(title = "MENU") { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GROUPS.forEach { group ->
                item(key = "hdr_${group.label}") {
                    LcarsHeaderBar(group.label, Modifier.padding(top = 12.dp, bottom = 2.dp))
                }
                items(group.entries, key = { it.route }) { entry ->
                    MenuRow(entry, group.accent(c), onOpen)
                }
            }
            item(key = "footer_pad") { Box(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun MenuRow(entry: MenuEntry, accent: Color, onOpen: (String) -> Unit) {
    val c = Pulse.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(lcarsBlockShape(14.dp, LcarsCorner.TopStart))
            .background(c.raise)
            .clickable { onOpen(entry.route) }
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(12.dp)
                .height(52.dp)
                .background(accent, lcarsBlockShape(10.dp, LcarsCorner.TopStart)),
        )
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(
                entry.label.uppercase(),
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 1.2.sp,
                color = c.ink,
            )
            Text(
                entry.description,
                fontFamily = JetBrainsMono,
                fontSize = 10.sp,
                color = c.muted,
            )
        }
    }
}
