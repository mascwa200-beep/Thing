package dev.mascwa.pulse.feature.fuel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import dev.mascwa.pulse.feature.common.LcarsIcons
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.MarketExplainers
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.markets.Quote
import dev.mascwa.pulse.feature.common.CyberHeader
import dev.mascwa.pulse.feature.common.CyberRowFrame
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.ExplainerDialog
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.feature.markets.QuoteRow
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun FuelScreen(vm: FuelViewModel, onBack: (() -> Unit)? = null) {
    PulseScaffold(
        title = "Fuel & Energy",
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(LcarsIcons.ArrowBack, "Back") }
            }
        },
    ) { innerPadding ->
        FuelBody(vm, Modifier.padding(innerPadding))
    }
}

/** The Fuel & Energy feed body, scaffold-free so it can be hosted as a Markets sub-tab. */
@Composable
fun FuelBody(vm: FuelViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Quote?>(null) }

    PullToRefreshBox(
        isRefreshing = state.loading && state.data != null,
        onRefresh = { vm.refresh() },
        modifier = modifier,
    ) {
            when {
                state.isInitialLoading -> LoadingState()
                state.isError -> ErrorState(state.error ?: "Error", onRetry = { vm.refresh() })
                else -> {
                    val data = state.data
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        if (state.stale) item { StaleBanner(true) }

                        item { SectionLabel("Energy benchmarks (live)") }
                        items(data?.benchmarks.orEmpty(), key = { "b_${it.id}" }) { q ->
                            CyberRowFrame(onClick = { selected = q }) { QuoteRow(q) }
                        }
                        if (data?.benchmarks.isNullOrEmpty()) {
                            item {
                                Text(
                                    "Couldn't reach the energy markets feed. Pull to refresh.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }

                        if (!data?.nationalPrices.isNullOrEmpty()) {
                            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                            item { SectionLabel("National pump prices — ${data?.countryName ?: ""}") }
                            items(data!!.nationalPrices, key = { it.fuel }) { PumpPriceCard(it) }
                        }

                        // Why there are no pump prices, when there are none.
                        //
                        // The benchmarks above are worldwide crude and gas contracts; what a driver
                        // actually pays is national, and the two sources for that are both narrow —
                        // the World Bank retired its pump-price indicators (both now answer
                        // "indicator not found"), and the EIA covers the United States and needs a
                        // key. Without a word, the section simply is not there, and an absence with
                        // no reason reads as a fault rather than a limit.
                        if (data?.nationalPrices.isNullOrEmpty() && data?.usRetail.isNullOrEmpty()) {
                            item {
                                Text(
                                    "No pump prices for your country. The benchmarks above are the " +
                                        "worldwide crude and gas contracts that drive them — the " +
                                        "free source for national averages was retired, and the US " +
                                        "figures need an EIA key in Settings.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        }

                        if (!data?.usRetail.isNullOrEmpty()) {
                            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                            item { SectionLabel("US weekly retail (EIA)") }
                            items(data!!.usRetail, key = { "r_${it.product}" }) { rp ->
                                val cc = Pulse.colors
                                CyberRowFrame {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(rp.product, fontFamily = ChakraPetch, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = cc.ink)
                                            rp.period?.let {
                                                Text(it.uppercase(), fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.6.sp, color = cc.muted)
                                            }
                                        }
                                        Text(
                                            "${Formatters.currency(rp.usdPerGallon, "USD", 3)} /gal",
                                            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = cc.ink,
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Text(
                                "Benchmarks: live energy futures (Yahoo Finance, keyless). " +
                                    "Add an EIA key in Settings for live US weekly retail pump prices.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }

    selected?.let { q ->
        ExplainerDialog(
            q.label,
            buildList {
                add(MarketExplainers.instrument(q.id, q.label, q.type))
                q.changePercent?.let { add(MarketExplainers.changePercent(it)) }
            },
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun PumpPriceCard(price: dev.mascwa.pulse.data.fuel.PumpPrice) {
    val c = Pulse.colors
    val perLitre = price.usdPerLitre
    val perGallon = perLitre?.let { it * 3.78541 }
    NeonPanel(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        corners = true,
        padding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                Text(price.fuel.uppercase(), fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.ink)
                price.year?.let {
                    Text("AS OF $it", fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.6.sp, color = c.muted)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${Formatters.currency(perLitre, "USD", 2)} /L",
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.ink,
                )
                Text(
                    "${Formatters.currency(perGallon, "USD", 2)} /gal",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    CyberHeader(text)
}
