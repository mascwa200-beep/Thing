package dev.mascwa.pulse.desktop.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.telemetry.Guide
import dev.mascwa.pulse.core.telemetry.GuideIndexEntry
import dev.mascwa.pulse.core.telemetry.GuideSection
import dev.mascwa.pulse.desktop.library.LibraryRepository
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsBusyBar
import dev.mascwa.pulse.desktop.theme.LcarsButton
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.Pulse

/**
 * LIBRARY — the bundled Knowledge Base, browsed and read.
 *
 * A supergroup ▸ category rail on the left, the category's guides beside it, and the reader in place of
 * the list once one is open. Reading long-form is the thing a desktop is genuinely better at than a
 * phone, which is most of why this screen exists here at all.
 */
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    repository: LibraryRepository,
    onStudy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val c = Pulse.colors

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        LcarsHeaderBar("Library", trailing = if (state.total > 0) "${state.total} GUIDES" else null)
        LcarsBusyBar(state.loading || state.openBusy)

        Row(Modifier.fillMaxWidth().weight(1f)) {
            CategoryRail(
                groups = state.groups,
                selected = state.category,
                onSelect = { vm.select(it) },
                modifier = Modifier.width(210.dp).fillMaxHeight(),
            )
            Spacer(Modifier.width(16.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val open = state.open
                if (open != null) {
                    Reader(
                        guide = open,
                        repository = repository,
                        notice = state.imageNotice,
                        onClose = { vm.close() },
                        onStudy = { onStudy(open.id) },
                    )
                } else {
                    GuideList(state.guides, onOpen = { vm.open(it) })
                }
            }
        }
    }
}

@Composable
private fun CategoryRail(
    groups: List<Pair<String, List<String>>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Pulse.colors
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        groups.forEach { (group, categories) ->
            item {
                Text(
                    group.uppercase(),
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                    fontSize = 11.sp, letterSpacing = 2.sp, color = c.faint,
                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                )
            }
            items(categories) { category ->
                val on = category == selected
                Text(
                    category,
                    fontFamily = JetBrainsMono, fontSize = 12.sp,
                    color = if (on) c.void else c.ink2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (on) c.accent else c.void)
                        .clickable { onSelect(category) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun GuideList(guides: List<GuideIndexEntry>, onOpen: (String) -> Unit) {
    val c = Pulse.colors
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        items(guides, key = { it.id }) { g ->
            LcarsFrame(Modifier.fillMaxWidth().clickable { onOpen(g.id) }) {
                Column {
                    Text(g.title, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.ink)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        g.summary,
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                        modifier = Modifier.widthIn(max = 900.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${g.headings.size} sections",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
                    )
                }
            }
        }
    }
}

@Composable
private fun Reader(
    guide: Guide,
    repository: LibraryRepository,
    notice: String,
    onClose: () -> Unit,
    onStudy: () -> Unit,
) {
    val c = Pulse.colors
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LcarsButton("◂ BACK", onClick = onClose, accent = c.muted)
                LcarsButton("STUDY THIS", onClick = onStudy)
            }
        }
        item {
            Column {
                Text(guide.title, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = c.ink)
                Spacer(Modifier.height(4.dp))
                Text(guide.category, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.accent)
                Spacer(Modifier.height(8.dp))
                Text(
                    guide.summary,
                    fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted, lineHeight = 18.sp,
                    modifier = Modifier.widthIn(max = READING_WIDTH),
                )
            }
        }
        guide.safetyNote?.takeIf { it.isNotBlank() }?.let { note ->
            item {
                // Amber and above the prose, as on the phone: a safety note that scrolls past with the
                // body is a safety note nobody read.
                LcarsFrame(Modifier.widthIn(max = READING_WIDTH), accent = c.amber) {
                    Column {
                        Text(
                            "SAFETY",
                            fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 2.sp, color = c.amber,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(note, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink, lineHeight = 18.sp)
                    }
                }
            }
        }
        items(guide.sections) { section -> Section(section, repository) }
        if (notice.isNotBlank() && guide.sections.any { it.image != null }) {
            item {
                Text(
                    notice.trim(),
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.faint, lineHeight = 13.sp,
                    modifier = Modifier.widthIn(max = READING_WIDTH).padding(top = 18.dp),
                )
            }
        }
    }
}

@Composable
private fun Section(section: GuideSection, repository: LibraryRepository) {
    val c = Pulse.colors
    Column(Modifier.padding(top = 10.dp)) {
        Text(
            section.heading,
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.accent,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            section.body,
            fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink2, lineHeight = 19.sp,
            modifier = Modifier.widthIn(max = READING_WIDTH),
        )
        section.ingredients?.takeIf { it.isNotEmpty() }?.let { list ->
            Spacer(Modifier.height(8.dp))
            list.forEach {
                Text("· $it", fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink2, lineHeight = 18.sp)
            }
        }
        section.steps?.takeIf { it.isNotEmpty() }?.let { list ->
            Spacer(Modifier.height(8.dp))
            list.forEachIndexed { i, step ->
                Text(
                    "${i + 1}. $step",
                    fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink2, lineHeight = 18.sp,
                    modifier = Modifier.widthIn(max = READING_WIDTH),
                )
            }
        }
        section.image?.takeIf { it.isNotBlank() }?.let { image ->
            Spacer(Modifier.height(10.dp))
            Diagram(repository, image)
        }
    }
}

/** A measure, not the window width. Prose set the full width of a desktop monitor is unreadable. */
private val READING_WIDTH = 780.dp
