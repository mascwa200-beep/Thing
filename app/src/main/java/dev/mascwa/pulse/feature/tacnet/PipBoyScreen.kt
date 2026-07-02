package dev.mascwa.pulse.feature.tacnet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.data.sensors.Telemetry
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.sky.DataBody
import dev.mascwa.pulse.feature.sky.OrbitalViewModel
import dev.mascwa.pulse.feature.sky.SpaceWeatherViewModel
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.LocalNightwire
import dev.mascwa.pulse.ui.theme.Pulse
import dev.mascwa.pulse.ui.theme.pipBoyPalette
import kotlin.math.roundToInt

/**
 * The combined PIP-BOY hub — every TOOLS feed under one Fallout Pip-Boy device, organised under the
 * three canonical Pip-Boy sections selected from the global bottom nav (STATS / ITEMS / DATA):
 *  - STATS  → STATUS (phone telemetry / condition / S.P.E.C.I.A.L.), plus the folded-in SURVIVE hub,
 *             SOCIAL feed and web SEARCH sub-tabs.
 *  - ITEMS  → the collected-inventory view (favourite stations, notes, tracked objectives).
 *  - DATA   → MAP (the NAV map), RADAR (the RADSCOPE), ORBIT (orbital + space weather), QUESTS
 *             (objectives log), NOTES (library), RADIO and MUSIC.
 * The sub-tab rail shows only the active section's tabs; each feed's scaffold-free *Body is hosted
 * inside the green CRT chrome.
 */
private enum class PipSection(val label: String) { STATS("STATS"), ITEMS("ITEMS"), DATA("DATA") }

private enum class PipTab(val label: String, val section: PipSection) {
    STATUS("STATUS", PipSection.STATS),
    SURVIVE("SURVIVE", PipSection.STATS),
    SOCIAL("SOCIAL", PipSection.STATS),
    SEARCH("SEARCH", PipSection.STATS),
    ITEMS("ITEMS", PipSection.ITEMS),
    MAP("MAP", PipSection.DATA),
    RADAR("RADAR", PipSection.DATA),
    ORBIT("ORBIT", PipSection.DATA),
    QUESTS("QUESTS", PipSection.DATA),
    NOTES("NOTES", PipSection.DATA),
    DIARY("DIARY", PipSection.DATA),
    RADIO("STATIONS", PipSection.DATA),
    MUSIC("MUSIC", PipSection.DATA),
    ;

    companion object {
        /** The default (first) tab for a section — what the bottom-nav section button selects. */
        fun firstOf(section: PipSection): PipTab = entries.first { it.section == section }
    }
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
    // Live device readouts for the persistent STAT strip (STATUS is the default tab, so telemetry
    // starts on open; values persist across sub-tabs).
    val telem by telemetryVm.telemetry.collectAsStateWithLifecycle()
    PulseScaffold(
        title = "PIP-BOY",
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
                    PipTab.ITEMS -> objectivesVm.refresh() // refresh tracked objectives in the inventory
                    PipTab.ORBIT -> { orbitalVm.refresh(); spaceWxVm.refresh() }
                    PipTab.MAP -> Unit // the NAV map is live (self-managed)
                    PipTab.RADAR -> radarVm.refresh()
                    PipTab.QUESTS -> objectivesVm.refresh()
                    PipTab.NOTES -> Unit // the library is local; nothing to refresh
                    PipTab.DIARY -> Unit // the diary is local; nothing to refresh
                    PipTab.RADIO -> Unit // the radio has its own tuner controls
                    PipTab.MUSIC -> spotifyVm.refresh()
                }
            }) { Icon(Icons.Filled.Refresh, "Refresh", tint = Pip.bright) }
        },
    ) { innerPadding ->
        // Re-theme the whole subtree to phosphor green so the four feeds read as one Pip-Boy unit
        // (also provided app-wide for the TOOLS section; kept here so PIP-BOY is self-contained).
        CompositionLocalProvider(LocalNightwire provides pipBoyPalette) {
            Column(Modifier.padding(innerPadding).fillMaxSize().background(Pip.bg)) {
                // The STATS / ITEMS / DATA menu (with the HP/AP gauges) now sits at the TOP — the primary
                // Pip-Boy selector — with the section's sub-tabs and the [SECTION] readout beneath it.
                PipTopNav(telem, section = tab.section, onSection = { tab = PipTab.firstOf(it) })
                PipTabRail(tab) { tab = it }
                PipStatHeader(telem, tab.section)

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        PipTab.STATUS -> TelemetryBody(telemetryVm)
                        PipTab.SURVIVE -> dev.mascwa.pulse.feature.survive.SurviveBody(onOpenRoute, Modifier.fillMaxSize())
                        PipTab.SOCIAL -> dev.mascwa.pulse.feature.social.SocialBody(socialVm, Modifier.fillMaxSize())
                        PipTab.SEARCH -> dev.mascwa.pulse.feature.search.SearchBody(searchVm, Modifier.fillMaxSize())
                        PipTab.ITEMS -> ItemsBody(tasksVm, notesVm, objectivesVm)
                        PipTab.ORBIT -> DataBody(orbitalVm, spaceWxVm)
                        PipTab.MAP -> dev.mascwa.pulse.feature.nav.NavBody(navVm, objectivesVm, Modifier.fillMaxSize())
                        PipTab.RADAR -> RadarBody(radarVm)
                        PipTab.QUESTS -> dev.mascwa.pulse.feature.objectives.ObjectivesPanel(
                            objectivesVm, Pulse.colors, Modifier.fillMaxSize(),
                        )
                        PipTab.NOTES -> dev.mascwa.pulse.feature.notes.NotesBody(notesVm)
                        PipTab.DIARY -> dev.mascwa.pulse.feature.diary.DiaryBody(diaryVm)
                        PipTab.RADIO -> RadioBody(radioVm)
                        PipTab.MUSIC -> dev.mascwa.pulse.feature.spotify.SpotifyBody(spotifyVm)
                    }
                    // A faint CRT scanline tube over every feed — decorative, so it passes touches
                    // through (no pointer input) and stays low-alpha to keep text readable.
                    Canvas(Modifier.matchParentSize()) { crtScanlines(Color.Black.copy(alpha = 0.12f), gap = 3f) }
                }

                PipUtilBar(onOpenSettings = onOpenSettings, onExit = onBack)
            }
        }
    }
}

/** Fallout Pip-Boy section rail: tabs joined by em-dash connectors, the selected one framed in corner
 *  brackets (pixel-faithful to the reference's `Status — S.P.E.C.I.A.L. — …` sub-tab bar). */
@Composable
private fun PipTabRail(current: PipTab, onSelect: (PipTab) -> Unit) {
    Box(Modifier.fillMaxWidth().background(Pip.bg)) {
        Canvas(Modifier.matchParentSize()) { crtScanlines(Pip.gridSoft, gap = 3f) }
        val tabs = PipTab.entries.filter { it.section == current.section }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { i, t ->
                if (i > 0) {
                    // em-dash connector line between tabs
                    Box(Modifier.padding(horizontal = 5.dp).width(14.dp).height(1.5.dp).background(Pip.mid))
                }
                val on = t == current
                Box(
                    Modifier
                        .clickable { onSelect(t) }
                        .then(if (on) Modifier.drawBehind { drawCornerBrackets(Pip.glow) } else Modifier)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        t.label,
                        fontFamily = ChakraPetch,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 14.sp,
                        letterSpacing = 1.2.sp,
                        color = if (on) Pip.glow else Pip.dim,
                    )
                }
            }
        }
    }
}

/** L-shaped corner brackets framing the selected tab — the Fallout Pip-Boy selection box. */
private fun DrawScope.drawCornerBrackets(color: Color) {
    val w = size.width
    val h = size.height
    val len = 7.dp.toPx()
    val sw = 1.4.dp.toPx()
    drawLine(color, Offset(0f, 0f), Offset(len, 0f), sw)
    drawLine(color, Offset(0f, 0f), Offset(0f, len), sw)
    drawLine(color, Offset(w, 0f), Offset(w - len, 0f), sw)
    drawLine(color, Offset(w, 0f), Offset(w, len), sw)
    drawLine(color, Offset(0f, h), Offset(len, h), sw)
    drawLine(color, Offset(0f, h), Offset(0f, h - len), sw)
    drawLine(color, Offset(w, h), Offset(w - len, h), sw)
    drawLine(color, Offset(w, h), Offset(w, h - len), sw)
}

/** Free memory as a 0–100 %, used for the AP gauge. */
private fun freeMemPercent(t: Telemetry): Float =
    if (t.memTotalMb > 0) (((t.memTotalMb - t.memUsedMb) * 100f) / t.memTotalMb).coerceIn(0f, 100f) else 0f

/** The top STAT readout strip — `[SECTION] LVL n · HP x/100 · AP x/100 · NET`, pixel-faithful to the
 *  reference's top bar. HP = battery, AP = free memory, LVL = build. Live device data in the Pip-Boy
 *  idiom; the leading bracket names the active section (STATS / ITEMS / DATA). */
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
            color = Pip.bright,
        )
        StatReadout("LVL", "$level")
        StatReadout("HP", "$bat / 100" + if (t.charging) " ⚡" else "")
        StatReadout("AP", "$mem / 100")
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
 * The top Pip-Boy section nav: the HP/AP gauges over the STATS / ITEMS / DATA circular selector — the
 * primary menu, now at the top of the device. HP = battery, AP = free memory.
 */
@Composable
private fun PipTopNav(t: Telemetry, section: PipSection, onSection: (PipSection) -> Unit) {
    val bat = (t.batteryPct ?: 0).coerceIn(0, 100)
    val freeMem = freeMemPercent(t)
    Column(Modifier.fillMaxWidth().background(Pip.bg).padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatGauge("HP", "$bat%", bat / 100f, Modifier.weight(1f))
            StatGauge("AP", "${freeMem.roundToInt()}%", freeMem / 100f, Modifier.weight(1f))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PipSection.entries.forEach { s -> SectionButton(s.label, s == section) { onSection(s) } }
        }
    }
}

/**
 * The slim bottom Pip-Boy frame: the Fallout utility pills (♥ SET/BUG/EXIT) + the build version.
 * SET opens Settings, EXIT backs out; BUG is dim chrome. (The section nav + gauges moved to the top.)
 */
@Composable
private fun PipUtilBar(onOpenSettings: (() -> Unit)?, onExit: (() -> Unit)?) {
    val version = "v" + dev.mascwa.pulse.BuildConfig.VERSION_CODE
    Column(Modifier.fillMaxWidth().background(Pip.bg)) {
        // Bright phosphor rule separating the frame from the feed above.
        Canvas(Modifier.fillMaxWidth().height(1.5.dp)) {
            drawLine(Pip.grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("♥", fontFamily = JetBrainsMono, fontSize = 11.sp, color = Pip.mid)
                UtilPill("SET", onOpenSettings); UtilPill("BUG", null); UtilPill("EXIT", onExit)
            }
            Text("ARGUS DYNAMICS · $version", fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = Pip.dim)
        }
    }
}

/** One global-section button: a Pip-Boy circle (selected = filled & larger) with a label beneath. */
@Composable
private fun SectionButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val diameter = if (selected) 30.dp else 22.dp
    Column(
        Modifier.padding(horizontal = 14.dp).clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(30.dp), // fixed footprint so the row doesn't reflow as selection changes
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(diameter)) {
                val r = size.minDimension / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                if (selected) {
                    drawCircle(Pip.bright, radius = r, center = c)
                    drawCircle(Pip.bg, radius = r * 0.38f, center = c)
                } else {
                    drawCircle(Pip.dim, radius = r, center = c, style = Stroke(width = 1.5.dp.toPx()))
                }
            }
        }
        Text(
            label,
            fontFamily = ChakraPetch,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            color = if (selected) Pip.glow else Pip.dim,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** A small Fallout utility pill. Interactive (bright, bordered) when [onClick] is set; else dim chrome. */
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

/** The collected-inventory categories for the ITEMS section, mapped to what the app actually saves. */
private enum class ItemCat(val label: String) { TASKS("TASKS"), NOTES("NOTES"), OBJECTIVES("OBJECTIVES") }

/**
 * The ITEMS section body — a Fallout inventory of the things the user is tracking in Pulse: open tasks
 * (the deliberate-goal layer J.A.R.V.I.S. captures), saved notes and tracked objectives, picked through
 * a category rail and listed as the canonical Pip-Boy data rows. Read-only here; each item is curated
 * from its own screen (J.A.R.V.I.S. / NOTES / QUESTS). Favourite stations now live under the STATIONS tab.
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
                val on = ic == cat
                Box(
                    Modifier
                        .clickable { cat = ic }
                        .then(if (on) Modifier.drawBehind { drawCornerBrackets(Pip.glow) } else Modifier)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        "${ic.label} [$n]",
                        fontFamily = ChakraPetch,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 13.sp,
                        letterSpacing = 1.sp,
                        color = if (on) Pip.glow else Pip.dim,
                    )
                }
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
                if (objectives.isEmpty()) EmptyItems("No tracked objectives. Add one in QUESTS / MAP.")
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

/** One labelled HP/AP-style gauge: label + value over a thin filled bar. */
@Composable
private fun StatGauge(label: String, value: String, fraction: Float, modifier: Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontFamily = JetBrainsMono, fontSize = 9.sp, color = Pip.dim)
            Text(value, fontFamily = JetBrainsMono, fontSize = 9.sp, color = Pip.mid)
        }
        Canvas(Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp)) {
            drawRect(Pip.gridSoft)
            drawRect(Pip.bright, size = androidx.compose.ui.geometry.Size(size.width * fraction.coerceIn(0f, 1f), size.height))
        }
    }
}
