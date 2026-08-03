package dev.mascwa.pulse.feature.tacnet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.data.sensors.Telemetry
import dev.mascwa.pulse.feature.common.PipChip
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.sky.DataBody
import dev.mascwa.pulse.feature.sky.OrbitalViewModel
import dev.mascwa.pulse.feature.sky.SpaceWeatherViewModel
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.LocalNightwire
import dev.mascwa.pulse.ui.theme.Pulse
import dev.mascwa.pulse.ui.theme.lcarsPalette
import kotlin.math.roundToInt

/**
 * The combined TOOLS hub — every feed under one LCARS console, organised under four balanced bridge-
 * station sections selected from the top nav (OPS / NAV / LOGS / COMMS):
 *  - OPS   → STATUS (phone telemetry / condition) — operations, alone at the top.
 *  - NAV   → MAP (the NAV map), RADAR (the RADSCOPE), ORBIT (orbital + space weather), and the folded-in
 *            SURVIVE hub — everything about the world around you.
 *  - LOGS  → OBJECTIVES (the objective log), NOTES (library), DIARY, and STORED (the tasks/notes/
 *            objectives inventory) — everything you record.
 *  - COMMS → the folded-in SOCIAL feed, web SEARCH, RADIO and MUSIC — everything in and out.
 * The sub-tab rail shows only the active section's tabs; each feed's scaffold-free *Body is hosted
 * inside the LCARS chrome.
 */
private enum class PipSection(val label: String) { OPS("OPS"), NAV("NAV"), LOGS("LOGS"), COMMS("COMMS") }

/** The hero accent each section reads in its chrome (section buttons, sub-tab pills). */
private fun sectionAccent(section: PipSection): Color = when (section) {
    PipSection.OPS -> Pip.bright
    PipSection.NAV -> Pip.mid
    PipSection.LOGS -> Pip.violet
    PipSection.COMMS -> Pip.glow
}

private enum class PipTab(val label: String, val section: PipSection) {
    STATUS("STATUS", PipSection.OPS),
    MAP("MAP", PipSection.NAV),
    RADAR("RADAR", PipSection.NAV),
    ORBIT("ORBIT", PipSection.NAV),
    SURVIVE("SURVIVE", PipSection.NAV),
    OBJECTIVES("OBJECTIVES", PipSection.LOGS),
    NOTES("NOTES", PipSection.LOGS),
    DIARY("DIARY", PipSection.LOGS),
    STORED("STORED", PipSection.LOGS),
    SOCIAL("SOCIAL", PipSection.COMMS),
    SEARCH("SEARCH", PipSection.COMMS),
    RADIO("STATIONS", PipSection.COMMS),
    MUSIC("MUSIC", PipSection.COMMS),
    ;

    companion object {
        /** The default (first) tab for a section — what the top-nav section button selects. */
        fun firstOf(section: PipSection): PipTab = entries.first { it.section == section }
    }
}

/**
 * A process-scoped deep-link target for the TOOLS screen's sub-tab. A notification or launcher shortcut
 * sets [target] to a [PipTab] name (e.g. "STATUS", "OBJECTIVES") just before navigating to TACNET; because
 * TACNET is a top destination navigated without query args, this holder carries the sub-tab. [PipBoyScreen]
 * consumes and clears it on arrival. Backed by Compose state so an already-composed screen reacts too.
 */
object PipBoyDeepLink {
    val target = mutableStateOf<String?>(null)
}

@Composable
fun PipBoyScreen(
    radarVm: RadarViewModel,
    telemetryVm: TelemetryViewModel,
    orbitalVm: OrbitalViewModel,
    spaceWxVm: SpaceWeatherViewModel,
    radioVm: RadioViewModel,
    notesVm: dev.mascwa.pulse.feature.notes.NotesViewModel,
    diaryVm: dev.mascwa.pulse.feature.diary.DiaryViewModel,
    tasksVm: dev.mascwa.pulse.feature.tasks.TasksViewModel,
    objectivesVm: dev.mascwa.pulse.feature.objectives.ObjectivesViewModel,
    navVm: dev.mascwa.pulse.feature.nav.NavViewModel,
    spotifyVm: dev.mascwa.pulse.feature.spotify.SpotifyViewModel,
    socialVm: dev.mascwa.pulse.feature.social.SocialViewModel,
    searchVm: dev.mascwa.pulse.feature.search.SearchViewModel,
    onOpenRoute: (String) -> Unit = {},
    onBack: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
) {
    var tab by remember { mutableStateOf(PipTab.STATUS) }
    // A notification/shortcut can deep-link straight to a specific sub-tab (e.g. a nearby-safety alert → NAV
    // ▸ MAP) by setting PipBoyDeepLink.target before navigating here. Consume it reactively — the screen may
    // already be composed when the deep-link arrives.
    LaunchedEffect(PipBoyDeepLink.target.value) {
        val t = PipBoyDeepLink.target.value ?: return@LaunchedEffect
        PipTab.entries.firstOrNull { it.name == t }?.let { tab = it }
        PipBoyDeepLink.target.value = null
    }
    // Live device readouts for the persistent STAT strip (STATUS is the default tab, so telemetry
    // starts on open; values persist across sub-tabs).
    val telem by telemetryVm.telemetry.collectAsStateWithLifecycle()
    PulseScaffold(
        title = "LCARS",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Pip.bright)
            }
        },
        actions = {
            IconButton(onClick = {
                when (tab) {
                    PipTab.STATUS -> Unit // telemetry is live; nothing to pull
                    PipTab.SURVIVE -> Unit // the survive hub is a static tile grid
                    PipTab.SOCIAL -> socialVm.refresh()
                    PipTab.SEARCH -> Unit // search has no feed to pull
                    PipTab.STORED -> objectivesVm.refresh() // refresh tracked objectives in the inventory
                    PipTab.ORBIT -> { orbitalVm.refresh(); spaceWxVm.refresh() }
                    PipTab.MAP -> Unit // the NAV map is live (self-managed)
                    PipTab.RADAR -> radarVm.refresh()
                    PipTab.OBJECTIVES -> objectivesVm.refresh()
                    PipTab.NOTES -> Unit // the library is local; nothing to refresh
                    PipTab.DIARY -> Unit // the diary is local; nothing to refresh
                    PipTab.RADIO -> Unit // the radio has its own tuner controls
                    PipTab.MUSIC -> spotifyVm.refresh()
                }
            }) { Icon(Icons.Filled.Refresh, "Refresh", tint = Pip.bright) }
        },
    ) { innerPadding ->
        // Re-theme the whole subtree to LCARS so the sections read as one console (also provided
        // app-wide for the TOOLS section; kept here so this screen is self-contained).
        CompositionLocalProvider(LocalNightwire provides lcarsPalette) {
            Column(Modifier.padding(innerPadding).fillMaxSize().background(Pip.bg)) {
                // The OPS / NAV / LOGS / COMMS menu (with the PWR/MEM gauges) now sits at the TOP — the
                // primary selector — with the section's sub-tabs and the [SECTION] readout beneath it.
                PipTopNav(telem, section = tab.section, onSection = { tab = PipTab.firstOf(it) })
                PipTabRail(tab) { tab = it }
                PipStatHeader(telem, tab.section)

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        PipTab.STATUS -> TelemetryBody(telemetryVm)
                        PipTab.SURVIVE -> dev.mascwa.pulse.feature.survive.SurviveBody(onOpenRoute, Modifier.fillMaxSize())
                        PipTab.SOCIAL -> dev.mascwa.pulse.feature.social.SocialBody(socialVm, Modifier.fillMaxSize())
                        PipTab.SEARCH -> dev.mascwa.pulse.feature.search.SearchBody(searchVm, Modifier.fillMaxSize())
                        PipTab.STORED -> ItemsBody(tasksVm, notesVm, objectivesVm)
                        PipTab.ORBIT -> DataBody(orbitalVm, spaceWxVm)
                        PipTab.MAP -> dev.mascwa.pulse.feature.nav.NavBody(navVm, objectivesVm, Modifier.fillMaxSize())
                        PipTab.RADAR -> RadarBody(radarVm)
                        PipTab.OBJECTIVES -> dev.mascwa.pulse.feature.objectives.ObjectivesPanel(
                            objectivesVm, Pulse.colors, Modifier.fillMaxSize(),
                        )
                        PipTab.NOTES -> dev.mascwa.pulse.feature.notes.NotesBody(notesVm)
                        PipTab.DIARY -> dev.mascwa.pulse.feature.diary.DiaryBody(diaryVm)
                        PipTab.RADIO -> RadioBody(radioVm)
                        PipTab.MUSIC -> dev.mascwa.pulse.feature.spotify.SpotifyBody(spotifyVm)
                    }
                }

                PipUtilBar(onOpenSettings = onOpenSettings, onExit = onBack)
            }
        }
    }
}

/** The sub-tab rail: the active section's tabs as a row of LCARS pills, the current one solid-filled
 *  in the section's hero accent. */
@Composable
private fun PipTabRail(current: PipTab, onSelect: (PipTab) -> Unit) {
    val accent = sectionAccent(current.section)
    Box(Modifier.fillMaxWidth().background(Pip.bg)) {
        val tabs = PipTab.entries.filter { it.section == current.section }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEach { t ->
                PipChip(t.label, selected = t == current, onClick = { onSelect(t) }, accent = accent)
            }
        }
    }
}

/** Free memory as a 0–100 %, used for the MEM gauge. */
private fun freeMemPercent(t: Telemetry): Float =
    if (t.memTotalMb > 0) (((t.memTotalMb - t.memUsedMb) * 100f) / t.memTotalMb).coerceIn(0f, 100f) else 0f

/** The top STAT readout strip — `[SECTION] REV n · PWR x/100 · MEM x/100 · NET`. PWR = battery, MEM =
 *  free memory, REV = build. Live device data; the leading bracket names the active section. */
@Composable
private fun PipStatHeader(t: Telemetry, section: PipSection) {
    val bat = (t.batteryPct ?: 0).coerceIn(0, 100)
    val mem = freeMemPercent(t).roundToInt()
    val level = dev.mascwa.pulse.BuildConfig.VERSION_CODE
    Row(
        Modifier.fillMaxWidth().background(Pip.bg).horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            "[${section.label}]",
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.5.sp,
            color = sectionAccent(section),
        )
        StatReadout("REV", "$level")
        StatReadout("PWR", "$bat / 100" + if (t.charging) " ⚡" else "")
        StatReadout("MEM", "$mem / 100")
        if (t.netType.isNotBlank()) StatReadout("NET", t.netType)
    }
}

/** One `LABEL value` readout in the top stat strip. */
@Composable
private fun StatReadout(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp, color = Pip.glow)
        Text(value, fontFamily = JetBrainsMono, fontSize = 11.sp, color = Pip.bright)
    }
}

/**
 * The top section nav: the PWR/MEM gauges over the OPS / NAV / LOGS / COMMS selector — the primary menu,
 * at the top of the console. PWR = battery, MEM = free memory.
 */
@Composable
private fun PipTopNav(t: Telemetry, section: PipSection, onSection: (PipSection) -> Unit) {
    val bat = (t.batteryPct ?: 0).coerceIn(0, 100)
    val freeMem = freeMemPercent(t)
    Column(Modifier.fillMaxWidth().background(Pip.bg).padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatGauge("PWR", "$bat%", bat / 100f, Modifier.weight(1f))
            StatGauge("MEM", "${freeMem.roundToInt()}%", freeMem / 100f, Modifier.weight(1f))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PipSection.entries.forEach { s -> SectionButton(s.label, sectionAccent(s), s == section) { onSection(s) } }
        }
    }
}

/**
 * The slim bottom frame: the utility pills (♥ SET/BUG/EXIT) + the build version. SET opens Settings,
 * EXIT backs out; BUG is dim chrome. (The section nav + gauges live at the top.)
 */
@Composable
private fun PipUtilBar(onOpenSettings: (() -> Unit)?, onExit: (() -> Unit)?) {
    val version = "v" + dev.mascwa.pulse.BuildConfig.VERSION_CODE
    Column(Modifier.fillMaxWidth().background(Pip.bg)) {
        // A rule separating the frame from the feed above.
        Canvas(Modifier.fillMaxWidth().height(1.5.dp)) {
            drawLine(Pip.grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("♦", fontFamily = JetBrainsMono, fontSize = 11.sp, color = Pip.mid)
                UtilPill("SET", onOpenSettings); UtilPill("BUG", null); UtilPill("EXIT", onExit)
            }
            Text("ARGUS DYNAMICS · $version", fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = Pip.dim)
        }
    }
}

/** One global-section button: a rounded LCARS block (selected = wider & full brightness) with a label
 *  beneath, coloured to the section's hero [accent]. */
@Composable
private fun SectionButton(label: String, accent: Color, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 10.dp).clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .height(8.dp)
                .width(if (selected) 46.dp else 30.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) accent else accent.copy(alpha = 0.4f)),
        )
        Text(
            label,
            fontFamily = ChakraPetch,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            color = if (selected) Pip.glow else Pip.dim,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/** A small utility pill. Interactive (bright, bordered) when [onClick] is set; else dim chrome. */
@Composable
private fun UtilPill(label: String, onClick: (() -> Unit)?) {
    val color = if (onClick != null) Pip.mid else Pip.dim
    Box(
        Modifier
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .drawBehind {
                drawRoundRect(
                    color.copy(alpha = 0.45f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                    style = Stroke(width = 1f),
                )
            }
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 0.5.sp, color = color)
    }
}

/** The collected-inventory categories for the STORED tab, mapped to what the app actually saves. */
private enum class ItemCat(val label: String) { TASKS("TASKS"), NOTES("NOTES"), OBJECTIVES("OBJECTIVES") }

/**
 * The LOGS section's STORED tab body — an inventory of the things the user is tracking in Pulse: open
 * tasks (the deliberate-goal layer J.A.R.V.I.S. captures), saved notes and tracked objectives, picked
 * through a category rail and listed as the canonical data rows. Read-only here; each item is curated
 * from its own screen (J.A.R.V.I.S. / NOTES / OBJECTIVES). Favourite stations now live under the STATIONS tab.
 */
@Composable
private fun ItemsBody(
    tasksVm: dev.mascwa.pulse.feature.tasks.TasksViewModel,
    notesVm: dev.mascwa.pulse.feature.notes.NotesViewModel,
    objectivesVm: dev.mascwa.pulse.feature.objectives.ObjectivesViewModel,
) {
    val tasks by tasksVm.tasks.collectAsStateWithLifecycle()
    val notes by notesVm.notes.collectAsStateWithLifecycle()
    val objectives by objectivesVm.objectives.collectAsStateWithLifecycle()
    // Pending (not-done) tasks first, then completed — the useful order for an inventory glance.
    val pendingTasks = tasks.filter { it.status != dev.mascwa.pulse.core.telemetry.TaskStatus.DONE }
    val doneTasks = tasks.filter { it.status == dev.mascwa.pulse.core.telemetry.TaskStatus.DONE }
    val orderedTasks = pendingTasks + doneTasks
    var cat by remember { mutableStateOf(ItemCat.TASKS) }
    val count = when (cat) {
        ItemCat.TASKS -> tasks.size
        ItemCat.NOTES -> notes.size
        ItemCat.OBJECTIVES -> objectives.size
    }
    val logsAccent = sectionAccent(PipSection.LOGS)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ItemCat.entries.forEach { ic ->
                val n = when (ic) {
                    ItemCat.TASKS -> tasks.size
                    ItemCat.NOTES -> notes.size
                    ItemCat.OBJECTIVES -> objectives.size
                }
                PipChip("${ic.label} [$n]", selected = ic == cat, onClick = { cat = ic }, accent = logsAccent)
            }
        }
        dev.mascwa.pulse.feature.common.PipHeader(cat.label, Modifier.padding(horizontal = 14.dp), trailing = "$count")
        when (cat) {
            ItemCat.TASKS ->
                if (orderedTasks.isEmpty()) EmptyItems("No tasks. J.A.R.V.I.S. captures \"I need to…\" / \"todo:\".")
                else orderedTasks.forEach { t ->
                    val detail = t.note.ifBlank { t.status.name }
                    dev.mascwa.pulse.feature.common.PipDataRow(
                        t.title, detail,
                        valueColor = if (t.status == dev.mascwa.pulse.core.telemetry.TaskStatus.DONE) Pip.dim else Pulse.colors.ink,
                    )
                }
            ItemCat.NOTES ->
                if (notes.isEmpty()) EmptyItems("No notes saved. Add one in NOTES.")
                else notes.forEach { n -> dev.mascwa.pulse.feature.common.PipDataRow(n.title.ifBlank { "Untitled" }, n.category.ifBlank { "—" }) }
            ItemCat.OBJECTIVES ->
                if (objectives.isEmpty()) EmptyItems("No tracked objectives. Add one in OBJECTIVES / MAP.")
                else objectives.forEach { o ->
                    val detail = o.whenLabel
                        ?: o.distanceMeters?.let { if (it >= 1000) "%.1f km".format(it / 1000) else "${it.roundToInt()} m" }
                        ?: o.kind.name
                    dev.mascwa.pulse.feature.common.PipDataRow(o.title, detail)
                }
        }
    }
}

/** Empty-state line for an inventory category. */
@Composable
private fun EmptyItems(message: String) {
    Text(
        message,
        fontFamily = JetBrainsMono,
        fontSize = 12.sp,
        color = Pip.dim,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
    )
}

/** One labelled PWR/MEM-style gauge: label + value over a thin rounded filled bar. */
@Composable
private fun StatGauge(label: String, value: String, fraction: Float, modifier: Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontFamily = JetBrainsMono, fontSize = 9.sp, color = Pip.dim)
            Text(value, fontFamily = JetBrainsMono, fontSize = 9.sp, color = Pip.mid)
        }
        Box(
            Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Pip.gridSoft),
        ) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(3.dp))
                    .background(Pip.bright),
            )
        }
    }
}
