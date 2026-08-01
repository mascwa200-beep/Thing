package dev.mascwa.pulse.feature.breaking

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.breaking.BreakingCoverage
import dev.mascwa.pulse.data.breaking.BreakingCoverageRepository
import dev.mascwa.pulse.data.news.Article
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RED = Color(0xFFD11414)
private val BG = Color(0xFF0A0405)
private val PANEL = Color(0xFF150A0B)
private val INK = Color(0xFFF4ECEC)
private val INK2 = Color(0xFFB9A9AA)
private val MUTED = Color(0xFF7C6E6F)

private enum class BTab(val label: String) { COVERAGE("TOP COVERAGE"), LATEST("LATEST"), SOURCES("SOURCES") }

/**
 * The cinematic BREAKING NEWS page — the way TV/film portray it: a red BREAKING banner with a pulsing LIVE,
 * a live clock, the headline, and a tab system over aggregated, ad-free coverage from trusted free sources.
 * Self-contained (hardcodes its dark takeover theme), so it renders identically from the lock screen.
 */
@Composable
fun BreakingNewsScreen(
    headline: String,
    coverage: suspend (Boolean) -> BreakingCoverage,
    onOpenUrl: (String) -> Unit,
    onClose: () -> Unit,
) {
    var data by remember { mutableStateOf<BreakingCoverage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(BTab.COVERAGE) }

    LaunchedEffect(Unit) {
        loading = true
        data = runCatching { coverage(false) }.getOrNull()
        loading = false
    }

    // Pulsing LIVE dot + a ticking clock for the "instant/live" feel.
    val inf = rememberInfiniteTransition(label = "live")
    val pulse by inf.animateFloat(
        0.25f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse",
    )
    var nowMs by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val clock = remember(nowMs) {
        if (nowMs == 0L) "" else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nowMs))
    }

    Column(
        Modifier.fillMaxSize().background(BG).windowInsetsPadding(WindowInsets.systemBars),
    ) {
        // --- BREAKING banner ---
        Row(
            Modifier.fillMaxWidth().background(RED).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(Color.White.copy(alpha = pulse)))
            Spacer(Modifier.width(8.dp))
            Text(
                "BREAKING NEWS",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Black, fontSize = 18.sp,
                letterSpacing = 2.sp, color = Color.White,
            )
            Spacer(Modifier.weight(1f))
            Text("● LIVE", fontFamily = JetBrainsMono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = pulse))
            Spacer(Modifier.width(10.dp))
            Text(
                "✕",
                fontFamily = JetBrainsMono, fontSize = 16.sp, color = Color.White,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        // A thin red under-rule + clock strip.
        Row(
            Modifier.fillMaxWidth().background(PANEL).padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("THIS JUST IN", fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = RED)
            Spacer(Modifier.weight(1f))
            if (clock.isNotBlank()) Text(clock, fontFamily = JetBrainsMono, fontSize = 10.sp, color = INK2)
        }

        // --- Headline ---
        Text(
            headline,
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = INK,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
        )
        data?.let { d ->
            val n = d.sources.size
            if (n > 0) {
                Text(
                    "Covered by $n source${if (n == 1) "" else "s"} · updated ${Formatters.relativeTime(d.fetchedAtMs)}",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = MUTED,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                )
            }
        }

        // --- Tabs ---
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BTab.entries.forEach { t -> TabChip(t.label, selected = t == tab) { tab = t } }
        }

        // --- Content ---
        Box(Modifier.fillMaxSize()) {
            val d = data
            when {
                loading && d == null -> Center { LoadingBlock() }
                d == null || d.isEmpty -> Center {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Gathering coverage…", fontFamily = JetBrainsMono, fontSize = 12.sp, color = INK2)
                        Text("(offline? the latest cached coverage shows here)", fontFamily = JetBrainsMono,
                            fontSize = 9.sp, color = MUTED, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                tab == BTab.SOURCES -> SourcesList(d, onOpenUrl)
                else -> {
                    val list = if (tab == BTab.LATEST) d.articles.sortedByDescending { it.publishedEpochMs } else d.articles
                    LazyColumn(
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 28.dp, top = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(list.distinctBy { it.url }, key = { it.url }) { a ->
                            CoverageRow(a, trusted = isTrusted(a.source), onOpenUrl)
                        }
                    }
                }
            }
        }
    }
}

private fun isTrusted(source: String): Boolean =
    BreakingCoverageRepository.TRUSTED.any { source.lowercase().contains(it) }

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontFamily = JetBrainsMono, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
        color = if (selected) Color.White else INK2,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) RED else PANEL)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun CoverageRow(a: Article, trusted: Boolean, onOpenUrl: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(PANEL)
            .clickable { if (a.url.isNotBlank()) onOpenUrl(a.url) }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (trusted) {
                Text("✔ TRUSTED", fontFamily = JetBrainsMono, fontSize = 7.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFF35C46A),
                    modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Color(0xFF35C46A).copy(alpha = 0.14f))
                        .padding(horizontal = 4.dp, vertical = 1.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                (a.source.ifBlank { "Source" }) + (if (a.publishedEpochMs > 0) " · ${Formatters.relativeTime(a.publishedEpochMs)}" else ""),
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = RED,
            )
        }
        Text(
            a.title,
            fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = INK,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp),
        )
        if (a.summary.isNotBlank()) {
            Text(
                a.summary,
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = INK2,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SourcesList(d: BreakingCoverage, onOpenUrl: (String) -> Unit) {
    // The outlets covering this, trusted first; tap a source to jump to its lead article.
    val leadBySource = remember(d) { d.articles.associateBy({ it.source }, { it }) }
    val ordered = remember(d) {
        d.sources.sortedByDescending { isTrusted(it) }
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 28.dp, top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(ordered, key = { it }) { src ->
            val lead = leadBySource[src]
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(PANEL)
                    .clickable { lead?.url?.takeIf { it.isNotBlank() }?.let(onOpenUrl) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape)
                    .background(if (isTrusted(src)) Color(0xFF35C46A) else MUTED))
                Spacer(Modifier.width(10.dp))
                Text(src, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = INK,
                    modifier = Modifier.weight(1f))
                if (isTrusted(src)) Text("TRUSTED", fontFamily = JetBrainsMono, fontSize = 8.sp, color = Color(0xFF35C46A))
            }
        }
    }
}

@Composable
private fun LoadingBlock() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = RED, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
        Text("PULLING THE WIRE…", fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp,
            color = INK2, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
