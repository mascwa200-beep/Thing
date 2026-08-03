package dev.mascwa.pulse.feature.survive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.data.survival.Guide
import dev.mascwa.pulse.feature.common.PipFrame
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.navigation.Routes
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun SurviveHubScreen(onOpenRoute: (String) -> Unit, onBack: (() -> Unit)? = null) {
    PulseScaffold(
        title = "Survive",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        SurviveBody(onOpenRoute, Modifier.padding(innerPadding))
    }
}

/** The scaffold-free SURVIVE hub — hosted standalone in [SurviveHubScreen] and as the SURVIVE sub-tab
 *  inside the LCARS STATS page. A file-explorer-style search across every survival destination + offline
 *  guide sits on top; empty query shows the tile grid, so nothing heavy loads until you pick a page.
 *  Search results deep-link straight to the exact page (a guide result opens that specific guide). */
@Composable
fun SurviveBody(onOpenRoute: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PulseApplication).container }
    // Only the bundled guide TITLES/headings are read here (cheap, cached) — not the network-bound screens.
    var guides by remember { mutableStateOf<List<Guide>>(emptyList()) }
    LaunchedEffect(Unit) {
        guides = runCatching { container.survivalContentRepository.guides() }.getOrDefault(emptyList())
    }
    var query by remember { mutableStateOf("") }
    val index = remember(guides) { buildSurviveIndex(guides) }
    val q = query.trim()
    val results = remember(q, index) { if (q.isBlank()) emptyList() else index.filter { it.matches(q) } }

    Column(modifier.fillMaxSize()) {
        SurviveSearchField(query, onQuery = { query = it })
        if (q.isBlank()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { PipHubTile("SOS", "Strobe, alarm, call & text for help", Icons.Filled.Sos, c.magenta) { onOpenRoute(Routes.SOS) } }
                item { PipHubTile("Nearest Help", "Hospitals, shelters, food banks, towers", Icons.Filled.LocalHospital, c.accent) { onOpenRoute(Routes.PLACES) } }
                item { PipHubTile("Nearby Safety", "Quakes, disasters & weather alerts near you", Icons.Filled.Warning, c.amber) { onOpenRoute(Routes.SAFETY) } }
                item { PipHubTile("Map", "Incidents & help on the live nav map", Icons.Filled.Map, c.accent) { onOpenRoute(Routes.NAV) } }
                item { PipHubTile("Knowledge Base", "Science · medicine · math · survival — offline wiki", Icons.AutoMirrored.Filled.MenuBook, c.positive) { onOpenRoute(Routes.SURVIVAL) } }
                item { PipHubTile("Wildlife", "Animals in your region + what to do · offline", Icons.Filled.Pets, c.amber) { onOpenRoute(Routes.HABITAT) } }
                item { PipHubTile("Tools", "SOS strobe, alarm, morse · offline", Icons.Filled.Bolt, c.positive) { onOpenRoute(Routes.TOOLS) } }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (results.isEmpty()) {
                    item {
                        Text(
                            "No matches for \"$q\".", fontFamily = JetBrainsMono, fontSize = 11.sp,
                            color = c.muted, modifier = Modifier.padding(8.dp),
                        )
                    }
                } else {
                    items(results, key = { it.route + it.label }) { r ->
                        SurviveResultRow(r) { onOpenRoute(r.route) }
                    }
                }
            }
        }
    }
}

/** The LCARS search bar: a corner-bracketed frame with a monospace field. */
@Composable
private fun SurviveSearchField(query: String, onQuery: (String) -> Unit) {
    val c = Pulse.colors
    PipFrame(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = TextStyle(color = c.ink, fontFamily = JetBrainsMono, fontSize = 13.sp),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text("▸ SEARCH SURVIVAL — guides, tools, help…", fontFamily = JetBrainsMono,
                        fontSize = 12.sp, color = c.muted)
                }
                inner()
            },
        )
    }
}

/** A search result row: an LCARS framed entry that deep-links to its exact page on tap. */
@Composable
private fun SurviveResultRow(r: SurviveResult, onClick: () -> Unit) {
    val c = Pulse.colors
    PipFrame(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column {
            Text(r.sub, fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.accent)
            Text(r.label, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                color = c.ink, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** One searchable SURVIVE entry — a hub destination or an offline guide. */
private data class SurviveResult(val label: String, val sub: String, val route: String, val keywords: String) {
    fun matches(q: String): Boolean {
        val needle = q.lowercase()
        return label.lowercase().contains(needle) || keywords.lowercase().contains(needle)
    }
}

/** Builds the full index: the six hub destinations plus one entry per bundled offline guide (indexed by
 *  title, category, summary and section headings, so "knot"/"cpr"/"compass"/"morse" all resolve). */
private fun buildSurviveIndex(guides: List<Guide>): List<SurviveResult> {
    val hub = listOf(
        SurviveResult("SOS", "TOOL · strobe · alarm · call · text", Routes.SOS,
            "sos emergency strobe alarm call text help flare signal light distress 911"),
        SurviveResult("Nearest Help", "MAP · hospitals · shelters · towers", Routes.PLACES,
            "hospital shelter food bank tower pharmacy clinic police places nearby help"),
        SurviveResult("Nearby Safety", "ALERTS · quakes · disasters · weather", Routes.SAFETY,
            "quake earthquake disaster weather alert flood storm wildfire safety hazard"),
        SurviveResult("Map", "NAV · incidents & help", Routes.NAV,
            "map incident nav navigation route waypoint compass directions"),
        SurviveResult("Knowledge Base", "WIKI · offline library", Routes.SURVIVAL,
            "guide guides library offline reference knowledge wiki textbook encyclopedia science chemistry biology physics math medicine engineering astronomy nutrition"),
        SurviveResult("Wildlife", "MAP · animals in your region", Routes.HABITAT,
            "wildlife animal animals habitat bear snake spider scorpion shark predator venom bite sting heat map offline"),
        SurviveResult("Tools", "TOOLS · strobe · morse · whistle", Routes.TOOLS,
            "tool tools torch flashlight strobe alarm morse whistle compass siren beacon"),
    )
    val guideResults = guides.map { g ->
        val headings = g.sections.joinToString(" ") { it.heading }
        SurviveResult(
            label = g.title,
            sub = "GUIDE · ${g.category}",
            route = "${Routes.SURVIVAL}?guide=${g.id}",
            keywords = "${g.title} ${g.category} ${g.summary} $headings",
        )
    }
    return hub + guideResults
}

/** A Survive hub tile in the LCARS terminal idiom: a flat corner-bracketed frame with an accent
 *  icon, title, and subtitle. */
@Composable
private fun PipHubTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
) {
    val c = Pulse.colors
    PipFrame(Modifier.fillMaxWidth().clickable { onClick() }, accent = accent) {
        Column {
            Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
            Text(
                title, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                color = c.ink, modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                subtitle, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
