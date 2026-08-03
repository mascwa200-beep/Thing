package dev.mascwa.pulse.feature.news

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.mascwa.pulse.core.telemetry.BiasBreakdown
import dev.mascwa.pulse.core.telemetry.ImpactLevel
import dev.mascwa.pulse.core.telemetry.Lean
import dev.mascwa.pulse.core.telemetry.MarketImpact
import dev.mascwa.pulse.core.telemetry.MarketLink
import dev.mascwa.pulse.core.telemetry.MediaBias
import dev.mascwa.pulse.core.telemetry.NewsInsights
import dev.mascwa.pulse.core.telemetry.NewsMarketLink
import dev.mascwa.pulse.core.telemetry.Tone
import dev.mascwa.pulse.core.telemetry.ToneBreakdown
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.breaking.BreakingCoverage
import dev.mascwa.pulse.data.news.Article
import dev.mascwa.pulse.data.news.NewsAnalysis
import dev.mascwa.pulse.feature.common.CyberChipCut
import dev.mascwa.pulse.feature.common.CyberRowFrame
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/** A news article as a chamfered CP2077 HUD panel — crimson meta stamp, mono technical metadata. */
@Composable
fun ArticleCard(
    article: Article,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pulse: Map<String, Double> = emptyMap(),
    /** Every article currently loaded in this feed (including [article] itself) — used only to compute the
     *  "how many other stories are on this right now" crowd signal in [GlanceStrip]. Empty = no crowd read. */
    allArticles: List<Article> = emptyList(),
    /** The cloud "what's really going on" synthesis for this article, if one's cached — upgrades the mood/
     *  market copy from the heuristic read and adds the WIDER PICTURE block. Null = heuristic-only. */
    analysis: NewsAnalysis? = null,
    /** Called once as this card first composes, so its analysis can be requested lazily (only for cards
     *  that actually become visible in the LazyColumn). Null = don't request one (e.g. a compact preview). */
    onNeedsAnalysis: ((Article) -> Unit)? = null,
    /** Multi-outlet coverage of this story, if cached — drives the COVERAGE bias-distribution strip. Null =
     *  not fetched yet / disabled / genuinely no cross-outlet matches found. */
    coverage: BreakingCoverage? = null,
    /** Called once as this card first composes, so coverage can be requested lazily — mirrors
     *  [onNeedsAnalysis]. Null = don't request one. */
    onNeedsCoverage: ((Article) -> Unit)? = null,
) {
    val c = Pulse.colors
    if (onNeedsAnalysis != null) {
        LaunchedEffect(article.url) { onNeedsAnalysis(article) }
    }
    if (onNeedsCoverage != null) {
        LaunchedEffect(article.url) { onNeedsCoverage(article) }
    }
    NeonPanel(
        modifier.fillMaxWidth().clickable(onClick = onClick),
        corners = true,
        padding = PaddingValues(0.dp),
    ) {
        Column {
            ArticleImage(article, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Column(Modifier.padding(14.dp)) {
                Text(
                    article.title,
                    fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                if (article.summary.isNotBlank()) {
                    Text(
                        article.summary,
                        fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink2,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
                // At-a-glance infographics: the story's mood + auto topic tags + the live crowd signal.
                GlanceStrip(article, allArticles, analysis)
                // Who's covering this story and how their politics spread — the Ground-News-style read.
                coverage?.let { cov ->
                    val breakdown = remember(cov.sources) { MediaBias.breakdown(cov.sources) }
                    if (breakdown.total > 0) CoverageStrip(breakdown)
                }
                // Which markets this story touches / would move, and which way (+ a short why).
                val links = remember(article.url) {
                    NewsMarketLink.linksFor(article.title, article.summary, article.category)
                }
                if (links.isNotEmpty() || analysis != null) MarketStrip(links, pulse, analysis)
                // The deeper cross-domain read — LLM-only, never fabricated from heuristics.
                analysis?.let { WiderPictureStrip(it) }
                Text(
                    meta(article).uppercase(),
                    fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 0.8.sp, color = c.accent,
                    modifier = Modifier.padding(top = 9.dp),
                )
            }
        }
    }
}

/** The article's lead image, ALWAYS a full-width slot — every tab, every story, a real photo where the feed
 *  actually has one, a themed procedural placeholder otherwise (a category-tinted gradient + glyph, no
 *  network, no bundled asset). Wire/social sources without a thumbnail no longer read as text-only rows. */
@Composable
private fun ArticleImage(article: Article, modifier: Modifier) {
    val c = Pulse.colors
    if (!article.imageUrl.isNullOrBlank()) {
        AsyncImage(
            model = article.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(c.raise),
        )
    } else {
        ArticlePlaceholder(article, modifier)
    }
}

/** A deterministic gradient + initial, tinted by the story's category (or source, if categoryless) so a
 *  browse session reads as visually consistent per topic rather than a random colour every time. */
@Composable
private fun ArticlePlaceholder(article: Article, modifier: Modifier) {
    val key = article.category.ifBlank { article.source }.ifBlank { article.title }
    val hue = (key.hashCode().mod(360)).toFloat()
    val base = Color.hsv(hue, 0.5f, 0.30f)
    val edge = Color.hsv((hue + 26f).mod(360f), 0.55f, 0.16f)
    val glyph = key.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "◉"
    Box(
        modifier.background(Brush.linearGradient(listOf(base, edge))),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 44.sp, color = Color.White.copy(alpha = 0.22f))
    }
}

/** An at-a-glance infographic band: the story's MOOD as a SEGMENTED bar that shows its own work (real
 *  positive/negative/tense keyword counts, not an opaque single fill), a live "how many other stories are
 *  on this right now" crowd signal, and auto-extracted topic/region tags. The insider-knowledge read: not
 *  just what the mood is, but why, and how much of the feed is already talking about it. [allArticles] only
 *  feeds the crowd count, never leaves the device. The caption prefers the cloud [analysis]'s plain-English
 *  mood line when cached, falling back to the heuristic keyword-count caption otherwise; a small permanent
 *  legend under the bar keeps it readable either way. */
@Composable
private fun GlanceStrip(article: Article, allArticles: List<Article>, analysis: NewsAnalysis?) {
    val c = Pulse.colors
    val breakdown = remember(article.url) { NewsInsights.toneBreakdown(article.title, article.summary) }
    val tags = remember(article.url) { NewsInsights.topics(article.title, article.summary) }
    val cluster = remember(article.url, allArticles) {
        if (tags.isEmpty()) {
            0
        } else {
            val othersTags = allArticles.asSequence()
                .filter { it.url != article.url }
                .map { NewsInsights.topics(it.title, it.summary) }
                .toList()
            NewsInsights.clusterSize(tags, othersTags)
        }
    }
    val toneCol = toneColor(breakdown.tone)
    Column(Modifier.padding(top = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MOOD", fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 1.sp, color = c.muted)
            Spacer(Modifier.width(6.dp))
            SegmentedMoodBar(breakdown, Modifier.height(6.dp).weight(1f))
            Spacer(Modifier.width(6.dp))
            Text(breakdown.tone.label, fontFamily = JetBrainsMono, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = toneCol)
        }
        Text(
            "green = upbeat · red = tense · from the coverage's own words",
            fontFamily = JetBrainsMono, fontSize = 7.sp, color = c.faint,
            modifier = Modifier.padding(top = 2.dp),
        )
        val caption = analysis?.moodLine ?: moodCaption(breakdown)
        if (caption.isNotBlank() || cluster > 0) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (caption.isNotBlank()) {
                    Text(caption, fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted)
                }
                if (cluster > 0) {
                    Text(
                        "🔥 $cluster other ${if (cluster == 1) "story" else "stories"} on this",
                        fontFamily = JetBrainsMono, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = c.accent,
                    )
                }
            }
        }
        if (tags.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tags.forEach { tag ->
                    Text(
                        "#$tag",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.ink2,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(c.accent.copy(alpha = 0.10f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** How many keyword hits fill the whole bar — a story with this many charged terms (or more) reads as
 *  maximally intense; fewer hits leave visible neutral track, preserving a sense of magnitude rather than
 *  just direction (1 negative word looks very different from 5). */
private const val MOOD_BAR_SCALE = 6

/** A proportional multi-colour bar — upbeat/tense/negative segments sized by their real hit counts, plus a
 *  neutral remainder — so the bar visually explains itself instead of being an abstract single fill. */
@Composable
private fun SegmentedMoodBar(b: ToneBreakdown, modifier: Modifier) {
    val c = Pulse.colors
    val segments = buildList {
        if (b.positive > 0) add(b.positive.toFloat() to Color(0xFF35C46A))
        if (b.tense > 0) add(b.tense.toFloat() to Color(0xFFE0331A))
        if (b.negative > 0) add(b.negative.toFloat() to Color(0xFFE0721A))
    }
    val filled = segments.sumOf { it.first.toDouble() }.toFloat()
    val neutralWeight = (MOOD_BAR_SCALE - filled).coerceAtLeast(if (segments.isEmpty()) 1f else 0f)
    Row(modifier.clip(RoundedCornerShape(3.dp)).background(c.raise)) {
        segments.forEach { (weight, color) ->
            Box(Modifier.weight(weight).fillMaxHeight().background(color))
        }
        if (neutralWeight > 0f) {
            Box(Modifier.weight(neutralWeight).fillMaxHeight())
        }
    }
}

/** A short, confiding "here's what's actually driving the mood" line — e.g. "4 lines running upbeat, 1
 *  genuinely tense" — blank when there's nothing to show. Shows its work instead of a bare score. */
private fun moodCaption(b: ToneBreakdown): String {
    val bits = buildList {
        if (b.positive > 0) add("${b.positive} running upbeat")
        if (b.negative > 0) add("${b.negative} turning sour")
        if (b.tense > 0) add("${b.tense} genuinely tense")
    }
    return bits.joinToString(", ")
}

private fun toneColor(t: Tone): Color = when (t) {
    Tone.UPBEAT -> Color(0xFF35C46A)
    Tone.MIXED -> Color(0xFFC9B23A)
    Tone.GRIM -> Color(0xFFE0721A)
    Tone.TENSE -> Color(0xFFE0331A)
}

/** The blue-through-red spread Ground News made recognizable — used consistently for the bar segments, the
 *  legend dots, and nowhere else, so "blue/red" reads unambiguously as politics, never confused with the
 *  MOOD bar's unrelated green/red (upbeat/tense) or MARKET REACTION's up/down colours. */
private fun leanColor(l: Lean): Color = when (l) {
    Lean.LEFT -> Color(0xFF3B82F6)
    Lean.LEAN_LEFT -> Color(0xFF93C5FD)
    Lean.CENTER -> Color(0xFFB3A6D9)
    Lean.LEAN_RIGHT -> Color(0xFFF3A0A0)
    Lean.RIGHT -> Color(0xFFDC2626)
}

/** The "◢ COVERAGE" strip — the Ground-News-style read: how many outlets are covering this exact story, and
 *  how their politics spread from left to right. A DIFFERENT bar shape than [SegmentedMoodBar] on purpose:
 *  the mood bar's segments are sized against a fixed intensity ceiling ([MOOD_BAR_SCALE]); this bar's
 *  segments are sized against the REAL outlet count, because a proportion of outlets is exactly what it
 *  claims to show — normalizing it to an arbitrary scale would make the percentages read wrong. */
@Composable
private fun CoverageStrip(breakdown: BiasBreakdown) {
    val c = Pulse.colors
    Column(
        Modifier
            .padding(top = 9.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF3B82F6).copy(alpha = 0.06f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "◢ COVERAGE", fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold, color = Color(0xFF3B82F6),
            )
            val outlets = if (breakdown.total == 1) "outlet" else "outlets"
            Text("· ${breakdown.total} $outlets", fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted)
        }
        BiasBar(breakdown, Modifier.padding(top = 6.dp).height(8.dp).fillMaxWidth())
        val legend = buildList {
            if (breakdown.left > 0) add("Left ${breakdown.left}" to Lean.LEFT)
            if (breakdown.leanLeft > 0) add("Lean Left ${breakdown.leanLeft}" to Lean.LEAN_LEFT)
            if (breakdown.center > 0) add("Center ${breakdown.center}" to Lean.CENTER)
            if (breakdown.leanRight > 0) add("Lean Right ${breakdown.leanRight}" to Lean.LEAN_RIGHT)
            if (breakdown.right > 0) add("Right ${breakdown.right}" to Lean.RIGHT)
        }
        if (legend.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                legend.forEach { (label, lean) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(leanColor(lean)))
                        Spacer(Modifier.width(3.dp))
                        Text(label, fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.ink2)
                    }
                }
            }
        }
        val summary = remember(breakdown) { MediaBias.summarize(breakdown) }
        if (summary.isNotBlank()) {
            Text(
                summary, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

/** A proportional multi-colour bar sized against the REAL rated-outlet count (unlike [SegmentedMoodBar]'s
 *  fixed-scale sizing) — an unrated outlet renders as unfilled track, same convention as the mood bar's
 *  neutral remainder. */
@Composable
private fun BiasBar(b: BiasBreakdown, modifier: Modifier) {
    val c = Pulse.colors
    val segments = buildList {
        if (b.left > 0) add(b.left.toFloat() to leanColor(Lean.LEFT))
        if (b.leanLeft > 0) add(b.leanLeft.toFloat() to leanColor(Lean.LEAN_LEFT))
        if (b.center > 0) add(b.center.toFloat() to leanColor(Lean.CENTER))
        if (b.leanRight > 0) add(b.leanRight.toFloat() to leanColor(Lean.LEAN_RIGHT))
        if (b.right > 0) add(b.right.toFloat() to leanColor(Lean.RIGHT))
    }
    Row(modifier.clip(RoundedCornerShape(3.dp)).background(c.raise)) {
        segments.forEach { (weight, color) ->
            Box(Modifier.weight(weight).fillMaxHeight().background(color))
        }
        if (b.unrated > 0) {
            Box(Modifier.weight(b.unrated.toFloat()).fillMaxHeight())
        }
    }
}

/** The MARKET REACTION strip beneath a story's summary — the (legal) *Trading Places* read: which markets
 *  this news moves, which way, LIVE if we have a quote, and WHY. A framed readout: header · market chips ·
 *  a plain-English causal line — the cloud [analysis]'s MARKET line when cached (it already explains the
 *  "how big a deal" read in plain words, so the old unlabeled strength dots are dropped), the heuristic
 *  prose otherwise. */
@Composable
private fun MarketStrip(links: List<MarketLink>, pulse: Map<String, Double>, analysis: NewsAnalysis?) {
    val c = Pulse.colors
    val hasLive = links.any { pulse[it.market] != null }
    val impact = NewsInsights.marketImpact(links)
    Column(
        Modifier
            .padding(top = 9.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(c.accent.copy(alpha = 0.06f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "◢ MARKET REACTION",
                fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold, color = c.accent,
            )
            if (impact != ImpactLevel.NONE) {
                Text("· ${impact.label.uppercase()} IMPACT", fontFamily = JetBrainsMono, fontSize = 8.sp,
                    letterSpacing = 0.8.sp, color = c.ink2)
                ImpactBar(impact)
            }
            if (hasLive) Text("· LIVE", fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 0.8.sp, color = c.muted)
        }
        if (links.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                links.forEach { link -> MarketChip(link, pulse[link.market]) }
            }
        }
        // The causal read — the cloud synthesis in plain words when cached, "what reality is doing to this
        // market" (Trading Places framing) heuristic prose otherwise.
        val head = analysis?.marketLine ?: NewsMarketLink.headline(links)
        if (head.isNotBlank()) {
            Text(
                head,
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink2,
                modifier = Modifier.padding(top = 7.dp),
            )
        }
        // Winners/losers one-liner — heuristic only (the cloud MARKET line above already covers this read).
        if (analysis == null) {
            val why = NewsMarketLink.summarize(links)
            if (why.isNotBlank()) {
                Text(
                    why,
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/** One market chip: prefers the market's LIVE % move today (coloured by its actual sign); otherwise the
 *  story's heuristic lean (▲ up / ▼ down / • unclear). */
@Composable
private fun MarketChip(link: MarketLink, live: Double?) {
    val c = Pulse.colors
    val col = when {
        live != null && live > 0.0 -> c.positive
        live != null && live < 0.0 -> c.negative
        live != null -> c.muted
        link.impact == MarketImpact.UP -> c.positive
        link.impact == MarketImpact.DOWN -> c.negative
        else -> c.muted
    }
    val text = if (live != null) {
        val sign = if (live >= 0.0) "+" else ""
        "${if (live >= 0.0) "▲" else "▼"} ${link.market} $sign${"%.1f".format(live)}%"
    } else {
        val arrow = when (link.impact) {
            MarketImpact.UP -> "▲"
            MarketImpact.DOWN -> "▼"
            MarketImpact.MIXED -> "•"
        }
        "$arrow ${link.market}"
    }
    Text(
        text, fontFamily = JetBrainsMono, fontSize = 10.sp, color = col,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(col.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** The deeper cross-domain read — LLM-only (never fabricated from heuristics): how different audiences are
 *  likely reacting (everyday readers, social media, political leanings, the international angle, any real
 *  economic/conflict backdrop), plus a forward-looking "where this likely heads" line. A distinct accent
 *  (violet) separates it visually from the MARKET REACTION panel above. */
@Composable
private fun WiderPictureStrip(analysis: NewsAnalysis) {
    val c = Pulse.colors
    Column(
        Modifier
            .padding(top = 9.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(c.violet.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            "◈ WIDER PICTURE",
            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
            fontWeight = FontWeight.Bold, color = c.violet,
        )
        Text(
            analysis.widerLine,
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink2,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "▸ ${analysis.nextLine}",
            fontFamily = JetBrainsMono, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.violet,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/** A 3-segment fill bar for the IMPACT label — visual weight instead of just a text word. */
@Composable
private fun ImpactBar(impact: ImpactLevel) {
    val c = Pulse.colors
    val filled = when (impact) {
        ImpactLevel.NONE -> 0
        ImpactLevel.LOW -> 1
        ImpactLevel.MEDIUM -> 2
        ImpactLevel.HIGH -> 3
    }
    Row(horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .width(6.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i < filled) c.accent else c.raise),
            )
        }
    }
}

/** Compact list variant — the CP2077 inventory row (accent blade + hairline) with a chamfered thumb. */
@Composable
fun ArticleRowCompact(
    article: Article,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Pulse.colors
    CyberRowFrame(modifier, onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    article.title,
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                    color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                // A light market tag on the dense home row: up to 2 chips, arrow-coloured (no why caption).
                val links = remember(article.url) {
                    NewsMarketLink.linksFor(article.title, article.summary, article.category)
                }
                if (links.isNotEmpty()) {
                    FlowRow(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        links.take(2).forEach { link ->
                            val col = when (link.impact) {
                                MarketImpact.UP -> c.positive
                                MarketImpact.DOWN -> c.negative
                                MarketImpact.MIXED -> c.muted
                            }
                            val arrow = when (link.impact) {
                                MarketImpact.UP -> "▲"
                                MarketImpact.DOWN -> "▼"
                                MarketImpact.MIXED -> "•"
                            }
                            Text(
                                "$arrow ${link.market}",
                                fontFamily = JetBrainsMono, fontSize = 9.sp, color = col,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(col.copy(alpha = 0.13f))
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                Text(
                    meta(article).uppercase(),
                    fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.7.sp, color = c.muted,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (!article.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = article.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CyberChipCut)
                        .background(c.raise),
                )
            }
        }
    }
}

private fun meta(article: Article): String {
    val parts = buildList {
        if (article.source.isNotBlank()) add(article.source)
        if (article.publishedEpochMs > 0) add(Formatters.relativeTime(article.publishedEpochMs))
    }
    return parts.joinToString(" · ")
}
