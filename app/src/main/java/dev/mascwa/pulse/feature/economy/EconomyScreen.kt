package dev.mascwa.pulse.feature.economy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.EconomyExplainers
import dev.mascwa.pulse.data.economy.IndicatorSeries
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.ExplainerDialog
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner

@Composable
fun EconomyScreen(vm: EconomyViewModel, onBack: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val dash = state.dashboard
    var explain by remember { mutableStateOf<IndicatorSeries?>(null) }

    PulseScaffold(
        title = "Economy",
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = dash.loading && dash.data != null,
            onRefresh = { vm.refresh() },
            modifier = Modifier.padding(innerPadding),
        ) {
            when {
                dash.isInitialLoading -> LoadingState()
                dash.isError -> ErrorState(dash.error ?: "Error", onRetry = { vm.refresh() })
                else -> {
                    val data = dash.data
                    LazyColumn(
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (dash.stale) item { StaleBanner(true) }
                        item {
                            CountryPicker(
                                current = state.country,
                                onSelect = { vm.setCountry(it) },
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                            )
                        }
                        item {
                            Text(
                                data?.countryName ?: state.country,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp),
                            )
                        }
                        items(data?.series.orEmpty().filter { it.points.isNotEmpty() }, key = { it.indicatorId }) {
                            IndicatorCard(it, Modifier.clickable { explain = it })
                        }
                        item {
                            Text(
                                "Source: World Bank Open Data (annual). Some indicators lag by 1–2 years.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    explain?.let { s ->
        ExplainerDialog(
            s.indicatorTitle,
            listOf(EconomyExplainers.forIndicator(s.indicatorId, s.latest?.value)),
            onDismiss = { explain = null },
        )
    }
}
