package dev.mascwa.pulse.feature.survive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import dev.mascwa.pulse.feature.common.LcarsIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.mascwa.pulse.core.telemetry.StudyProgress
import dev.mascwa.pulse.core.telemetry.Guide
import dev.mascwa.pulse.core.telemetry.GuideIndexEntry
import dev.mascwa.pulse.core.telemetry.SUPERGROUPS
import dev.mascwa.pulse.core.telemetry.supergroupOf
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsChip
import dev.mascwa.pulse.feature.common.LcarsFillRow
import dev.mascwa.pulse.feature.common.LcarsField
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import kotlinx.coroutines.launch

/** Relevance rank over the catalog-index fields: title/category hits weigh most, headings least. Body
 *  hits arrive separately (the streamed per-shard scan in [GuidesViewModel.bodyMatches]). */
private fun rank(e: GuideIndexEntry, q: String): Int {
    var r = 0
    if (e.title.contains(q, true)) r += 100
    if (e.category.contains(q, true)) r += 60
    if (e.summary.contains(q, true)) r += 40
    r += e.headings.count { it.contains(q, true) } * 20
    return r
}

@Composable
fun GuidesScreen(
    vm: GuidesViewModel,
    onBack: (() -> Unit)? = null,
    initialGuideId: String? = null,
    onOpenStudy: (() -> Unit)? = null,
) {
    val entries by vm.index.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()

    // ⚠️ The system back gesture now agrees with the corner: inside a guide it closes the READER,
    // not the whole screen. `enabled` is gated on the sub-state — an always-enabled BackHandler
    // would swallow system back app-wide (it beats the NavHost's own handler).
    androidx.activity.compose.BackHandler(enabled = selected != null) { vm.closeReader() }
    val mastery by vm.mastery.collectAsStateWithLifecycle()
    val taught by vm.taught.collectAsStateWithLifecycle()
    val bodyMatches by vm.bodyMatches.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var supergroup by remember { mutableStateOf<String?>(null) } // null = every supergroup
    var category by remember { mutableStateOf<String?>(null) } // null = every category within scope

    // rememberSaveable: the reader now lives in the ViewModel (survives rotation), so the one-shot
    // deep-link open must survive rotation too — else recreation re-fires it and hijacks the reader.
    var openedInitial by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(entries, initialGuideId) {
        if (!openedInitial && !initialGuideId.isNullOrBlank() && entries.isNotEmpty()) {
            if (selected == null && entries.any { it.id == initialGuideId }) vm.open(initialGuideId)
            openedInitial = true
        }
    }
    val c = Pulse.colors

    val categories = remember(entries) { entries.map { it.category }.distinct().sorted() }
    val categoriesInScope = remember(categories, supergroup) {
        if (supergroup == null) categories else categories.filter { supergroupOf(it) == supergroup }
    }

    val q = query.trim()
    val visible = remember(entries, q, supergroup, category, bodyMatches) {
        val scoped = entries.filter {
            (supergroup == null || supergroupOf(it.category) == supergroup) &&
                (category == null || it.category == category)
        }
        if (q.isBlank()) scoped
        else scoped.map { it to rank(it, q) }
            .filter { (e, r) -> r > 0 || e.id in bodyMatches }
            .sortedByDescending { (e, r) -> r + if (e.id in bodyMatches) 4 else 0 }
            .map { it.first }
    }

    PulseScaffold(
        title = selected?.title ?: "Knowledge Base",
        // Context-aware: inside a guide the corner closes the READER; on the list it leaves the
        // screen — and the system back gesture now agrees (see the BackHandler below).
        onBack = { if (selected != null) vm.closeReader() else onBack?.invoke() },
    ) { innerPadding ->
        val sel = selected
        if (sel == null) {
            Column(Modifier.padding(innerPadding).fillMaxWidth()) {
                // Search — instant over the index fields; section bodies stream in per shard behind it.
                LcarsField(
                    value = query,
                    onValueChange = { query = it; vm.search(it) },
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
                    placeholder = "▸ SEARCH EVERYTHING — chemistry, first aid, math, wiring…",
                )
                // Supergroup rail — the top level of the taxonomy.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LcarsChip("ALL", selected = supergroup == null, onClick = { supergroup = null; category = null })
                    SUPERGROUPS.forEach { sg ->
                        LcarsChip(sg, selected = supergroup == sg, onClick = {
                            supergroup = if (supergroup == sg) null else sg
                            category = null
                        })
                    }
                }
                // Category rail — the second level, scoped to the selected supergroup.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LcarsChip("ALL", selected = category == null, onClick = { category = null },
                        accent = c.violet)
                    categoriesInScope.forEach { cat ->
                        LcarsChip(cat, selected = category == cat, onClick = { category = if (category == cat) null else cat },
                            accent = c.violet)
                    }
                }
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp, top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            "${entries.size} guides · fully offline · search any fact" +
                                (if (q.isNotBlank()) "  ·  ${visible.size} match" else ""),
                            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                    }
                    if (visible.isEmpty()) {
                        item {
                            Text("No matches for \"$q\".", fontFamily = JetBrainsMono, fontSize = 11.sp,
                                color = c.muted, modifier = Modifier.padding(8.dp))
                        }
                    }
                    itemsIndexed(visible, key = { _, g -> g.id }) { _, g ->
                        LcarsFrame(Modifier.fillMaxWidth().clickable { vm.open(g.id) }) {
                            Column {
                                Text(g.category.uppercase(), fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.accent)
                                Text(g.title, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                    color = c.ink, modifier = Modifier.padding(top = 2.dp))
                                Text(g.summary, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                                    maxLines = 3, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                }
            }
        } else {
            GuideReader(
                sel,
                mastery = mastery,
                taught = taught,
                onTeach = { vm.teach() },
                onOpenStudy = onOpenStudy,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

/** The single-guide reader: a collapsible table of contents (jump-to-section), a slim read-progress bar,
 *  the safety note (if any), then every section in order. */
@Composable
private fun GuideReader(
    sel: Guide,
    mastery: StudyProgress.Mastery?,
    taught: Int?,
    onTeach: () -> Unit,
    onOpenStudy: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = Pulse.colors
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var tocExpanded by remember(sel.id) { mutableStateOf(false) }

    // Item order inside the LazyColumn below: [title/summary, ToC, safety?, sections…, footer].
    val leadingCount = 2 + (if (sel.safetyNote != null) 1 else 0)

    val progress by remember(sel.id) {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total <= 1) 0f else (listState.firstVisibleItemIndex.toFloat() / (total - 1)).coerceIn(0f, 1f)
        }
    }

    Column(modifier.fillMaxWidth()) {
        LcarsFillRow(
            listOf(progress to c.accent, (1f - progress) to c.lineSoft),
            Modifier.fillMaxWidth().height(4.dp),
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column {
                    Text(sel.category.uppercase(), fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.accent)
                    Text(sel.summary, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted,
                        modifier = Modifier.padding(top = 4.dp))
                    // ⚠️ Folded into THIS item rather than added as its own. `leadingCount` above is what
                    // the table of contents scrolls by, so a new item would silently send every
                    // jump-to-section one section short — a defect that renders perfectly and only shows
                    // up as the reader landing in the wrong place.
                    StudyStrip(mastery, taught, onTeach, onOpenStudy)
                }
            }
            item {
                LcarsFrame(Modifier.fillMaxWidth().clickable { tocExpanded = !tocExpanded }) {
                    Column {
                        Text(
                            "TABLE OF CONTENTS · ${sel.sections.size} SECTIONS " + if (tocExpanded) "▾" else "▸",
                            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                            letterSpacing = 1.sp, color = c.ink,
                        )
                        if (tocExpanded) {
                            Column(Modifier.padding(top = 8.dp)) {
                                sel.sections.forEachIndexed { i, section ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                tocExpanded = false
                                                scope.launch { listState.animateScrollToItem(leadingCount + i) }
                                            }
                                            .padding(vertical = 5.dp),
                                    ) {
                                        Text(
                                            "${i + 1}. ", fontFamily = JetBrainsMono, fontSize = 11.sp,
                                            color = c.accent,
                                        )
                                        Text(
                                            section.heading, fontFamily = JetBrainsMono, fontSize = 11.sp,
                                            color = c.ink2,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            sel.safetyNote?.let { note ->
                item {
                    LcarsFrame(Modifier.fillMaxWidth(), accent = c.amber) {
                        Column {
                            Text("⚠ SAFETY", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                                fontSize = 12.sp, letterSpacing = 1.sp, color = c.amber)
                            Text(note, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
            itemsIndexed(sel.sections, key = { _, s -> s.heading }) { _, section ->
                Column {
                    LcarsHeaderBar(section.heading)
                    LcarsFrame(Modifier.fillMaxWidth()) {
                        Column {
                            if (section.body.isNotBlank()) {
                                Text(section.body, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink2)
                            }
                            section.ingredients?.let { list ->
                                Column(Modifier.padding(top = if (section.body.isNotBlank()) 8.dp else 0.dp)) {
                                    list.forEach { line ->
                                        Row {
                                            Text("▸ ", fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.accent)
                                            Text(line, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink2)
                                        }
                                    }
                                }
                            }
                            section.steps?.let { list ->
                                Column(Modifier.padding(top = 8.dp)) {
                                    list.forEachIndexed { i, step ->
                                        Row {
                                            Text("${i + 1}. ", fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp, color = c.accent)
                                            Text(step, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink2)
                                        }
                                    }
                                }
                            }
                            section.image?.let { SurvivalDiagram(it) }
                        }
                    }
                }
            }
            item {
                Text("Bundled reference — always offline. Not a substitute for professional advice.",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Renders a bundled offline survival diagram (from `assets/survival/images/`). */
/**
 * Reading and being taught, joined.
 *
 * These were entirely disconnected surfaces on Android — you could read the whole bundled library and
 * the study deck would never hear about it, while the desktop reader has had a STUDY THIS button since
 * it was built. This is the phone catching up.
 *
 * ⚠️ [mastery] is null both when there is no record and when the guide is [StudyProgress.Level.UNSEEN],
 * and then no standing line is drawn at all. A reader that announced "Not started" on each of 581
 * guides would be saying nothing, loudly.
 */
@Composable
private fun StudyStrip(
    mastery: StudyProgress.Mastery?,
    taught: Int?,
    onTeach: () -> Unit,
    onOpenStudy: (() -> Unit)?,
) {
    val c = Pulse.colors
    Column(Modifier.padding(top = 10.dp)) {
        mastery?.let {
            Text(
                it.describe(),
                fontFamily = JetBrainsMono, fontSize = 10.sp,
                color = if (it.level == StudyProgress.Level.SHAKY) c.negative else c.accent,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        when {
            // A one-shot confirmation that says what happened AND where it went — "ready" with no route
            // is a dead end, since the questions live on a screen two taps away in another section.
            taught != null && taught > 0 -> {
                Text(
                    "$taught question${if (taught == 1) "" else "s"} ready.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.positive,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                if (onOpenStudy != null) LcarsButton("ANSWER THEM", onClick = onOpenStudy)
            }
            // Nothing could be extracted — said plainly rather than left looking like a broken button.
            taught != null -> Text(
                "Nothing in this guide could be turned into a question.",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
            )
            else -> LcarsButton("TEACH ME THIS", onClick = onTeach)
        }
    }
}

@Composable
private fun SurvivalDiagram(image: String) {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = "file:///android_asset/survival/images/$image",
            contentDescription = "Diagram",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .heightIn(max = 260.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFF3EFE2))
                .padding(8.dp),
        )
    }
}
