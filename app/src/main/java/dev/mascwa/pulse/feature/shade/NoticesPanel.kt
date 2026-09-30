package dev.mascwa.pulse.feature.shade

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.lcarsboard.ConsolePanel
import dev.mascwa.pulse.feature.lcarsboard.legibleOn
import dev.mascwa.pulse.feature.lcarsboard.textOnBlock
import dev.mascwa.pulse.ui.effects.HapticCue
import dev.mascwa.pulse.ui.effects.SoundCue
import dev.mascwa.pulse.ui.effects.rememberLcarsCue
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import kotlinx.coroutines.delay

/** What the notices panel can be asked to do. */
internal class NoticeActions(
    val open: (String) -> Unit,
    val dismiss: (String) -> Unit,
    val clear: (List<String>) -> Unit,
    val grant: () -> Unit,
)

/**
 * The home console's notification centre: every app's notifications, newest first, grouped by app.
 *
 * A tap opens what the notification opens; ✕ clears it the way a swipe on Android's shade would;
 * CLEAR clears everything the shade itself would let "Clear all" clear. The panel says which of the
 * three states the reader is in — switched off, connecting, or reading — so an empty panel is never
 * ambiguous between "nothing waiting" and "LCARS cannot see".
 */
@Composable
internal fun NoticesPanel(
    status: ShadeDigest.Status,
    groups: List<ShadeDigest.Group>,
    tab: Color,
    progress: () -> Float,
    index: Int,
    actions: NoticeActions,
    modifier: Modifier = Modifier,
) {
    val c = Pulse.colors
    val cue = rememberLcarsCue()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(AGE_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    val count = ShadeDigest.count(groups)
    val clearable = ShadeDigest.clearableKeys(groups)
    ConsolePanel(
        title = if (count > 0) "$TITLE · $count" else TITLE,
        tab = tab,
        progress = progress,
        index = index,
        modifier = modifier,
        trailing = if (clearable.isEmpty()) {
            null
        } else {
            {
                Box(
                    Modifier
                        .background(c.negative)
                        .clickable(onClickLabel = "Clear every notification that can be cleared") {
                            cue(SoundCue.TAP, HapticCue.TAP_CRISP)
                            actions.clear(clearable)
                        }
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(
                        CLEAR_LABEL,
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = textOnBlock(c.negative),
                    )
                }
            }
        },
    ) {
        when {
            status == ShadeDigest.Status.OFF -> NoticeLine(OFF_LINE, c.amber) {
                cue(SoundCue.TAP, HapticCue.TAP_CRISP)
                actions.grant()
            }
            status == ShadeDigest.Status.CONNECTING -> NoticeLine(CONNECTING_LINE, c.muted)
            groups.isEmpty() -> NoticeLine(EMPTY_LINE, c.muted)
            else -> {
                groups.take(MAX_GROUPS).forEach { group ->
                    Text(
                        group.appLabel.uppercase(),
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = legibleOn(tab, c.void),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    group.entries.take(MAX_PER_GROUP).forEach { entry -> NoticeRow(entry, now, actions) }
                    val more = group.entries.size - MAX_PER_GROUP
                    if (more > 0) NoticeLine("+$more more from ${group.appLabel}", c.muted)
                }
                val moreApps = groups.size - MAX_GROUPS
                if (moreApps > 0) NoticeLine("+$moreApps more ${if (moreApps == 1) "app" else "apps"} with notices", c.muted)
            }
        }
    }
}

@Composable
private fun NoticeRow(entry: ShadeDigest.Entry, now: Long, actions: NoticeActions) {
    val c = Pulse.colors
    val cue = rememberLcarsCue()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open") { cue(SoundCue.TAP, HapticCue.TAP_CRISP); actions.open(entry.key) }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (entry.title.isNotBlank()) {
                Text(
                    entry.title,
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = legibleOn(c.ink, c.void),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.text.isNotBlank()) {
                Text(
                    entry.text,
                    fontFamily = ChakraPetch,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    color = legibleOn(c.muted, c.void),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            ShadeDigest.age(now, entry.postedAtMs),
            fontFamily = JetBrainsMono,
            fontSize = 10.sp,
            color = legibleOn(c.muted, c.void),
        )
        if (entry.clearable) {
            Box(
                Modifier
                    .clickable(onClickLabel = "Clear") { cue(SoundCue.TAP, HapticCue.TAP_CRISP); actions.dismiss(entry.key) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("✕", fontFamily = JetBrainsMono, fontSize = 14.sp, color = legibleOn(c.negative, c.void))
            }
        }
    }
}

@Composable
private fun NoticeLine(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Text(
        text,
        fontFamily = JetBrainsMono,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = legibleOn(color, Pulse.colors.void),
        modifier = if (onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier,
    )
}

private const val TITLE = "Notices"
private const val CLEAR_LABEL = "CLEAR"
private const val MAX_GROUPS = 8
private const val MAX_PER_GROUP = 3
private const val AGE_TICK_MS = 30_000L
private const val OFF_LINE = "Android is not letting LCARS read notifications. Tap to switch it on."
private const val CONNECTING_LINE = "Connecting to Android's notifications…"
private const val EMPTY_LINE = "Nothing waiting."
