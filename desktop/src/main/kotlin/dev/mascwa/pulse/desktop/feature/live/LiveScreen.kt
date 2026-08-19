package dev.mascwa.pulse.desktop.feature.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.desktop.live.LivePlayer
import dev.mascwa.pulse.desktop.telemetry.ChannelLineup
import dev.mascwa.pulse.desktop.telemetry.LiveChannels
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsButton
import dev.mascwa.pulse.desktop.theme.LcarsGhostButton
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.LcarsTextField
import dev.mascwa.pulse.desktop.theme.Pulse
import kotlinx.coroutines.delay

/**
 * LIVE — television as a cable box, on the same lineup the phone uses.
 *
 * Channel numbers, channel up/down, a keypad and a guide, all decided by the shared
 * [ChannelLineup] core — so **channel 7 is the same broadcaster on both machines**, which is the
 * whole reason that core is mirrored rather than written twice.
 *
 * ⚠️ The desktop has a real keyboard, so it gets the thing a remote is an imitation of: **type the
 * digits, or press the arrow keys.** The on-screen keypad stays anyway, and deliberately — key
 * events need focus, focus is the least predictable part of any desktop UI, and a box you cannot
 * operate because a click landed somewhere unexpected is worse than one with a redundant keypad.
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

    // Arriving asks for the catalogue. A no-op unless the switch is on, and a no-op once it is held.
    LaunchedEffect(Unit) { vm.loadCommunity() }

    // Leaving the page stops the stream, unless it has been popped out — see LiveViewModel.onLeave.
    DisposableEffect(Unit) { onDispose { vm.onLeave() } }

    val lineup = remember(community) { ChannelLineup.lineup(community = community) }
    val current = remember(lineup, state.channel?.id) {
        lineup.firstOrNull { it.channel.id == state.channel?.id }
    }
    var lastNumber by remember { mutableIntStateOf(0) }
    var entry by remember { mutableStateOf(ChannelLineup.Entry()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }

    fun tune(slot: ChannelLineup.Slot) {
        current?.let { lastNumber = it.number }
        entry = ChannelLineup.Entry()
        notice = null
        vm.watch(slot.channel)
    }

    fun settle(result: ChannelLineup.Tune) {
        when (result) {
            is ChannelLineup.Tune.Typing -> entry = result.entry
            is ChannelLineup.Tune.Tuned -> tune(result.slot)
            is ChannelLineup.Tune.NoChannel -> {
                entry = ChannelLineup.Entry()
                notice = "Nothing on channel ${result.number}."
            }
        }
    }

    // A half-keyed number has to tune itself — nothing else will.
    LaunchedEffect(entry) {
        if (entry.empty) return@LaunchedEffect
        delay(ChannelLineup.ENTRY_TIMEOUT_MS)
        settle(ChannelLineup.commit(entry, lineup))
    }
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        delay(3_500)
        notice = null
    }

    fun up() = ChannelLineup.next(lineup, current?.number ?: 1)?.let { tune(it) }
    fun down() = ChannelLineup.previous(lineup, current?.number ?: 1)?.let { tune(it) }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .focusRequester(focus)
            .focusable()
            // ⚠️ Consumed only for keys the box actually uses, and only on KeyDown. Swallowing
            // everything would take the text field's own typing away from it, and reacting to both
            // down and up would key every digit twice.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = DIGIT_KEYS[event.key]
                when {
                    digit != null -> {
                        settle(ChannelLineup.key(entry, digit, System.currentTimeMillis(), lineup))
                        true
                    }
                    event.key == Key.DirectionUp -> { up(); true }
                    event.key == Key.DirectionDown -> { down(); true }
                    else -> false
                }
            },
    ) {
        LcarsHeaderBar(
            "Live",
            trailing = current
                ?.takeIf { state.status != LivePlayer.Status.IDLE }
                ?.let { "CH ${ChannelLineup.display(it.number)} · ${it.channel.name.uppercase()}" },
        )

        // ⚠️ The picture sits OUTSIDE the scrolling guide, deliberately. A SwingPanel is a
        // heavyweight AWT component drawn over the Compose surface, and one placed inside a lazy
        // list clips against the scroll rather than with it. Fixed above it, it has nothing to fight.
        if (state.status != LivePlayer.Status.IDLE) {
            LiveStage(vm, Modifier.fillMaxWidth().padding(top = 8.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LcarsButton("CH ▲", ::up)
            LcarsButton("CH ▼", ::down)
            LcarsGhostButton("Last", { ChannelLineup.at(lineup, lastNumber)?.let { tune(it) } })
            if (state.status != LivePlayer.Status.IDLE) {
                LcarsGhostButton("Open in a window", vm::popOut)
                LcarsGhostButton("Stop", vm::stop)
            }
            Keypad { digit ->
                settle(ChannelLineup.key(entry, digit, System.currentTimeMillis(), lineup))
            }
            if (!entry.empty) {
                Text(
                    entry.digits,
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                    fontSize = 22.sp, letterSpacing = 2.sp, color = c.accent,
                )
            }
        }

        val line = notice
            ?: (state.detail?.takeIf { state.status == LivePlayer.Status.ERROR }?.let { "No signal — $it" })
            ?: (state.channel?.name?.let { "Opening $it…" }?.takeIf { state.status == LivePlayer.Status.CONNECTING })
            ?: "Type a channel number, use the arrow keys, or pick one below. Nothing plays until you ask."
        Text(
            line,
            fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 17.sp,
            color = when {
                notice != null || state.status == LivePlayer.Status.ERROR -> c.negative
                state.status == LivePlayer.Status.CONNECTING -> c.accent
                else -> c.ink2
            },
        )

        if (community.isNotEmpty()) {
            LcarsTextField(
                label = "Filter the community directory",
                value = filter,
                onValueChange = { filter = it },
                placeholder = "Name or country · ${community.size} channels, unverified",
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }

        Guide(
            lineup = lineup,
            playing = current?.number.takeIf { state.status != LivePlayer.Status.IDLE },
            filter = filter,
            onPick = ::tune,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp),
        )
    }
}

/** Which physical keys are digits. Written out because [Key] has no numeric mapping of its own. */
private val DIGIT_KEYS: Map<Key, Int> = mapOf(
    Key.Zero to 0, Key.One to 1, Key.Two to 2, Key.Three to 3, Key.Four to 4,
    Key.Five to 5, Key.Six to 6, Key.Seven to 7, Key.Eight to 8, Key.Nine to 9,
    Key.NumPad0 to 0, Key.NumPad1 to 1, Key.NumPad2 to 2, Key.NumPad3 to 3, Key.NumPad4 to 4,
    Key.NumPad5 to 5, Key.NumPad6 to 6, Key.NumPad7 to 7, Key.NumPad8 to 8, Key.NumPad9 to 9,
)

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

/** Ten keys in a row — the desktop has the width for it, so it needs no telephone block. */
@Composable
private fun Keypad(onDigit: (Int) -> Unit) {
    val c = Pulse.colors
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        (0..9).forEach { digit ->
            Text(
                digit.toString(),
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                color = c.ink, textAlign = TextAlign.Center,
                modifier = Modifier
                    .size(width = 28.dp, height = 28.dp)
                    .background(c.raise)
                    .border(1.dp, c.lineSoft)
                    .clickable { onDigit(digit) }
                    .padding(top = 5.dp),
            )
        }
    }
}

/**
 * Every channel at once, as numbers, grouped by band.
 *
 * ⚠️ This is the piece the phone cannot do as well, and it is why the desktop was worth doing in the
 * same slice: a window this wide fits the whole curated lineup without scrolling at all, which is
 * exactly what the owner asked for and what a phone-sized grid can only approximate.
 */
@Composable
private fun Guide(
    lineup: List<ChannelLineup.Slot>,
    playing: Int?,
    filter: String,
    onPick: (ChannelLineup.Slot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Pulse.colors
    val bands = remember(lineup) { ChannelLineup.bands(lineup) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        bands.forEach { (band, slots) ->
            val shown =
                if (band != ChannelLineup.Band.COMMUNITY || filter.isBlank()) slots
                else slots.filter {
                    it.channel.name.contains(filter, ignoreCase = true) ||
                        it.channel.region.contains(filter, ignoreCase = true)
                }
            if (shown.isEmpty()) return@forEach
            item(span = { GridItemSpan(maxLineSpan) }, key = "band-${band.name}") {
                Text(
                    "${band.label.uppercase()} · FROM ${band.first}",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.5.sp,
                    color = c.muted, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }
            items(shown, key = { it.number }) { slot ->
                GuideTile(slot, slot.number == playing) { onPick(slot) }
            }
        }
    }
}

@Composable
private fun GuideTile(slot: ChannelLineup.Slot, on: Boolean, onPick: () -> Unit) {
    val c = Pulse.colors
    val tint = when {
        on -> c.accent
        slot.channel.verification == LiveChannels.Verification.SEGMENT -> c.ink2
        else -> c.amber
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .background(if (on) c.accent else Color.Transparent)
            .border(1.dp, tint.copy(alpha = 0.6f))
            .clickable { onPick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            ChannelLineup.display(slot.number),
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            color = if (on) c.void else c.accent,
        )
        Text(
            slot.channel.name,
            fontFamily = JetBrainsMono, fontSize = 10.sp,
            color = if (on) c.void else tint,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}
