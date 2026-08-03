package dev.mascwa.pulse.feature.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.data.notes.Note
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The LIBRARY (NOTES) feed — a DATA>NOTES log: add an entry, then browse it sorted into
 *  category sections with banded rows. Renders in the LCARS palette. */
@Composable
fun NotesBody(vm: NotesViewModel, modifier: Modifier = Modifier) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    val c = Pulse.colors
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(vm.categories.first()) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        // ---- ADD ENTRY ----
        LcarsHeaderBar("Add Entry")
        LcarsFrame(Modifier.fillMaxWidth()) {
            Column {
                NoteField(title, { title = it }, "Title", c)
                Box(Modifier.fillMaxWidth().padding(top = 8.dp)) { NoteField(body, { body = it }, "Details…", c, single = false) }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    vm.categories.forEach { cat ->
                        CatChip(cat, selected = cat == category, c = c) { category = cat }
                    }
                }
                Box(
                    Modifier.padding(top = 12.dp)
                        .border(1.dp, c.accent, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .clickable {
                            if (title.isNotBlank() || body.isNotBlank()) {
                                vm.add(title, body, category)
                                title = ""; body = ""
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("▸ FILE ENTRY", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        letterSpacing = 1.5.sp, color = c.accent)
                }
            }
        }

        // ---- LIBRARY (grouped) ----
        if (notes.isEmpty()) {
            Text(
                "No entries yet — file a note above. Everything stays on this device.",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
            )
        } else {
            val grouped = notes.groupBy { it.category }
            val order = vm.categories + grouped.keys.filterNot { it in vm.categories }
            order.forEach { cat ->
                val items = grouped[cat] ?: return@forEach
                LcarsHeaderBar(cat, trailing = items.size.toString())
                items.forEach { note -> NoteRow(note, c) { vm.delete(note.id) } }
            }
            Box(Modifier.padding(bottom = 24.dp))
        }
    }
}

/** A banded library row in the canonical DATA>STATS look: an edge-to-edge banded row with
 *  a bright hairline rule beneath (rows stack gap-free), holding title + body + date and a delete control. */
@Composable
private fun NoteRow(note: Note, c: NightwirePalette, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .drawBehind {
                drawRect(c.accent.copy(alpha = 0.08f))
                drawLine(c.accent.copy(alpha = 0.35f), Offset(0f, size.height), Offset(size.width, size.height), 1.2.dp.toPx())
            }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(note.title, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.ink)
            if (note.body.isNotBlank() && note.body != note.title) {
                Text(note.body, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
                    modifier = Modifier.padding(top = 2.dp))
            }
            Text(DATE_FMT.format(Date(note.createdMs)), fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                modifier = Modifier.padding(top = 4.dp))
        }
        Text("✕", fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.muted,
            modifier = Modifier.clickable { onDelete() }.padding(start = 10.dp))
    }
}

/** A category selector pill. */
@Composable
private fun CatChip(label: String, selected: Boolean, c: NightwirePalette, onClick: () -> Unit) {
    Box(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .background(if (selected) c.accent.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (selected) c.accent else c.line, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp,
            color = if (selected) c.ink else c.muted, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/** An LCARS terminal text input. */
@Composable
private fun NoteField(value: String, onChange: (String) -> Unit, placeholder: String, c: NightwirePalette, single: Boolean = true) {
    Box(
        Modifier.fillMaxWidth()
            .border(1.dp, c.line, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = single,
            textStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val DATE_FMT = SimpleDateFormat("MM.dd.yyyy", Locale.US)
