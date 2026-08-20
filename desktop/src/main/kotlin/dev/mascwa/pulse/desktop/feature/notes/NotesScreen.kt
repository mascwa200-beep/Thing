package dev.mascwa.pulse.desktop.feature.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.telemetry.ElapsedPhrase
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsButton
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsGhostButton
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.LcarsTextField
import dev.mascwa.pulse.desktop.theme.Pulse

/**
 * Notes and the diary, which are the same screen with two different words in it.
 *
 * ⚠️ Both are edit-in-place, not add-and-delete. That was a real gap on the phone until recently:
 * finding the entry you wanted to fix was easy and fixing it was impossible. Tapping a row loads it
 * into the composer above and the button becomes SAVE CHANGES — and the id and the date are kept, so
 * correcting a word does not make an entry new or re-date it over a typo.
 *
 * A desktop window is wide, so the composer sits BESIDE the list rather than above it: on a phone
 * that would be two cramped columns, but here it means you can read what you already wrote while
 * writing the next one, which is most of what a notes app is for.
 */
@Composable
fun NotesScreen(vm: NotesViewModel, modifier: Modifier = Modifier) {
    val notes by vm.notes.collectAsState(emptyList())
    val c = Pulse.colors

    var editingId by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }

    fun clear() {
        editingId = null; title = ""; body = ""; category = ""
    }

    Row(modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(360.dp)) {
            LcarsHeaderBar(if (editingId == null) "New note" else "Editing")
            LcarsFrame(Modifier.fillMaxWidth()) {
                Column {
                    LcarsTextField("Title", title, { title = it }, placeholder = "What is it")
                    Box(Modifier.height(8.dp))
                    LcarsTextField("Category", category, { category = it }, placeholder = "General")
                    Box(Modifier.height(8.dp))
                    LcarsTextField("Note", body, { body = it }, placeholder = "The note itself")
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LcarsButton(
                            if (editingId == null) "SAVE" else "SAVE CHANGES",
                            {
                                val id = editingId
                                if (id == null) vm.add(title, body, category) else vm.update(id, title, body, category)
                                clear()
                            },
                            // ⚠️ Disabled rather than silently no-op: the store refuses an entry with
                            // neither a title nor a body, and a button that appears to save and does
                            // not is worse than one that plainly cannot yet.
                            enabled = title.isNotBlank() || body.isNotBlank(),
                        )
                        if (editingId != null) LcarsGhostButton("CANCEL", { clear() })
                    }
                }
            }
        }

        Column(Modifier.weight(1f)) {
            LcarsHeaderBar("Notes", trailing = if (notes.isEmpty()) null else "${notes.size}")
            if (vm.loadFailed) {
                // ⚠️ The honest message, not an empty list. A file that exists and cannot be parsed is
                // someone's notes written by another build; showing "no notes yet" would be a lie that
                // invites them to start again on top of it.
                LcarsFrame(Modifier.fillMaxWidth(), accent = c.negative) {
                    Text(
                        "Your notes file could not be read by this build. Nothing has been overwritten — " +
                            "it is still on disk exactly as it was.",
                        fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 17.sp, color = c.ink,
                    )
                }
            } else if (notes.isEmpty()) {
                LcarsFrame(Modifier.fillMaxWidth()) {
                    Text("No notes yet.", fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted)
                }
            }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(notes, key = { it.id }) { n ->
                    LcarsFrame(Modifier.fillMaxWidth()) {
                        Column {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    n.title.ifBlank { "Untitled" },
                                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp, color = c.ink, modifier = Modifier.weight(1f),
                                )
                                Text(
                                    n.category.uppercase(),
                                    fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp,
                                    color = c.accent,
                                )
                            }
                            if (n.body.isNotBlank()) {
                                Text(
                                    n.body,
                                    fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 17.sp,
                                    color = c.ink2, modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            Row(
                                Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                LcarsGhostButton("EDIT", {
                                    editingId = n.id; title = n.title; body = n.body; category = n.category
                                })
                                LcarsGhostButton("DELETE", { vm.remove(n.id); if (editingId == n.id) clear() })
                                Text(
                                    // The shared elapsed-phrase core, so "3 hours ago" is worded
                                    // identically on both machines.
                                    ElapsedPhrase.describe(System.currentTimeMillis() - n.createdMs),
                                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.faint,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The journal. Same arrangement as [NotesScreen]; a mood rather than a category. */
@Composable
fun DiaryScreen(vm: DiaryViewModel, modifier: Modifier = Modifier) {
    val entries by vm.entries.collectAsState(emptyList())
    val c = Pulse.colors

    var editingId by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var mood by remember { mutableStateOf("") }

    fun clear() {
        editingId = null; title = ""; body = ""; mood = ""
    }

    Row(modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(360.dp)) {
            LcarsHeaderBar(if (editingId == null) "Today" else "Editing")
            LcarsFrame(Modifier.fillMaxWidth()) {
                Column {
                    LcarsTextField("Title", title, { title = it }, placeholder = "The day in a few words")
                    Box(Modifier.height(8.dp))
                    LcarsTextField("Mood", mood, { mood = it }, placeholder = "Optional")
                    Box(Modifier.height(8.dp))
                    LcarsTextField("Entry", body, { body = it }, placeholder = "What happened")
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LcarsButton(
                            if (editingId == null) "SAVE" else "SAVE CHANGES",
                            {
                                val id = editingId
                                if (id == null) vm.add(title, body, mood) else vm.update(id, title, body, mood)
                                clear()
                            },
                            enabled = title.isNotBlank() || body.isNotBlank(),
                        )
                        if (editingId != null) LcarsGhostButton("CANCEL", { clear() })
                    }
                }
            }
        }

        Column(Modifier.weight(1f)) {
            LcarsHeaderBar("Diary", trailing = if (entries.isEmpty()) null else "${entries.size}")
            if (vm.loadFailed) {
                LcarsFrame(Modifier.fillMaxWidth(), accent = c.negative) {
                    Text(
                        "Your diary file could not be read by this build. Nothing has been overwritten — " +
                            "it is still on disk exactly as it was.",
                        fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 17.sp, color = c.ink,
                    )
                }
            } else if (entries.isEmpty()) {
                LcarsFrame(Modifier.fillMaxWidth()) {
                    Text("Nothing written yet.", fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted)
                }
            }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries, key = { it.id }) { e ->
                    LcarsFrame(Modifier.fillMaxWidth()) {
                        Column {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    e.title.ifBlank { "Untitled" },
                                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp, color = c.ink, modifier = Modifier.weight(1f),
                                )
                                if (e.mood.isNotBlank()) {
                                    Text(
                                        e.mood.uppercase(),
                                        fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp,
                                        color = c.violet,
                                    )
                                }
                            }
                            if (e.body.isNotBlank()) {
                                Text(
                                    e.body,
                                    fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 17.sp,
                                    color = c.ink2, modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            Row(
                                Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                LcarsGhostButton("EDIT", {
                                    editingId = e.id; title = e.title; body = e.body; mood = e.mood
                                })
                                LcarsGhostButton("DELETE", { vm.remove(e.id); if (editingId == e.id) clear() })
                                Text(
                                    ElapsedPhrase.describe(System.currentTimeMillis() - e.createdMs),
                                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.faint,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
