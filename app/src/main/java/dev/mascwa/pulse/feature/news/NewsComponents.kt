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
import dev.mascwa.pulse.core.telemetry.BuzzLevel
import dev.mascwa.pulse.core.telemetry.Explainer
import dev.mascwa.pulse.core.telemetry.ImpactLevel
import dev.mascwa.pulse.core.telemetry.MarketImpact
import dev.mascwa.pulse.core.telemetry.MarketLink
import dev.mascwa.pulse.core.telemetry.NewsExplainers
import dev.mascwa.pulse.core.telemetry.NewsInsights
import dev.mascwa.pulse.core.telemetry.NewsSummary
import dev.mascwa.pulse.core.telemetry.NewsMarketLink
import dev.mascwa.pulse.core.telemetry.SocialBuzz
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.breaking.BreakingCoverage
import dev.mascwa.pulse.data.news.Article
import dev.mascwa.pulse.data.news.NewsAnalysis
import dev.mascwa.pulse.feature.common.CyberChipCut
import dev.mascwa.pulse.feature.common.CyberRowFrame
import dev.mascwa.pulse.feature.common.ExplainerDialog
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import java.util.Locale

/** Which block's methodology the open [ExplainerDialog] is showing — [title] is the block's own short name
 *  (matches its header glyph text), [items] the [Explainer]s that explain it. Only MARKET REACTION + IMPACT
 *  has one now; the three bars that needed a dialog before their graphic meant anything are gone. */
private data class ExplainerRequest(val title: String, val items: List<Explainer>)

/** A news article as a chamfered CP2077 HUD panel — crimson meta stamp, mono technical metadata.
 *
 *  Below the summary: topic tags (always visible — what a story is ABOUT is the most load-bearing thing on
 *  the card), then a single always-visible ◢ INSIGHTS line that is entirely counts and names: how many
 *  outlets are carrying it, how many other stories in this feed are on it, whether it is live on social,
 *  whether it touches a market. Tapping that expands the detail — who those outlets ARE, the market
 *  reaction, and the cloud's wider read when one is cached.
 *
 *  ⚠️ There were three coloured bars here — MOOD (a green-to-red keyword tone score), COVERAGE (a
 *  political-lean distribution over the outlets) and BUZZ (a chatter meter). All three are gone. A strip of
 *  colour is not a fact: the scale was invisible, the reader could not act on any of it, and the lean bar
 *  rated newspapers rather than reporting the event. Nothing replaced them with another graphic — the facts
 *  they were drawn from are simply written out in words, which is the only form that survives being read. */
@Composable
fun ArticleCard(
    article: Article,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pulse: Map<String, Double> = emptyMap(),
    /** Every article currently loaded in this feed (including [article] itself) — used only to compute the
     *  "how many other stories are on this right now" crowd signal. Empty = no crowd read. */
    allArticles: List<Article> = emptyList(),
    /** The cloud "what's really going on" synthesis for this article, if one's cached — upgrades the market
     *  copy from the heuristic read, adds a written read of the coverage, and adds the WIDER PICTURE block.
     *  Null = heuristic-only. */
    analysis: NewsAnalysis? = null,
    /** Called once as this card first composes, so its analysis can be requested lazily (only for cards
     *  that actually become visible in the LazyColumn). Null = don't request one (e.g. a compact preview). */
    onNeedsAnalysis: ((Article) -> Unit)? = null,
    /** Multi-outlet coverage of this story, if cached — supplies the outlet NAMES. Null = not fetched yet /
     *  disabled / genuinely no cross-outlet matches found. */
    coverage: BreakingCoverage? = null,
    /** Called once as this card first composes, so coverage can be requested lazily — mirrors
     *  [onNeedsAnalysis]. Null = don't request one. */
    onNeedsCoverage: ((Article) -> Unit)? = null,
    /** Raw Lemmy/HN/Mastodon-status titles fetched once this session — feeds the "live on social right now"
     *  sentence. Empty = no social data yet / nothing fetched. */
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
    // ⚠️ How many outlets, counted straight off the names — NOT off a political-lean table. The count used
    // to come from `MediaBias.breakdown`, whose only remaining job on this card was to be counted; the
    // table itself is gone. This is the same list [CoverageDetail] prints, so the summary line and the
    // detail can never disagree about how many there are.
    val outlets = remember(coverage) {
        coverage?.sources.orEmpty().filter { it.isNotBlank() }.distinct()
    }
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
                    outlets = outlets.size,
                    cluster = cluster,
                    buzz = buzz,
                    impact = impact,
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                    modifier = Modifier.padding(top = 7.dp),
                )
                if (expanded) {
                    CoverageDetail(outlets, cluster, buzz, analysis)
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

/** The story's auto-extracted topic/region tags — kept always visible (unlike the coverage/market detail
 *  below it), since a fast skim of what a story is ABOUT beats any read of how it is written. */
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

/** The one-line "read this and you're caught up" takeaway, plus the ◢ INSIGHTS / ◣ LESS toggle for the
 *  detail below it.
 *
 *  ⚠️ Every clause here is a COUNT or a NAME — how many outlets are carrying the story, how many other
 *  stories in this feed are on it, whether it is live on social, whether it touches a market. There was once
 *  a "grim coverage" clause fed by a keyword tone score, and a "leans left" one fed by an outlet-politics
 *  table; both were removed with the strips they belonged to. A word like "grim" describes a reader's
 *  reaction, not the story, and the reader is right there having their own.
 */
@Composable
private fun InsightsTakeaway(
    outlets: Int,
    cluster: Int,
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
            insightsSummary(outlets, cluster, buzz, impact),
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

/** Builds [InsightsTakeaway]'s sentence. A signal that genuinely is not there is omitted rather than padded,
 *  so a quiet story gets a short line and never a claimed read it does not have — and when nothing at all is
 *  known yet the line says exactly that instead of inventing a verdict. */
private fun insightsSummary(outlets: Int, cluster: Int, buzz: BuzzLevel, impact: ImpactLevel): String {
    val bits = buildList {
        if (outlets > 1) add("$outlets outlets carrying it")
        if (cluster > 0) add("$cluster more ${if (cluster == 1) "story" else "stories"} here on it")
        if (buzz != BuzzLevel.NONE) add(buzz.label.lowercase())
        if (impact != ImpactLevel.NONE) add("${impact.label.lowercase()} market impact")
    }
    return if (bits.isEmpty()) "No wider coverage found yet" else bits.joinToString(" · ")
}

/** What replaced the MOOD and COVERAGE bars: the facts they were drawn from, said in words.
 *
 *  The MOOD bar was a green-to-red strip scored from charged keywords in the headline, and the COVERAGE bar
 *  was a blue-to-red political-lean distribution over the outlets carrying the story. Both are gone. Neither
 *  told a reader anything they could act on — a colour is not a fact, the scale was invisible, and a lean
 *  table is an opinion about a newspaper rather than information about the event.
 *
 *  What is here instead is only things that are true and checkable: WHO is carrying this story, by name;
 *  how many other stories in this same feed are on it; and, when the cloud analysis is cached, its written
 *  read. No bars, no legend to decode, nothing that needs a methodology dialog to mean anything.
 */
@Composable
private fun CoverageDetail(
    /** The outlets carrying this story, by name — computed once by the caller so this block and the
     *  one-line takeaway above it can never report a different number. */
    outlets: List<String>,
    cluster: Int,
    buzz: BuzzLevel,
    analysis: NewsAnalysis?,
) {
    val c = Pulse.colors
    val line = analysis?.moodLine?.takeIf { it.isNotBlank() }
    if (outlets.isEmpty() && cluster == 0 && line == null && buzz == BuzzLevel.NONE) return
    Column(
        Modifier
            .padding(top = 9.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(c.accent.copy(alpha = 0.06f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            "◢ WHO ELSE IS CARRYING THIS",
            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
            fontWeight = FontWeight.Bold, color = c.accent,
        )
        if (outlets.isEmpty()) {
            Text(
                "No other outlet found on this story yet.",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                modifier = Modifier.padding(top = 5.dp),
            )
        } else {
            Text(
                outlets.joinToString(" · "),
                fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 15.sp, color = c.ink2,
                modifier = Modifier.padding(top = 5.dp),
            )
            Text(
                "${outlets.size} ${if (outlets.size == 1) "outlet" else "outlets"} · named, not rated",
                fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.faint,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (cluster > 0) {
            Text(
                "$cluster other ${if (cluster == 1) "story" else "stories"} in this feed on the same subject",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        if (buzz != BuzzLevel.NONE) {
            Text(
                "Live on Lemmy, Hacker News and Mastodon right now — ${buzz.label.lowercase()}",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (line != null) {
            Text(
                line,
                fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 15.sp, color = c.ink2,
                modifier = Modifier.padding(top = 6.dp),
            )
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
