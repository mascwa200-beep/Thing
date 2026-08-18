package dev.mascwa.pulse.feature.live

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.connectivity.LocalIsMetered
import dev.mascwa.pulse.core.telemetry.LiveChannels
import dev.mascwa.pulse.core.telemetry.LiveChannels.LiveChannel
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.lcarsBlockShape
import dev.mascwa.pulse.feature.media.AudioFloor
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * A complete live-television panel: pick a channel, watch it, know what it is costing.
 *
 * Self-contained on purpose — the News tab and the breaking-news takeover both want exactly this,
 * and a panel each screen assembles from parts is a panel the two screens will drift apart on.
 *
 * ⚠️ **Nothing plays until it is tapped**, on every host including the takeover. A full-screen
 * interruption that opens on the lock screen with audio already running is hostile, and "auto-play
 * on Wi-Fi" would make the behaviour differ silently by connection.
 *
 * Leaving the screen stops playback, which is why there is no foreground service: a live stream
 * drawing to a surface nobody can see is data spent on nothing.
 */
@Composable
fun LiveVideoPlayer(
    modifier: Modifier = Modifier,
    channels: List<LiveChannel> = LiveChannels.offer(),
) {
    val c = Pulse.colors
    val context = LocalContext.current
    val state by LiveVideoController.state.collectAsStateWithLifecycle()
    val dataRate by LiveVideoController.dataRate.collectAsStateWithLifecycle()
    val displaced by AudioFloor.note.collectAsStateWithLifecycle()
    val metered = LocalIsMetered.current

    if (channels.isEmpty()) {
        Text(
            "No live channel is available.",
            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
            modifier = modifier.padding(12.dp),
        )
        return
    }

    // The panel owns the playback lifetime: arriving costs nothing, leaving stops the stream.
    DisposableEffect(Unit) {
        onDispose { LiveVideoController.stop(context) }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {

        ChannelRail(channels, state.channel?.id) { LiveVideoController.toggle(context, it) }

        VideoFrame(state, c.void, c.accent, c.negative, c.muted) {
            state.channel?.let { LiveVideoController.play(context, it) }
        }

        if (state.status == LiveVideoController.Status.PLAYING) {
            LcarsButton("STOP", { LiveVideoController.stop(context) })
        }

        state.channel?.let { channel ->
            Text(
                LiveChannels.describe(channel).uppercase(),
                fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp,
                color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }

        // What it costs, once the player has settled on a variant — never a figure we invented.
        // "On mobile data" is added only where that is a fact we hold, so on an unclassifiable
        // connection the line says the rate and nothing more.
        dataRate?.let { rate ->
            Text(
                if (metered) "$rate · on mobile data" else rate,
                fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp,
                color = if (metered) c.amber else c.muted,
            )
        }

        // Audio that stops on its own is alarming unless it is explained.
        displaced?.let { note ->
            Text(
                note,
                fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = c.amber,
                modifier = Modifier.clickable { AudioFloor.clearNote() },
            )
        }
    }
}

/** The channels on offer, the playing one lit. */
@Composable
private fun ChannelRail(
    channels: List<LiveChannel>,
    playingId: String?,
    onPick: (LiveChannel) -> Unit,
) {
    val c = Pulse.colors
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(channels, key = { it.id }) { channel ->
            val on = channel.id == playingId
            // An unconfirmed channel is drawn as one: it may well work, and it may well not.
            val tint = when {
                on -> c.accent
                channel.verification == LiveChannels.Verification.SEGMENT -> c.ink2
                else -> c.muted
            }
            val shape = lcarsBlockShape(8.dp, LcarsCorner.TopStart)
            Text(
                channel.name.uppercase(),
                fontFamily = ChakraPetch, fontSize = 11.sp, letterSpacing = 1.sp,
                color = if (on) c.void else tint,
                maxLines = 1,
                modifier = Modifier
                    .clip(shape)
                    .background(if (on) c.accent else Color.Transparent)
                    .border(1.dp, tint.copy(alpha = 0.6f), shape)
                    .clickable { onPick(channel) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/**
 * The picture, and whatever stands in for it.
 *
 * The surface is created once and handed to the controller, which holds it — the player can be
 * rebuilt underneath this composable (a reconnect does exactly that) and has to find its way back
 * to the same view.
 */
@Composable
private fun VideoFrame(
    state: LiveVideoController.LiveState,
    ground: Color,
    accent: Color,
    negative: Color,
    muted: Color,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val view = remember { SurfaceView(context) }
    DisposableEffect(view) {
        LiveVideoController.attach(view)
        onDispose { LiveVideoController.detach(view) }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(ground)
            .border(1.dp, accent.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())

        when (state.status) {
            LiveVideoController.Status.IDLE -> Text(
                "SELECT A CHANNEL",
                fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 2.sp, color = muted,
            )
            LiveVideoController.Status.CONNECTING -> Text(
                (state.detail ?: "OPENING ${state.channel?.name.orEmpty()}").uppercase(),
                fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 2.sp, color = accent,
            )
            // The error carries the player's own code name, so a dead stream is diagnosable rather
            // than just "it didn't work".
            LiveVideoController.Status.ERROR -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "NO SIGNAL · ${state.detail.orEmpty()}".trim().uppercase(),
                    fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp,
                    color = negative, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                LcarsButton("TRY AGAIN", onRetry, color = negative)
            }
            // Nothing over the picture once there is a picture.
            LiveVideoController.Status.PLAYING -> Unit
        }
    }
}
