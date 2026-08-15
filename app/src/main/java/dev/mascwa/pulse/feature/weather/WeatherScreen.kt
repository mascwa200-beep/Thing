package dev.mascwa.pulse.feature.weather

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
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
import dev.mascwa.pulse.core.telemetry.WeatherComfort
import dev.mascwa.pulse.core.telemetry.WeatherExplainers
import dev.mascwa.pulse.core.telemetry.WeatherUnits
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.weather.WeatherCode
import dev.mascwa.pulse.feature.common.CyberChipCut
import dev.mascwa.pulse.feature.common.CyberHeader
import dev.mascwa.pulse.feature.common.CyberRowFrame
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.ExplainerDialog
import dev.mascwa.pulse.feature.common.LcarsIcons
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.NeonChip
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import androidx.compose.ui.unit.sp

/** The weather tab's own sections, mirroring the Markets sub-tab pattern. */
private enum class WeatherTab(val label: String) {
    NOW("NOW"), HOURS("HOURS"), DAYS("DAYS"), AIR("AIR")
}

@Composable
fun WeatherScreen(vm: WeatherViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(WeatherTab.NOW) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> vm.onPermissionResult(result.values.any { it }) }

    val data = state.data
    var explainer by remember { mutableStateOf<Pair<String, List<Explainer>>?>(null) }
    val onExplain: (String, List<Explainer>) -> Unit = { t, l -> explainer = t to l }

    // The shared chrome every tab shows. Passed as one object so each body's signature stays
    // readable and so adding to the header later touches one place.
    val chrome = WeatherChrome(
        state = state,
        vm = vm,
        showSearch = showSearch,
        query = query,
        onQuery = { query = it; vm.search(it) },
        onAdded = { showSearch = false; query = "" },
        onRequestPermission = {
            permLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        },
    )

    PulseScaffold(
        title = data.data?.locationName ?: "Weather",
        actions = {
            IconButton(onClick = { showSearch = !showSearch }) { Icon(LcarsIcons.Search, "Search location") }
            IconButton(onClick = { vm.refresh() }) { Icon(LcarsIcons.Refresh, "Refresh") }
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            // Only the rail is fixed. Everything else scrolls, as it did when this screen was one
            // long page — a permanent header would cost real estate a phone does not have.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WeatherTab.entries.forEach { t ->
                    NeonChip(t.label, selected = t == tab, onClick = { tab = t })
                }
            }
            when (tab) {
                WeatherTab.NOW -> NowBody(vm, chrome, onExplain)
                WeatherTab.HOURS -> HoursBody(vm, chrome)
                WeatherTab.DAYS -> DaysBody(vm, chrome)
                WeatherTab.AIR -> AirBody(vm, chrome, onExplain)
            }
        }
    }

    explainer?.let { (title, list) ->
        ExplainerDialog(title, list, onDismiss = { explainer = null })
    }
}

/** Everything the shared header needs, so each body takes one parameter rather than seven. */
private data class WeatherChrome(
    val state: WeatherUiState,
    val vm: WeatherViewModel,
    val showSearch: Boolean,
    val query: String,
    val onQuery: (String) -> Unit,
    val onAdded: () -> Unit,
    val onRequestPermission: () -> Unit,
)

/**
 * The stale banner, city search, location picker and permission prompt.
 *
 * A `LazyListScope` extension rather than a composable above the rail, so it scrolls away with the
 * content exactly as it did when this screen was a single page, and so the location picker stays
 * reachable from every tab without being pinned to the top of all of them.
 */
private fun LazyListScope.weatherHeader(c: WeatherChrome) {
    if (c.state.data.stale) item { StaleBanner(true) }

    if (c.showSearch) {
        item {
            OutlinedTextField(
                value = c.query,
                onValueChange = c.onQuery,
                label = { Text("Search city") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(
            c.state.searchResults.distinctBy { "${it.name}_${it.latitude}" },
            key = { "${it.name}_${it.latitude}" },
        ) { loc ->
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(loc.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        loc.country, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AssistChip(
                    onClick = { c.vm.addAndSelect(loc); c.onAdded() },
                    label = { Text("Add") },
                )
            }
            HorizontalDivider()
        }
    }

    item {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = c.state.useDeviceLocation,
                    onClick = { c.vm.useDeviceLocation() },
                    label = { Text("My location") },
                    leadingIcon = { Icon(Icons.Filled.MyLocation, null, Modifier.size(16.dp)) },
                )
            }
            itemsIndexed(c.state.savedLocations) { i, loc ->
                FilterChip(
                    selected = !c.state.useDeviceLocation && i == c.state.selectedIndex,
                    onClick = { c.vm.selectSaved(i) },
                    label = { Text(loc.name) },
                )
            }
        }
    }

    if (c.state.needsLocationPermission && c.state.useDeviceLocation) {
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
                        onClick = c.onRequestPermission,
                        label = { Text("Grant access") },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * One tab's scrolling page: pull-to-refresh, the shared header, then whatever [content] the tab
 * draws once there is data to draw.
 *
 * Loading and error live here rather than in each body, so all four behave the same way when the
 * forecast has not arrived.
 */
@Composable
private fun WeatherTabScaffold(
    vm: WeatherViewModel,
    chrome: WeatherChrome,
    content: LazyListScope.(dev.mascwa.pulse.data.weather.WeatherData) -> Unit,
) {
    val data = chrome.state.data
    PullToRefreshBox(
        isRefreshing = data.loading && data.data != null,
        onRefresh = { vm.refresh() },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            weatherHeader(chrome)
            when {
                data.isInitialLoading ->
                    item { Box(Modifier.fillMaxWidth().padding(48.dp)) { LoadingState() } }
                data.isError && data.data == null ->
                    item { ErrorState(data.error ?: "Error", onRetry = { vm.refresh() }) }
                data.data != null -> {
                    content(data.data!!)
                    item { SourceNote() }
                }
            }
        }
    }
}

@Composable
private fun NowBody(
    vm: WeatherViewModel,
    chrome: WeatherChrome,
    onExplain: (String, List<Explainer>) -> Unit,
) {
    WeatherTabScaffold(vm, chrome) { wd ->
        item { CurrentWeatherCard(wd, onExplain) }
        // CAPE and UV are hourly rather than current, so the present hour is read out of the
        // hourly series using the same index the forecast strip already uses.
        val nowHour = wd.hourly.getOrNull(WeatherFormat.nowIndex(wd.hourly.map { it.timeIso }))
        item { ConditionsCard(wd, nowHour, onExplain) }
    }
}

/**
 * The readings beyond the headline number: what the wind is really doing, how the air feels to
 * breathe, how far you can see, and whether the atmosphere is carrying storm fuel.
 *
 * Every row is absent when its value is missing or when the underlying index does not apply, so a
 * mild, clear, still day shows very little here. That is the intent — the card says something when
 * there is something to say.
 */
@Composable
private fun ConditionsCard(
    wd: dev.mascwa.pulse.data.weather.WeatherData,
    nowHour: dev.mascwa.pulse.data.weather.HourlyPoint?,
    onExplain: (String, List<Explainer>) -> Unit,
) {
    val c = wd.current ?: return
    val p = Pulse.colors
    val imperial = wd.precipUnitSymbol.contains("in")

    // The indices are defined in Celsius and km/h; these are the converted companions. A blob
    // cached by an earlier build has none of them, so the headline is simply absent until the next
    // refresh rather than taking the whole card down with it.
    val headline = c.temperatureC?.let {
        WeatherComfort.headline(
            temperatureC = it,
            humidityPercent = c.humidity,
            windKmh = c.windKmh,
            gustKmh = c.gustKmh,
            dewPointC = c.dewPointC,
            unitSymbol = wd.tempUnitSymbol,
        )
    }
    val uv = nowHour?.uvIndex
    val burn = WeatherComfort.burnMinutes(uv)
    val thunder = WeatherComfort.thunderPotential(nowHour?.capeJkg)
    val visibility = WeatherUnits.describeVisibility(c.visibilityMetres, imperial)

    // Nothing to add beyond the main card — say nothing rather than draw an empty frame.
    if (headline == null && c.gustKmh == null && c.dewPointC == null && visibility == null && uv == null) return

    NeonPanel(Modifier.fillMaxWidth(), corners = true, padding = PaddingValues(16.dp)) {
        Column {
            Text(
                "CONDITIONS", fontFamily = JetBrainsMono, fontSize = 11.sp,
                letterSpacing = 1.5.sp, color = p.accent,
            )
            headline?.let {
                Text(
                    it, fontFamily = ChakraPetch, fontSize = 14.sp, color = p.ink,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Stat("Gusts", c.windGust?.let { "${Formatters.number(it, 0)} ${wd.windUnitSymbol}" } ?: "—") {
                    WeatherExplainers.gusts(c.windKmh, c.gustKmh, wd.windUnitSymbol, c.windGust)
                        ?.let { onExplain("Gusts", listOf(it)) }
                }
                Stat("Dew point", c.dewPoint?.let { "${Formatters.number(it, 0)}${wd.tempUnitSymbol}" } ?: "—") {
                    WeatherExplainers.dewPoint(c.dewPointC, wd.tempUnitSymbol)
                        ?.let { onExplain("Dew point", listOf(it)) }
                }
                Stat("Visibility", visibility ?: "—") {
                    WeatherExplainers.visibility(c.visibilityMetres, imperial)
                        ?.let { onExplain("Visibility", listOf(it)) }
                }
                Stat("UV", uv?.let { Formatters.number(it, 0) } ?: "—") {
                    WeatherExplainers.uvIndex(uv)?.let { onExplain("UV index", listOf(it)) }
                }
            }
            burn?.let {
                Text(
                    "Unprotected skin burns in about $it min",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = p.amber,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            thunder?.let {
                Text(
                    it,
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = p.amber,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable {
                            WeatherExplainers.cape(nowHour?.capeJkg)
                                ?.let { e -> onExplain("Storm potential", listOf(e)) }
                        },
                )
            }
            WeatherComfort.mugginess(c.dewPointC)?.let { m ->
                Text(
                    "Air feels $m".lowercase().replaceFirstChar { ch -> ch.uppercase() },
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = p.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun HoursBody(vm: WeatherViewModel, chrome: WeatherChrome) {
    WeatherTabScaffold(vm, chrome) { wd ->
        val hourly = wd.hourly
        if (hourly.isNotEmpty()) {
            item { SectionLabel("Next 24 hours") }
            item {
                val start = WeatherFormat.nowIndex(hourly.map { it.timeIso })
                val window = hourly.drop(start).take(24)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(window, key = { it.timeIso }) { h -> HourCell(h, wd.tempUnitSymbol) }
                }
            }
        }
    }
}

@Composable
private fun DaysBody(vm: WeatherViewModel, chrome: WeatherChrome) {
    WeatherTabScaffold(vm, chrome) { wd ->
        if (wd.daily.isNotEmpty()) {
            item { SectionLabel("7-day forecast") }
            itemsIndexed(wd.daily, key = { _, d -> d.dateIso }) { i, d ->
                DailyRow(i, d, wd.tempUnitSymbol)
            }
        }
    }
}

@Composable
private fun AirBody(
    vm: WeatherViewModel,
    chrome: WeatherChrome,
    onExplain: (String, List<Explainer>) -> Unit,
) {
    WeatherTabScaffold(vm, chrome) { wd ->
        wd.airQuality?.let { aq -> item { AirQualityCard(aq, onExplain) } }
    }
}

@Composable
private fun SourceNote() {
    Text(
        "Source: Open-Meteo (forecast & air quality). Free, keyless, global.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CurrentWeatherCard(
    wd: dev.mascwa.pulse.data.weather.WeatherData,
    onExplain: (String, List<Explainer>) -> Unit = { _, _ -> },
) {
    val c = wd.current ?: return
    val p = Pulse.colors
    NeonPanel(Modifier.fillMaxWidth(), corners = true, padding = PaddingValues(20.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(WeatherCode.emoji(c.weatherCode, c.isDay), fontSize = 44.sp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        "${Formatters.number(c.temperature, 0)}${wd.tempUnitSymbol}",
                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 40.sp, color = p.ink,
                    )
                    Text(
                        WeatherCode.describe(c.weatherCode).uppercase(),
                        fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 1.sp, color = p.accent,
                    )
                }
            }
            Text(
                "FEELS LIKE ${Formatters.number(c.apparentTemperature, 0)}${wd.tempUnitSymbol}",
                fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 0.8.sp, color = p.ink2,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable {
                        WeatherExplainers.feelsLike(c.temperature, c.apparentTemperature, wd.tempUnitSymbol)
                            ?.let { onExplain("Feels like", listOf(it)) }
                    },
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = p.line)
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
    val p = Pulse.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier,
    ) {
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = p.ink)
        Text(label.uppercase(), fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.6.sp, color = p.muted)
    }
}

@Composable
private fun HourCell(h: dev.mascwa.pulse.data.weather.HourlyPoint, unit: String) {
    val p = Pulse.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(CyberChipCut)
            .background(p.panel)
            .border(androidx.compose.foundation.BorderStroke(1.dp, p.lineSoft), CyberChipCut)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(WeatherFormat.timeLabel(h.timeIso, true), fontFamily = JetBrainsMono, fontSize = 10.sp, color = p.muted)
        Text(WeatherCode.emoji(h.weatherCode), fontSize = 22.sp)
        Text("${Formatters.number(h.temperature, 0)}$unit", fontFamily = ChakraPetch, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = p.ink)
        Text("${h.precipProbability ?: 0}%", fontFamily = JetBrainsMono, fontSize = 10.sp, color = p.accent)
    }
}

@Composable
private fun DailyRow(index: Int, d: dev.mascwa.pulse.data.weather.DailyPoint, unit: String) {
    val p = Pulse.colors
    CyberRowFrame {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                WeatherFormat.dayLabel(d.dateIso, index).uppercase(),
                fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = p.ink,
                modifier = Modifier.width(60.dp),
            )
            Text(WeatherCode.emoji(d.weatherCode), fontSize = 20.sp)
            Text(
                "${d.precipProbabilityMax ?: 0}%",
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = p.accent,
                modifier = Modifier.width(52.dp).padding(start = 12.dp),
            )
            Text(
                WeatherCode.describe(d.weatherCode),
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = p.muted, modifier = Modifier.weight(1f),
            )
            Text(
                "${Formatters.number(d.tempMax, 0)}° / ${Formatters.number(d.tempMin, 0)}°",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = p.ink,
            )
        }
    }
}

@Composable
private fun AirQualityCard(
    aq: dev.mascwa.pulse.data.weather.AirQuality,
    onExplain: (String, List<Explainer>) -> Unit = { _, _ -> },
) {
    val p = Pulse.colors
    NeonPanel(
        Modifier.fillMaxWidth().clickable {
            WeatherExplainers.airQuality(aq.usAqi, aq.europeanAqi)?.let { onExplain("Air quality", listOf(it)) }
        },
        corners = true,
        padding = PaddingValues(16.dp),
    ) {
        Column {
            Text("AIR QUALITY", fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.5.sp, color = p.accent)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("EU AQI", "${Formatters.number(aq.europeanAqi, 0)}")
                Stat("US AQI", "${Formatters.number(aq.usAqi, 0)}")
                Stat("PM2.5", "${Formatters.number(aq.pm25, 0)}")
                Stat("PM10", "${Formatters.number(aq.pm10, 0)}")
            }
            Text(
                WeatherFormat.aqiLabel(aq.europeanAqi).uppercase(),
                fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 0.6.sp, color = p.accent,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    CyberHeader(text)
}
