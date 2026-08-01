package dev.mascwa.pulse.feature.news

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.mascwa.pulse.core.telemetry.MarketImpact
import dev.mascwa.pulse.core.telemetry.MarketLink
import dev.mascwa.pulse.core.telemetry.NewsMarketLink
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.news.Article
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
) {
    val c = Pulse.colors
    NeonPanel(
        modifier.fillMaxWidth().clickable(onClick = onClick),
        corners = true,
        padding = PaddingValues(0.dp),
    ) {
        Column {
            if (!article.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = article.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(c.raise),
                )
            }
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
                // Which markets this story touches / would move, and which way (+ a short why).
                val links = remember(article.url) {
                    NewsMarketLink.linksFor(article.title, article.summary, article.category)
                }
                if (links.isNotEmpty()) MarketStrip(links)
                Text(
                    meta(article).uppercase(),
                    fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 0.8.sp, color = c.accent,
                    modifier = Modifier.padding(top = 9.dp),
                )
            }
        }
    }
}

/** The market strip beneath a story's summary: a chip per associated market, coloured + arrowed by the
 *  story's likely push (▲ up / ▼ down / • unclear), and a one-line "why" caption. Heuristic, not a quote. */
@Composable
private fun MarketStrip(links: List<MarketLink>) {
    val c = Pulse.colors
    Column(Modifier.padding(top = 7.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            links.forEach { link ->
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
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = col,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(col.copy(alpha = 0.13f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        val why = NewsMarketLink.summarize(links)
        if (why.isNotBlank()) {
            Text(
                why,
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                modifier = Modifier.padding(top = 4.dp),
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
