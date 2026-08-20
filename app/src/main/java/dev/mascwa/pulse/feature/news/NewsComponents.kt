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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.mascwa.pulse.core.telemetry.BuzzLevel
import dev.mascwa.pulse.core.telemetry.Explainer
import dev.mascwa.pulse.core.telemetry.ImpactLevel
import dev.mascwa.pulse.core.telemetry.Lean
import dev.mascwa.pulse.core.telemetry.MarketImpact
import dev.mascwa.pulse.core.telemetry.MarketLink
import dev.mascwa.pulse.core.telemetry.MediaBias
import dev.mascwa.pulse.core.telemetry.NewsExplainers
import dev.mascwa.pulse.core.telemetry.NewsInsights
import dev.mascwa.pulse.core.telemetry.NewsSummary
import dev.mascwa.pulse.core.telemetry.NewsMarketLink
import dev.mascwa.pulse.core.telemetry.SocialBuzz
import dev.mascwa.pulse.core.telemetry.Tone
import dev.mascwa.pulse.core.telemetry.ToneBreakdown
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.breaking.BreakingCoverage
import dev.mascwa.pulse.data.news.Article
import dev.mascwa.pulse.data.news.NewsAnalysis
import dev.mascwa.pulse.feature.common.CyberChipCut
import dev.mascwa.pulse.feature.common.CyberRowFrame
import dev.mascwa.pulse.feature.common.ExplainerDialog
import dev.mascwa.pulse.feature.common.LcarsFillRow
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import java.util.Locale

/** Which strip's methodology the open [ExplainerDialog] is showing — [title] is the strip's own short name
 *  (matches its header glyph text), [items] the one or two [Explainer]s that explain it. */
private data class ExplainerRequest(val title: String, val items: List<Explainer>)

/** A news article as a chamfered CP2077 HUD panel — crimson meta stamp, mono technical metadata.
 *
 *  Below the summary: topic tags (always visible — what a story's ABOUT is more load-bearing than a mood/
 *  bias/buzz/market read), then a single always-visible ◢ INSIGHTS takeaway line that reads the whole story
 *  at a glance (mood + bias lean + social buzz + market impact, one sentence). Tapping that line expands the
 *  full detail — the MOOD/COVERAGE/MARKET REACTION/WIDER PICTURE strips — so a reader who just wants the
 *  gist never has to scroll past four stacked bars, and a reader who wants the depth gets it one tap away. */
@Composable
fun ArticleCard(
    article: Article,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pulse: Map<String, Double> = emptyMap(),
    /** Every article currently loaded in this feed (including [article] itself) — used only to compute the
     *  "how many other stories are on this right now" crowd signal in the expanded MOOD detail. Empty = no
     *  crowd read. */
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
    /** Raw Lemmy/HN/Mastodon-status titles fetched once this session — feeds the buzz bar. Empty = no
     *  social data yet / nothing fetched. */
    socialTitles: List<String> = emptyList(),
    /** Mastodon trending hashtag names fetched alongside [socialTitles]. */
    trendTagNames: List<String> = emptyList(),
) {
    val c = Pulse.colors
    if (onNeedsAnalysis != null) {
        LaunchedEffect(article.url) { onNeedsAnalysis(article) }
    }
    if (onNeedsCoverage != null) {
        LaunchedEffect(article.url) { onNeedsCoverage(article) }
    }

    // Computed once per article — feeds both the always-visible takeaway line and the expanded detail below.
    val subtitle = remember(article.url) {
        NewsSummary.subtitle(article.title, article.summary, article.source)
    }
    val mood = remember(article.url) { NewsInsights.toneBreakdown(article.title, article.summary) }
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
    val buzz = remember(article.url, socialTitles, trendTagNames) {
        SocialBuzz.score(tags, socialTitles, article.title, article.summary, trendTagNames)
    }
    val breakdown = coverage?.let { cov -> remember(cov.sources) { MediaBias.breakdown(cov.sources) } }
    val links = remember(article.url) {
        NewsMarketLink.linksFor(article.title, article.summary, article.category)
    }
    val impact = remember(links) { NewsInsights.marketImpact(links) }

    var expanded by remember(article.url) { mutableStateOf(false) }
    var explainerRequest by remember { mutableStateOf<ExplainerRequest?>(null) }

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
                // ⚠️ Not `article.summary` directly. An aggregator's description opens with the
                // article's own headline, so printing it here printed the line above it again —
                // see [NewsSummary.subtitle], which drops it and keeps whatever follows.
                subtitle?.let {
                    Text(
                        it,
                        fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink2,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
                if (tags.isNotEmpty()) {
                    TopicTagsRow(tags, Modifier.padding(top = 7.dp))
                }
                InsightsTakeaway(
                    mood = mood,
                    bias = breakdown,
                    buzz = buzz,
                    impact = impact,
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                    modifier = Modifier.padding(top = 7.dp),
                )
                if (expanded) {
                    MoodDetail(
                        mood, cluster, analysis,
                        onExplain = { explainerRequest = ExplainerRequest("MOOD", listOf(NewsExplainers.mood(mood))) },
                    )
                    if (buzz != BuzzLevel.NONE || (breakdown != null && breakdown.total > 0)) {
                        CoverageStrip(
                            breakdown, buzz,
                            onExplain = {
                                val items = buildList {
                                    if (breakdown != null && breakdown.total > 0) add(NewsExplainers.bias(breakdown))
                                    if (buzz != BuzzLevel.NONE) add(NewsExplainers.buzz(buzz))
                                }
                                if (items.isNotEmpty()) explainerRequest = ExplainerRequest("COVERAGE", items)
                            },
                        )
                    }
                    if (links.isNotEmpty() || analysis != null) {
                        MarketStrip(
                            links, pulse, analysis,
                            onExplain = {
                                explainerRequest =
                                    ExplainerRequest("MARKET REACTION + IMPACT", listOf(NewsExplainers.market(impact, links)))
                            },
                        )
                    }
                    analysis?.let { WiderPictureStrip(it) }
                }
                Text(
                    meta(article).uppercase(),
                    fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 0.8.sp, color = c.accent,
                    modifier = Modifier.padding(top = 9.dp),
                )
            }
        }
    }
    explainerRequest?.let { req ->
        ExplainerDialog(req.title, req.items, onDismiss = { explainerRequest = null })
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

/** The story's auto-extracted topic/region tags — kept always visible (unlike the mood/bias/buzz/market
 *  detail below it) since a fast skim of what a story's ABOUT is more load-bearing than why it reads that way. */
@Composable
private fun TopicTagsRow(tags: List<String>, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    FlowRow(
        modifier,
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

/** The one-line "read this and you're caught up" takeaway — combines mood, bias lean, social buzz and market
 *  impact into a single sentence so a reader gets the point without opening anything, plus the ◢ INSIGHTS /
 *  ◣ LESS toggle for the full detail below it. Any signal that's genuinely absent (no coverage fetched yet,
 *  no market link, nothing trending) is simply omitted, never padded with a filler clause — so the line is
 *  shorter for a quieter story and never claims a read it doesn't have. */
@Composable
private fun InsightsTakeaway(
    mood: ToneBreakdown,
    bias: BiasBreakdown?,
    buzz: BuzzLevel,
    impact: ImpactLevel,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Pulse.colors
    Row(
        modifier.fillMaxWidth().clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            insightsSummary(mood, bias, buzz, impact),
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink2,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (expanded) "◣ LESS" else "◢ INSIGHTS",
            fontFamily = JetBrainsMono, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp, color = c.accent,
        )
    }
}

/** Builds [InsightsTakeaway]'s sentence — short (a few words each) clauses joined with "·", NOT the fuller
 *  sentences [MediaBias.summarize]/[moodCaption] use in the expanded detail, which would overflow one line. */
private fun insightsSummary(mood: ToneBreakdown, bias: BiasBreakdown?, buzz: BuzzLevel, impact: ImpactLevel): String {
    val bits = buildList {
        add("${mood.tone.label} coverage")
        val biasBit = bias?.takeIf { it.total > 0 }?.let { shortBiasClause(it) }
        if (!biasBit.isNullOrBlank()) add(biasBit)
        if (buzz != BuzzLevel.NONE) add(buzz.label.lowercase())
        if (impact != ImpactLevel.NONE) add("${impact.label.lowercase()} market impact")
    }
    return bits.joinToString(" · ")
}

/** A short (2-3 word) lean clause for [insightsSummary] — the terse cousin of [MediaBias.summarize]'s full
 *  sentence, which is used instead inside the expanded COVERAGE detail. */
private fun shortBiasClause(b: BiasBreakdown): String {
    if (b.rated == 0) return ""
    val left = b.left + b.leanLeft
    val right = b.right + b.leanRight
    return when {
        left == 0 && right == 0 -> "balanced coverage"
        left > right * 2 -> "mostly left-leaning"
        right > left * 2 -> "mostly right-leaning"
        left > right -> "leans left"
        right > left -> "leans right"
        else -> "mixed lean"
    }
}

/** How many keyword hits fill the whole row — a story with this many charged terms (or more) reads as
 *  maximally intense; fewer hits leave a visible dim block, preserving a sense of magnitude rather than
 *  just direction (1 negative word looks very different from 5). */
private const val MOOD_BAR_SCALE = 6

/** The MOOD strip's full detail — segmented bar + methodology caption + the live "how many other stories are
 *  on this right now" crowd signal — shown inside the expanded ◢ INSIGHTS region. The whole block is one tap
 *  target for the [onExplain] methodology dialog (no competing gesture lives inside it). */
@Composable
private fun MoodDetail(mood: ToneBreakdown, cluster: Int, analysis: NewsAnalysis?, onExplain: () -> Unit) {
    val c = Pulse.colors
    val toneCol = toneColor(mood.tone)
    Column(Modifier.padding(top = 9.dp).fillMaxWidth().clickable(onClick = onExplain)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("◢ MOOD", fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = toneCol)
            Spacer(Modifier.width(6.dp))
            Text(mood.tone.label, fontFamily = JetBrainsMono, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = toneCol)
        }
        MoodBlockRow(mood, Modifier.padding(top = 5.dp).height(6.dp).fillMaxWidth())
        Text(
            "green = upbeat · red = tense · from the coverage's own words",
            fontFamily = JetBrainsMono, fontSize = 7.sp, color = c.faint,
            modifier = Modifier.padding(top = 3.dp),
        )
        val caption = analysis?.moodLine ?: moodCaption(mood)
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
    }
}

/** A row of solid LCARS blocks — upbeat/tense/negative segments sized by their real hit counts, plus a
 *  neutral remainder rendered as a REAL dim block (not empty space) — so the row always visually sums to
 *  [MOOD_BAR_SCALE] regardless of how many charged keywords actually hit. 1 tense word reads as a small
 *  sliver next to a wide dim block; 5 tense words fill most of the row — instead of both stretching to fill
 *  100% width the moment any signal exists, which is what a naively-normalizing bar would do. Built on
 *  [LcarsFillRow] (this app's blocky-segment LCARS primitive) rather than a smooth proportional bar — same
 *  green/orange/red read, textured to match the rest of the app's LCARS chrome. */
@Composable
private fun MoodBlockRow(b: ToneBreakdown, modifier: Modifier) {
    val c = Pulse.colors
    val segments = buildList {
        if (b.positive > 0) add(b.positive.toFloat() to Color(0xFF35C46A))
        if (b.tense > 0) add(b.tense.toFloat() to Color(0xFFE0331A))
        if (b.negative > 0) add(b.negative.toFloat() to Color(0xFFE0721A))
    }
    val filled = segments.sumOf { it.first.toDouble() }.toFloat()
    val neutralWeight = (MOOD_BAR_SCALE - filled).coerceAtLeast(if (segments.isEmpty()) 1f else 0f)
    val blocks = if (neutralWeight > 0f) segments + (neutralWeight to c.raise) else segments
    LcarsFillRow(blocks, modifier, gap = 1.5.dp)
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

/** The "◢ COVERAGE" strip — the Ground-News-style bias read (how many outlets are covering this exact story,
 *  and how their politics spread from left to right) stacked with a separate BUZZ bar (how much of the
 *  story's own vocabulary is live on Lemmy/HN/Mastodon right now), both under one header since they're both
 *  "who else is paying attention to this" reads. Two distinct bars, not one bar with an extra segment: a
 *  political-lean distribution is a PROPORTION (sums to the outlet count) while buzz is a MAGNITUDE
 *  (unbounded) — forcing both into one [Row.weight]-based bar would need an arbitrary weight-to-magnitude
 *  mapping and risk the lean percentages reading wrong. Either half can render alone: [breakdown] is null
 *  when no coverage has been fetched (or the setting is off) — buzz still shows; [buzz] is [BuzzLevel.NONE]
 *  when nothing overlaps on social — the bias half still shows. The caller only invokes this when at least
 *  one half has something to say. The whole block is one tap target for [onExplain] (no competing gesture
 *  lives inside it — the legend dots below are plain text, not their own tap targets). */
@Composable
private fun CoverageStrip(breakdown: BiasBreakdown?, buzz: BuzzLevel, onExplain: () -> Unit) {
    val c = Pulse.colors
    val hasBias = breakdown != null && breakdown.total > 0
    Column(
        Modifier
            .padding(top = 9.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF3B82F6).copy(alpha = 0.06f))
            .clickable(onClick = onExplain)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "◢ COVERAGE", fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold, color = Color(0xFF3B82F6),
            )
            if (hasBias) {
                val outlets = if (breakdown!!.total == 1) "outlet" else "outlets"
                Text("· ${breakdown.total} $outlets", fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted)
            }
        }
        if (hasBias) {
            BiasBar(breakdown!!, Modifier.padding(top = 6.dp).height(8.dp).fillMaxWidth())
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
        if (buzz != BuzzLevel.NONE) {
            Row(
                Modifier.padding(top = if (hasBias) 8.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "◈ BUZZ", fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.Bold, color = c.violet,
                )
                Text("· ${buzz.label}", fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted)
            }
            BuzzBar(buzz, Modifier.padding(top = 6.dp).height(6.dp).fillMaxWidth())
        }
    }
}

/** A single-fill bar sized by [BuzzLevel] intensity — a different shape than [BiasBar] on purpose (buzz is a
 *  magnitude with no natural "total" to be proportional against, unlike the bias bar's real outlet count). */
@Composable
private fun BuzzBar(level: BuzzLevel, modifier: Modifier) {
    val c = Pulse.colors
    val frac = when (level) {
        BuzzLevel.NONE -> 0f
        BuzzLevel.LOW -> 0.25f
        BuzzLevel.MODERATE -> 0.5f
        BuzzLevel.HIGH -> 0.75f
        BuzzLevel.VIRAL -> 1f
    }
    Row(modifier.clip(RoundedCornerShape(3.dp)).background(c.raise)) {
        if (frac > 0f) Box(Modifier.weight(frac).fillMaxHeight().background(c.violet))
        if (frac < 1f) Box(Modifier.weight(1f - frac).fillMaxHeight())
    }
}

/** A proportional multi-colour bar sized against the REAL rated-outlet count (unlike [MoodBlockRow]'s
 *  fixed-scale sizing) — an unrated outlet renders as unfilled track, same convention as the mood row's
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

/** The MARKET REACTION + IMPACT strip beneath a story's summary. A framed readout: header · market chips
 *  (LIVE % where we have a quote) · the body — when the cloud [analysis] is cached, its MARKET line is the
 *  owner-specified 180-240-word clinical desk note (instruments moved → transmission mechanism →
 *  positioning backdrop → one catalyst); otherwise the heuristic *Trading Places* one-liners. The whole
 *  block is one tap target for the [onExplain] methodology dialog. */
@Composable
private fun MarketStrip(links: List<MarketLink>, pulse: Map<String, Double>, analysis: NewsAnalysis?, onExplain: () -> Unit) {
    val c = Pulse.colors
    val hasLive = links.any { pulse[it.market] != null }
    val impact = NewsInsights.marketImpact(links)
    Column(
        Modifier
            .padding(top = 9.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(c.accent.copy(alpha = 0.06f))
            .clickable(onClick = onExplain)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "◢ MARKET REACTION + IMPACT",
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
        // The body — the cached cloud desk note (a ~200-word continuous paragraph, so it gets a reading
        // line-height), else the heuristic "what reality is doing to this market" one-liner.
        val head = analysis?.marketLine ?: NewsMarketLink.headline(links)
        if (head.isNotBlank()) {
            Text(
                head,
                fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 15.sp, color = c.ink2,
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
        // Locale.US: the sign, the arrow and the number are one compound token, and a
        // comma-decimal device rendered "▲ Oil +2,3%" beside "+2.3%" elsewhere on the same card.
        "${if (live >= 0.0) "▲" else "▼"} ${link.market} $sign" +
            String.format(Locale.US, "%.1f%%", live)
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
 *  (violet) separates it visually from the MARKET REACTION panel above. No [ExplainerDialog] — this is
 *  already the cloud's own synthesized prose, not a heuristic that needs a methodology explained. */
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
