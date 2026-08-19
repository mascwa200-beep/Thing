package dev.mascwa.pulse.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.mascwa.pulse.core.telemetry.Readability
import dev.mascwa.pulse.core.util.openUrl
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.LcarsIcons
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.lcarsBlockShape
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * The reader — an article with the page taken off it.
 *
 * ⚠️ **The way out is always on screen.** Every state, including a perfectly good article, keeps
 * OPEN IN BROWSER, because the decimator can be wrong in ways it cannot detect: a page whose
 * interactive half carried the point, a story continued behind a "read more", a chart that was an
 * embed. Offering it only on failure would make the reader a trap on exactly the pages where it
 * quietly got half the story.
 */
@Composable
fun ReaderScreen(
    vm: ReaderViewModel,
    url: String,
    fallbackTitle: String = "",
    onBack: (() -> Unit)? = null,
) {
    val c = Pulse.colors
    val ctx = LocalContext.current
    val loading by vm.loading.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()

    LaunchedEffect(url) { vm.load(url) }

    PulseScaffold(
        title = "Reader",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(LcarsIcons.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val e = result
                item {
                    Header(
                        title = e?.meta?.title?.takeIf { it.isNotBlank() }
                            ?: fallbackTitle.takeIf { it.isNotBlank() }
                            ?: "Reading…",
                        byline = e?.meta?.byline,
                        site = e?.meta?.siteName ?: Readability.hostOf(url),
                        words = e?.takeIf { it.isArticle }?.wordCount,
                    )
                }

                when {
                    loading && e == null -> item { Working() }

                    e == null -> Unit

                    e.isArticle -> {
                        items(e.blocks) { block -> BlockView(block) }
                        if (e.truncated) {
                            item { Notice("This is a long page and only the first part was kept.") }
                        }
                    }

                    else -> item { Refusal(e) { vm.retry() } }
                }

                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LcarsButton(
                            text = "OPEN IN BROWSER",
                            onClick = { openUrl(ctx, url) },
                            modifier = Modifier.weight(1f),
                            color = c.sky,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, byline: String?, site: String?, words: Int?) {
    val c = Pulse.colors
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            title,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = 23.sp,
            lineHeight = 28.sp,
            color = c.ink,
        )
        val detail = listOfNotNull(
            site?.uppercase(),
            byline,
            words?.let { "${ReaderViewModel.minutesToRead(it)} MIN READ" },
        ).joinToString("  ·  ")
        if (detail.isNotBlank()) {
            Text(
                detail,
                fontFamily = JetBrainsMono,
                fontSize = 10.sp,
                color = c.muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Box(
            Modifier.padding(top = 10.dp).fillMaxWidth().height(3.dp)
                .clip(lcarsBlockShape(3.dp, LcarsCorner.TopStart))
                .background(c.accent),
        )
    }
}

@Composable
private fun BlockView(block: Readability.Block) {
    val c = Pulse.colors
    when (block) {
        is Readability.Block.Paragraph -> Text(
            block.text,
            fontFamily = ChakraPetch,
            fontSize = 16.sp,
            lineHeight = 25.sp,
            color = c.ink,
        )

        is Readability.Block.Heading -> Text(
            block.text,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            // Levels are clamped rather than trusted: pages nest headings freely and a level-6
            // subheading rendered at 10sp would be smaller than the body it introduces.
            fontSize = when (block.level.coerceIn(1, 6)) {
                1, 2 -> 19.sp
                3 -> 17.sp
                else -> 15.sp
            },
            color = c.accent,
            modifier = Modifier.padding(top = 6.dp),
        )

        // The rule runs the height of the quote, so the Row is measured at its minimum intrinsic
        // height and the rule fills it — a fixed height would be wrong for every quote but one.
        is Readability.Block.Quote -> Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(c.amber))
            Text(
                block.text,
                fontFamily = ChakraPetch,
                fontSize = 16.sp,
                lineHeight = 25.sp,
                color = c.ink2,
                modifier = Modifier.padding(start = 10.dp),
            )
        }

        is Readability.Block.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { i, item ->
                Row {
                    Text(
                        if (block.ordered) "${i + 1}." else "▪",
                        fontFamily = JetBrainsMono,
                        fontSize = 13.sp,
                        color = c.accent,
                        modifier = Modifier.width(26.dp),
                    )
                    Text(
                        item,
                        fontFamily = ChakraPetch,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = c.ink,
                    )
                }
            }
        }

        is Readability.Block.Image -> Column(Modifier.fillMaxWidth()) {
            AsyncImage(
                model = block.url,
                contentDescription = block.caption,
                modifier = Modifier.fillMaxWidth().clip(lcarsBlockShape(10.dp, LcarsCorner.TopStart)),
            )
            block.caption?.let {
                Text(
                    it,
                    fontFamily = JetBrainsMono,
                    fontSize = 10.sp,
                    color = c.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        is Readability.Block.Code -> Text(
            block.text,
            fontFamily = JetBrainsMono,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = c.ink2,
            modifier = Modifier.fillMaxWidth()
                .clip(lcarsBlockShape(8.dp, LcarsCorner.TopStart))
                .background(c.raise)
                .padding(10.dp),
        )
    }
}

@Composable
private fun Working() {
    val c = Pulse.colors
    Text(
        "STRIPPING THE PAGE…",
        fontFamily = JetBrainsMono,
        fontSize = 11.sp,
        color = c.muted,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Notice(text: String) {
    val c = Pulse.colors
    Text(
        text,
        fontFamily = JetBrainsMono,
        fontSize = 10.sp,
        color = c.muted,
        modifier = Modifier.fillMaxWidth()
            .clip(lcarsBlockShape(8.dp, LcarsCorner.TopStart))
            .background(c.raise)
            .padding(10.dp),
    )
}

/**
 * Why there is no article, and what can be done about it.
 *
 * ⚠️ The note is the whole point of this screen's failure state, so it is rendered at reading size
 * rather than as a caption. "Google News links point at a redirect" tells someone to press the
 * button below it; "Something went wrong" tells them nothing.
 */
@Composable
private fun Refusal(e: Readability.Extraction, onRetry: () -> Unit) {
    val c = Pulse.colors
    val tint = when (e.outcome) {
        Readability.Outcome.BLOCKED -> c.amber
        Readability.Outcome.THIN -> c.amber
        else -> c.muted
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(lcarsBlockShape(10.dp, LcarsCorner.TopStart))
            .background(c.panel)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(26.dp).height(10.dp)
                    .clip(lcarsBlockShape(5.dp, LcarsCorner.TopStart))
                    .background(tint),
            )
            Text(
                when (e.outcome) {
                    Readability.Outcome.BLOCKED -> "  BLOCKED"
                    Readability.Outcome.THIN -> "  NOT MUCH THERE"
                    else -> "  NOT AN ARTICLE"
                },
                fontFamily = JetBrainsMono,
                fontSize = 11.sp,
                color = tint,
            )
        }
        Text(
            e.note ?: "This page did not give up an article.",
            fontFamily = ChakraPetch,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            color = c.ink,
        )
        LcarsButton(text = "TRY AGAIN", onClick = onRetry, color = c.muted)
    }
}
