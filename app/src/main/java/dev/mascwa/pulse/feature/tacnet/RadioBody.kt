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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    val c = Pulse.colors
    val tuned = state.tuned
    val context = LocalContext.current
    val favUrls = remember(favorites) { favorites.mapTo(HashSet()) { it.streamUrl } }

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
            }
        }

        // ---- FAVOURITES (starred; persisted) ----
        if (favorites.isNotEmpty()) {
            PipHeader("Favourites")
            favorites.forEach { st ->
                StationRow(st, state, c, isFavorite = true, onFavorite = { vm.toggleFavorite(st) }) { vm.toggle(context, st) }
            }
        }

        // ---- LOCAL signals (region-scoped, on-demand) ----
        PipHeader("Local Signals", trailing = localPlace?.takeIf { localStatus == RadioViewModel.LocalStatus.READY })
        LocalStatusLine(
            status = localStatus,
            count = localStations.size,
            c = c,
            onEnableLocation = { locationPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
            onRetry = { vm.loadLocal() },
        )
        localStations.forEach { st ->
            StationRow(st, state, c, isFavorite = st.streamUrl in favUrls, onFavorite = { vm.toggleFavorite(st) }) { vm.toggle(context, st) }
        }

        // ---- CURATED streams (always available) ----
        PipHeader("Stations")
        vm.curatedStations.forEach { st ->
            StationRow(st, state, c, isFavorite = st.streamUrl in favUrls, onFavorite = { vm.toggleFavorite(st) }) { vm.toggle(context, st) }
        }

        Text(
            "Tap ★ to save a station to Favourites. Local stations via Radio Browser (community-run, free); " +
                "curated streams: SomaFM — free & listener-supported. Playback keeps going in the background " +
                "(a notification with Stop). Needs a connection; nearby stations need location.",
            fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
        )
    }
}

/** One tunable station card — play/pause glyph + name + band tag + a star to favourite it. */
@Composable
private fun StationRow(
    st: RadioStation,
    state: RadioController.RadioState,
    c: NightwirePalette,
    isFavorite: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
) {
    val active = state.tuned?.streamUrl == st.streamUrl
    val onAir = active && state.status == RadioController.Status.ON_AIR
    val tuningThis = active && state.status == RadioController.Status.TUNING
    PipFrame(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(onClick = onClick),
        accent = if (active) c.accent else c.line,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (onAir || tuningThis) "❚❚" else "▶",
                fontFamily = JetBrainsMono, fontSize = 14.sp,
                color = if (active) c.accent else c.muted,
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    st.name, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    color = if (active) c.ink else c.ink2,
                )
                Text(st.band, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.8.sp, color = c.muted)
            }
            Text(
                if (isFavorite) "★" else "☆",
                fontFamily = JetBrainsMono, fontSize = 18.sp,
                color = if (isFavorite) c.amber else c.muted,
                modifier = Modifier.clickable(onClick = onFavorite).padding(start = 8.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
            )
        }
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
