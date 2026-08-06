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
import androidx.compose.material.icons.Icons
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
import dev.mascwa.pulse.data.survival.Guide
import dev.mascwa.pulse.data.survival.SUPERGROUPS
import dev.mascwa.pulse.data.survival.supergroup
import dev.mascwa.pulse.data.survival.supergroupOf
import dev.mascwa.pulse.feature.common.LcarsChip
import dev.mascwa.pulse.feature.common.LcarsFillRow
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import kotlinx.coroutines.launch

/** A searchable, category-filtered index entry over the whole guide (title + category + summary + every
 *  section heading AND body) so any fact in the knowledge base is findable by full-text search. */
private data class GuideIndex(val guide: Guide, val blob: String) {
    /** A crude relevance rank for a query: title/category hits weigh most, headings next, body least. */
    fun rank(q: String): Int {
        val g = guide
        var r = 0
        if (g.title.contains(q, true)) r += 100
        if (g.category.contains(q, true)) r += 60
        if (g.summary.contains(q, true)) r += 40
        r += g.sections.count { it.heading.contains(q, true) } * 20
        r += g.sections.count { it.body.contains(q, true) } * 4
        return r
    }
}

@Composable
fun GuidesScreen(vm: GuidesViewModel, onBack: (() -> Unit)? = null, initialGuideId: String? = null) {
    val guides by vm.guides.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Guide?>(null) }
    var query by remember { mutableStateOf("") }
    var supergroup by remember { mutableStateOf<String?>(null) } // null = every supergroup
    var category by remember { mutableStateOf<String?>(null) } // null = every category within scope

    var openedInitial by remember { mutableStateOf(false) }
    LaunchedEffect(guides, initialGuideId) {
        if (!openedInitial && !initialGuideId.isNullOrBlank() && guides.isNotEmpty()) {
            guides.firstOrNull { it.id == initialGuideId }?.let { selected = it }
            openedInitial = true
        }
    }
    val c = Pulse.colors

    // Build the full-text index + the two-level taxonomy rail once per catalog change.
    val index = remember(guides) {
        guides.map { g ->
            val blob = buildString {
                append(g.title); append(' '); append(g.category); append(' '); append(g.summary); append(' ')
                g.safetyNote?.let { append(it); append(' ') }
                g.sections.forEach { s ->
                    append(s.heading); append(' '); append(s.body); append(' ')
                    s.ingredients?.forEach { append(it); append(' ') }
                    s.steps?.forEach { append(it); append(' ') }
                }
            }.lowercase()
            GuideIndex(g, blob)
        }
    }
    val categories = remember(guides) { guides.map { it.category }.distinct().sorted() }
    val categoriesInScope = remember(categories, supergroup) {
        if (supergroup == null) categories else categories.filter { supergroupOf(it) == supergroup }
    }

    val q = query.trim()
    val visible = remember(index, q, supergroup, category) {
        val scoped = index.filter {
            (supergroup == null || it.guide.supergroup() == supergroup) &&
                (category == null || it.guide.category == category)
        }
        if (q.isBlank()) scoped.map { it.guide }
        else scoped.filter { it.blob.contains(q.lowercase()) }
            .sortedByDescending { it.rank(q) }
            .map { it.guide }
    }

    PulseScaffold(
        title = selected?.title ?: "Knowledge Base",
        navigationIcon = {
            IconButton(onClick = { if (selected != null) selected = null else onBack?.invoke() }) {
                Icon(LcarsIcons.ArrowBack, "Back")
            }
        },
    ) { innerPadding ->
        val sel = selected
        if (sel == null) {
            Column(Modifier.padding(innerPadding).fillMaxWidth()) {
                // Search — full-text across every guide + section body.
                LcarsFrame(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = TextStyle(color = c.ink, fontFamily = JetBrainsMono, fontSize = 13.sp),
                        cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text("▸ SEARCH EVERYTHING — chemistry, first aid, math, wiring…",
                                    fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted)
                            }
                            inner()
                        },
                    )
                }
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
                            "${guides.size} guides · fully offline · search any fact" +
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
                        LcarsFrame(Modifier.fillMaxWidth().clickable { selected = g }) {
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
            GuideReader(sel, Modifier.padding(innerPadding))
        }
    }
}

/** The single-guide reader: a collapsible table of contents (jump-to-section), a slim read-progress bar,
 *  the safety note (if any), then every section in order. */
@Composable
private fun GuideReader(sel: Guide, modifier: Modifier = Modifier) {
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
