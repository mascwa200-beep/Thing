package dev.mascwa.pulse.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.core.util.openUrl
import dev.mascwa.pulse.data.news.Article
import dev.mascwa.pulse.data.settings.HomeSection
import dev.mascwa.pulse.feature.common.ChangePill
import dev.mascwa.pulse.feature.common.SectionHeader
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.feature.news.ArticleRowCompact
import dev.mascwa.pulse.feature.weather.WeatherFormat
import dev.mascwa.pulse.data.weather.WeatherCode

class HomeNav(
    val openNews: () -> Unit,
    val openMarkets: () -> Unit,
    val openWeather: () -> Unit,
    val openEconomy: () -> Unit,
    val openInflation: () -> Unit,
    val openFuel: () -> Unit,
)

@Composable
fun HomeScreen(vm: HomeViewModel, nav: HomeNav) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    dev.mascwa.pulse.feature.common.PulseScaffold(
        title = "Pulse",
        actions = { IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.headlines.loading && state.headlines.data != null,
            onRefresh = { vm.refresh() },
            modifier = Modifier.padding(innerPadding),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val anyStale = listOf(
                    state.headlines, state.markets, state.weather, state.economy, state.fuel,
                ).any { it.stale }
                if (anyStale) item { StaleBanner(true) }

                state.sections.forEach { section ->
                    when (section) {
                        HomeSection.HEADLINES -> articleSection(
                            "Top Headlines", state.headlines, nav.openNews, context,
                        )
                        HomeSection.MARKETS -> item { MarketsCard(state.markets, nav.openMarkets) }
                        HomeSection.WEATHER -> item { WeatherCard(state.weather, nav.openWeather) }
                        HomeSection.ECONOMY -> item { EconomyCard(state, nav.openEconomy) }
                        HomeSection.INFLATION -> item { InflationCard(state, nav.openInflation) }
                        HomeSection.FUEL -> item { FuelCard(state, nav.openFuel) }
                        HomeSection.POLITICS -> articleSection("Politics", state.politics, nav.openNews, context)
                        HomeSection.TECH -> articleSection("Tech", state.tech, nav.openNews, context)
                        HomeSection.POPCULTURE -> articleSection("Pop Culture", state.popculture, nav.openNews, context)
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.articleSection(
    title: String,
    async: Async<List<Article>>,
    onSeeAll: () -> Unit,
    context: android.content.Context,
) {
    item { SectionHeader(title, actionLabel = "See all", onAction = onSeeAll) }
    val list = async.data.orEmpty().take(5)
    if (async.isInitialLoading) {
        item { LoadingRow() }
    } else if (list.isEmpty()) {
        item {
            Text(
                async.error ?: "No stories yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    } else {
        items(count = list.size, key = { "$title-${list[it].url}" }) { i ->
            ArticleRowCompact(list[i], onClick = { openUrl(context, list[i].url) })
        }
    }
}

@Composable
private fun LoadingRow() {
    Text(
        "Loading…",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun DashCard(title: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

@Composable
private fun MarketsCard(async: Async<List<dev.mascwa.pulse.data.markets.Quote>>, onClick: () -> Unit) {
    DashCard("Markets", onClick) {
        val quotes = async.data.orEmpty().take(4)
        if (quotes.isEmpty()) {
            Text(if (async.loading) "Loading…" else "—", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            quotes.forEach { q ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(q.label, style = MaterialTheme.typography.bodyMedium)
                    ChangePill(q.changePercent)
                }
            }
        }
    }
}

@Composable
private fun WeatherCard(async: Async<dev.mascwa.pulse.data.weather.WeatherData>, onClick: () -> Unit) {
    DashCard("Weather", onClick) {
        val wd = async.data
        val c = wd?.current
        if (c == null) {
            Text(if (async.loading) "Loading…" else "—", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(WeatherCode.emoji(c.weatherCode, c.isDay), style = MaterialTheme.typography.displaySmall)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        "${Formatters.number(c.temperature, 0)}${wd.tempUnitSymbol} · ${WeatherCode.describe(c.weatherCode)}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(wd.locationName, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EconomyCard(state: HomeUiState, onClick: () -> Unit) {
    DashCard("Economy", onClick) {
        val dash = state.economy.data
        if (dash == null) {
            Text(if (state.economy.loading) "Loading…" else "—", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val keys = listOf("FP.CPI.TOTL.ZG", "NY.GDP.MKTP.KD.ZG", "SL.UEM.TOTL.ZS")
            dash.series.filter { it.indicatorId in keys && it.latest != null }.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(s.indicatorTitle, style = MaterialTheme.typography.bodyMedium)
                    Text(Formatters.percent(s.latest?.value), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun InflationCard(state: HomeUiState, onClick: () -> Unit) {
    DashCard("Inflation", onClick) {
        val infl = state.economy.data?.series?.firstOrNull { it.indicatorId == "FP.CPI.TOTL.ZG" }?.latest
        if (infl == null) {
            Text(if (state.economy.loading) "Loading…" else "—", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(Formatters.percent(infl.value), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("CPI, annual · ${infl.year}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FuelCard(state: HomeUiState, onClick: () -> Unit) {
    DashCard("Fuel & Energy", onClick) {
        val benches = state.fuel.data?.benchmarks?.take(3).orEmpty()
        if (benches.isEmpty()) {
            Text(if (state.fuel.loading) "Loading…" else "—", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            benches.forEach { q ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(q.label, style = MaterialTheme.typography.bodyMedium)
                    Row {
                        Text(Formatters.number(q.price, 2), style = MaterialTheme.typography.bodyMedium)
                        ChangePill(q.changePercent, Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}
