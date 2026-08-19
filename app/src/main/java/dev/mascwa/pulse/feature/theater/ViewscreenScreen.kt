package dev.mascwa.pulse.feature.theater

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.mascwa.pulse.core.telemetry.MediaItem
import dev.mascwa.pulse.core.telemetry.MediaResolution
import dev.mascwa.pulse.core.telemetry.SponsorSegments
import dev.mascwa.pulse.core.telemetry.TheaterModel
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsFillRow
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.feature.common.LcarsIcons
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.media.AudioFloor
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import java.io.File

/**
 * The Theater — browse, search and watch, without leaving the app or knowing anything outside it.
 *
 * The lazy path is the whole path: open → shelves are already filling (what you were watching,
 * videos about today's stories, what your feeds are linking to, curated subjects, what is saved on
 * this device) → tap a card → it plays right here. Typing is only for search; pasting is only for
 * the demoted DIRECT ADDRESS affordance at the bottom, kept because the `play` tool and power users
 * still speak URL.
 *
 * The screen narrates every stage honestly, because most of them can refuse: the resolve step names
 * its reason ([MediaResolution.say]), a refused shelf becomes one sentence instead of a blank row,
 * the skip line says whether the community database was even asked, and the floor's displacement
 * note explains an interrupted radio rather than letting audio die silently.
 *
 * ⚠️ Everything here is CI-compile-gated only — no build machine has ever resolved or played a
 * video. Owner-verify on the Pixel.
 */
@Composable
fun ViewscreenScreen(
    vm: ViewscreenViewModel,
    onBack: (() -> Unit)? = null,
    playAddress: String? = null,
) {
    val c = Pulse.colors
    val context = LocalContext.current
    val input by vm.input.collectAsStateWithLifecycle()
    val resolve by vm.resolve.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val skipNote by vm.skipNote.collectAsStateWithLifecycle()
    val floorNote by AudioFloor.note.collectAsStateWithLifecycle()
    val harvestState by vm.harvest.collectAsStateWithLifecycle()
    val shelves by vm.shelves.collectAsStateWithLifecycle()
    val query by vm.search.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val searchNote by vm.searchNote.collectAsStateWithLifecycle()

    // A `viewscreen?play=` deep-link (a feed's play affordance, a shared address) starts playback
    // once per address, surviving recomposition but re-firing on a genuinely new one.
    LaunchedEffect(playAddress) {
        if (!playAddress.isNullOrBlank()) vm.playAddress(context, playAddress)
    }

    var addressOpen by remember { mutableStateOf(false) }
    val searchActive = query.isNotBlank()

    PulseScaffold(
        title = "Theater",
        onBack = onBack,
    ) { innerPadding ->
        LazyColumn(
            Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
        ) {
            // ---- The player, pinned first whenever something is (or is becoming) on screen ----
            item(key = "player") {
                Column {
                    when (val r = resolve) {
                        ViewscreenViewModel.Resolve.Idle -> {}
                        ViewscreenViewModel.Resolve.Working -> StatusLine("Resolving…", c.amber)
                        is ViewscreenViewModel.Resolve.Refused ->
                            StatusLine(
                                MediaResolution.say(r.reason) +
                                    if (r.detail.isNotBlank()) "  (${r.detail})" else "",
                                c.negative,
                            )
                        is ViewscreenViewModel.Resolve.Ready ->
                            PlayerPanel(vm = vm, ready = r, playback = playback, progress = progress)
                    }
                    // The lines the picture cannot say for itself, in the order they matter.
                    skipNote?.let { StatusLine(it, c.amber) }
                    floorNote?.let { StatusLine(it, c.muted) }
                    playback.detail?.takeIf { playback.status == OnDemandController.Status.ERROR }?.let {
                        StatusLine("Playback failed: $it", c.negative)
                    }
                    HarvestLine(harvestState)
                }
            }

            // ---- Search — the one box that covers everything the shelves don't ----
            item(key = "search") {
                SearchField(
                    query = query,
                    onChange = vm::setSearch,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
            }

            if (searchActive) {
                // ---- Results replace the shelves while a query stands ----
                item(key = "search_status") {
                    when {
                        searching -> StatusLine("Searching…", c.amber)
                        searchNote != null -> StatusLine(searchNote!!, c.negative)
                        results.isEmpty() -> StatusLine("Nothing found for that.", c.muted)
                        else -> {}
                    }
                }
                // Pairs of cards, not a nested lazy grid — a LazyVerticalGrid inside a LazyColumn
                // dies on infinite height constraints; chunked rows are the stable idiom.
                val pairs = results.chunked(2)
                items(pairs.size, key = { "res$it" }) { rowIdx ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        for (item in pairs[rowIdx]) {
                            Box(Modifier.weight(1f)) {
                                TheaterCard(
                                    item,
                                    onClick = { vm.playItem(context, item) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        if (pairs[rowIdx].size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                // ---- The shelves: something to watch with zero typing ----
                if (shelves.continueWatching.isNotEmpty()) {
                    item(key = "continue") {
                        TheaterShelfRow(
                            label = "CONTINUE WATCHING",
                            items = shelves.continueWatching.map { it.toMediaItem() },
                            onPlay = { vm.playItem(context, it) },
                        )
                    }
                }
                items(shelves.rows.size, key = { shelves.rows[it].id }) { idx ->
                    val row = shelves.rows[idx]
                    TheaterShelfRow(
                        label = row.label,
                        items = row.items,
                        onPlay = { vm.playItem(context, it) },
                    )
                }
                if (shelves.loading) {
                    item(key = "loading") { StatusLine("Scanning the archives…", c.amber) }
                } else if (shelves.note != null && shelves.rows.isEmpty()) {
                    item(key = "note") { StatusLine(shelves.note!!, c.negative) }
                } else if (shelves.note != null) {
                    item(key = "note_soft") { StatusLine(shelves.note!!, c.muted) }
                }
                if (shelves.harvested.isNotEmpty()) {
                    item(key = "harvested_hdr") { LcarsHeaderBar("ON THIS DEVICE") }
                    items(shelves.harvested.size, key = { "file" + shelves.harvested[it].name }) { i ->
                        HarvestedRow(
                            file = shelves.harvested[i],
                            onPlay = { vm.playHarvested(context, it) },
                            onDelete = { vm.deleteHarvested(it) },
                        )
                    }
                }
            }

            // ---- The demoted power-user path: a collapsed direct-address affordance ----
            item(key = "address") {
                Column(Modifier.padding(top = 12.dp, bottom = 16.dp)) {
                    Text(
                        if (addressOpen) "▾ DIRECT ADDRESS" else "▸ DIRECT ADDRESS",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                        modifier = Modifier.clickable { addressOpen = !addressOpen }.padding(vertical = 4.dp),
                    )
                    if (addressOpen) {
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
                        Spacer(Modifier.height(6.dp))
                        // The literal "audio only background playback" mode: keeps playing when you
                        // leave, held alive by a mediaPlayback foreground service with a Stop action.
                        LcarsButton(
                            "LISTEN · AUDIO ONLY",
                            onClick = { vm.playFromInput(context, audioOnly = true) },
                        )
                    }
                }
            }
        }
    }
}

/** Ledger entry → display card. Tapping re-resolves the page; the saved position seeks after READY. */
private fun TheaterModel.Viewing.toMediaItem() = MediaItem(
    id = id,
    title = title,
    durationS = durationMs / 1000.0,
    uploader = "",
    thumbnailUrl = thumbnailUrl,
    pageUrl = pageUrl,
)

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    LcarsFrame(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(LcarsIcons.Search, contentDescription = null, tint = c.muted, modifier = Modifier.padding(end = 8.dp))
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = c.ink, fontFamily = JetBrainsMono, fontSize = 13.sp),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            "Search for anything to watch…",
                            fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.muted,
                        )
                    }
                    inner()
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(LcarsIcons.Close, contentDescription = "Clear", tint = c.muted)
                }
            }
        }
    }
}

/** One saved file: play it (works with no network at all) or delete it. */
@Composable
private fun HarvestedRow(file: File, onPlay: (File) -> Unit, onDelete: (File) -> Unit) {
    val c = Pulse.colors
    LcarsFrame(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    file.nameWithoutExtension,
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "%.1f MB · plays offline".format(java.util.Locale.US, file.length() / (1024f * 1024f)),
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                )
            }
            LcarsButton("PLAY", onClick = { onPlay(file) })
            LcarsButton("DELETE", onClick = { onDelete(file) }, color = c.negative)
        }
    }
}

@Composable
private fun HarvestLine(state: dev.mascwa.pulse.data.media.MediaHarvester.Harvest) {
    val c = Pulse.colors
    when (state) {
        dev.mascwa.pulse.data.media.MediaHarvester.Harvest.Idle -> {}
        is dev.mascwa.pulse.data.media.MediaHarvester.Harvest.Working ->
            StatusLine("Harvesting ${state.title}…", c.violet)
        is dev.mascwa.pulse.data.media.MediaHarvester.Harvest.Done ->
            StatusLine("Saved to this device: ${state.file} · ${state.bytes / (1024 * 1024)} MB", c.positive)
        is dev.mascwa.pulse.data.media.MediaHarvester.Harvest.Failed ->
            StatusLine(
                "Harvest failed — " + MediaResolution.say(state.reason) +
                    if (state.detail.isNotBlank()) " (${state.detail})" else "",
                c.negative,
            )
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

    if (playback.audioOnly) {
        // No dark 16:9 rectangle for a session with no picture — a slim badge says what is running
        // and that leaving the screen does not end it.
        Box(
            Modifier.fillMaxWidth().background(c.void).border(1.dp, c.accent.copy(alpha = 0.4f))
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "♪ AUDIO ONLY · keeps playing in the background",
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.amber,
            )
        }
    } else {
        // The picture. The SurfaceView is created once and handed to the controller, which holds
        // it — the player can be rebuilt underneath this composable (a retry does exactly that).
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

    Spacer(Modifier.height(8.dp))

    // The harvester's button form. The gesture form is the same trigger: hold a volume key while
    // this item is on the viewscreen.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        LcarsButton("HARVEST", onClick = { vm.harvestCurrent() }, color = c.violet)
        Text(
            "or hold a volume key",
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
        )
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
