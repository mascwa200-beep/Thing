package dev.mascwa.pulse.desktop.feature.world

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.telemetry.EconomyVintage
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.data.economy.EconomyDashboard
import dev.mascwa.pulse.data.economy.EconomyRepository
import dev.mascwa.pulse.data.economy.IndicatorSeries
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsBusyBar
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsGhostButton
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.Pulse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A country's figures. Like Markets, this wants a preference rather than a coordinate. */
class EconomyViewModel(
    private val scope: CoroutineScope,
    private val repository: EconomyRepository,
) {
    private val _state = MutableStateFlow(Async<EconomyDashboard>())
    val state: StateFlow<Async<EconomyDashboard>> = _state.asStateFlow()

    private var job: Job? = null

    fun ensureLoaded() {
        if (_state.value.hasData || job?.isActive == true) return
        refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        job?.cancel()
        job = scope.launch { _state.load(force) { f -> repository.fetchDashboard(f) } }
    }
}

/**
 * The economy, and — the part that matters — how old each number is.
 *
 * ⚠️ Every figure carries its vintage. The World Bank publishes annually and lags, so today's newest
 * inflation figure can be well over a year old, and a bare percentage reads as current when it is not.
 * That judgement lives in `EconomyVintage`, which counts age from the END of the year a figure
 * describes — a figure stamped 1 January would be aged by twelve months it never lived.
 */
@Composable
fun EconomyScreen(vm: EconomyViewModel, modifier: Modifier = Modifier) {
    val state: Async<EconomyDashboard> by vm.state.collectAsState()
    val c = Pulse.colors

    LaunchedEffect(Unit) { vm.ensureLoaded() }

    Column(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsHeaderBar(
                "Economy",
                Modifier.weight(1f),
                trailing = state.data?.countryName?.uppercase(),
            )
            LcarsGhostButton("REFRESH", { vm.refresh() })
        }
        LcarsBusyBar(active = state.loading, modifier = Modifier.fillMaxWidth())

        state.error?.let { err ->
            LcarsFrame(Modifier.fillMaxWidth().padding(top = 10.dp), accent = c.negative) {
                Column {
                    Text(err, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink)
                    LcarsGhostButton("TRY AGAIN", { vm.refresh() }, Modifier.padding(top = 8.dp))
                }
            }
        }

        val dash = state.data
        if (dash == null) {
            if (!state.loading && state.error == null) {
                LcarsFrame(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text("Nothing loaded yet.", fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted)
                }
            }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(dash.series, key = { it.indicatorId }) { IndicatorRow(it) }
            item {
                Text(
                    "World Bank open data · annual series, revised on their schedule",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
                    modifier = Modifier.padding(top = 10.dp, bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun IndicatorRow(series: IndicatorSeries) {
    val c = Pulse.colors
    val latest = series.points.lastOrNull()
    val previous = series.points.getOrNull(series.points.size - 2)
    // ⚠️ The core has `describe`, `band` and `caution` — three separate answers, not one object.
    // Derived from the source rather than recalled, which is the discipline this file's own market
    // sibling had to be corrected for.
    val now = System.currentTimeMillis()
    val vintage = latest?.let { EconomyVintage.describe(it.year, now) }
    val dated = latest != null && EconomyVintage.band(latest.year, now) !in
        setOf(EconomyVintage.Vintage.CURRENT, EconomyVintage.Vintage.RECENT)

    // ⚠️ Nullable on purpose. Whether a country should spend more on its military is politics, not
    // statistics, and colouring that change green or red would be the app answering it.
    val better = series.higherIsBetter
    val delta = if (latest != null && previous != null) latest.value - previous.value else null
    val tone = when {
        delta == null || better == null || delta == 0.0 -> c.ink
        (delta > 0) == better -> c.positive
        else -> c.negative
    }

    LcarsFrame(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        series.indicatorTitle,
                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        color = c.ink,
                    )
                    Text(series.unit, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint)
                }
                Text(
                    latest?.let { String.format(java.util.Locale.US, "%,.2f", it.value) } ?: "—",
                    fontFamily = JetBrainsMono, fontSize = 15.sp, color = tone,
                )
            }
            if (vintage != null) {
                Text(
                    vintage,
                    fontFamily = JetBrainsMono, fontSize = 10.sp,
                    // Amber only once the figure is genuinely old — the ordinary annual lag is how
                    // annual statistics work, and colouring it would teach the reader to ignore the
                    // colour on the figures that are actually stale.
                    color = if (dated) c.amber else c.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // The core's own caution, which is null for anything not genuinely out of date.
            latest?.let { EconomyVintage.caution(it.year, now) }?.let {
                Text(
                    it,
                    fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 15.sp, color = c.amber,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
