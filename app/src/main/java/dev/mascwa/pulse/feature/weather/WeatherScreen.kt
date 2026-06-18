package dev.mascwa.pulse.feature.weather

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.Explainer
import dev.mascwa.pulse.core.telemetry.WeatherExplainers
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.weather.WeatherCode
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.ExplainerDialog
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner

@Composable
fun WeatherScreen(vm: WeatherViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> vm.onPermissionResult(result.values.any { it }) }

    val data = state.data
    var explainer by remember { mutableStateOf<Pair<String, List<Explainer>>?>(null) }

    PulseScaffold(
        title = data.data?.locationName ?: "Weather",
        actions = {
            IconButton(onClick = { showSearch = !showSearch }) { Icon(Icons.Filled.Search, "Search location") }
            IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = data.loading && data.data != null,
            onRefresh = { vm.refresh() },
            modifier = Modifier.padding(innerPadding),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (data.stale) item { StaleBanner(true) }

                if (showSearch) {
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it; vm.search(it) },
                            label = { Text("Search city") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    items(state.searchResults.distinctBy { "${it.name}_${it.latitude}" }, key = { "${it.name}_${it.latitude}" }) { loc ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(loc.name, style = MaterialTheme.typography.bodyLarge)
                                Text(loc.country, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            AssistChip(onClick = {
                                vm.addAndSelect(loc); showSearch = false; query = ""
                            }, label = { Text("Add") })
                        }
                        HorizontalDivider()
                    }
                }

                // Location chips
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = state.useDeviceLocation,
                                onClick = { vm.useDeviceLocation() },
                                label = { Text("My location") },
                                leadingIcon = { Icon(Icons.Filled.MyLocation, null, Modifier.size(16.dp)) },
                            )
                        }
                        itemsIndexed(state.savedLocations) { i, loc ->
                            FilterChip(
                                selected = !state.useDeviceLocation && i == state.selectedIndex,
                                onClick = { vm.selectSaved(i) },
                                label = { Text(loc.name) },
                            )
                        }
                    }
                }

                if (state.needsLocationPermission && state.useDeviceLocation) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Location permission", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Grant location access for weather at your current position, " +
                                        "or search for a city above.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                AssistChip(
                                    onClick = {
                                        permLauncher.launch(arrayOf(
                                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                                            android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                        ))
                                    },
                                    label = { Text("Grant access") },
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                }

                when {
                    data.isInitialLoading -> item { Box(Modifier.fillMaxWidth().padding(48.dp)) { LoadingState() } }
                    data.isError && data.data == null ->
                        item { ErrorState(data.error ?: "Error", onRetry = { vm.refresh() }) }
                    data.data != null -> {
                        val wd = data.data!!
                        item { CurrentWeatherCard(wd) { t, l -> explainer = t to l } }

                        val hourly = wd.hourly
                        if (hourly.isNotEmpty()) {
                            item { SectionLabel("Next 24 hours") }
                            item {
                                val start = WeatherFormat.nowIndex(hourly.map { it.timeIso })
                                val window = hourly.drop(start).take(24)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(window, key = { it.timeIso }) { h ->
                                        HourCell(h, wd.tempUnitSymbol)
                                    }
                                }
                            }
                        }

                        if (wd.daily.isNotEmpty()) {
                            item { SectionLabel("7-day forecast") }
                            itemsIndexed(wd.daily, key = { _, d -> d.dateIso }) { i, d ->
                                DailyRow(i, d, wd.tempUnitSymbol)
                            }
                        }

                        wd.airQuality?.let { aq -> item { AirQualityCard(aq) { t, l -> explainer = t to l } } }

                        item {
                            Text(
                                "Source: Open-Meteo (forecast & air quality). Free, keyless, global.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    explainer?.let { (title, list) ->
        ExplainerDialog(title, list, onDismiss = { explainer = null })
    }
}

@Composable
private fun CurrentWeatherCard(
    wd: dev.mascwa.pulse.data.weather.WeatherData,
    onExplain: (String, List<Explainer>) -> Unit = { _, _ -> },
) {
    val c = wd.current ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(WeatherCode.emoji(c.weatherCode, c.isDay), style = MaterialTheme.typography.displayMedium)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        "${Formatters.number(c.temperature, 0)}${wd.tempUnitSymbol}",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(WeatherCode.describe(c.weatherCode), style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(
                "Feels like ${Formatters.number(c.apparentTemperature, 0)}${wd.tempUnitSymbol}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable {
                        WeatherExplainers.feelsLike(c.temperature, c.apparentTemperature, wd.tempUnitSymbol)
                            ?.let { onExplain("Feels like", listOf(it)) }
                    },
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Humidity", "${Formatters.number(c.humidity, 0)}%") {
                    WeatherExplainers.humidity(c.humidity)?.let { onExplain("Humidity", listOf(it)) }
                }
                Stat("Wind", "${Formatters.number(c.windSpeed, 0)} ${wd.windUnitSymbol}")
                Stat("Pressure", "${Formatters.number(c.pressure, 0)} hPa") {
                    WeatherExplainers.pressure(c.pressure)?.let { onExplain("Pressure", listOf(it)) }
                }
                Stat("Clouds", "${Formatters.number(c.cloudCover, 0)}%")
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HourCell(h: dev.mascwa.pulse.data.weather.HourlyPoint, unit: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(WeatherFormat.timeLabel(h.timeIso, true), style = MaterialTheme.typography.labelSmall)
        Text(WeatherCode.emoji(h.weatherCode), style = MaterialTheme.typography.titleLarge)
        Text("${Formatters.number(h.temperature, 0)}$unit", style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium)
        Text("${h.precipProbability ?: 0}%", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun DailyRow(index: Int, d: dev.mascwa.pulse.data.weather.DailyPoint, unit: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            WeatherFormat.dayLabel(d.dateIso, index),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.width(64.dp),
        )
        Text(WeatherCode.emoji(d.weatherCode), style = MaterialTheme.typography.titleLarge)
        Text(
            "${d.precipProbabilityMax ?: 0}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(56.dp).padding(start = 12.dp),
        )
        Text(WeatherCode.describe(d.weatherCode), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            "${Formatters.number(d.tempMax, 0)}° / ${Formatters.number(d.tempMin, 0)}°",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
    HorizontalDivider()
}

@Composable
private fun AirQualityCard(
    aq: dev.mascwa.pulse.data.weather.AirQuality,
    onExplain: (String, List<Explainer>) -> Unit = { _, _ -> },
) {
    Card(
        Modifier.fillMaxWidth().clickable {
            WeatherExplainers.airQuality(aq.usAqi, aq.europeanAqi)?.let { onExplain("Air quality", listOf(it)) }
        },
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Air quality", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("EU AQI", "${Formatters.number(aq.europeanAqi, 0)}")
                Stat("US AQI", "${Formatters.number(aq.usAqi, 0)}")
                Stat("PM2.5", "${Formatters.number(aq.pm25, 0)}")
                Stat("PM10", "${Formatters.number(aq.pm10, 0)}")
            }
            Text(
                WeatherFormat.aqiLabel(aq.europeanAqi),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp),
    )
}
