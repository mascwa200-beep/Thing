package dev.mascwa.pulse.feature.survive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
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
import dev.mascwa.pulse.navigation.menuLabel
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * What a destination needs before it can do its job.
 *
 * This lives on the tile record rather than in prose because the offline list used to be curated by
 * hand, and its own doc comment argued — wrongly — that Wildlife "needs a live GPS+Overpass fetch" and
 * so dropped it, while keeping Nearest Help and Nearby Safety, which genuinely cannot work without a
 * connection. Classifying each tile once and deriving the list is the only version that cannot drift
 * back, the same fix already applied here when the hub and the offline grid were two hand-copied lists.
 */
enum class Need {
    /** Runs on what is already on the device. Nothing to fetch, nothing to wait for. */
    NOTHING,

    /** Needs a position, which the GPS radio supplies with no connection of any kind. */
    GPS,

    /**
     * Needs a live request to refresh, but what it last received is still worth looking at — a list of
     * nearby hospitals does not stop being useful because the connection dropped.
     */
    CACHE,

    /** Cannot do anything useful without a connection, so offering it offline would be a lie. */
    NETWORK,
}

/**
 * One destination tile — shared by the grouped hub ([surviveGroups]) and the offline takeover
 * ([offlineReadyTiles] / [offlineCachedTiles]) so the two can never silently drift apart the way they
 * used to. [accent] reads the live palette rather than baking in a `Color` at construction time, so it
 * re-themes correctly with [Pulse.colors].
 */
data class SurviveTile(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accent: (NightwirePalette) -> Color,
    val route: String,
    val needs: Need,
)

/** A labelled group of [SurviveTile]s for the hub's section-headed layout. */
data class SurviveGroup(val label: String, val tiles: List<SurviveTile>)

// The strobe, alarm and morse are hardware; a call or a text rides the cellular voice network, which is
// there when the data connection is not. Nothing here waits on a request.
private val SOS_TILE = SurviveTile(
    menuLabel(Routes.SOS) ?: "SOS", "Strobe, alarm, call & text for help", Icons.Filled.Sos, { it.magenta }, Routes.SOS,
    needs = Need.NOTHING,
)
// Overpass. Without a connection this shows the last places it fetched, which is worth having.
private val NEAREST_HELP_TILE = SurviveTile(
    menuLabel(Routes.PLACES) ?: "Nearest Help", "Hospitals, shelters, food banks, towers", Icons.Filled.LocalHospital, { it.accent }, Routes.PLACES,
    needs = Need.CACHE,
)
// Live incident feeds, same story: the last quakes and alerts it saw still mean something, and its
// repository already caches and serves them.
private val NEARBY_SAFETY_TILE = SurviveTile(
    menuLabel(Routes.SAFETY) ?: "Nearby Safety", "Quakes, disasters & weather alerts near you", Icons.Filled.Warning, { it.amber }, Routes.SAFETY,
    needs = Need.CACHE,
)
// Map tiles and routing are both live, and an area with nothing cached renders an empty grid.
private val MAP_TILE = SurviveTile(
    menuLabel(Routes.NAV) ?: "Map", "Incidents & help on the live nav map", Icons.Filled.Map, { it.accent }, Routes.NAV,
    needs = Need.NETWORK,
)
private val KNOWLEDGE_BASE_TILE = SurviveTile(
    menuLabel(Routes.SURVIVAL) ?: "Knowledge Library", "Science · medicine · math · survival — offline wiki",
    Icons.AutoMirrored.Filled.MenuBook, { it.positive }, Routes.SURVIVAL,
    needs = Need.NOTHING,
)
// A position fix and a pure lookup table — no request of any kind. This is the tile the old hand-written
// offline list dropped, on the stated grounds that it needed an Overpass fetch. It never did.
private val WILDLIFE_TILE = SurviveTile(
    menuLabel(Routes.HABITAT) ?: "Wildlife Guide", "Animals in your region + what to do · offline", Icons.Filled.Pets, { it.amber }, Routes.HABITAT,
    needs = Need.GPS,
)
private val TOOLS_TILE = SurviveTile(
    menuLabel(Routes.TOOLS) ?: "Field Tools", "SOS strobe, alarm, morse · offline", Icons.Filled.Bolt, { it.positive }, Routes.TOOLS,
    needs = Need.NOTHING,
)
private val COMPASS_TILE = SurviveTile(
    menuLabel(Routes.COMPASS) ?: "Compass", "Heading & true north", Icons.Filled.Explore, { it.positive }, Routes.COMPASS,
    needs = Need.NOTHING,
)

// Destinations that are not survival tools — so they stay off the SURVIVE hub — but that work with no
// signal at all, and so belong on the offline screen. The app grew every one of these after that screen
// was written, and it never learned about them.
private val KB_STUDY_TILE = SurviveTile(
    menuLabel(Routes.STUDY) ?: "Study", "Be taught the library a piece at a time", Icons.Filled.School, { it.sky }, Routes.STUDY,
    needs = Need.NOTHING,
)
private val DEVICE_SEARCH_TILE = SurviveTile(
    menuLabel(Routes.SEARCH) ?: "Search", "Every guide and note held on this device", Icons.Filled.Search, { it.sky }, Routes.SEARCH,
    needs = Need.NOTHING,
)
private val NOTES_TILE = SurviveTile(
    menuLabel(Routes.NOTES) ?: "Notes", "Write it down", Icons.Filled.Description, { it.sky }, Routes.NOTES,
    needs = Need.NOTHING,
)
private val DIARY_TILE = SurviveTile(
    menuLabel(Routes.DIARY) ?: "Diary", "Your daily log", Icons.Filled.Book, { it.sky }, Routes.DIARY,
    needs = Need.NOTHING,
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

/**
 * Every tile this file knows about, hub and offline-only alike. The two offline lists below are
 * **derived** from it by [SurviveTile.needs] rather than written out again, which is the only
 * arrangement in which they cannot disagree with reality.
 */
private val ALL_TILES: List<SurviveTile> = listOf(
    SOS_TILE, NEARBY_SAFETY_TILE, KNOWLEDGE_BASE_TILE, WILDLIFE_TILE, TOOLS_TILE,
    NEAREST_HELP_TILE, MAP_TILE, COMPASS_TILE,
    KB_STUDY_TILE, DEVICE_SEARCH_TILE, NOTES_TILE, DIARY_TILE,
)

/** What genuinely works right now with no connection: nothing to fetch, or only a position to fix. */
fun offlineReadyTiles(): List<SurviveTile> =
    ALL_TILES.filter { it.needs == Need.NOTHING || it.needs == Need.GPS }

/**
 * What can only show what it last received. Offered, but under its own heading — cached hospital
 * locations are worth having in an emergency, provided the screen does not imply they are current.
 */
fun offlineCachedTiles(): List<SurviveTile> = ALL_TILES.filter { it.needs == Need.CACHE }

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
