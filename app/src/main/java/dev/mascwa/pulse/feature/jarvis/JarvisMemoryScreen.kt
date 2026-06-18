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
