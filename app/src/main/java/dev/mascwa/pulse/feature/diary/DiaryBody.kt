package dev.mascwa.pulse.feature.diary

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dev.mascwa.pulse.data.diary.DiaryEntry
import dev.mascwa.pulse.feature.common.PipFrame
import dev.mascwa.pulse.feature.common.PipHeader
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The DIARY feed — a dated personal journal: write an entry (optionally moodtagged), then browse the
 *  whole journal newest-first. Renders in the PIP-BOY green palette, mirroring the LIBRARY (NOTES). */
@Composable
fun DiaryBody(vm: DiaryViewModel, modifier: Modifier = Modifier) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val c = Pulse.colors
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var mood by remember { mutableStateOf("") }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        // ---- NEW ENTRY ----
        PipHeader("New Entry")
        PipFrame(Modifier.fillMaxWidth()) {
            Column {
                DiaryField(title, { title = it }, "Title (optional)", c)
                Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    DiaryField(body, { body = it }, "How did it go? What's on your mind…", c, single = false)
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    vm.moods.forEach { m ->
                        MoodChip(if (m.isBlank()) "—" else m, selected = m == mood, c = c) { mood = m }
                    }
                }
                Box(
                    Modifier.padding(top = 12.dp)
                        .border(1.dp, c.accent, RoundedCornerShape(6.dp))
                        .clickable {
                            if (title.isNotBlank() || body.isNotBlank()) {
                                vm.add(title, body, mood)
                                title = ""; body = ""; mood = ""
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("▸ SAVE ENTRY", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        letterSpacing = 1.5.sp, color = c.accent)
                }
            }
        }

        // ---- JOURNAL (newest first) ----
        if (entries.isEmpty()) {
            Text(
                "No entries yet — write one above, or ask J.A.R.V.I.S. to journal for you. Everything " +
                    "stays on this device.",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
            )
        } else {
            PipHeader("Journal", trailing = entries.size.toString())
            entries.forEach { entry -> EntryRow(entry, c) { vm.delete(entry.id) } }
            Box(
                Modifier.padding(top = 14.dp, bottom = 28.dp)
                    .border(1.dp, c.line, RoundedCornerShape(6.dp))
                    .clickable { vm.clear() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("CLEAR DIARY", fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.muted)
            }
        }
    }
}

/** A banded journal row (Fallout DATA>STATS look): date + optional mood header, title, body, delete. */
@Composable
private fun EntryRow(entry: DiaryEntry, c: NightwirePalette, onDelete: () -> Unit) {
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
            val stamp = DATE_FMT.format(Date(entry.createdMs)) + if (entry.mood.isNotBlank()) "  ·  ${entry.mood}" else ""
            Text(stamp, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.5.sp, color = c.accent)
            if (entry.title.isNotBlank() && entry.title != entry.body) {
                Text(entry.title, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.ink,
                    modifier = Modifier.padding(top = 3.dp))
            }
            if (entry.body.isNotBlank()) {
                Text(entry.body, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
        Text("✕", fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.muted,
            modifier = Modifier.clickable { onDelete() }.padding(start = 10.dp))
    }
}

/** A mood selector pill. */
@Composable
private fun MoodChip(label: String, selected: Boolean, c: NightwirePalette, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (selected) c.accent.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (selected) c.accent else c.line, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp,
            color = if (selected) c.ink else c.muted, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/** A Pip-Boy terminal text input. */
@Composable
private fun DiaryField(value: String, onChange: (String) -> Unit, placeholder: String, c: NightwirePalette, single: Boolean = true) {
    Box(
        Modifier.fillMaxWidth()
            .border(1.dp, c.line, RoundedCornerShape(4.dp))
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

private val DATE_FMT = SimpleDateFormat("EEE MM.dd.yyyy · HH:mm", Locale.US)
