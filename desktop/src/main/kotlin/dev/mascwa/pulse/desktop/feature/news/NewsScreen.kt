package dev.mascwa.pulse.desktop.feature.news

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.desktop.news.Article
import dev.mascwa.pulse.desktop.news.NewsCategory
import dev.mascwa.pulse.desktop.telemetry.Freshness
import dev.mascwa.pulse.desktop.telemetry.MediaBias
import dev.mascwa.pulse.desktop.telemetry.NewsInsights
import dev.mascwa.pulse.desktop.telemetry.NewsMarketLink
import dev.mascwa.pulse.desktop.telemetry.Tone
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsBusyBar
import dev.mascwa.pulse.desktop.theme.LcarsChip
import dev.mascwa.pulse.desktop.theme.LcarsFillRow
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.Pulse

/**
 * The News feed. Renders through the insight cores that were ported earlier but never wired to anything.
 *
 * Follows the design the Android app converged on rather than its older stacked-strips layout: one
 * plain-English takeaway line per article, always visible, with the segmented MOOD read rendered as
 * discrete blocks (a real dim remainder block, so magnitude stays visible and it never auto-normalises to
 * full width) instead of a smooth bar.
 */
@Composable
fun NewsScreen(vm: NewsViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    val c = Pulse.colors

    // The feed keeps itself current only while it is the screen being shown. Off it, there is no timer
    // running at all — see NewsViewModel.setOnScreen.
    DisposableEffect(Unit) {
        vm.setOnScreen(true)
        onDispose { vm.setOnScreen(false) }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        LcarsHeaderBar(
            "News",
            // Said rather than left to be discovered: a page that silently rewrites itself every five
            // minutes is unsettling; one that says it is live is a feature.
            trailing = if (state.articles.isEmpty()) null else "LIVE · ${state.articles.size} STORIES",
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NewsCategory.entries.forEach { cat ->
                LcarsChip(cat.title, selected = cat == state.category, onClick = { vm.select(cat) })
            }
        }

        LcarsBusyBar(active = state.loading, modifier = Modifier.fillMaxWidth())

        state.error?.let { err ->
            LcarsFrame(Modifier.fillMaxWidth().padding(top = 10.dp), accent = c.negative) {
                Text(err, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink)
            }
        }

        // How old what is on screen actually is. The repository serves the last stored headlines when a
        // request fails, which used to be indistinguishable from a live fetch.
        val freshness = Freshness.assess(
            lastUpdatedMs = state.lastUpdatedEpochMs,
            nowMs = System.currentTimeMillis(),
            online = true, // A desktop has no connectivity signal to consult; the failure flag carries it.
            servingStored = state.servingStored,
            refreshFailed = state.refreshFailed,
        )
        if (freshness.worthShowing) {
            Text(
                freshness.label,
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (state.articles.isEmpty() && !state.loading && state.error == null) {
            LcarsFrame(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text("No stories yet.", fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted)
            }
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.articles, key = { it.url }) { article ->
                ArticleCard(article, state.articles)
            }
        }
    }
}

@Composable
private fun ArticleCard(article: Article, all: List<Article>) {
    val c = Pulse.colors
    val mood = NewsInsights.toneBreakdown(article.title, article.summary)
    val topics = NewsInsights.topics(article.title, article.summary)
    val links = NewsMarketLink.linksFor(article.title, article.summary, article.category)
    val impact = NewsInsights.marketImpact(links)

    LcarsFrame(Modifier.fillMaxWidth()) {
        Column {
            Text(
                article.source.uppercase(),
                fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.8.sp, color = c.accent,
            )
            Text(
                article.title,
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.ink,
                modifier = Modifier.padding(top = 3.dp),
            )
            if (article.summary.isNotBlank()) {
                Text(
                    article.summary,
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // The always-visible one-liner: what the coverage feels like and whether markets care.
            Text(
                takeaway(mood.tone, impact.label, links.size),
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
                modifier = Modifier.padding(top = 8.dp),
            )

            // MOOD as discrete blocks. The dim remainder is a real segment, so a story with one charged
            // word reads visibly differently from one with six — a proportional bar would hide that.
            MoodBlocks(mood.positive, mood.tense, mood.negative)

            if (topics.isNotEmpty()) {
                Text(
                    topics.joinToString("  ") { "#$it" },
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (links.isNotEmpty()) {
                Text(
                    NewsMarketLink.summarize(links),
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            MediaBias.leanOf(article.source)?.let { lean ->
                Text(
                    "Outlet lean: ${lean.label}",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.faint,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Segmented mood read: filled blocks for charged words, a dim block for the unspent remainder. */
@Composable
private fun MoodBlocks(positive: Int, tense: Int, negative: Int) {
    val c = Pulse.colors
    val filled = positive + tense + negative
    val remainder = (MOOD_SCALE - filled).coerceAtLeast(0)
    val segments = buildList<Pair<Float, Color>> {
        if (positive > 0) add(positive.toFloat() to c.positive)
        if (tense > 0) add(tense.toFloat() to c.amber)
        if (negative > 0) add(negative.toFloat() to c.negative)
        if (remainder > 0) add(remainder.toFloat() to c.raise)
    }
    if (segments.isEmpty()) return
    LcarsFillRow(segments, Modifier.fillMaxWidth().height(6.dp).padding(top = 8.dp), gap = 1.5.dp)
}

/** One plain sentence combining tone and market relevance — no jargon, no glyph legend to decode. */
private fun takeaway(tone: Tone, impactLabel: String, linkCount: Int): String {
    val mood = when (tone) {
        Tone.UPBEAT -> "Coverage reads positive"
        Tone.MIXED -> "Coverage reads mixed"
        Tone.GRIM -> "Coverage reads grim"
        Tone.TENSE -> "Coverage reads tense"
    }
    val market = when {
        linkCount == 0 -> "no obvious market angle"
        impactLabel == "High" -> "a strong market angle"
        impactLabel == "Medium" -> "some market relevance"
        else -> "a slight market angle"
    }
    return "$mood · $market."
}

/** Matches the Android app's scale so a story looks the same on both. */
private const val MOOD_SCALE = 6
