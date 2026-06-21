package dev.mascwa.pulse.feature.tacnet

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.data.radio.RadioStation
import dev.mascwa.pulse.feature.common.PipFrame
import dev.mascwa.pulse.feature.common.PipHeader
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse
import kotlin.math.sin

/** The RADIO feed — a Fallout-style tuner: a now-playing readout + local & curated stations to tune. */
@Composable
fun RadioBody(vm: RadioViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val localStations by vm.localStations.collectAsStateWithLifecycle()
    val localStatus by vm.localStatus.collectAsStateWithLifecycle()
    val localPlace by vm.localPlace.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val searchStatus by vm.searchStatus.collectAsStateWithLifecycle()
    val sleepMinutes by vm.sleepMinutes.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val browseStations by vm.browseStations.collectAsStateWithLifecycle()
    val browseStatus by vm.browseStatus.collectAsStateWithLifecycle()
    val browseCountry by vm.browseCountry.collectAsStateWithLifecycle()
    val c = Pulse.colors
    val tuned = state.tuned
    val context = LocalContext.current
    val favUrls = remember(favorites) { favorites.mapTo(HashSet()) { it.streamUrl } }
    var query by remember { mutableStateOf("") }

    // Look up nearby stations the first time the dial is opened.
    LaunchedEffect(Unit) { vm.loadLocal() }

    val locationPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.loadLocal()
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        PipHeader("Tuner")
        PipFrame(Modifier.fillMaxWidth()) {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val label = when (state.status) {
                        RadioController.Status.ON_AIR -> "▸ ON AIR"
                        RadioController.Status.TUNING -> "··· TUNING"
                        RadioController.Status.ERROR -> "✕ NO SIGNAL"
                        RadioController.Status.IDLE -> "○ STANDBY"
                    }
                    val labelColor = when (state.status) {
                        RadioController.Status.ERROR -> c.amber
                        RadioController.Status.ON_AIR -> c.accent
                        else -> c.muted
                    }
                    Text(label, fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.5.sp, color = labelColor)
                    if (state.status == RadioController.Status.ON_AIR) {
                        SignalBars(c.accent, Modifier.padding(start = 10.dp).size(width = 26.dp, height = 14.dp))
                    }
                }
                Text(
                    tuned?.name ?: "— SELECT A STATION —",
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = c.ink,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    tuned?.band ?: "LOCAL & FREE LISTENER-SUPPORTED STREAMS",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.accent,
                )
                // Live now-playing track (ICY metadata) when the tuned station is on air.
                if (state.status == RadioController.Status.ON_AIR && !nowPlaying.isNullOrBlank()) {
                    Text(
                        "♪ $nowPlaying",
                        fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink2,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                // On failure, surface the reason so the problem is visible, not silent.
                if (state.status == RadioController.Status.ERROR && !state.detail.isNullOrBlank()) {
                    Text(
                        "Couldn't open the stream — ${state.detail}. Try another station.",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                SleepTimerRow(
                    active = sleepMinutes,
                    c = c,
                    onSelect = { vm.setSleep(context, it) },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        // ---- SEARCH any station ----
        PipHeader("Search")
        PipFrame(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⌕", fontFamily = JetBrainsMono, fontSize = 16.sp, color = c.accent)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = c.ink, fontFamily = JetBrainsMono, fontSize = 14.sp),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text("Station name…", fontFamily = JetBrainsMono, fontSize = 14.sp, color = c.muted)
                        }
                        inner()
                    },
                )
                if (query.isNotEmpty() || searchStatus != RadioViewModel.SearchStatus.IDLE) {
                    Text(
                        "✕", fontFamily = JetBrainsMono, fontSize = 15.sp, color = c.muted,
                        modifier = Modifier.clickable { query = ""; vm.clearSearch() }.padding(6.dp),
                    )
                }
            }
        }
        when (searchStatus) {
            RadioViewModel.SearchStatus.SEARCHING ->
                Text("··· SEARCHING", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, modifier = Modifier.padding(top = 4.dp))
            RadioViewModel.SearchStatus.EMPTY ->
                Text("No stations match \"$query\".", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, modifier = Modifier.padding(top = 4.dp))
            RadioViewModel.SearchStatus.ERROR ->
                Text("Search unavailable — check your connection.", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber, modifier = Modifier.padding(top = 4.dp))
            RadioViewModel.SearchStatus.READY ->
                StationStrip(searchResults, state, c, favUrls, nowPlaying, vm::toggleFavorite) { vm.toggle(context, it) }
            RadioViewModel.SearchStatus.IDLE -> Unit
        }

        // ---- FAVOURITES (starred; persisted) ----
        if (favorites.isNotEmpty()) {
            PipHeader("Favourites")
            StationStrip(favorites, state, c, favUrls, nowPlaying, vm::toggleFavorite) { vm.toggle(context, it) }
        }

        // ---- LOCAL signals (geo-sourced, on-demand) ----
        PipHeader("Local Signals", trailing = localPlace?.takeIf { localStatus == RadioViewModel.LocalStatus.READY })
        LocalStatusLine(
            status = localStatus,
            count = localStations.size,
            c = c,
            onEnableLocation = { locationPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
            onRetry = { vm.loadLocal() },
        )
        if (localStations.isNotEmpty()) {
            StationStrip(localStations, state, c, favUrls, nowPlaying, vm::toggleFavorite) { vm.toggle(context, it) }
        }

        // ---- WORLD (browse the planet's stations by country) ----
        PipHeader("World")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            vm.countries.forEach { country ->
                CountryChip(country.label, selected = country.code == browseCountry, c = c) { vm.browse(country) }
            }
        }
        when (browseStatus) {
            RadioViewModel.BrowseStatus.IDLE ->
                Text("Pick a country to scan its airwaves.", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
            RadioViewModel.BrowseStatus.LOADING ->
                Text("Scanning…", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.accent)
            RadioViewModel.BrowseStatus.EMPTY ->
                Text("No stations found there.", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
            RadioViewModel.BrowseStatus.ERROR ->
                Text("Browse unavailable — check your connection.", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber)
            RadioViewModel.BrowseStatus.READY ->
                StationStrip(browseStations, state, c, favUrls, nowPlaying, vm::toggleFavorite) { vm.toggle(context, it) }
        }

        // ---- CURATED streams (always available) ----
        PipHeader("Stations")
        StationStrip(vm.curatedStations, state, c, favUrls, nowPlaying, vm::toggleFavorite) { vm.toggle(context, it) }

        Text(
            "Swipe each rail to browse · tap a card to tune · ★ saves it. Local stations via Radio Browser " +
                "(community-run, free); curated streams: SomaFM. Playback keeps going in the background " +
                "(a notification with Stop). Needs a connection; nearby stations need location.",
            fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
        )
    }
}

/** A Pip-Boy country selector pill for the WORLD browse — bright/filled when selected. */
@Composable
private fun CountryChip(label: String, selected: Boolean, c: NightwirePalette, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) c.accent.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (selected) c.accent else c.line, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            label.uppercase(),
            fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp,
            color = if (selected) c.ink else c.muted,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A vertical Fallout list of stations — canonical banded rows, tap to tune. */
@Composable
private fun StationStrip(
    stations: List<RadioStation>,
    state: RadioController.RadioState,
    c: NightwirePalette,
    favUrls: Set<String>,
    nowPlaying: String?,
    onFavorite: (RadioStation) -> Unit,
    onTune: (RadioStation) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        stations.forEach { st ->
            StationRow(st, state, c, st.streamUrl in favUrls, nowPlaying, { onFavorite(st) }) { onTune(st) }
        }
    }
}

/** A compact sleep-timer selector inside the tuner: OFF · 15 · 30 · 60 min (auto-stops playback). */
@Composable
private fun SleepTimerRow(active: Int?, c: NightwirePalette, onSelect: (Int?) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("☾ SLEEP", fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = c.muted)
        listOf<Pair<String, Int?>>("OFF" to null, "15" to 15, "30" to 30, "60" to 60).forEach { (label, mins) ->
            val on = active == mins
            Text(
                label,
                fontFamily = JetBrainsMono, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                color = if (on) c.void else c.ink2,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) c.accent else c.panel)
                    .border(1.dp, if (on) c.accent else c.line, RoundedCornerShape(6.dp))
                    .clickable { onSelect(mins) }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
    }
}

/** A station as a canonical Fallout banded list row: a status glyph / signal bars, the station name + a
 *  tiny genre/region (or live now-playing) line, and a ★ favourite toggle. Tap the row to tune. Rows
 *  stack gap-free, separated by the bright hairline rule. */
@Composable
private fun StationRow(
    st: RadioStation,
    state: RadioController.RadioState,
    c: NightwirePalette,
    isFavorite: Boolean,
    nowPlaying: String?,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
) {
    val active = state.tuned?.streamUrl == st.streamUrl
    val onAir = active && state.status == RadioController.Status.ON_AIR
    val tuningThis = active && state.status == RadioController.Status.TUNING
    val track = if (onAir && !nowPlaying.isNullOrBlank()) nowPlaying else null
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(c.accent.copy(alpha = if (active) 0.16f else 0.08f))
                drawLine(c.accent.copy(alpha = 0.35f), Offset(0f, size.height), Offset(size.width, size.height), 1.2.dp.toPx())
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
            if (onAir) {
                SignalBars(c.accent, Modifier.size(width = 22.dp, height = 12.dp))
            } else {
                Text(
                    if (tuningThis) "···" else "▶",
                    fontFamily = JetBrainsMono, fontSize = 13.sp, color = if (active) c.accent else c.muted,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                st.name, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                track?.let { "♪ $it" } ?: st.band,
                fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.6.sp,
                color = if (track != null) c.accent else c.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp),
            )
        }
        Text(
            if (isFavorite) "★" else "☆",
            fontFamily = JetBrainsMono, fontSize = 17.sp, color = if (isFavorite) c.amber else c.muted,
            modifier = Modifier.clickable(onClick = onFavorite).padding(start = 12.dp),
        )
    }
}

/** The LOCAL section's status line: scanning / count / "enable location" / retry. */
@Composable
private fun LocalStatusLine(
    status: RadioViewModel.LocalStatus,
    count: Int,
    c: NightwirePalette,
    onEnableLocation: () -> Unit,
    onRetry: () -> Unit,
) {
    when (status) {
        RadioViewModel.LocalStatus.LOADING ->
            Text("··· SCANNING THE DIAL NEAR YOU", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
        RadioViewModel.LocalStatus.READY ->
            Text("$count STATIONS NEARBY", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.accent)
        RadioViewModel.LocalStatus.EMPTY ->
            Text(
                "No local stations found — try the curated streams below.",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
            )
        RadioViewModel.LocalStatus.NO_LOCATION ->
            Text(
                "▸ ENABLE LOCATION TO FIND NEARBY STATIONS",
                fontFamily = JetBrainsMono, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.amber,
                modifier = Modifier.clickable(onClick = onEnableLocation).padding(vertical = 2.dp),
            )
        RadioViewModel.LocalStatus.ERROR ->
            Text(
                "⟳ COULDN'T LOAD LOCAL STATIONS — RETRY",
                fontFamily = JetBrainsMono, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.amber,
                modifier = Modifier.clickable(onClick = onRetry).padding(vertical = 2.dp),
            )
        RadioViewModel.LocalStatus.IDLE -> Unit
    }
}

/** A small animated equaliser, drawn analytically (no retained buffers) while a station is on air. */
@Composable
private fun SignalBars(color: Color, modifier: Modifier) {
    val t by rememberInfiniteTransition(label = "eq").animateFloat(
        0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Restart), label = "eqp",
    )
    Canvas(modifier.height(14.dp)) {
        val bars = 4
        val gap = size.width * 0.12f
        val bw = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val phase = t * 6.2831855f + i * 1.3f
            val h = (0.35f + 0.65f * (0.5f + 0.5f * sin(phase))) * size.height
            val x = i * (bw + gap)
            drawRect(color, topLeft = Offset(x, size.height - h), size = androidx.compose.ui.geometry.Size(bw, h))
        }
    }
}
