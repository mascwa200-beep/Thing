package dev.mascwa.pulse.feature.markets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.MarketExplainers
import dev.mascwa.pulse.core.telemetry.MarketMood
import dev.mascwa.pulse.data.markets.Quote
import dev.mascwa.pulse.data.settings.WatchType
import dev.mascwa.pulse.feature.common.ChangePill
import dev.mascwa.pulse.feature.common.ExplainerDialog
import dev.mascwa.pulse.feature.common.EmptyState
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.NeonChip
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.feature.economy.EconomyBody
import dev.mascwa.pulse.feature.economy.EconomyViewModel
import dev.mascwa.pulse.feature.economy.InflationBody
import dev.mascwa.pulse.feature.fuel.FuelBody
import dev.mascwa.pulse.feature.fuel.FuelViewModel

/** The Markets hub: live quotes plus Economy / Inflation / Fuel folded in as sub-tab feeds. */
private enum class MarketsTab(val label: String) {
    MARKETS("MARKETS"), ECONOMY("ECONOMY"), INFLATION("INFLATION"), FUEL("FUEL")
}

@Composable
fun MarketsScreen(
    marketsVm: MarketsViewModel,
    economyVm: EconomyViewModel,
    fuelVm: FuelViewModel,
) {
    var tab by remember { mutableStateOf(MarketsTab.MARKETS) }
    PulseScaffold(
        title = "Markets",
        actions = {
            IconButton(onClick = {
                when (tab) {
                    MarketsTab.MARKETS -> marketsVm.refresh()
                    MarketsTab.ECONOMY, MarketsTab.INFLATION -> economyVm.refresh()
                    MarketsTab.FUEL -> fuelVm.refresh()
                }
            }) { Icon(Icons.Filled.Refresh, "Refresh") }
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MarketsTab.entries.forEach { t ->
                    NeonChip(t.label, selected = t == tab, onClick = { tab = t })
                }
            }
            when (tab) {
                MarketsTab.MARKETS -> MarketsBody(marketsVm)
                MarketsTab.ECONOMY -> EconomyBody(economyVm)
                MarketsTab.INFLATION -> InflationBody(economyVm)
                MarketsTab.FUEL -> FuelBody(fuelVm)
            }
        }
    }
}

/** The live-quotes feed body, scaffold-free so it can be hosted as a Markets sub-tab. */
@Composable
fun MarketsBody(vm: MarketsViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Tap a row → what this instrument is + what today's move means.
    var selected by remember { mutableStateOf<Quote?>(null) }

    Box(modifier) {
        val watch = state.watchlist
        val crypto = state.crypto
        val anyLoadingInitial = watch.isInitialLoading && crypto.isInitialLoading
        val anyError = watch.isError && crypto.isError

        PullToRefreshBox(
            isRefreshing = (watch.loading || crypto.loading) && (watch.data != null || crypto.data != null),
            onRefresh = { vm.refresh() },
        ) {
            when {
                anyLoadingInitial -> LoadingState()
                anyError -> ErrorState(watch.error ?: crypto.error ?: "Error", onRetry = { vm.refresh() })
                else -> {
                    val grouped = vm.grouped(watch.data ?: emptyList())
                    val order = listOf(
                        WatchType.INDEX to "Indices",
                        WatchType.STOCK to "Stocks",
                        WatchType.FOREX to "Forex",
                        WatchType.COMMODITY to "Commodities",
                    )
                    val all = ((watch.data ?: emptyList()) + (crypto.data ?: emptyList()))
                        .filter { it.changePercent != null }
                    val gainers = all.sortedByDescending { it.changePercent!! }.take(3)
                    val losers = (all - gainers.toSet()).sortedBy { it.changePercent!! }.take(3)

                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        if (watch.stale || crypto.stale) item { StaleBanner(true) }

                        MarketMood.summarize(all.mapNotNull { it.changePercent })?.let { mood ->
                            item(key = "mood") { MoodBanner(mood) }
                        }

                        if (gainers.isNotEmpty()) {
                            item(key = "h_gain") { SectionLabel("Top gainers") }
                            items(gainers, key = { "g_${it.id}" }) { q -> MoverRow(q) { selected = q } }
                        }
                        if (losers.isNotEmpty()) {
                            item(key = "h_lose") { SectionLabel("Top losers") }
                            items(losers, key = { "l_${it.id}" }) { q -> MoverRow(q) { selected = q } }
                        }
                        if (gainers.isNotEmpty() || losers.isNotEmpty()) {
                            item(key = "d_movers") { HorizontalDivider() }
                        }

                        order.forEach { (type, label) ->
                            val rows = grouped[type].orEmpty()
                            if (rows.isNotEmpty()) {
                                item(key = "h_$label") { SectionLabel(label) }
                                items(rows, key = { it.id }) { QuoteRow(it, Modifier.clickable { selected = it }) }
                                item(key = "d_$label") { HorizontalDivider() }
                            }
                        }
                        val cryptoRows = crypto.data.orEmpty()
                        if (cryptoRows.isNotEmpty()) {
                            item(key = "h_crypto") { SectionLabel("Crypto") }
                            items(cryptoRows, key = { "c_${it.id}" }) { QuoteRow(it, Modifier.clickable { selected = it }) }
                        }
                        if (grouped.isEmpty() && cryptoRows.isEmpty()) {
                            item { EmptyState("Your watchlist is empty. Add symbols in Settings.") }
                        }
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
private fun MoverRow(q: Quote, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            q.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text(formatPrice(q), style = MaterialTheme.typography.bodyMedium)
        ChangePill(q.changePercent)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun MoodBanner(mood: MarketMood.Mood) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        Text(
            "MARKET MOOD",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(mood.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            mood.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
