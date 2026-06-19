package dev.mascwa.pulse.feature.objectives

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.data.objectives.Objective
import dev.mascwa.pulse.data.objectives.ObjectiveKind
import dev.mascwa.pulse.data.objectives.ObjectiveSource
import dev.mascwa.pulse.feature.common.NeonChip
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * Standalone OBJECTIVES screen (kept for deep-links). The day-to-day surface is now the NAV map's
 * OBJECTIVES sub-tab, which embeds [ObjectivesPanel] directly over the map.
 */
@Composable
fun ObjectivesScreen(vm: ObjectivesViewModel, onBack: () -> Unit) {
    val c = Pulse.colors
    PulseScaffold(
        title = "OBJECTIVES",
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.ink) }
        },
        actions = { IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh", tint = c.ink) } },
    ) { innerPadding ->
        ObjectivesPanel(vm, c, Modifier.fillMaxSize().padding(innerPadding))
    }
}

/**
 * The scaffold-free objectives manager: link-calendar prompt, manual add form, and the tracked list
 * (each row tracks → becomes the active map waypoint). Reused by the standalone screen and the NAV
 * map's OBJECTIVES sub-tab. [modifier] sets the surface (size/background); content insets are internal.
 */
@Composable
fun ObjectivesPanel(vm: ObjectivesViewModel, c: NightwirePalette, modifier: Modifier = Modifier) {
    val objectives by vm.objectives.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val needsPerm by vm.needsCalendarPermission.collectAsState()

    val calendarPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.refresh()
    }

    var label by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ObjectiveKind.PLAIN) }

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (needsPerm) {
            item {
                NeonPanel(Modifier.fillMaxWidth(), corners = true, borderColor = c.amber.copy(alpha = 0.6f)) {
                    Column {
                        Text("LINK CALENDAR", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.ink)
                        Text(
                            "Grant calendar access to surface events with a location as objectives on the map.",
                            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted, modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            "◢ GRANT ACCESS",
                            fontFamily = JetBrainsMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.accent,
                            modifier = Modifier.padding(top = 10.dp).clickable { calendarPerm.launch(Manifest.permission.READ_CALENDAR) },
                        )
                    }
                }
            }
        }

        // Manual add.
        item {
            NeonPanel(Modifier.fillMaxWidth(), corners = true) {
                Column {
                    Text("ADD WAYPOINT", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.ink)
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Place or address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NeonChip("Main", kind == ObjectiveKind.MAIN, onClick = { kind = ObjectiveKind.MAIN })
                        NeonChip("Side", kind == ObjectiveKind.SIDE, onClick = { kind = ObjectiveKind.SIDE })
                        NeonChip("Plain", kind == ObjectiveKind.PLAIN, onClick = { kind = ObjectiveKind.PLAIN })
                    }
                    Text(
                        "◢ ADD",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = if (label.isBlank()) c.muted else c.accent,
                        modifier = Modifier.padding(top = 10.dp).clickable(enabled = label.isNotBlank()) {
                            vm.addManual(label.trim(), kind)
                            label = ""
                        },
                    )
                }
            }
        }

        if (objectives.isEmpty()) {
            item {
                Text(
                    "No quests yet. Add a waypoint above, or link your calendar. Tracked quests appear on " +
                        "the NAV map as ★ main / ◆ side / ● misc markers.",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // Fallout-style quest log: grouped by tier (MAIN / SIDE / MISC), tracked one highlighted.
        listOf(
            "MAIN QUESTS" to ObjectiveKind.MAIN,
            "SIDE QUESTS" to ObjectiveKind.SIDE,
            "MISCELLANEOUS" to ObjectiveKind.PLAIN,
        ).forEach { (title, kindOf) ->
            val group = objectives.filter { it.kind == kindOf }
            if (group.isNotEmpty()) {
                item(key = "hdr-$title") { QuestSectionHeader(title, group.size, c) }
                items(group, key = { it.id }) { o ->
                    QuestRow(
                        o = o,
                        active = o.id == activeId,
                        c = c,
                        onTrack = { vm.track(o) },
                        onRemove = if (o.source == ObjectiveSource.MANUAL) ({ vm.remove(o.id) }) else null,
                    )
                }
            }
        }
    }
}

/** A Fallout quest-log section header: a bracketed tier label + a count, over a rule line. */
@Composable
private fun QuestSectionHeader(title: String, count: Int, c: NightwirePalette) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "▌$title",
                fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                letterSpacing = 1.5.sp, color = c.accent,
            )
            Text(
                "  · $count",
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
            )
        }
        Box(Modifier.fillMaxWidth().padding(top = 5.dp).height(1.dp).background(c.lineSoft))
    }
}

/** A Fallout quest-log entry: a diamond marker (filled when tracked), the title, and an objective
 *  sub-line. Tapping the row tracks it; the active quest is highlighted with a "TRACKING" flag. */
@Composable
private fun QuestRow(
    o: Objective,
    active: Boolean,
    c: NightwirePalette,
    onTrack: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val kindColor = Color(o.kind.colorArgb)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onTrack).padding(vertical = 9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                if (active) "◆" else "◇",
                fontFamily = JetBrainsMono, fontSize = 14.sp,
                color = if (active) kindColor else c.muted,
                modifier = Modifier.padding(end = 10.dp, top = 1.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    o.title,
                    fontFamily = ChakraPetch, fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 15.sp, color = if (active) c.ink else c.ink2,
                )
                val meta = buildString {
                    append(if (o.source == ObjectiveSource.CALENDAR) "CAL" else "PIN")
                    o.distanceMeters?.let { append(" · ").append(Geo.formatDistance(it)) }
                    o.whenLabel?.let { append(" · ").append(it) }
                }
                Text(
                    "▸ $meta",
                    fontFamily = JetBrainsMono, fontSize = 10.sp,
                    color = if (active) c.accent else c.muted, modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (active) {
                Text(
                    "TRACKING", fontFamily = JetBrainsMono, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    color = c.amber, modifier = Modifier.padding(start = 6.dp, top = 2.dp),
                )
            }
            if (onRemove != null) {
                Text(
                    "✕", fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.muted,
                    modifier = Modifier.clickable(onClick = onRemove).padding(start = 10.dp, top = 1.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineSoft.copy(alpha = 0.4f)))
    }
}
