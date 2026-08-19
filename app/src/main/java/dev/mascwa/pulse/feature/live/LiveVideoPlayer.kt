package dev.mascwa.pulse.feature.live

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.core.connectivity.LocalIsMetered
import dev.mascwa.pulse.core.telemetry.ChannelLineup
import dev.mascwa.pulse.core.telemetry.LiveChannels
import dev.mascwa.pulse.core.telemetry.LiveChannels.LiveChannel
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.lcarsBlockShape
import dev.mascwa.pulse.feature.media.AudioFloor
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import kotlinx.coroutines.delay

/**
 * Live television as a cable box: every channel has a number, and you change channel by number.
 *
 * This replaced a horizontally scrolling rail of channel names, which was the right shape for five
 * channels and the wrong one for forty — and hopeless for the ~660 the opt-in community catalogue
 * adds. The fix is not a better list. **A cable box does not ask you to read a list at all:** you
 * press channel-up, or you key the digits, and the number you learned last week still works.
 *
 * Three ways to reach a channel, in the order a viewer actually uses them:
 *
 *  - **CH▲ / CH▼** — walk the lineup, wrapping at both ends.
 *  - **The keypad** — key `7`, key `1`·`2`. Commits the instant no further digit could reach
 *    anything, so single-digit channels are instant rather than waiting out the timeout.
 *  - **GUIDE** — every channel at once as numbered tiles grouped by band, drawn over the picture
 *    while it keeps playing, exactly as a real guide does.
 *
 * Plus **LAST**, the jump-back button every remote has had for thirty years.
 *
 * Self-contained on purpose — the News tab and the breaking-news takeover both host this, and a
 * panel each screen assembles from parts is a panel the two will drift apart on.
 *
 * ⚠️ **Nothing plays until it is tapped**, on every host including the takeover. A full-screen
 * interruption that opens on the lock screen with audio already running is hostile, and "auto-play
 * on Wi-Fi" would make the behaviour differ silently by connection.
 */
@Composable
fun LiveVideoPlayer(
    modifier: Modifier = Modifier,
    channels: List<LiveChannel> = LiveChannels.CURATED,
) {
    val c = Pulse.colors
    val context = LocalContext.current
    val state by LiveVideoController.state.collectAsStateWithLifecycle()
    val dataRate by LiveVideoController.dataRate.collectAsStateWithLifecycle()
    val displaced by AudioFloor.note.collectAsStateWithLifecycle()
    val metered = LocalIsMetered.current

    // The opt-in community catalogue, loaded here rather than by each host — this panel is
    // deliberately self-contained so the News tab and the takeover cannot drift apart, and that
    // applies to what is on offer as much as to how it looks.
    var community by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    LaunchedEffect(Unit) {
        val container = (context.applicationContext as? PulseApplication)?.container ?: return@LaunchedEffect
        // Read the switch before touching the network. Off means no fetch at all, not a fetch whose
        // result is then discarded.
        if (!container.settingsRepository.current().communityChannels) return@LaunchedEffect
        community = runCatching { container.liveCatalogRepository.channels() }.getOrDefault(emptyList())
    }

    val lineup = remember(channels, community) { ChannelLineup.lineup(channels, community) }

    if (lineup.isEmpty()) {
        Text(
            "No live channel is available.",
            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
            modifier = modifier.padding(12.dp),
        )
        return
    }

    // The panel owns the playback lifetime: arriving costs nothing, leaving stops the stream.
    //
    // ⚠️ **Start, not dispose.** Locking the screen or pressing home does NOT dispose a composable —
    // the Activity stops and the composition survives — so a plain DisposableEffect would have left
    // the stream running with the screen off: mobile data burning on pixels nobody can see, and the
    // channel's audio still playing with no notification and no way to stop it short of coming back
    // into the app. That is precisely the state the radio's foreground service exists to make
    // legitimate, and video has no such service by design.
    LifecycleStartEffect(Unit) {
        onStopOrDispose { LiveVideoController.stop(context) }
    }

    // ── the box's own state ─────────────────────────────────────────────────────────────────────
    val current = remember(lineup, state.channel?.id) {
        lineup.firstOrNull { it.channel.id == state.channel?.id }
    }
    var lastNumber by remember { mutableIntStateOf(0) }
    var entry by remember { mutableStateOf(ChannelLineup.Entry()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var guideOpen by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    // Bumped on every tune so the banner reappears even when you land back on the same channel.
    var bannerTick by remember { mutableIntStateOf(0) }
    var bannerShown by remember { mutableStateOf(false) }

    fun tune(slot: ChannelLineup.Slot) {
        current?.let { lastNumber = it.number }
        entry = ChannelLineup.Entry()
        notice = null
        guideOpen = false
        bannerTick++
        bannerShown = true
        LiveVideoController.play(context, slot.channel)
    }

    fun settle(result: ChannelLineup.Tune) {
        when (result) {
            is ChannelLineup.Tune.Typing -> entry = result.entry
            is ChannelLineup.Tune.Tuned -> tune(result.slot)
            is ChannelLineup.Tune.NoChannel -> {
                entry = ChannelLineup.Entry()
                notice = "NO CHANNEL ${result.number}"
            }
        }
    }

    // ⚠️ A half-keyed number has to tune itself — nothing else will. Keyed on `entry`, so every
    // fresh digit restarts the wait, which is what makes `1` then `2` mean twelve rather than one.
    LaunchedEffect(entry) {
        if (entry.empty) return@LaunchedEffect
        delay(ChannelLineup.ENTRY_TIMEOUT_MS)
        settle(ChannelLineup.commit(entry, lineup))
    }

    LaunchedEffect(bannerTick) {
        if (!bannerShown) return@LaunchedEffect
        delay(BANNER_MS)
        bannerShown = false
    }

    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        delay(BANNER_MS)
        notice = null
    }

    // ⚠️ **Exactly one [TvScreen] exists at a time, and that is not a style choice.** It owns the
    // `SurfaceView`, so rendering it in both the inline column and the fullscreen dialog would put
    // two of them on one player: the second `attach` would win and the first would be a black
    // rectangle nobody could get rid of. Swapping instead means the outgoing one is disposed while
    // the incoming one attaches, in whatever order Compose likes — which is precisely the race
    // [LiveVideoController.detach]'s identity guard was written for.
    // Composable LAMBDAS rather than local composable functions: the lambda form is unambiguously
    // legal and costs nothing, and whether the compiler plugin accepts a local `@Composable fun` is
    // not something worth finding out from a CI failure.
    val screen: @Composable (Modifier) -> Unit = { boxModifier ->
        TvScreen(
            modifier = boxModifier,
            state = state,
            slot = current,
            entry = entry,
            notice = notice,
            bannerShown = bannerShown,
            guideOpen = guideOpen,
            lineup = lineup,
            onTune = { tune(it) },
            onCloseGuide = { guideOpen = false },
            onToggleFullscreen = { fullscreen = !fullscreen },
            onRetry = { state.channel?.let { LiveVideoController.play(context, it) } },
        )
    }

    val remoteBar: @Composable (Boolean) -> Unit = { compact ->
        Remote(
            compact = compact,
            playing = state.status == LiveVideoController.Status.PLAYING,
            onUp = { ChannelLineup.next(lineup, current?.number ?: 1)?.let { tune(it) } },
            onDown = { ChannelLineup.previous(lineup, current?.number ?: 1)?.let { tune(it) } },
            onLast = { ChannelLineup.at(lineup, lastNumber)?.let { tune(it) } },
            onGuide = { guideOpen = !guideOpen },
            onFullscreen = { fullscreen = !fullscreen },
            onStop = { LiveVideoController.stop(context) },
        )
    }

    if (fullscreen) {
        FullscreenTv(
            onLeave = { fullscreen = false },
            remote = { remoteBar(true) },
            content = { screen(Modifier.fillMaxSize()) },
        )
    }

    Box(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // While the dialog has it, this slot holds the space and nothing else — see above.
            if (fullscreen) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(c.void)
                        .border(1.dp, c.accent.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "PLAYING FULL SCREEN",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 2.sp,
                        color = c.muted,
                    )
                }
            } else {
                screen(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            }

            remoteBar(false)

            Keypad { digit ->
                settle(ChannelLineup.key(entry, digit, System.currentTimeMillis(), lineup))
            }

            current?.let { slot ->
                Text(
                    "CH ${ChannelLineup.display(slot.number)} · ${LiveChannels.describe(slot.channel)}".uppercase(),
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
}

/** How long the channel banner and a "no channel" notice stay up. */
private const val BANNER_MS = 3_500L

/**
 * The picture, everything drawn over it, and the guide.
 *
 * The SurfaceView is created once and handed to the controller, which holds it — the player can be
 * rebuilt underneath this composable (a retry does exactly that) and has to find its way back to
 * the same view.
 */
@Composable
private fun TvScreen(
    modifier: Modifier,
    state: LiveVideoController.LiveState,
    slot: ChannelLineup.Slot?,
    entry: ChannelLineup.Entry,
    notice: String?,
    bannerShown: Boolean,
    guideOpen: Boolean,
    lineup: List<ChannelLineup.Slot>,
    onTune: (ChannelLineup.Slot) -> Unit,
    onCloseGuide: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onRetry: () -> Unit,
) {
    val c = Pulse.colors
    val context = LocalContext.current
    val view = remember { SurfaceView(context) }
    DisposableEffect(view) {
        LiveVideoController.attach(view)
        onDispose { LiveVideoController.detach(view) }
    }

    Box(
        modifier
            .background(c.void)
            .border(1.dp, c.accent.copy(alpha = 0.4f))
            .clickable { onToggleFullscreen() },
    ) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())

        // What the picture cannot say for itself.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (state.status) {
                LiveVideoController.Status.IDLE -> Text(
                    "PRESS CH▲ OR KEY A CHANNEL",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 2.sp, color = c.muted,
                )
                LiveVideoController.Status.CONNECTING -> Text(
                    (state.detail ?: "TUNING ${state.channel?.name.orEmpty()}").uppercase(),
                    fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 2.sp, color = c.accent,
                )
                // The error carries the player's own code name, so a dead stream is diagnosable
                // rather than just "it didn't work".
                LiveVideoController.Status.ERROR -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "NO SIGNAL · ${state.detail.orEmpty()}".trim().uppercase(),
                        fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp,
                        color = c.negative, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    LcarsButton("TRY AGAIN", onRetry, color = c.negative)
                }
                // Nothing over the picture once there is a picture.
                LiveVideoController.Status.PLAYING -> Unit
            }
        }

        // The banner a box throws up when you land somewhere, then takes away.
        if (bannerShown && slot != null) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(lcarsBlockShape(10.dp, LcarsCorner.TopStart))
                    .background(c.accent)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    ChannelLineup.display(slot.number),
                    fontFamily = ChakraPetch, fontSize = 20.sp, letterSpacing = 1.sp, color = c.void,
                )
                Text(
                    slot.channel.name.uppercase(),
                    fontFamily = ChakraPetch, fontSize = 13.sp, letterSpacing = 1.5.sp, color = c.void,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Digits as they are keyed — the big translucent number every box puts in the corner.
        if (!entry.empty) {
            Text(
                entry.digits,
                fontFamily = ChakraPetch, fontSize = 40.sp, letterSpacing = 3.sp, color = c.accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(14.dp),
            )
        }

        notice?.let {
            Text(
                it,
                fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.5.sp, color = c.negative,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .background(c.void.copy(alpha = 0.85f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        if (guideOpen) {
            Guide(
                lineup = lineup,
                playing = slot?.number,
                onPick = onTune,
                onClose = onCloseGuide,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Fullscreen, in a Dialog rather than by growing in place.
 *
 * ⚠️ **The layout swap alone cannot do this, and that is worth stating.** This panel is rendered
 * inside the News scaffold's content slot with the scaffold's own padding applied to it, so nothing
 * it does to its own modifiers can reach the edges of the display. A Dialog with
 * `usePlatformDefaultWidth = false` gets its own window and can.
 *
 * ⚠️ The cost is that the `SurfaceView` changes parent when this opens. That is safe here for a
 * reason worth checking rather than assuming: [LiveVideoController.attach] holds the view and calls
 * `setVideoSurfaceView` once, and [LiveVideoController.detach] is identity-guarded — so re-parenting
 * fires neither, and ExoPlayer's own `SurfaceHolder.Callback` handles the surface being destroyed
 * and recreated. Expect a black frame across the transition; expect nothing worse.
 *
 * ⚠️ Orientation and the system bars are restored in `onDispose`, not on the way out. Leaving the
 * tab, being killed, or any other route out of this composition all have to put the phone back the
 * way they found it — otherwise the app is stranded sideways with no bars and no way to say so.
 */
@Composable
private fun FullscreenTv(
    onLeave: () -> Unit,
    remote: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Dialog(
        onDismissRequest = onLeave,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // ⚠️ **The dialog's own window, not the Activity's.** A Dialog gets its own window, so
        // hiding the system bars on the Activity's would leave this one's bars exactly where they
        // were — the bug would be invisible in code and obvious on the device. `DialogWindowProvider`
        // is the documented way to reach it from inside the dialog's composition.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            val controller = dialogWindow?.let {
                WindowCompat.setDecorFitsSystemWindows(it, false)
                WindowInsetsControllerCompat(it, it.decorView)
            }
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            content()
            Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) { remote() }
        }
    }
}

/** The buttons every remote has. */
@Composable
private fun Remote(
    compact: Boolean,
    playing: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLast: () -> Unit,
    onGuide: () -> Unit,
    onFullscreen: () -> Unit,
    onStop: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LcarsButton("CH ▲", onUp)
        LcarsButton("CH ▼", onDown, corner = LcarsCorner.BottomStart)
        LcarsButton("LAST", onLast, color = Pulse.colors.ink2)
        LcarsButton("GUIDE", onGuide, color = Pulse.colors.amber)
        if (compact) {
            LcarsButton("EXIT", onFullscreen, color = Pulse.colors.ink2)
        } else {
            LcarsButton("FULL", onFullscreen, color = Pulse.colors.ink2)
        }
        if (playing) LcarsButton("STOP", onStop, color = Pulse.colors.negative)
    }
}

/**
 * The number pad.
 *
 * Ten keys and nothing else: no OK, no clear. An entry that cannot grow tunes itself immediately
 * and one that can tunes itself after the timeout, so there is nothing for a confirm key to do —
 * and a key that does nothing on most presses is worse than an absent one.
 */
@Composable
private fun Keypad(onDigit: (Int) -> Unit) {
    val rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(0))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { digit -> Key(digit, onDigit) }
            }
        }
    }
}

@Composable
private fun Key(digit: Int, onDigit: (Int) -> Unit) {
    val c = Pulse.colors
    Text(
        digit.toString(),
        fontFamily = ChakraPetch, fontSize = 18.sp, letterSpacing = 1.sp,
        color = c.ink, textAlign = TextAlign.Center,
        modifier = Modifier
            .size(width = 54.dp, height = 40.dp)
            .clip(lcarsBlockShape(8.dp, LcarsCorner.TopStart))
            .background(c.raise)
            .border(1.dp, c.lineSoft, lcarsBlockShape(8.dp, LcarsCorner.TopStart))
            .clickable { onDigit(digit) }
            .padding(top = 9.dp),
    )
}

/**
 * The guide: every channel at once, as numbers.
 *
 * ⚠️ Drawn OVER the picture, which keeps playing behind it — that is what a guide does, and it also
 * means the `SurfaceView` never leaves the composition, so opening the guide cannot cost a
 * re-attach. Grouped by band with the band's own name as a header, so "the African ones" is a place
 * on the page rather than a search.
 *
 * ⚠️ The community band is filtered rather than listed. Six hundred tiles is the scrolling rail
 * this whole screen exists to replace, in a different shape.
 */
@Composable
private fun Guide(
    lineup: List<ChannelLineup.Slot>,
    playing: Int?,
    onPick: (ChannelLineup.Slot) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Pulse.colors
    var filter by remember { mutableStateOf("") }
    val bands = remember(lineup) { ChannelLineup.bands(lineup) }

    Box(modifier.background(c.void.copy(alpha = 0.96f))) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "GUIDE · ${lineup.size} CHANNELS",
                    fontFamily = ChakraPetch, fontSize = 13.sp, letterSpacing = 2.sp, color = c.accent,
                )
                LcarsButton("CLOSE", onClose, color = c.ink2)
            }

            val community = bands.firstOrNull { it.first == ChannelLineup.Band.COMMUNITY }?.second.orEmpty()
            if (community.isNotEmpty()) {
                BasicTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink),
                    cursorBrush = SolidColor(c.accent),
                    decorationBox = { inner ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(c.raise)
                                .border(1.dp, c.lineSoft)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            if (filter.isEmpty()) {
                                Text(
                                    "Filter the ${community.size} community channels",
                                    fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted,
                                )
                            }
                            inner()
                        }
                    },
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
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
                            "${band.label.uppercase()} · ${band.first}",
                            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.5.sp,
                            color = c.muted, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                        )
                    }
                    items(shown, key = { it.number }) { slot ->
                        GuideTile(slot, slot.number == playing) { onPick(slot) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideTile(slot: ChannelLineup.Slot, on: Boolean, onPick: () -> Unit) {
    val c = Pulse.colors
    // An unconfirmed channel is drawn as one: it may well work, and it may well not.
    val tint = when {
        on -> c.accent
        slot.channel.verification == LiveChannels.Verification.SEGMENT -> c.ink2
        else -> c.muted
    }
    val shape = lcarsBlockShape(8.dp, LcarsCorner.TopStart)
    Column(
        Modifier
            .clip(shape)
            .background(if (on) c.accent else Color.Transparent)
            .border(1.dp, tint.copy(alpha = 0.6f), shape)
            .clickable { onPick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .heightIn(min = 34.dp),
    ) {
        Text(
            ChannelLineup.display(slot.number),
            fontFamily = ChakraPetch, fontSize = 14.sp, letterSpacing = 1.sp,
            color = if (on) c.void else c.accent,
        )
        Text(
            slot.channel.name,
            fontFamily = JetBrainsMono, fontSize = 9.sp,
            color = if (on) c.void else tint,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The Activity behind a composable's context, or null.
 *
 * Written here rather than imported because the app has no such helper — checked before adding one,
 * since a second copy of a utility is a mistake this repository has corrected repeatedly.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
