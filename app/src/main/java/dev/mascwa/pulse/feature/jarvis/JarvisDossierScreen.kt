package dev.mascwa.pulse.feature.jarvis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.telemetry.ProfileEntry
import dev.mascwa.pulse.core.telemetry.Task
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.SectionBar
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * OPERATOR DOSSIER — a spy-style intelligence file J.A.R.V.I.S. keeps on its operator, compiled from
 * data the app already holds on-device (profile, objectives, device disposition, recent activity). Pure
 * presentation; the derived callsign / intel-level come from the CI-tested [dev.mascwa.pulse.core.telemetry.OperatorDossier].
 */
@Composable
fun JarvisDossierScreen(vm: JarvisDossierViewModel, onBack: () -> Unit) {
    val c = Pulse.colors
    val meta by vm.meta.collectAsState()
    val profile by vm.profile.collectAsState()
    val objectives by vm.objectives.collectAsState()

    PulseScaffold(
        title = "DOSSIER",
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.ink) }
        },
    ) { innerPadding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { DossierCover(meta, c) }

            item { SectionBar("IDENTITY · ${profile.size}") }
            if (profile.isEmpty()) {
                item {
                    EmptyLine(
                        "No profile on file. As J.A.R.V.I.S. learns your preferences, interests and " +
                            "projects — only from your answers — they're logged here.",
                        c,
                    )
                }
            }
            items(profile, key = { it.category.name + "·" + it.text }) { entry ->
                DossierRow(entry.category.name, entry.text, c)
            }

            item { SectionBar("OBJECTIVES · ${objectives.size}") }
            if (objectives.isEmpty()) {
                item { EmptyLine("No objectives on record. Tracked tasks and waypoints appear here.", c) }
            }
            items(objectives, key = { it.title }) { task ->
                DossierRow(task.status.name, task.title, c)
            }

            item { SectionBar("ASSET") }
            item {
                NeonPanel(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        KeyVal("DEVICE", meta.device.ifBlank { "—" }, c)
                        KeyVal("OS", meta.os.ifBlank { "—" }, c)
                        KeyVal("BUILD", meta.build.ifBlank { "—" }, c)
                        KeyVal("DISPOSITION", meta.disposition, c)
                    }
                }
            }

            item { SectionBar("ACTIVITY · ${meta.activity.size}") }
            if (meta.activity.isEmpty()) {
                item { EmptyLine("No recent activity logged.", c) }
            }
            items(meta.activity) { line ->
                Text(line, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted)
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun DossierCover(meta: JarvisDossierViewModel.Meta, c: NightwirePalette) {
    NeonPanel(Modifier.fillMaxWidth(), corners = true) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "ARGUS DYNAMICS // OPERATOR DOSSIER",
                fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.5.sp, color = c.accent,
            )
            Text(
                meta.codename,
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = c.ink,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("CLEARANCE", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
                Text(
                    meta.classification,
                    fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = c.amber,
                )
            }
            IntelBar(meta.intelLevel, c)
        }
    }
}

@Composable
private fun IntelBar(level: Int, c: NightwirePalette) {
    val frac = level.coerceIn(0, 100) / 100f
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("INTEL", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
            Text("$level%", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.sky)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).background(c.panel)) {
            Box(Modifier.fillMaxWidth(frac).height(6.dp).background(c.sky))
        }
    }
}

@Composable
private fun DossierRow(tag: String, text: String, c: NightwirePalette) {
    NeonPanel(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tag, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.accent, modifier = Modifier.width(70.dp))
            Text(text, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun KeyVal(k: String, v: String, c: NightwirePalette) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(k, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, modifier = Modifier.width(96.dp))
        Text(v, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EmptyLine(text: String, c: NightwirePalette) {
    Text(text, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted)
}
