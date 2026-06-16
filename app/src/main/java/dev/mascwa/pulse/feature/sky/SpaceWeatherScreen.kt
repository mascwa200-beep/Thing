package dev.mascwa.pulse.feature.sky

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.space.SpaceWeather
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.LineChart
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.SectionBar
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.feature.common.StatTile
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun SpaceWeatherScreen(vm: SpaceWeatherViewModel, onBack: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = Pulse.colors

    PulseScaffold(
        title = "Space Weather",
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
                    val sw = state.data
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.stale) item { StaleBanner(true) }
                        item {
                            val kp = sw?.kp
                            val storm = sw?.stormLevel ?: "—"
                            val stormy = (kp ?: 0.0) >= 5
                            NeonPanel(Modifier.fillMaxWidth(), borderColor = if (stormy) c.magenta else c.lineSoft, corners = true) {
                                Column {
                                    Text("PLANETARY K-INDEX", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
                                    Text(
                                        kp?.let { "%.1f".format(it) } ?: "—",
                                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 44.sp,
                                        color = if (stormy) c.magenta else c.accent,
                                    )
                                    Text("Storm level: $storm", style = MaterialTheme.typography.bodyMedium,
                                        color = if (stormy) c.magenta else c.ink2)
                                    if ((sw?.kpSeries?.size ?: 0) >= 2) {
                                        LineChart(sw!!.kpSeries, Modifier.fillMaxWidth().height(70.dp).padding(top = 10.dp))
                                    }
                                }
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                StatTile("Solar wind", sw?.solarWindSpeed?.let { "${it.toInt()} km/s" } ?: "—",
                                    Modifier.weight(1f))
                                StatTile("Bz (IMF)", sw?.bz?.let { "%+.1f nT".format(it) } ?: "—",
                                    Modifier.weight(1f),
                                    valueColor = if ((sw?.bz ?: 0.0) < -5) c.magenta else c.ink)
                            }
                        }
                        item {
                            val pct = sw?.auroraProbabilityPct
                            val bright = (pct ?: 0) >= 25
                            NeonPanel(Modifier.fillMaxWidth(), borderColor = if (bright) c.violet else c.lineSoft) {
                                Column {
                                    Text("AURORA CHANCE", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
                                    Text(sw?.auroraChance ?: "—", style = MaterialTheme.typography.titleMedium, color = c.sky)
                                    if (pct != null) {
                                        Text(
                                            "$pct% overhead probability at your location",
                                            fontFamily = JetBrainsMono, fontSize = 11.sp,
                                            color = if (bright) c.violet else c.ink2,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                    } else {
                                        Text(
                                            "Grant location for the OVATION probability here.",
                                            fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                        item { SectionBar("Active alerts") }
                        val alerts = sw?.alerts.orEmpty()
                        if (alerts.isEmpty()) {
                            item {
                                Text("No active space-weather alerts.", style = MaterialTheme.typography.bodyMedium,
                                    color = c.muted, modifier = Modifier.padding(4.dp))
                            }
                        } else {
                            items(alerts, key = { it.title + it.issued }) { a ->
                                NeonPanel(Modifier.fillMaxWidth()) {
                                    Column {
                                        Text(a.title, style = MaterialTheme.typography.titleSmall, color = c.amber)
                                        if (a.issued.isNotBlank())
                                            Text(a.issued, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted)
                                        Text(a.message, style = MaterialTheme.typography.bodySmall, color = c.ink2,
                                            modifier = Modifier.padding(top = 6.dp))
                                    }
                                }
                            }
                        }
                        item {
                            Text("Source: NOAA Space Weather Prediction Center (keyless).",
                                style = MaterialTheme.typography.labelSmall, color = c.muted,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
    }
}
