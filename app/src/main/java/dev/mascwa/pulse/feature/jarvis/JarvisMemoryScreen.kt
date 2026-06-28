package dev.mascwa.pulse.feature.jarvis

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.telemetry.Memory
import dev.mascwa.pulse.core.telemetry.ProfileEntry
import dev.mascwa.pulse.core.telemetry.Task
import dev.mascwa.pulse.core.telemetry.TaskStatus
import dev.mascwa.pulse.data.jarvis.db.AgentNoteEntity
import dev.mascwa.pulse.data.jarvis.db.NoteSource
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.SectionBar
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun JarvisMemoryScreen(vm: JarvisMemoryViewModel, onBack: () -> Unit) {
    val c = Pulse.colors
    val notes by vm.notes.collectAsState()
    val profile by vm.profile.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val episodic by vm.episodic.collectAsState()
    val interests by vm.interests.collectAsState()
    val findings by vm.findings.collectAsState()
    val procedures by vm.procedures.collectAsState()

    PulseScaffold(
        title = "MEMORY",
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.ink) }
        },
    ) { innerPadding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionBar("REMEMBERED · ${notes.size}") }
            if (notes.isEmpty()) {
                item {
                    Text(
                        "Nothing remembered yet. As J.A.R.V.I.S. learns about you — only from your answers, " +
                            "and only after you confirm — facts appear here, editable and deletable anytime.",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                    )
                }
            }
            items(notes, key = { it.id }) { note ->
                MemoryCard(note, c, onSave = { vm.edit(note.id, it) }, onDelete = { vm.delete(note.id) })
            }
            if (notes.isNotEmpty()) {
                item {
                    MemButton("CLEAR ALL", c.magenta) { vm.clearAll() }
                }
            }

            item { SectionBar("PROFILE · ${profile.size}") }
            if (profile.isEmpty()) {
                item {
                    Text(
                        "No preferences, interests or projects remembered yet. Tell J.A.R.V.I.S. things " +
                            "like \"I prefer concise answers\" or \"I'm building an app\" and they'll appear here.",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                    )
                }
            }
            items(profile, key = { it.category.name + "·" + it.text }) { entry ->
                ProfileCard(entry, c, onForget = { vm.forgetProfile(entry.text) })
            }
            if (profile.isNotEmpty()) {
                item {
                    MemButton("CLEAR PROFILE", c.magenta) { vm.clearProfile() }
                }
            }

            item { SectionBar("TASKS · ${tasks.size}") }
            if (tasks.isEmpty()) {
                item {
                    Text(
                        "No tasks tracked yet. Say things like \"I need to finish the report\" or ask " +
                            "J.A.R.V.I.S. to track a task, and your open tasks appear here.",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                    )
                }
            }
            items(tasks, key = { it.title }) { task ->
                TaskCard(task, c, onForget = { vm.forgetTask(task.title) })
            }
            if (tasks.isNotEmpty()) {
                item {
                    MemButton("CLEAR TASKS", c.magenta) { vm.clearTasks() }
                }
            }

            item { SectionBar("EPISODIC · ${episodic.size}") }
            if (episodic.isEmpty()) {
                item {
                    Text(
                        "No episodic memories yet. As you talk, J.A.R.V.I.S. records significant moments " +
                            "(time-stamped) and recalls the relevant ones later. They appear here to review " +
                            "and forget.",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                    )
                }
            }
            items(episodic, key = { it.id }) { mem ->
                EpisodicCard(mem, c, onForget = { vm.forgetMemory(mem.id) })
            }
            if (episodic.isNotEmpty()) {
                item {
                    MemButton("CLEAR EPISODIC", c.magenta) { vm.clearEpisodic() }
                }
            }

            item { SectionBar("INTERESTS · ${interests.size}") }
            if (interests.isEmpty()) {
                item {
                    Text(
                        "No standing interests yet. Give J.A.R.V.I.S. a standing order (\"keep an eye on " +
                            "temporal-AI-consciousness research\") and he'll track it — and develop his own " +
                            "curiosities over time. They appear here.",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                    )
                }
            }
            items(interests, key = { it.id }) { interest ->
                InterestCard(interest, c, onForget = { vm.forgetInterest(interest.topic) })
            }
            if (interests.isNotEmpty()) {
                item {
                    MemButton("CLEAR INTERESTS", c.magenta) { vm.clearInterests() }
                }
            }

            item { SectionBar("FINDINGS · ${findings.size}") }
            if (findings.isEmpty()) {
                item {
                    Text(
                        "Nothing curated yet. As J.A.R.V.I.S. follows his interests and audits this device, " +
                            "he records things worth showing you here — newest first, with a NEW badge until " +
                            "you've seen them.",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                    )
                }
            }
            items(findings, key = { it.id }) { finding ->
                FindingCard(
                    finding, c,
                    onSeen = { vm.markFindingSeen(finding.id) },
                    onForget = { vm.forgetFinding(finding.id) },
                )
            }
            if (findings.isNotEmpty()) {
                item {
                    MemButton("CLEAR FINDINGS", c.magenta) { vm.clearFindings() }
                }
            }

            item { SectionBar("PROCEDURES · ${procedures.size}") }
            if (procedures.isEmpty()) {
                item {
                    Text(
                        "No procedures learned yet. When J.A.R.V.I.S. carries out a multi-step request with " +
                            "tools and it works, he remembers the recipe here — so a similar goal later follows " +
                            "the known plan. They appear here to review and forget.",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                    )
                }
            }
            items(procedures, key = { it.name }) { proc ->
                ProcedureCard(proc, c, onForget = { vm.forgetProcedure(proc.name) })
            }
            if (procedures.isNotEmpty()) {
                item {
                    MemButton("CLEAR PROCEDURES", c.magenta) { vm.clearProcedures() }
                }
            }
        }
    }
}

@Composable
private fun ProcedureCard(proc: dev.mascwa.pulse.core.telemetry.Procedure, c: NightwirePalette, onForget: () -> Unit) {
    NeonPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${(proc.reliability * 100).toInt()}% · ${proc.timesApplied}×",
                    fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 1.sp, color = c.positive,
                )
                if (!proc.practiced()) {
                    Text("LEARNING", fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted)
                }
            }
            Text(proc.name, fontFamily = JetBrainsMono, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.ink)
            Text(proc.steps.joinToString(" → "), fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.accent)
            MemButton("FORGET", c.magenta, onForget)
        }
    }
}

@Composable
private fun InterestCard(interest: dev.mascwa.pulse.data.interests.Interest, c: NightwirePalette, onForget: () -> Unit) {
    val isOwner = interest.origin == dev.mascwa.pulse.data.interests.InterestOrigin.OWNER
    NeonPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (isOwner) "STANDING ORDER" else "OWN CURIOSITY",
                fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 1.sp,
                color = if (isOwner) c.accent else c.violet,
            )
            Text(interest.topic, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink)
            if (interest.note.isNotBlank()) {
                Text(interest.note, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
            }
            MemButton("FORGET", c.magenta, onForget)
        }
    }
}

@Composable
private fun FindingCard(
    finding: dev.mascwa.pulse.data.findings.Finding,
    c: NightwirePalette,
    onSeen: () -> Unit,
    onForget: () -> Unit,
) {
    val kindColor = when (finding.kind) {
        dev.mascwa.pulse.data.findings.FindingKind.STANDING -> c.accent
        dev.mascwa.pulse.data.findings.FindingKind.EMERGENT -> c.violet
        dev.mascwa.pulse.data.findings.FindingKind.DEVICE -> c.positive
    }
    NeonPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    finding.kind.name,
                    fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 1.sp, color = kindColor,
                )
                Text(
                    DateUtils.getRelativeTimeSpanString(finding.createdMs).toString(),
                    fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted,
                )
                if (!finding.seen) {
                    Text("• NEW", fontFamily = JetBrainsMono, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = c.magenta)
                }
            }
            Text(finding.headline, fontFamily = JetBrainsMono, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.ink)
            if (finding.body.isNotBlank()) {
                Text(finding.body, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted)
            }
            if (finding.sourceUrl.isNotBlank()) {
                Text(finding.sourceUrl, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.accent)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!finding.seen) MemButton("MARK SEEN", c.accent, onSeen)
                MemButton("FORGET", c.magenta, onForget)
            }
        }
    }
}

@Composable
private fun EpisodicCard(mem: Memory, c: NightwirePalette, onForget: () -> Unit) {
    NeonPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    mem.kind.name,
                    fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 1.sp,
                    color = if (mem.kind.name == "REFLECTION") c.violet else c.accent,
                )
                Text(
                    DateUtils.getRelativeTimeSpanString(mem.createdMs).toString(),
                    fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted,
                )
                Text(
                    "importance ${mem.importance}",
                    fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted,
                )
            }
            Text(mem.text, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink)
            MemButton("FORGET", c.magenta, onForget)
        }
    }
}

@Composable
private fun TaskCard(task: Task, c: NightwirePalette, onForget: () -> Unit) {
    val statusColor = when (task.status) {
        TaskStatus.ACTIVE -> c.positive
        TaskStatus.BLOCKED -> c.magenta
        TaskStatus.DONE -> c.muted
        TaskStatus.OPEN -> c.accent
    }
    NeonPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                task.status.name,
                fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 1.sp, color = statusColor,
            )
            Text(task.title, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink)
            if (task.note.isNotBlank()) {
                Text(task.note, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
            }
            MemButton("FORGET", c.magenta, onForget)
        }
    }
}

@Composable
private fun ProfileCard(entry: ProfileEntry, c: NightwirePalette, onForget: () -> Unit) {
    NeonPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                entry.category.name,
                fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 1.sp, color = c.accent,
            )
            Text(entry.text, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink)
            MemButton("FORGET", c.magenta, onForget)
        }
    }
}

@Composable
private fun MemoryCard(
    note: AgentNoteEntity,
    c: NightwirePalette,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var draft by remember(note.id, note.noteText) { mutableStateOf(note.noteText) }
    val dirty = draft.trim() != note.noteText.trim() && draft.isNotBlank()
    NeonPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    note.source.uppercase(),
                    fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 1.sp,
                    color = if (note.source == NoteSource.LEARNED) c.positive else c.muted,
                )
                Text(
                    DateUtils.getRelativeTimeSpanString(note.timestamp).toString(),
                    fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted,
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = TextStyle(color = c.ink, fontFamily = JetBrainsMono, fontSize = 12.sp),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MemButton("SAVE", if (dirty) c.accent else c.muted) { if (dirty) onSave(draft) }
                MemButton("DELETE", c.magenta, onDelete)
            }
        }
    }
}

@Composable
private fun MemButton(text: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text, fontFamily = JetBrainsMono, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = color)
    }
}
