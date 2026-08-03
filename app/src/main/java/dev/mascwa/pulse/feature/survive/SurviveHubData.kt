package dev.mascwa.pulse.feature.survive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.navigation.Routes
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * One SURVIVE hub destination — shared by the grouped hub ([surviveGroups]) and the offline-mode tile grid
 * ([offlineSurviveTiles]) so the two can never silently drift apart the way they used to (two independently
 * hand-copied tile lists — one missing Wildlife/Map, the other missing Compass, with duplicated tile-card
 * composables). [accent] reads the live palette rather than baking in a `Color` at construction time, so it
 * re-themes correctly with [Pulse.colors].
 */
data class SurviveTile(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accent: (NightwirePalette) -> Color,
    val route: String,
)

/** A labelled group of [SurviveTile]s for the hub's section-headed layout. */
data class SurviveGroup(val label: String, val tiles: List<SurviveTile>)

private val SOS_TILE = SurviveTile(
    "SOS", "Strobe, alarm, call & text for help", Icons.Filled.Sos, { it.magenta }, Routes.SOS,
)
private val NEAREST_HELP_TILE = SurviveTile(
    "Nearest Help", "Hospitals, shelters, food banks, towers", Icons.Filled.LocalHospital, { it.accent }, Routes.PLACES,
)
private val NEARBY_SAFETY_TILE = SurviveTile(
    "Nearby Safety", "Quakes, disasters & weather alerts near you", Icons.Filled.Warning, { it.amber }, Routes.SAFETY,
)
private val MAP_TILE = SurviveTile(
    "Map", "Incidents & help on the live nav map", Icons.Filled.Map, { it.accent }, Routes.NAV,
)
private val KNOWLEDGE_BASE_TILE = SurviveTile(
    "Knowledge Base", "Science · medicine · math · survival — offline wiki",
    Icons.AutoMirrored.Filled.MenuBook, { it.positive }, Routes.SURVIVAL,
)
private val WILDLIFE_TILE = SurviveTile(
    "Wildlife", "Animals in your region + what to do · offline", Icons.Filled.Pets, { it.amber }, Routes.HABITAT,
)
private val TOOLS_TILE = SurviveTile(
    "Tools", "SOS strobe, alarm, morse · offline", Icons.Filled.Bolt, { it.positive }, Routes.TOOLS,
)
private val COMPASS_TILE = SurviveTile(
    "Compass", "Heading & true north", Icons.Filled.Explore, { it.positive }, Routes.COMPASS,
)

/**
 * The full SURVIVE hub, grouped by actual purpose (replaces the earlier flat, ungrouped 7-tile grid):
 * EMERGENCY (something is actively wrong right now), REFERENCE (an offline lookup), FIELD TOOLS (device
 * hardware utilities), NAVIGATION (where do I go / what's near me).
 */
fun surviveGroups(): List<SurviveGroup> = listOf(
    SurviveGroup("Emergency", listOf(SOS_TILE, NEARBY_SAFETY_TILE)),
    SurviveGroup("Reference", listOf(KNOWLEDGE_BASE_TILE, WILDLIFE_TILE)),
    SurviveGroup("Field Tools", listOf(TOOLS_TILE)),
    SurviveGroup("Navigation", listOf(NEAREST_HELP_TILE, MAP_TILE)),
)

/** The connectivity-appropriate curated subset/superset for the "no signal" takeover: drops Map (needs live
 *  map tiles/routing) and Wildlife (needs a live GPS+Overpass fetch), adds Compass (a pure on-device sensor
 *  reading, no network at all). Sourced from the SAME tile records as [surviveGroups] — the offline screen
 *  and the main hub can never silently drift apart again. */
fun offlineSurviveTiles(): List<SurviveTile> = listOf(
    SOS_TILE, COMPASS_TILE, KNOWLEDGE_BASE_TILE, TOOLS_TILE, NEAREST_HELP_TILE, NEARBY_SAFETY_TILE,
)

/** The shared SURVIVE tile card — an LCARS-framed icon/title/subtitle, used by both the hub and the offline
 *  takeover (previously two independently hand-duplicated `PipHubTile` composables). */
@Composable
fun SurviveTileCard(tile: SurviveTile, onClick: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    val accent = tile.accent(c)
    LcarsFrame(modifier.fillMaxWidth().clickable { onClick(tile.route) }, accent = accent) {
        Column {
            Icon(tile.icon, null, tint = accent, modifier = Modifier.size(22.dp))
            Text(
                tile.title, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                color = c.ink, modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                tile.subtitle, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
