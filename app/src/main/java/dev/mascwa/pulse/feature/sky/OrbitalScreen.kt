package dev.mascwa.pulse.feature.sky

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.orbital.NeoObject
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.SectionBar
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.feature.common.StatTile
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun OrbitalScreen(vm: OrbitalViewModel, onBack: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = Pulse.colors

    PulseScaffold(
        title = "Orbital",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.loading && state.data != null,
            onRefresh = { vm.refresh() },
            modifier = Modifier.padding(innerPadding),
        ) {
            when {
                state.isInitialLoading -> LoadingState()
                state.isError -> ErrorState(state.error ?: "Error", onRetry = { vm.refresh() })
                else -> {
                    val d = state.data
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.stale) item { StaleBanner(true) }

                        // ISS
                        item { SectionBar("International Space Station") }
                        item {
                            val iss = d?.iss
                            NeonPanel(Modifier.fillMaxWidth()) {
                                if (iss == null) {
                                    Text("ISS position unavailable.", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                                } else Column {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        KV("Latitude", "%.2f".format(iss.latitude))
                                        KV("Longitude", "%.2f".format(iss.longitude))
                                    }
                                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        KV("Altitude", "${iss.altitudeKm.toInt()} km")
                                        KV("Speed", "${Formatters.compact(iss.velocityKmh)} km/h")
                                    }
                                }
                            }
                        }

                        // Sun & Moon
                        item { SectionBar("Sun & Moon") }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                StatTile("Sunrise", timeOrDash(d?.sun?.sunriseEpochMs), Modifier.weight(1f))
                                StatTile("Sunset", timeOrDash(d?.sun?.sunsetEpochMs), Modifier.weight(1f))
                            }
                        }
                        item {
                            val moon = d?.moon
                            NeonPanel(Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(moon?.emoji ?: "🌙", fontSize = 40.sp)
                                    Column(Modifier.padding(start = 14.dp)) {
                                        Text(moon?.phaseName ?: "—", style = MaterialTheme.typography.titleMedium, color = c.ink)
                                        Text("${((moon?.illumination ?: 0.0) * 100).toInt()}% illuminated",
                                            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted)
                                    }
                                }
                            }
                        }

                        // Planets
                        item { SectionBar("Planets — visible now") }
                        item {
                            val visible = d?.planets.orEmpty().filter { it.aboveHorizon }.sortedBy { it.magnitude }
                            NeonPanel(Modifier.fillMaxWidth()) {
                                if (visible.isEmpty()) {
                                    Text("No naked-eye planets above the horizon right now.",
                                        style = MaterialTheme.typography.bodyMedium, color = c.muted)
                                } else Column {
                                    visible.forEach { p ->
                                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(p.name, style = MaterialTheme.typography.titleSmall, color = c.ink)
                                            Text(
                                                "alt ${p.altitudeDeg.toInt()}° · ${dev.mascwa.pulse.core.util.Geo.cardinal(p.azimuthDeg)} · mag ${"%.1f".format(p.magnitude)}",
                                                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.accent,
                                            )
                                        }
                                    }
                                    Text("Approximate naked-eye positions (low-precision ephemeris).",
                                        style = MaterialTheme.typography.labelSmall, color = c.muted,
                                        modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }

                        // NEOs
                        item {
                            SectionBar(
                                "Near-Earth objects (today)",
                                trailing = (d?.neoHazardousCount ?: 0).takeIf { it > 0 }?.let { "$it HAZARDOUS" },
                            )
                        }
                        val neos = d?.neos.orEmpty().take(12)
                        if (neos.isEmpty()) {
                            item { Text("No close approaches catalogued today.", style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(4.dp)) }
                        } else {
                            items(neos, key = { it.name }) { neo -> NeoRow(neo) }
                        }
                        item {
                            Text("Sources: wheretheiss.at, sunrise-sunset.org, NASA NeoWs. Moon phase computed offline.",
                                style = MaterialTheme.typography.labelSmall, color = c.muted, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KV(label: String, value: String) {
    val c = Pulse.colors
    Column {
        Text(label, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted)
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = c.ink)
    }
}

@Composable
private fun NeoRow(neo: NeoObject) {
    val c = Pulse.colors
    NeonPanel(Modifier.fillMaxWidth(), borderColor = if (neo.hazardous) c.magenta else c.lineSoft) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(neo.name.removeSurrounding("(", ")"), style = MaterialTheme.typography.titleSmall, color = c.ink)
                if (neo.hazardous) Text("⚠ HAZARD", fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.magenta)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                neo.diameterMetersMax?.let { Mono("⌀ ${it.toInt()} m") }
                neo.missDistanceKm?.let { Mono("miss ${Formatters.compact(it)} km") }
                neo.velocityKmh?.let { Mono("${Formatters.compact(it)} km/h") }
            }
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = JetBrainsMono, fontSize = 10.sp, color = Pulse.colors.ink2)
}

private fun timeOrDash(epochMs: Long?): String {
    if (epochMs == null || epochMs <= 0) return "—"
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
}
