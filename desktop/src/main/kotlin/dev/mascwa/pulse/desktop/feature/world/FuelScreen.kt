package dev.mascwa.pulse.desktop.feature.world

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.telemetry.Freshness
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.data.fuel.FuelData
import dev.mascwa.pulse.data.fuel.FuelRepository
import dev.mascwa.pulse.data.markets.Quote
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsBusyBar
import dev.mascwa.pulse.desktop.theme.LcarsDataRow
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

/**
 * What energy costs — a country's figures, not a coordinate's, so like Markets and Economy this is a
 * preference rather than a place.
 */
class FuelViewModel(
    private val scope: CoroutineScope,
    private val repository: FuelRepository,
) {
    private val _state = MutableStateFlow(Async<FuelData>())
    val state: StateFlow<Async<FuelData>> = _state.asStateFlow()

    private var job: Job? = null

    fun ensureLoaded() {
        if (_state.value.hasData || job?.isActive == true) return
        refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        job?.cancel()
        job = scope.launch { _state.load(force) { f -> repository.fetch(f) } }
    }
}

/**
 * Fuel and energy.
 *
 * ⚠️ **What this screen can and cannot show, said on the page rather than left to be discovered.**
 * The five energy benchmarks are live futures and work everywhere. National pump prices do not:
 * the World Bank retired both indicators (`EP.PMP.SGAS.CD` / `EP.PMP.DESL.CD` — verified, they now
 * answer "indicator not found"), so the only real pump figures left are the EIA's, which are US-only
 * and need a free key. The phone learned to say that rather than rendering an empty section, and so
 * does this.
 */
@Composable
fun FuelScreen(vm: FuelViewModel, modifier: Modifier = Modifier) {
    val state: Async<FuelData> by vm.state.collectAsState()
    val c = Pulse.colors

    LaunchedEffect(Unit) { vm.ensureLoaded() }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsHeaderBar("Fuel & energy", Modifier.weight(1f), trailing = state.data?.countryCode)
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

        val freshness = Freshness.assess(
            lastUpdatedMs = state.lastUpdatedEpochMs,
            nowMs = System.currentTimeMillis(),
            online = true,
            servingStored = state.stale,
            refreshFailed = state.stale && state.error != null,
        )
        if (freshness.worthShowing) {
            Text(
                freshness.label,
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        val data = state.data
        if (data == null) {
            if (!state.loading && state.error == null) {
                LcarsFrame(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text("Nothing loaded yet.", fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted)
                }
            }
            return@Column
        }

        if (data.benchmarks.isNotEmpty()) {
            LcarsHeaderBar("Benchmarks", Modifier.padding(top = 10.dp), trailing = "LIVE")
            data.benchmarks.forEach { BenchmarkRow(it) }
        }

        if (data.usRetail.isNotEmpty()) {
            LcarsHeaderBar("At the pump", Modifier.padding(top = 12.dp), trailing = "EIA · US")
            LcarsFrame(Modifier.fillMaxWidth()) {
                Column {
                    data.usRetail.forEach { p ->
                        LcarsDataRow(
                            p.product,
                            p.usdPerGallon
                                ?.let { String.format(java.util.Locale.US, "$%.3f / gal", it) }
                                ?: "—",
                        )
                    }
                    weekEnding(data)?.let {
                        Text(
                            it,
                            fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.faint,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        } else {
            // ⚠️ A sentence rather than an absent section. Rendering nothing here would read as a
            // failure, when in fact the data does not exist to render.
            LcarsFrame(Modifier.fillMaxWidth().padding(top = 12.dp), accent = c.muted) {
                Text(
                    "No pump prices for ${data.countryCode}. The World Bank retired its petrol and " +
                        "diesel price indicators, and the only free replacement — the US Energy " +
                        "Information Administration's weekly retail series — covers the United States " +
                        "and needs a key, which you can add in SETTINGS.",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 16.sp, color = c.muted,
                )
            }
        }

        Text(
            "Futures via Stooq · retail prices via the U.S. Energy Information Administration",
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
            modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
        )
    }
}

/** The week the retail figures describe, where they say. */
private fun weekEnding(data: FuelData): String? =
    data.usRetail.firstNotNullOfOrNull { it.period }?.let { "Week ending $it" }

@Composable
private fun BenchmarkRow(quote: Quote) {
    val c = Pulse.colors
    val pct = quote.changePercent
    val tone = when {
        pct == null -> c.muted
        pct > 0 -> c.positive
        pct < 0 -> c.negative
        else -> c.ink
    }
    LcarsFrame(Modifier.fillMaxWidth().padding(top = 3.dp), accent = tone) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                quote.label,
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                color = c.ink, modifier = Modifier.weight(1f),
            )
            Column {
                Text(
                    // Precision belongs to the instrument, not the value — the venue's own hint
                    // where it gave one, and four places for anything trading under ten.
                    quote.price?.let {
                        val digits = quote.priceHint ?: if (it < 10) 4 else 2
                        String.format(java.util.Locale.US, "%,.${digits.coerceIn(0, 8)}f", it)
                    } ?: "—",
                    fontFamily = JetBrainsMono, fontSize = 14.sp, color = c.ink,
                )
                Text(
                    Formatters.signedPercent(pct),
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = tone,
                )
            }
        }
    }
}
