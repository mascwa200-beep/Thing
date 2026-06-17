package dev.mascwa.pulse.feature.objectives

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import dev.mascwa.pulse.feature.common.SectionBar
import dev.mascwa.pulse.feature.common.StatusDot
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun ObjectivesScreen(vm: ObjectivesViewModel, onBack: () -> Unit) {
    val c = Pulse.colors
    val objectives by vm.objectives.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val needsPerm by vm.needsCalendarPermission.collectAsState()

    val calendarPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.refresh()
    }

    var label by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ObjectiveKind.PLAIN) }

    PulseScaffold(
        title = "OBJECTIVES",
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.ink) }
        },
        actions = { IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh", tint = c.ink) } },
    ) { innerPadding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
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

            item { SectionBar("TRACKED · ${objectives.size}") }

            if (objectives.isEmpty()) {
                item {
                    Text(
                        "No objectives yet. Add a waypoint above, or link your calendar.",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                    )
                }
            }

            items(objectives, key = { it.id }) { o ->
                ObjectiveRow(
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

@Composable
private fun ObjectiveRow(
    o: Objective,
    active: Boolean,
    c: dev.mascwa.pulse.ui.theme.NightwirePalette,
    onTrack: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val kindColor = Color(o.kind.colorArgb)
    NeonPanel(
        Modifier.fillMaxWidth(),
        corners = active,
        borderColor = if (active) kindColor else c.lineSoft,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(kindColor, 10)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(o.title, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.ink)
                val meta = buildString {
                    append(if (o.source == ObjectiveSource.CALENDAR) "CAL" else "PIN")
                    o.distanceMeters?.let { append(" · ").append(Geo.formatDistance(it)) }
                    o.whenLabel?.let { append(" · ").append(it) }
                }
                Text(meta, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, modifier = Modifier.padding(top = 2.dp))
            }
            Text(
                if (active) "TRACKED" else "TRACK",
                fontFamily = JetBrainsMono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = if (active) c.amber else c.accent,
                modifier = Modifier.clickable(onClick = onTrack).padding(8.dp),
            )
            if (onRemove != null) {
                Text("✕", fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.muted,
                    modifier = Modifier.clickable(onClick = onRemove).padding(8.dp))
            }
        }
    }
}
