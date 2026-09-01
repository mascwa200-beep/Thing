package dev.mascwa.pulse.feature.jarvis

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
import dev.mascwa.pulse.feature.common.LcarsIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.data.selfedit.ArtifactVersion
import dev.mascwa.pulse.data.selfedit.PendingAction
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.SectionBar
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun JarvisApprovalsScreen(vm: JarvisApprovalsViewModel, onBack: () -> Unit) {
    val c = Pulse.colors
    val pending by vm.pending.collectAsState()
    val versions by vm.versions.collectAsState()
    val tools by vm.tools.collectAsState()
    val result by vm.result.collectAsState()

    PulseScaffold(
        title = "APPROVALS",
        onBack = onBack,
    ) { innerPadding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (result.isNotBlank()) {
                item {
                    Text("◢ $result", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.positive, modifier = Modifier.padding(top = 8.dp))
                }
            }

            item { SectionBar("PENDING · ${pending.size}") }
            if (pending.isEmpty()) {
                item {
                    Text(
                        "Nothing awaiting approval. When the computer proposes a change, it appears here — " +
                            "and only takes effect when you tap APPROVE.",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                    )
                }
            }
            items(pending, key = { "pending_${it.id}" }) { action ->
                ApprovalCard(action, c, onApprove = { vm.approve(action) }, onReject = { vm.reject(action.id) })
            }

            if (tools.isNotEmpty()) {
                item { SectionBar("AUTHORED TOOLS · ${tools.size}") }
                items(tools, key = { "tool_${it.name}" }) { t ->
                    AuthoredToolRow(
                        t, c,
                        onToggle = { vm.setToolEnabled(t.name, !t.enabled) },
                        onRemove = { vm.removeTool(t.name) },
                    )
                }
            }

            if (versions.isNotEmpty()) {
                item { SectionBar("HISTORY · tap to roll back") }
                items(versions, key = { "ver_${it.type}_${it.key}_${it.timestamp}" }) { v ->
                    VersionRow(v, c, onRollback = { vm.rollback(v) })
                }
            }
        }
    }
}

@Composable
private fun AuthoredToolRow(
    tool: dev.mascwa.pulse.data.selfedit.AuthoredTool,
    c: dev.mascwa.pulse.ui.theme.NightwirePalette,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    NeonPanel(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    tool.name + (if (!tool.enabled) " · off" else ""),
                    fontFamily = JetBrainsMono, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = if (tool.enabled) c.ink else c.muted,
                )
                val caps = if (tool.caps.isEmpty()) "no capabilities" else "caps: ${tool.caps.joinToString(", ")}"
                Text(caps, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, modifier = Modifier.padding(top = 2.dp))
            }
            ActionButton(if (tool.enabled) "DISABLE" else "ENABLE", if (tool.enabled) c.amber else c.positive, onToggle)
            ActionButton("DELETE", c.magenta, onRemove)
        }
    }
}

@Composable
private fun ApprovalCard(
    action: PendingAction,
    c: dev.mascwa.pulse.ui.theme.NightwirePalette,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    NeonPanel(Modifier.fillMaxWidth(), corners = true, borderColor = c.accent.copy(alpha = 0.6f)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(action.title, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.ink)
            val diff = action.payload["diff"]
            if (action.type == dev.mascwa.pulse.data.selfedit.ActionType.CODE_PR && !diff.isNullOrBlank()) {
                dev.mascwa.pulse.feature.common.DiffText(diff, maxLines = 400)
            } else {
                Text(action.preview.take(600), fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink2)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("APPROVE", c.positive, onApprove)
                ActionButton("REJECT", c.magenta, onReject)
            }
        }
    }
}

@Composable
private fun VersionRow(v: ArtifactVersion, c: dev.mascwa.pulse.ui.theme.NightwirePalette, onRollback: () -> Unit) {
    NeonPanel(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (v.key.isBlank()) v.type else "${v.type} · ${v.key}",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.ink,
                )
                Text(v.content.take(120), fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, modifier = Modifier.padding(top = 2.dp))
            }
            ActionButton("ROLL BACK", c.amber, onRollback)
        }
    }
}

@Composable
private fun ActionButton(text: String, color: Color, onClick: () -> Unit) {
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
