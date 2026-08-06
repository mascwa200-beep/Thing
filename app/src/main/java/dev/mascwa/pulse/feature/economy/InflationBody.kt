package dev.mascwa.pulse.feature.economy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.feature.common.CyberHeader
import dev.mascwa.pulse.feature.common.CyberRowFrame
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.LineChart
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import dev.mascwa.pulse.ui.theme.trendColor

@Composable
private fun InflationExplainer() {
    val c = Pulse.colors
    NeonPanel(Modifier.fillMaxWidth(), corners = true) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("UNDERSTANDING THIS NUMBER", fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.2.sp, color = c.accent)
            ExplainBlock(
                "What it measures",
                "Consumer Price Inflation tracks the average change in prices a household pays for a fixed " +
                    "\"basket\" of goods and services. The headline figure is the annual % change — how much " +
                    "more (or less) the same basket costs versus a year earlier.",
            )
            ExplainBlock(
                "What's in the basket",
                "Housing & utilities, food & drink, transport & fuel, healthcare, recreation, clothing and " +
                    "personal care — each weighted by how much a typical household spends on it (weights vary by " +
                    "country). Energy and food are the most volatile; \"core\" inflation strips those out.",
            )
            ExplainBlock(
                "What drives it",
                "Common factors: energy & supply shocks, demand outpacing supply, wage growth, currency moves, " +
                    "and government fiscal + central-bank interest-rate policy. A reading near ~2% is the target " +
                    "many central banks aim for.",
            )
            Text(
                "Note: this is general context. Pinning a specific year's inflation on particular policies or " +
                    "events requires official national statistics and analysis — not something this annual figure " +
                    "alone can attribute.",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
            )
        }
    }
}

@Composable
private fun ExplainBlock(title: String, body: String) {
    val c = Pulse.colors
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.ink)
        Text(body, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2)
    }
}

/** The Inflation feed body, scaffold-free so it can be hosted as a Markets sub-tab. */
@Composable
fun InflationBody(vm: EconomyViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val infl = state.inflation

    PullToRefreshBox(
        isRefreshing = infl.loading && infl.data != null,
        onRefresh = { vm.refresh() },
        modifier = modifier,
    ) {
            when {
                infl.isInitialLoading -> LoadingState()
                infl.isError -> ErrorState(infl.error ?: "Error", onRetry = { vm.refresh() })
                else -> {
                    val series = infl.data
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (infl.stale) item { StaleBanner(true) }
                        item {
                            CountryPicker(current = state.country, onSelect = { vm.setCountry(it) })
                        }
                        item {
                            val c = Pulse.colors
                            NeonPanel(Modifier.fillMaxWidth(), corners = true) {
                                Column {
                                    Text(
                                        "CONSUMER PRICE INFLATION",
                                        fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.2.sp, color = c.accent,
                                    )
                                    val latest = series?.latest
                                    Text(
                                        Formatters.percent(latest?.value),
                                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 36.sp,
                                        color = trendColor((latest?.value ?: 0.0) <= 3.0),
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                    Text(
                                        ("${series?.countryName ?: state.country}" +
                                            (latest?.year?.let { " · $it" } ?: "")).uppercase(),
                                        fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 0.6.sp, color = c.muted,
                                    )
                                    if ((series?.points?.size ?: 0) >= 2) {
                                        LineChart(
                                            values = series!!.points.map { it.value },
                                            modifier = Modifier.fillMaxWidth().height(160.dp).padding(top = 12.dp),
                                            showZeroBaseline = true,
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            val pts = series?.points.orEmpty()
                            if (pts.size >= 2) {
                                val latest = pts.last().value
                                val prev = pts[pts.size - 2].value
                                val rising = latest > prev
                                Text(
                                    (if (rising) "▲ Accelerating" else "▼ Cooling") +
                                        " vs ${pts[pts.size - 2].year} (${Formatters.percent(prev)})",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = trendColor(!rising),
                                )
                            }
                        }
                        item { InflationExplainer() }
                        item { CyberHeader("History") }
                        items(series?.points.orEmpty().reversed(), key = { it.year }) { p ->
                            val c = Pulse.colors
                            CyberRowFrame {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    Text("${p.year}", fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.ink2)
                                    Text(
                                        Formatters.percent(p.value),
                                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                        color = trendColor(p.value <= 3.0),
                                    )
                                }
                            }
                        }
                        item {
                            Text(
                                "Source: World Bank Open Data — inflation, consumer prices (annual %).",
                                fontFamily = JetBrainsMono, fontSize = 10.sp, color = Pulse.colors.muted,
                            )
                        }
                    }
                }
            }
        }
    }
