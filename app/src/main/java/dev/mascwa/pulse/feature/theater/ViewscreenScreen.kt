package dev.mascwa.pulse.feature.theater

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.MediaResolution
import dev.mascwa.pulse.core.telemetry.SponsorSegments
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsFillRow
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.feature.common.LcarsIcons
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.media.AudioFloor
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * The viewscreen — paste an address, watch or listen to what it points at.
 *
 * The screen narrates every stage honestly, because most of them can refuse: the resolve step names
 * its reason ([MediaResolution.say]), the skip line says whether the community database was even
 * asked, and the floor's displacement note explains an interrupted radio rather than letting audio
 * die silently.
 *
 * ⚠️ Everything here is CI-compile-gated only — no build machine has ever resolved or played a
 * video. Owner-verify on the Pixel.
 */
@Composable
fun ViewscreenScreen(vm: ViewscreenViewModel, onBack: (() -> Unit)? = null) {
    val c = Pulse.colors
    val context = LocalContext.current
    val input by vm.input.collectAsStateWithLifecycle()
    val resolve by vm.resolve.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val skipNote by vm.skipNote.collectAsStateWithLifecycle()
    val floorNote by AudioFloor.note.collectAsStateWithLifecycle()

    PulseScaffold(
        title = "Viewscreen",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(LcarsIcons.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            LcarsHeaderBar("Address")
            LcarsFrame(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = input,
                        onValueChange = vm::setInput,
                        singleLine = true,
                        textStyle = TextStyle(color = c.ink, fontFamily = JetBrainsMono, fontSize = 13.sp),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { vm.playFromInput(context) }),
                        modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                        decorationBox = { inner ->
                            if (input.isEmpty()) {
                                Text(
                                    "Paste a video or track address…",
                                    fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.muted,
                                )
                            }
                            inner()
                        },
                    )
                    LcarsButton("PLAY", onClick = { vm.playFromInput(context) })
                }
            }

            Spacer(Modifier.height(10.dp))

            when (val r = resolve) {
                ViewscreenViewModel.Resolve.Idle -> {}
                ViewscreenViewModel.Resolve.Working -> StatusLine("Resolving…", c.amber)
                is ViewscreenViewModel.Resolve.Refused ->
                    StatusLine(
                        MediaResolution.say(r.reason) + if (r.detail.isNotBlank()) "  (${r.detail})" else "",
                        c.negative,
                    )
                is ViewscreenViewModel.Resolve.Ready -> {
                    PlayerPanel(
                        vm = vm,
                        ready = r,
                        playback = playback,
                        progress = progress,
                    )
                }
            }

            // The lines the picture cannot say for itself, in the order they matter.
            skipNote?.let { StatusLine(it, c.amber) }
            floorNote?.let { StatusLine(it, c.muted) }
            playback.detail?.takeIf { playback.status == OnDemandController.Status.ERROR }?.let {
                StatusLine("Playback failed: $it", c.negative)
            }
        }
    }
}

@Composable
private fun PlayerPanel(
    vm: ViewscreenViewModel,
    ready: ViewscreenViewModel.Resolve.Ready,
    playback: OnDemandController.OnDemandState,
    progress: OnDemandController.Progress,
) {
    val c = Pulse.colors
    val context = LocalContext.current

    // The picture. The SurfaceView is created once and handed to the controller, which holds it —
    // the player can be rebuilt underneath this composable (a retry does exactly that).
    val view = remember { SurfaceView(context) }
    DisposableEffect(view) {
        OnDemandController.attach(view)
        onDispose { OnDemandController.detach(view) }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(c.void)
            .border(1.dp, c.accent.copy(alpha = 0.4f)),
    ) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        if (playback.status == OnDemandController.Status.CONNECTING) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("WORKING…", fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.amber)
            }
        }
    }

    Spacer(Modifier.height(6.dp))

    // Title, then the transport. The skip readout says what the database held for THIS video —
    // "3 skips queued" or that skipping is off — so silence is never ambiguous.
    Text(
        ready.item.title.ifBlank { "Untitled" },
        fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
    val meta = buildList {
        if (ready.item.uploader.isNotBlank()) add(ready.item.uploader)
        if (ready.item.durationS > 0) add(clock((ready.item.durationS * 1000).toLong()))
        add(
            when {
                !ready.skippingOn -> "skipping off"
                ready.segments.isEmpty() -> "no flagged segments"
                else -> {
                    val total = SponsorSegments.totalSkippedS(ready.segments).toInt()
                    "${ready.segments.size} skips queued · ${total}s"
                }
            },
        )
    }
    Text(meta.joinToString(" · "), fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)

    Spacer(Modifier.height(8.dp))

    // Progress. A live-ish item with no known duration draws an empty bar rather than inventing one.
    val durMs = progress.durationMs
    val fraction = if (durMs > 0) (progress.positionMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
    LcarsFillRow(
        segments = listOf(fraction to c.accent, (1f - fraction) to c.raise),
        modifier = Modifier.fillMaxWidth().height(6.dp),
        gap = 1.5.dp,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(clock(progress.positionMs), fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
        if (durMs > 0) {
            Text(clock(durMs), fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
        }
    }

    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LcarsButton("−10s", onClick = { vm.seekBy(-10_000) })
        when (playback.status) {
            OnDemandController.Status.PLAYING -> LcarsButton("PAUSE", onClick = { vm.pause() })
            OnDemandController.Status.PAUSED -> LcarsButton("RESUME", onClick = { vm.resume() })
            else -> LcarsButton("PAUSE", onClick = {}, enabled = false)
        }
        LcarsButton("+30s", onClick = { vm.seekBy(30_000) })
        LcarsButton("STOP", onClick = { vm.stop(context) }, color = c.negative)
    }

    Spacer(Modifier.height(10.dp))
}

@Composable
private fun StatusLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        fontFamily = JetBrainsMono, fontSize = 11.sp, color = color,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/**
 * mm:ss, or h:mm:ss past an hour.
 *
 * Locale.US, the recurring rule: this string is digits and colons, and a default-locale `format`
 * can render digits themselves differently under some locales.
 */
private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.US, h, m, sec)
    else "%d:%02d".format(java.util.Locale.US, m, sec)
}
