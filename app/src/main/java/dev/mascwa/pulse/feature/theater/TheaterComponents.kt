package dev.mascwa.pulse.feature.theater

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.mascwa.pulse.core.telemetry.MediaItem
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import java.util.Locale

/**
 * The Theater's browse furniture: a 16:9 thumbnail card and the horizontal shelf that rows them.
 *
 * The card is the whole interaction: see it, tap it, watch it. It leans on the news components'
 * proven pattern — Coil AsyncImage where a thumbnail exists, a deterministic gradient-and-glyph
 * placeholder where none does — so a shelf never renders as a row of blank rectangles.
 */

/** One video as a tappable 16:9 card: thumbnail, two-line title, uploader · duration meta. */
@Composable
fun TheaterCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Pulse.colors
    Column(modifier.width(CARD_WIDTH).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(c.raise)
                .border(1.dp, c.lineSoft),
        ) {
            if (item.thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            } else {
                ThumbPlaceholder(item)
            }
            when {
                item.isLive -> CornerTag("LIVE", c.negative)
                item.durationS > 0 -> CornerTag(clockShort((item.durationS * 1000).toLong()), c.void)
            }
        }
        Text(
            item.title.ifBlank { "Untitled" },
            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (item.uploader.isNotBlank()) {
            Text(
                item.uploader,
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A labelled horizontal shelf of [TheaterCard]s. Renders nothing for an empty list. */
@Composable
fun TheaterShelfRow(
    label: String,
    items: List<MediaItem>,
    onPlay: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        LcarsHeaderBar(label)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(items, key = { it.pageUrl }) { item ->
                TheaterCard(item, onClick = { onPlay(item) })
            }
        }
    }
}

/** The deterministic no-thumbnail fallback — the ArticlePlaceholder pattern, keyed on uploader. */
@Composable
private fun ThumbPlaceholder(item: MediaItem) {
    val key = item.uploader.ifBlank { item.title }.ifBlank { item.pageUrl }
    val hue = (key.hashCode().mod(360)).toFloat()
    val base = Color.hsv(hue, 0.5f, 0.30f)
    val edge = Color.hsv((hue + 26f).mod(360f), 0.55f, 0.16f)
    val glyph = key.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "▶"
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .background(Brush.linearGradient(listOf(base, edge))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 34.sp,
            color = Color.White.copy(alpha = 0.22f),
        )
    }
}

/** The duration/LIVE tag in the thumbnail's corner — the idiom every video surface trains. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CornerTag(text: String, background: Color) {
    val c = Pulse.colors
    Text(
        text,
        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.ink,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(4.dp)
            .background(background.copy(alpha = 0.75f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

private val CARD_WIDTH = 160.dp

/**
 * mm:ss (or h:mm:ss) for the corner tag. Locale.US, the recurring rule: digits and colons only, and
 * a default-locale format can render the digits themselves differently.
 */
private fun clockShort(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, sec)
    else "%d:%02d".format(Locale.US, m, sec)
}
