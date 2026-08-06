package dev.mascwa.pulse.feature.sky

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import dev.mascwa.pulse.feature.common.LcarsIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.Explainer
import dev.mascwa.pulse.core.telemetry.SpaceWeatherExplainers
import dev.mascwa.pulse.data.space.SpaceWeather
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.ExplainerDialog
import dev.mascwa.pulse.feature.common.LineChart
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.feature.common.LcarsStatBlock
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun SpaceWeatherScreen(vm: SpaceWeatherViewModel, onBack: (() -> Unit)? = null) {
    PulseScaffold(
        title = "Space Weather",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(LcarsIcons.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        SpaceWeatherBody(vm, Modifier.padding(innerPadding))
    }
}

/** The space-weather feed body, scaffold-free for hosting as an LCARS sub-tab. */
@Composable
fun SpaceWeatherBody(vm: SpaceWeatherViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = Pulse.colors
    // Tap any metric → plain-language explanation (title + Explainer list).
    var explainer by remember { mutableStateOf<Pair<String, List<Explainer>>?>(null) }

    PullToRefreshBox(
        isRefreshing = state.loading && state.data != null,
        onRefresh = { vm.refresh() },
        modifier = modifier,
    ) {
        when {
            state.isInitialLoading -> LoadingState()
            state.isError -> ErrorState(state.error ?: "Error", onRetry = { vm.refresh() })
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                spaceWeatherSections(state.data, state.stale, c) { title, items -> explainer = title to items }
            }
        }
    }

    explainer?.let { (title, items) ->
        ExplainerDialog(title, items, onDismiss = { explainer = null })
    }
}

/** Space-weather readouts as LazyColumn sections so they can be mixed into the combined DATA feed.
 *  Tapping a metric calls [onExplain] with the title + Explainer list (the host owns the dialog). */
fun LazyListScope.spaceWeatherSections(
    sw: SpaceWeather?,
    stale: Boolean,
    c: NightwirePalette,
    onExplain: (String, List<Explainer>) -> Unit,
) {
    if (stale) item { StaleBanner(true) }
    item {
        Text(
            "Tap any metric below for a plain-English explanation.",
            fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
        )
    }
    item {
        val kp = sw?.kp
        val storm = sw?.stormLevel ?: "—"
        val stormy = (kp ?: 0.0) >= 5
        LcarsFrame(
            Modifier.fillMaxWidth().clickable(enabled = sw != null) {
                onExplain("Planetary K-index", buildList {
                    kp?.let { add(SpaceWeatherExplainers.kp(it)) }
                    add(SpaceWeatherExplainers.stormScale(storm))
                })
            },
            accent = if (stormy) c.magenta else c.accent,
        ) {
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
            LcarsStatBlock("Solar wind", sw?.solarWindSpeed?.let { "${it.toInt()} km/s" } ?: "—",
                Modifier.weight(1f).clickable(enabled = sw?.solarWindSpeed != null) {
                    onExplain("Solar wind", listOf(SpaceWeatherExplainers.solarWind(sw!!.solarWindSpeed!!)))
                })
            LcarsStatBlock("Bz (IMF)", sw?.bz?.let { "%+.1f nT".format(it) } ?: "—",
                Modifier.weight(1f).clickable(enabled = sw?.bz != null) {
                    onExplain("Bz (IMF)", listOf(SpaceWeatherExplainers.bz(sw!!.bz!!)))
                },
                valueColor = if ((sw?.bz ?: 0.0) < -5) c.magenta else c.ink)
        }
    }
    item {
        val pct = sw?.auroraProbabilityPct
        val bright = (pct ?: 0) >= 25
        LcarsFrame(
            Modifier.fillMaxWidth().clickable(enabled = pct != null) {
                onExplain("Aurora chance", listOf(SpaceWeatherExplainers.aurora(pct!!)))
            },
            accent = if (bright) c.violet else c.accent,
        ) {
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
    item { LcarsHeaderBar("Active alerts") }
    val alerts = sw?.alerts.orEmpty()
    if (alerts.isEmpty()) {
        item {
            Text("No active space-weather alerts.", style = MaterialTheme.typography.bodyMedium,
                color = c.muted, modifier = Modifier.padding(4.dp))
        }
    } else {
        items(alerts.distinctBy { it.title + it.issued }, key = { it.title + it.issued }) { a ->
            LcarsFrame(Modifier.fillMaxWidth()) {
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
