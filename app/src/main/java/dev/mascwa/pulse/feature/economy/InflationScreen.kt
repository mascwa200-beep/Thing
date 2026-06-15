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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.LineChart
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.ui.theme.trendColor

@Composable
fun InflationScreen(vm: EconomyViewModel, onBack: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val infl = state.inflation

    PulseScaffold(
        title = "Inflation",
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = infl.loading && infl.data != null,
            onRefresh = { vm.refresh() },
            modifier = Modifier.padding(innerPadding),
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
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(
                                        "Consumer Price Inflation",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    val latest = series?.latest
                                    Text(
                                        Formatters.percent(latest?.value),
                                        style = MaterialTheme.typography.displaySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = trendColor((latest?.value ?: 0.0) <= 3.0),
                                    )
                                    Text(
                                        "${series?.countryName ?: state.country}" +
                                            (latest?.year?.let { " · $it" } ?: ""),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            Text("History", style = MaterialTheme.typography.titleMedium)
                        }
                        items(series?.points.orEmpty().reversed(), key = { it.year }) { p ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("${p.year}", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    Formatters.percent(p.value),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = trendColor(p.value <= 3.0),
                                )
                            }
                            HorizontalDivider()
                        }
                        item {
                            Text(
                                "Source: World Bank Open Data — inflation, consumer prices (annual %).",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
