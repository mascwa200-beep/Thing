package dev.mascwa.pulse.desktop.feature.news

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.desktop.news.Article
import dev.mascwa.pulse.desktop.news.NewsCategory
import dev.mascwa.pulse.desktop.telemetry.Freshness
import dev.mascwa.pulse.desktop.telemetry.NewsInsights
import dev.mascwa.pulse.desktop.telemetry.NewsSummary
import dev.mascwa.pulse.desktop.telemetry.Readability
import dev.mascwa.pulse.desktop.telemetry.NewsMarketLink
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsBusyBar
import dev.mascwa.pulse.desktop.theme.LcarsButton
import dev.mascwa.pulse.desktop.theme.LcarsChip
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.Pulse

/**
 * The News feed, drawn the same way the phone draws it.
 *
 * One plain-English takeaway line per article, made only of counts and names — how many outlets are
 * carrying it, whether it is live on social, whether it touches a market.
 *
 * ⚠️ Two coloured strips used to sit under each story: a MOOD read scored from charged keywords, and an
 * outlet political-lean line. Both are gone from both platforms. A band of colour is not a fact, and a
 * lean label rates a newspaper rather than reporting the event. Nothing replaced them with another
 * graphic — the outlets are simply named.
 */
@Composable
fun NewsScreen(vm: NewsViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    val c = Pulse.colors
    val reading by vm.reading.collectAsState()

    // The reader REPLACES the feed rather than sitting beside it. A window this wide could hold both,
    // but a story being read wants the whole measure — a column of body text next to a column of
    // headlines is two things competing for the same attention.
    reading?.let { article ->
        ReaderPane(vm, article, modifier)
        return
    }

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
                ArticleCard(
                    article,
                    state.articles,
                    canRead = vm.canRead(article),
                    onRead = { vm.read(article) },
                )
            }
        }
    }
}

@Composable
private fun ArticleCard(
    article: Article,
    all: List<Article>,
    canRead: Boolean = false,
    onRead: () -> Unit = {},
) {
    val c = Pulse.colors
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
            // The same rule as the phone: an aggregator's description opens with the headline,
            // and printing it here printed the line above it again.
            NewsSummary.subtitle(article.title, article.summary, article.source)?.let {
                Text(
                    it,
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // The always-visible one-liner. Counts only — how many other stories in this feed are on the
            // same subject, and whether the story touches a market at all.
            Text(
                takeaway(cluster(article, all), impact.label, links.size),
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
                modifier = Modifier.padding(top = 8.dp),
            )

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
            // ⚠️ Shown only when it will work. Most of this feed is Google News redirect stubs,
            // and a button that is always there and usually apologises teaches people to ignore it.
            if (canRead) {
                LcarsButton(
                    "READ",
                    onRead,
                    modifier = Modifier.padding(top = 8.dp),
                    accent = Pulse.colors.sky,
                )
            }
        }
    }
}

/**
 * How many other stories in this same feed are on this subject — the phone's crowd signal, computed the
 * same way from the same core, so a story reads identically on both.
 */
private fun cluster(article: Article, all: List<Article>): Int {
    val tags = NewsInsights.topics(article.title, article.summary)
    if (tags.isEmpty()) return 0
    val others = all.asSequence()
        .filter { it.url != article.url }
        .map { NewsInsights.topics(it.title, it.summary) }
        .toList()
    return NewsInsights.clusterSize(tags, others)
}

/**
 * The one-line takeaway. Every clause is a COUNT — nothing here describes how the writing feels.
 *
 * ⚠️ This used to open with "Coverage reads grim" / "reads upbeat", scored from charged words in the
 * headline. That clause went with the MOOD bar it belonged to: a word like "grim" describes a reader's
 * reaction rather than the story, and the reader is right there having their own. A signal that is not
 * there is omitted rather than padded, so a quiet story gets a short line and never an invented verdict.
 */
private fun takeaway(cluster: Int, impactLabel: String, linkCount: Int): String {
    val bits = buildList {
        if (cluster > 0) add("$cluster more ${if (cluster == 1) "story" else "stories"} here on it")
        if (linkCount > 0) {
            add(
                when (impactLabel) {
                    "High" -> "a strong market angle"
                    "Medium" -> "some market relevance"
                    else -> "a slight market angle"
                },
            )
        }
    }
    return if (bits.isEmpty()) "No wider coverage found yet" else bits.joinToString(" · ")
}


/**
 * A story with the page taken off it.
 *
 * ⚠️ Deliberately the same block vocabulary and the same honest verdict as the phone, because the
 * judgement comes from the same mirrored core. What differs is only the measure: a desktop window is
 * wide, so the body is held to a readable column rather than run edge to edge.
 */
@Composable
private fun ReaderPane(vm: NewsViewModel, article: Article, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    val busy by vm.readerBusy.collectAsState()
    val e by vm.extraction.collectAsState()

    Column(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        LcarsHeaderBar("Reader", trailing = article.source.uppercase())
        Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsButton("BACK TO THE FEED", { vm.closeReader() })
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                e?.meta?.title?.takeIf { it.isNotBlank() } ?: article.title,
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 24.sp,
                color = c.ink, modifier = Modifier.widthIn(max = READING_MEASURE),
            )
            val detail = listOfNotNull(
                e?.meta?.siteName ?: Readability.hostOf(article.url)?.uppercase(),
                e?.meta?.byline,
                e?.takeIf { it.isArticle }?.let { "${(it.wordCount + 219) / 220} MIN READ" },
            ).joinToString("  ·  ")
            if (detail.isNotBlank()) {
                Text(
                    detail, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            when {
                busy -> LcarsBusyBar(true, Modifier.fillMaxWidth().padding(top = 16.dp))

                e == null -> Unit

                e!!.isArticle -> Column(
                    Modifier.padding(top = 14.dp).widthIn(max = READING_MEASURE),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    e!!.blocks.forEach { ReaderBlock(it) }
                    if (e!!.truncated) {
                        Text(
                            "This is a long page and only the first part was kept.",
                            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                        )
                    }
                }

                else -> LcarsFrame(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Column {
                        Text(
                            when (e!!.outcome) {
                                Readability.Outcome.BLOCKED -> "BLOCKED"
                                Readability.Outcome.THIN -> "NOT MUCH THERE"
                                else -> "NOT AN ARTICLE"
                            },
                            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber,
                        )
                        Text(
                            e!!.note ?: "This page did not give up an article.",
                            fontFamily = ChakraPetch, fontSize = 15.sp, color = c.ink,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The measure a column of body text is held to.
 *
 * Long lines are hard to track back to the start of the next one; typographic advice puts the
 * comfortable range near 60-80 characters, which at this size lands about here. A desktop window is
 * far wider than that, so this is a constraint rather than a size.
 */
private val READING_MEASURE = 760.dp

@Composable
private fun ReaderBlock(block: Readability.Block) {
    val c = Pulse.colors
    when (block) {
        is Readability.Block.Paragraph ->
            Text(block.text, fontFamily = ChakraPetch, fontSize = 16.sp, lineHeight = 26.sp, color = c.ink)

        is Readability.Block.Heading -> Text(
            block.text, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
            fontSize = when (block.level.coerceIn(1, 6)) {
                1, 2 -> 20.sp
                3 -> 18.sp
                else -> 16.sp
            },
            color = c.accent, modifier = Modifier.padding(top = 6.dp),
        )

        is Readability.Block.Quote -> Text(
            block.text, fontFamily = ChakraPetch, fontSize = 16.sp, lineHeight = 26.sp, color = c.ink2,
            modifier = Modifier.padding(start = 14.dp),
        )

        is Readability.Block.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { i, item ->
                Text(
                    (if (block.ordered) "${i + 1}.  " else "\u25aa  ") + item,
                    fontFamily = ChakraPetch, fontSize = 15.sp, lineHeight = 23.sp, color = c.ink,
                )
            }
        }

        // ⚠️ Images are named, not drawn. Loading a remote picture needs an image pipeline this
        // module does not carry, and inventing one for a caption's worth of value would be the
        // wrong trade — saying a picture was there is honest and costs nothing.
        is Readability.Block.Image -> Text(
            block.caption?.let { "[image] $it" } ?: "[image]",
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
        )

        is Readability.Block.Code -> Text(
            block.text, fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 18.sp, color = c.ink2,
        )
    }
}
