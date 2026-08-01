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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import coil.compose.AsyncImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.data.survival.Guide
import dev.mascwa.pulse.feature.common.PipChip
import dev.mascwa.pulse.feature.common.PipFrame
import dev.mascwa.pulse.feature.common.PipHeader
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

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
    var category by remember { mutableStateOf<String?>(null) } // null = ALL

    var openedInitial by remember { mutableStateOf(false) }
    LaunchedEffect(guides, initialGuideId) {
        if (!openedInitial && !initialGuideId.isNullOrBlank() && guides.isNotEmpty()) {
            guides.firstOrNull { it.id == initialGuideId }?.let { selected = it }
            openedInitial = true
        }
    }
    val c = Pulse.colors

    // Build the full-text index + the category rail once per catalog change.
    val index = remember(guides) {
        guides.map { g ->
            val blob = buildString {
                append(g.title); append(' '); append(g.category); append(' '); append(g.summary); append(' ')
                g.sections.forEach { append(it.heading); append(' '); append(it.body); append(' ') }
            }.lowercase()
            GuideIndex(g, blob)
        }
    }
    val categories = remember(guides) { guides.map { it.category }.distinct().sorted() }

    val q = query.trim()
    val visible = remember(index, q, category) {
        val byCat = if (category == null) index else index.filter { it.guide.category == category }
        if (q.isBlank()) byCat.map { it.guide }
        else byCat.filter { it.blob.contains(q.lowercase()) }
            .sortedByDescending { it.rank(q) }
            .map { it.guide }
    }

    PulseScaffold(
        title = selected?.title ?: "Knowledge Base",
        navigationIcon = {
            IconButton(onClick = { if (selected != null) selected = null else onBack?.invoke() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
    ) { innerPadding ->
        val sel = selected
        if (sel == null) {
            Column(Modifier.padding(innerPadding).fillMaxWidth()) {
                // Search — full-text across every guide + section body.
                PipFrame(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
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
                // Category rail.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PipChip("ALL", selected = category == null, onClick = { category = null })
                    categories.forEach { cat ->
                        PipChip(cat, selected = category == cat, onClick = { category = if (category == cat) null else cat })
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
                    items(visible, key = { it.id }) { g ->
                        PipFrame(Modifier.fillMaxWidth().clickable { selected = g }) {
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
            LazyColumn(
                modifier = Modifier.padding(innerPadding),
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
                items(sel.sections, key = { it.heading }) { section ->
                    Column {
                        PipHeader(section.heading)
                        PipFrame(Modifier.fillMaxWidth()) {
                            Column {
                                Text(section.body, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink2)
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
