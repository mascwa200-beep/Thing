package dev.mascwa.pulse.feature.fuel

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.MarketExplainers
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.markets.Quote
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.ExplainerDialog
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.feature.markets.QuoteRow

@Composable
fun FuelScreen(vm: FuelViewModel, onBack: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Quote?>(null) }

    PulseScaffold(
        title = "Fuel & Energy",
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }
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
                    val data = state.data
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        if (state.stale) item { StaleBanner(true) }

                        item { SectionLabel("Energy benchmarks (live)") }
                        items(data?.benchmarks.orEmpty(), key = { "b_${it.id}" }) {
                            QuoteRow(it, Modifier.clickable { selected = it })
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

                        if (!data?.usRetail.isNullOrEmpty()) {
                            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                            item { SectionLabel("US weekly retail (EIA)") }
                            items(data!!.usRetail, key = { "r_${it.product}" }) { rp ->
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(rp.product, style = MaterialTheme.typography.bodyLarge)
                                        rp.period?.let {
                                            Text(it, style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Text(
                                        "${Formatters.currency(rp.usdPerGallon, "USD", 3)} /gal",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                    )
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
    val perLitre = price.usdPerLitre
    val perGallon = perLitre?.let { it * 3.78541 }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(price.fuel, style = MaterialTheme.typography.titleMedium)
                price.year?.let {
                    Text("as of $it", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${Formatters.currency(perLitre, "USD", 2)} /L",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${Formatters.currency(perGallon, "USD", 2)} /gal",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
