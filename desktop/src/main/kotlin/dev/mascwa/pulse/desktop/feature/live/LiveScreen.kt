package dev.mascwa.pulse.desktop.feature.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.desktop.live.LivePlayer
import dev.mascwa.pulse.desktop.telemetry.LiveChannels
import dev.mascwa.pulse.desktop.telemetry.LiveChannels.LiveChannel
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsButton
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsGhostButton
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.LcarsTextField
import dev.mascwa.pulse.desktop.theme.Pulse

/**
 * LIVE — television news, in the page or in a window you can leave running.
 *
 * The channel list and every judgement about it come from the same shared core the phone uses, so
 * the two cannot disagree about what is on offer or how far a channel is to be trusted.
 *
 * ⚠️ Nothing plays until it is asked to, here as on the phone. And the honesty about verification is
 * not decoration: a playlist that answers HTTP 200 is not a playlist that plays, which is why a
 * channel nobody has confirmed says so rather than being presented as working or quietly dropped.
 */
@Composable
fun LiveScreen(vm: LiveViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    val community by vm.community.collectAsState()
    val c = Pulse.colors
    val channels = LiveChannels.offer()
    var filter by remember { mutableStateOf("") }

    // Arriving asks for the catalogue. A no-op unless the switch is on, and a no-op once it is held.
    LaunchedEffect(Unit) { vm.loadCommunity() }

    // Leaving the page stops the stream, unless it has been popped out — see LiveViewModel.onLeave.
    DisposableEffect(Unit) { onDispose { vm.onLeave() } }

    Column(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        LcarsHeaderBar(
            "Live",
            trailing = state.channel?.name?.uppercase()?.takeIf { state.status != LivePlayer.Status.IDLE },
        )

        // ⚠️ The picture sits OUTSIDE the scrolling list, deliberately. A SwingPanel is a heavyweight
        // AWT component drawn over the Compose surface, and one placed inside a LazyColumn clips
        // against the scroll rather than with it. Fixed above the list, it has nothing to fight.
        if (state.status != LivePlayer.Status.IDLE) {
            LiveStage(vm, Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LcarsButton("Open in a window", vm::popOut)
                LcarsGhostButton("Stop", vm::stop)
            }
        }

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 24.dp),
        ) {
            item {
                Text(
                    "Television news. It plays here, and opens in a window of its own if you want it " +
                        "on a second screen. These are broadcasters' own public streams, and nothing " +
                        "plays until you ask it to.",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2, lineHeight = 17.sp,
                )
            }

            if (state.status == LivePlayer.Status.ERROR) item {
                LcarsFrame(Modifier.fillMaxWidth(), accent = c.negative) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "NO SIGNAL",
                            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                            fontSize = 13.sp, letterSpacing = 1.5.sp, color = c.negative,
                        )
                        Text(
                            state.detail ?: "That channel did not open.",
                            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
                        )
                    }
                }
            }

            if (state.status == LivePlayer.Status.CONNECTING) item {
                Text(
                    "OPENING ${state.channel?.name.orEmpty().uppercase()}…",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.5.sp,
                    color = c.accent,
                )
            }

            items(channels.size) { i ->
                ChannelCard(
                    channel = channels[i],
                    playing = channels[i].id == state.channel?.id &&
                        state.status != LivePlayer.Status.IDLE,
                    onWatch = { vm.watch(channels[i]) },
                    onStop = { vm.stop() },
                )
            }

            // The community catalogue, if it has been asked for. Hundreds of entries, so it gets a
            // filter rather than being poured into the same list as the five curated ones.
            if (community.isNotEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LcarsHeaderBar("Community", trailing = "${community.size} · UNVERIFIED")
                        LcarsTextField(
                            label = "Filter",
                            value = filter,
                            onValueChange = { filter = it },
                            placeholder = "Filter by name or country",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                val shown = community.filter { ch ->
                    val q = filter.trim().lowercase()
                    q.isBlank() || ch.name.lowercase().contains(q) || ch.region.lowercase().contains(q)
                }
                items(shown.size) { i ->
                    ChannelCard(
                        channel = shown[i],
                        playing = shown[i].id == state.channel?.id &&
                            state.status != LivePlayer.Status.IDLE,
                        onWatch = { vm.watch(shown[i]) },
                        onStop = { vm.stop() },
                    )
                }
            }
        }
    }
}

/**
 * The picture, in the page.
 *
 * The panel is created once and handed back on dispose. JavaFX builds its scene asynchronously on
 * its own thread, so the component exists immediately and fills in a moment later — which is why the
 * ground behind it is black rather than the page colour: a flash of orange would be worse than a
 * beat of nothing.
 */
@Composable
private fun LiveStage(vm: LiveViewModel, modifier: Modifier = Modifier) {
    val panel = remember { vm.createPanel() }
    DisposableEffect(panel) { onDispose { vm.releasePanel(panel) } }
    SwingPanel(
        background = Color.Black,
        factory = { panel },
        modifier = modifier.aspectRatio(16f / 9f),
    )
}

@Composable
private fun ChannelCard(
    channel: LiveChannel,
    playing: Boolean,
    onWatch: () -> Unit,
    onStop: () -> Unit,
) {
    val c = Pulse.colors
    LcarsFrame(
        Modifier.fillMaxWidth(),
        accent = if (playing) c.accent else c.lineSoft,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    channel.name,
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    color = if (playing) c.accent else c.ink,
                )
                Text(
                    LiveChannels.describe(channel),
                    fontFamily = JetBrainsMono, fontSize = 10.sp,
                    color = if (channel.verification == LiveChannels.Verification.UNVERIFIED) {
                        c.amber
                    } else {
                        c.muted
                    },
                )
            }
            if (playing) LcarsGhostButton("Stop", onStop) else LcarsButton("Watch", onWatch)
        }
    }
}
