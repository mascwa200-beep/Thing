package dev.mascwa.pulse.feature.markets

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.data.settings.WatchType
import dev.mascwa.pulse.feature.common.EmptyState
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner

@Composable
fun MarketsScreen(vm: MarketsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    PulseScaffold(
        title = "Markets",
        actions = {
            IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") }
        },
    ) { innerPadding ->
        val watch = state.watchlist
        val crypto = state.crypto
        val anyLoadingInitial = watch.isInitialLoading && crypto.isInitialLoading
        val anyError = watch.isError && crypto.isError

        PullToRefreshBox(
            isRefreshing = (watch.loading || crypto.loading) && (watch.data != null || crypto.data != null),
            onRefresh = { vm.refresh() },
            modifier = Modifier.padding(innerPadding),
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
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        if (watch.stale || crypto.stale) item { StaleBanner(true) }
                        order.forEach { (type, label) ->
                            val rows = grouped[type].orEmpty()
                            if (rows.isNotEmpty()) {
                                item(key = "h_$label") { SectionLabel(label) }
                                items(rows, key = { it.id }) { QuoteRow(it) }
                                item(key = "d_$label") { HorizontalDivider() }
                            }
                        }
                        val cryptoRows = crypto.data.orEmpty()
                        if (cryptoRows.isNotEmpty()) {
                            item(key = "h_crypto") { SectionLabel("Crypto") }
                            items(cryptoRows, key = { "c_${it.id}" }) { QuoteRow(it) }
                        }
                        if (grouped.isEmpty() && cryptoRows.isEmpty()) {
                            item { EmptyState("Your watchlist is empty. Add symbols in Settings.") }
                        }
                    }
                }
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
