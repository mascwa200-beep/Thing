package dev.mascwa.pulse.feature.weather

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.AirQualityGuide
import dev.mascwa.pulse.core.telemetry.Explainer
import dev.mascwa.pulse.core.telemetry.WeatherComfort
import dev.mascwa.pulse.core.telemetry.WeatherExplainers
import dev.mascwa.pulse.core.telemetry.WeatherUnits
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.weather.DailyPoint
import dev.mascwa.pulse.data.weather.HourlyPoint
import dev.mascwa.pulse.data.weather.WeatherCode
import dev.mascwa.pulse.feature.common.ChartBand
import dev.mascwa.pulse.feature.common.ChartSeries
import dev.mascwa.pulse.feature.common.CyberChipCut
import dev.mascwa.pulse.feature.common.CyberHeader
import dev.mascwa.pulse.feature.common.CyberRowFrame
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.ExplainerDialog
import dev.mascwa.pulse.feature.common.LcarsChip
import dev.mascwa.pulse.feature.common.LcarsTabRow
import dev.mascwa.pulse.feature.common.LcarsField
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LcarsHistogram
import dev.mascwa.pulse.feature.common.LcarsIcons
import dev.mascwa.pulse.feature.common.LcarsMeter
import dev.mascwa.pulse.feature.common.LcarsTimeChart
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The weather tab's own sections, mirroring the Markets sub-tab pattern. */
private enum class WeatherTab(val label: String) {
    NOW("NOW"), HOURS("HOURS"), DAYS("DAYS"), AIR("AIR")
}

@Composable
fun WeatherScreen(vm: WeatherViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val tabIdx by vm.tabIndex.collectAsStateWithLifecycle()
    val tab = WeatherTab.entries[tabIdx.coerceIn(0, WeatherTab.entries.lastIndex)]

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
            LcarsTabRow(
                tabs = WeatherTab.entries.map { it.label },
                selected = tab.ordinal,
                onSelect = { vm.tabIndex.value = it },
            )
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
    item { StaleBanner(c.state.data) }

    if (c.showSearch) {
        item { CitySearchField(c.query, c.onQuery) }
        items(
            c.state.searchResults.distinctBy { "${it.name}_${it.latitude}" },
            key = { "${it.name}_${it.latitude}" },
        ) { loc ->
            CityResultRow(loc.name, loc.country) { c.vm.addAndSelect(loc); c.onAdded() }
        }
    }

    item {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            item {
                LcarsChip(
                    "My location",
                    selected = c.state.useDeviceLocation,
                    onClick = { c.vm.useDeviceLocation() },
                )
            }
            itemsIndexed(c.state.savedLocations) { i, loc ->
                LcarsChip(
                    loc.name,
                    selected = !c.state.useDeviceLocation && i == c.state.selectedIndex,
                    onClick = { c.vm.selectSaved(i) },
                )
            }
        }
    }

    if (c.state.needsLocationPermission && c.state.useDeviceLocation) {
        item { LocationPermissionCard(c.onRequestPermission) }
    }
}

/** The city search bar, in the same frame the knowledge base and radio tuner use. */
@Composable
private fun CitySearchField(query: String, onQuery: (String) -> Unit) {
    val p = Pulse.colors
    LcarsField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.padding(vertical = 4.dp),
        placeholder = "▸ SEARCH FOR A CITY",
    )
}

/** One search hit. The whole row adds the city, so there is no separate button to aim at. */
@Composable
private fun CityResultRow(name: String, country: String, onAdd: () -> Unit) {
    val p = Pulse.colors
    LcarsFrame(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(onClick = onAdd),
        padding = PaddingValues(horizontal = 13.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    name, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp, color = p.ink,
                )
                Text(country, fontFamily = JetBrainsMono, fontSize = 9.sp, color = p.muted)
            }
            Text(
                "ADD ▸", fontFamily = JetBrainsMono, fontSize = 10.sp,
                letterSpacing = 1.sp, color = p.accent,
            )
        }
    }
}

@Composable
private fun LocationPermissionCard(onRequest: () -> Unit) {
    val p = Pulse.colors
    LcarsFrame(Modifier.fillMaxWidth().padding(vertical = 4.dp), accent = p.amber) {
        Column {
            Text(
                "LOCATION", fontFamily = JetBrainsMono, fontSize = 11.sp,
                letterSpacing = 1.5.sp, color = p.amber,
            )
            Text(
                "Grant location access for the weather where you are, or search for a city above.",
                fontFamily = ChakraPetch, fontSize = 13.sp, color = p.ink,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "▸ GRANT ACCESS",
                fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.sp, color = p.amber,
                modifier = Modifier.padding(top = 10.dp).clickable(onClick = onRequest),
            )
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
            // The forecast starts at midnight, so everything here is measured from the present
            // hour rather than from the start of the array.
            val ahead = hourly.drop(WeatherFormat.nowIndex(hourly.map { it.timeIso })).take(48)
            item { SectionLabel("Next 24 hours") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ahead.take(24), key = { it.timeIso }) { h -> HourCell(h, wd.tempUnitSymbol) }
                }
            }
            item { TemperatureChart(ahead, wd.tempUnitSymbol) }
            item { WindChart(ahead, wd.windUnitSymbol) }
            item { UvChart(ahead.take(24)) }
        }
    }
}

/**
 * Air temperature against what it feels like.
 *
 * The gap between the two lines *is* the reading: where they run together the air is simply the
 * air, and where they part company something — wind, sun, humidity — is doing the work.
 */
@Composable
private fun TemperatureChart(hours: List<HourlyPoint>, unit: String) {
    val p = Pulse.colors
    val air = hours.pointsOf { it.temperature }
    if (air.size < 2) return
    val feels = hours.pointsOf { it.apparentTemperature }
    val axis = rememberHourAxis()
    Column(Modifier.padding(top = 4.dp)) {
        SectionLabel("Temperature · next 48 hours")
        LcarsTimeChart(
            series = listOfNotNull(
                ChartSeries("Air", air, p.accent),
                feels.takeIf { it.size >= 2 }?.let { ChartSeries("Feels like", it, p.sky) },
            ),
            modifier = Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp),
            xFormat = axis,
        )
        ChartKey(listOf("Air" to p.accent, "Feels like" to p.sky), unit)
    }
}

/**
 * Mean wind with the gusts drawn over it.
 *
 * A forecast quotes the mean, but the gust is what takes a branch down or catches a door, so both
 * belong on the same axis where the distance between them is visible.
 */
@Composable
private fun WindChart(hours: List<HourlyPoint>, unit: String) {
    val p = Pulse.colors
    val mean = hours.pointsOf { it.windSpeed }
    val gust = hours.pointsOf { it.windGust }
    if (mean.size < 2 && gust.size < 2) return
    val axis = rememberHourAxis()
    Column(Modifier.padding(top = 4.dp)) {
        SectionLabel("Wind · next 48 hours")
        LcarsTimeChart(
            series = listOfNotNull(
                gust.takeIf { it.size >= 2 }?.let { ChartSeries("Gusts", it, p.amber) },
                mean.takeIf { it.size >= 2 }?.let { ChartSeries("Mean", it, p.accent, filled = true) },
            ),
            modifier = Modifier.fillMaxWidth().height(140.dp).padding(top = 8.dp),
            forceMin = 0.0,
            xFormat = axis,
        )
        ChartKey(listOf("Mean" to p.accent, "Gusts" to p.amber), unit)
    }
}

/**
 * UV through the day, against the bands that decide whether it matters.
 *
 * The scale is pinned by the bands rather than by the data, so a winter reading sits visibly flat
 * along the bottom instead of being stretched to fill the box and looking like a summer noon.
 */
@Composable
private fun UvChart(hours: List<HourlyPoint>) {
    val p = Pulse.colors
    val uv = hours.pointsOf { it.uvIndex }
    if (uv.size < 2 || uv.none { it.second >= 1.0 }) return
    val axis = rememberHourAxis()
    Column(Modifier.padding(top = 4.dp)) {
        SectionLabel("UV index · next 24 hours")
        LcarsTimeChart(
            series = listOf(ChartSeries("UV", uv, p.amber, filled = true)),
            modifier = Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp),
            bands = listOf(
                ChartBand(3.0, 6.0, p.amber),
                ChartBand(6.0, 8.0, p.magenta),
                ChartBand(8.0, 12.0, p.negative),
            ),
            forceMin = 0.0,
            xFormat = axis,
        )
        Text(
            "Shaded from 3 — moderate, high above 6, very high above 8",
            fontFamily = JetBrainsMono, fontSize = 9.sp, color = p.muted,
            modifier = Modifier.padding(top = 6.dp),
        )
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
            item { HighLowChart(wd.daily, wd.tempUnitSymbol) }
            item { SunshineChart(wd.daily) }
            item { RainHoursChart(wd.daily) }
        }
    }
}

/** The week's shape: how warm the days get and how far the nights fall. */
@Composable
private fun HighLowChart(days: List<DailyPoint>, unit: String) {
    val p = Pulse.colors
    val high = days.dailyPointsOf { it.tempMax }
    val low = days.dailyPointsOf { it.tempMin }
    if (high.size < 2 && low.size < 2) return
    Column(Modifier.padding(top = 4.dp)) {
        SectionLabel("High and low")
        LcarsTimeChart(
            series = listOfNotNull(
                high.takeIf { it.size >= 2 }?.let { ChartSeries("High", it, p.amber) },
                low.takeIf { it.size >= 2 }?.let { ChartSeries("Low", it, p.sky) },
            ),
            modifier = Modifier.fillMaxWidth().height(140.dp).padding(top = 8.dp),
        )
        ChartKey(listOf("High" to p.amber, "Low" to p.sky), unit)
    }
}

/**
 * Sunshine against the daylight available to fill it — the honest "how grey was it" reading.
 *
 * Hours of sun on their own say nothing: five is a bright winter day and a dismal summer one. The
 * gap between the filled line and the daylight envelope is the part worth looking at.
 */
@Composable
private fun SunshineChart(days: List<DailyPoint>) {
    val p = Pulse.colors
    val sun = days.dailyPointsOf { s -> s.sunshineSeconds?.let { it / 3600.0 } }
    val daylight = days.dailyPointsOf { s -> s.daylightSeconds?.let { it / 3600.0 } }
    if (sun.size < 2) return
    Column(Modifier.padding(top = 4.dp)) {
        SectionLabel("Sunshine against daylight")
        LcarsTimeChart(
            series = listOfNotNull(
                daylight.takeIf { it.size >= 2 }?.let { ChartSeries("Daylight", it, p.lineSoft) },
                ChartSeries("Sunshine", sun, p.amber, filled = true),
            ),
            modifier = Modifier.fillMaxWidth().height(130.dp).padding(top = 8.dp),
            forceMin = 0.0,
        )
        ChartKey(listOf("Sunshine" to p.amber, "Daylight" to p.lineSoft), "hours")
    }
}

/**
 * Hours of rain per day, which answers a different question from the total.
 *
 * Ten millimetres in one hour is a thunderstorm you wait out; the same ten spread over twelve is a
 * day that never quite dries. Absent entirely when the week is dry, rather than drawing an empty
 * frame to say so.
 */
@Composable
private fun RainHoursChart(days: List<DailyPoint>) {
    val p = Pulse.colors
    val bars = days.mapNotNull { d -> d.precipitationHours?.let { WeatherFormat.shortDayLabel(d.dateIso) to it } }
    if (bars.size < 2 || bars.none { it.second > 0.0 }) return
    Column(Modifier.padding(top = 4.dp)) {
        SectionLabel("Hours of rain")
        LcarsHistogram(
            bars = bars,
            modifier = Modifier.fillMaxWidth().height(90.dp).padding(top = 8.dp),
            color = p.sky,
        )
        Text(
            "How long it rains, not how much — a total says nothing about the shape of a day.",
            fontFamily = JetBrainsMono, fontSize = 9.sp, color = p.muted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** (epoch millis, value) for every hour that has one, dropping the rest rather than guessing. */
private fun List<HourlyPoint>.pointsOf(pick: (HourlyPoint) -> Double?): List<Pair<Long, Double>> =
    mapNotNull { h -> WeatherFormat.parseHourly(h.timeIso)?.time?.let { t -> pick(h)?.let { t to it } } }

private fun List<DailyPoint>.dailyPointsOf(pick: (DailyPoint) -> Double?): List<Pair<Long, Double>> =
    mapNotNull { d -> WeatherFormat.parseDate(d.dateIso)?.time?.let { t -> pick(d)?.let { t to it } } }

/**
 * "Sat 14" for the hourly axis.
 *
 * The chart's own label falls back to dates once a run passes about a day and a half, which loses
 * the time of day on a two-day forecast; a bare clock, meanwhile, prints the same "12:00" twice.
 * The weekday and hour together are unambiguous at both ends.
 */
@Composable
private fun rememberHourAxis(): (Long) -> String {
    val fmt = remember { SimpleDateFormat("EEE HH", Locale.getDefault()) }
    return { t -> fmt.format(Date(t)) }
}

/** A one-line key, because two overlaid series with nothing naming them is a puzzle, not a chart. */
@Composable
private fun ChartKey(entries: List<Pair<String, Color>>, suffix: String? = null) {
    val p = Pulse.colors
    Row(
        Modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color))
                Text(
                    label, fontFamily = JetBrainsMono, fontSize = 9.sp, color = p.muted,
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
        }
        suffix?.let { Text(it, fontFamily = JetBrainsMono, fontSize = 9.sp, color = p.muted) }
    }
}

@Composable
private fun AirBody(
    vm: WeatherViewModel,
    chrome: WeatherChrome,
    onExplain: (String, List<Explainer>) -> Unit,
) {
    WeatherTabScaffold(vm, chrome) { wd ->
        wd.airQuality?.let { aq ->
            item { AirQualityCard(aq, onExplain) }
            item { PollutantsCard(aq, onExplain) }
            item { PollenCard(aq, onExplain) }
        }
    }
}

/**
 * What is actually in the air, rather than the index rolled up from it.
 *
 * Each pollutant is drawn against its own WHO guideline, so the bars are comparable to each other
 * in a way the raw concentrations are not — carbon monoxide is numerically enormous and entirely
 * ordinary, and only the ratio says so. The one furthest above its own line is named, because the
 * advice follows the driver: ozone means stay in through the afternoon, particulates mean shut the
 * windows.
 */
@Composable
private fun PollutantsCard(
    aq: dev.mascwa.pulse.data.weather.AirQuality,
    onExplain: (String, List<Explainer>) -> Unit,
) {
    val p = Pulse.colors
    val readings = listOfNotNull(
        AirQualityGuide.assess(AirQualityGuide.Pollutant.PM2_5, aq.pm25),
        AirQualityGuide.assess(AirQualityGuide.Pollutant.PM10, aq.pm10),
        AirQualityGuide.assess(AirQualityGuide.Pollutant.OZONE, aq.ozone),
        AirQualityGuide.assess(AirQualityGuide.Pollutant.NITROGEN_DIOXIDE, aq.nitrogenDioxide),
        AirQualityGuide.assess(AirQualityGuide.Pollutant.SULPHUR_DIOXIDE, aq.sulphurDioxide),
        AirQualityGuide.assess(AirQualityGuide.Pollutant.CARBON_MONOXIDE, aq.carbonMonoxide),
    )
    if (readings.isEmpty()) return
    val driver = AirQualityGuide.dominant(readings)

    NeonPanel(Modifier.fillMaxWidth(), corners = true, padding = PaddingValues(16.dp)) {
        Column {
            Text(
                "WHAT IS IN THE AIR", fontFamily = JetBrainsMono, fontSize = 11.sp,
                letterSpacing = 1.5.sp, color = p.accent,
            )
            AirQualityGuide.summary(readings)?.let {
                Text(
                    it, fontFamily = ChakraPetch, fontSize = 13.sp, color = p.ink,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            readings.forEach { r -> PollutantRow(r, r == driver, onExplain) }
            AirQualityGuide.scaleGap(aq.europeanAqi, aq.usAqi)?.let {
                Text(
                    "The two indices disagree — tap to see why",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = p.muted,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clickable {
                            WeatherExplainers.aqiScales(aq.europeanAqi, aq.usAqi)
                                ?.let { e -> onExplain("Air quality scales", listOf(e)) }
                        },
                )
            }
            aq.dust?.takeIf { it >= 5.0 }?.let {
                Text(
                    "Dust ${Formatters.number(it, 0)} µg/m³ — wind-blown mineral dust is reaching here " +
                        "and is counted inside PM10 above.",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = p.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Text(
                "Bars are each pollutant against its own WHO 2021 guideline, so they compare fairly.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = p.muted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** One pollutant: name, concentration, and how far along its own guideline it sits. */
@Composable
private fun PollutantRow(
    reading: AirQualityGuide.Reading,
    isDriver: Boolean,
    onExplain: (String, List<Explainer>) -> Unit,
) {
    val p = Pulse.colors
    val tint = when (reading.band) {
        AirQualityGuide.Band.WELL_UNDER -> p.positive
        AirQualityGuide.Band.WITHIN -> p.accent
        AirQualityGuide.Band.ABOVE -> p.amber
        AirQualityGuide.Band.FAR_ABOVE -> p.negative
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clickable {
                WeatherExplainers.pollutant(reading)
                    ?.let { onExplain(reading.pollutant.label, listOf(it)) }
            },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (isDriver) "▸ ${reading.pollutant.label}" else reading.pollutant.label,
                fontFamily = JetBrainsMono, fontSize = 10.sp,
                color = if (isDriver) p.ink else p.ink2,
            )
            Text(
                "${Formatters.number(reading.value, 0)} µg/m³",
                fontFamily = ChakraPetch, fontSize = 12.sp, color = tint,
            )
        }
        // The scale runs to twice the guideline, so "at the line" sits at the halfway mark and
        // anything worse is unmistakably past it rather than pinned at a full bar.
        LcarsMeter(
            value = reading.ratio.coerceAtMost(2.0),
            min = 0.0,
            max = 2.0,
            modifier = Modifier.fillMaxWidth().height(5.dp).padding(top = 4.dp),
            bands = listOf(ChartBand(0.0, reading.ratio.coerceIn(0.0, 2.0), tint)),
            markerColor = p.ink,
        )
    }
}

/**
 * Pollen, where the model has it.
 *
 * The forecast only covers Europe, so this is absent rather than zero everywhere else — which is
 * why the species with no reading are dropped upstream instead of being carried as a column of
 * noughts that would read as a measurement.
 */
@Composable
private fun PollenCard(
    aq: dev.mascwa.pulse.data.weather.AirQuality,
    onExplain: (String, List<Explainer>) -> Unit,
) {
    val p = Pulse.colors
    val present = aq.pollen.filter { it.grainsPerM3 > 0.0 }.sortedByDescending { it.grainsPerM3 }
    if (present.isEmpty()) return
    NeonPanel(Modifier.fillMaxWidth(), corners = true, padding = PaddingValues(16.dp)) {
        Column {
            Text(
                "POLLEN", fontFamily = JetBrainsMono, fontSize = 11.sp,
                letterSpacing = 1.5.sp, color = p.accent,
            )
            present.forEach { pc ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clickable {
                            WeatherExplainers.pollen(pc.species, pc.grainsPerM3)
                                ?.let { e -> onExplain("${pc.species} pollen", listOf(e)) }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(pc.species, fontFamily = JetBrainsMono, fontSize = 10.sp, color = p.ink2)
                    Text(
                        "${Formatters.number(pc.grainsPerM3, 0)} · " +
                            (AirQualityGuide.pollenBand(pc.grainsPerM3) ?: "—"),
                        fontFamily = ChakraPetch, fontSize = 12.sp, color = p.ink,
                    )
                }
            }
            Text(
                "grains/m³ on a general scale — the count that provokes symptoms differs by species " +
                    "and by person.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = p.muted,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
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
