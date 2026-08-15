package dev.mascwa.pulse.feature.sky

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.Explainer
import dev.mascwa.pulse.core.telemetry.HfPropagation
import dev.mascwa.pulse.core.telemetry.SolarActivity
import dev.mascwa.pulse.core.telemetry.SpaceWeatherExplainers
import dev.mascwa.pulse.data.space.ScaleForecast
import dev.mascwa.pulse.data.space.SolarRegion
import dev.mascwa.pulse.data.space.SpaceWeather
import dev.mascwa.pulse.feature.common.ChartBand
import dev.mascwa.pulse.feature.common.ChartSeries
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.ExplainerDialog
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LcarsGauge
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.feature.common.LcarsIcons
import dev.mascwa.pulse.feature.common.LcarsMeter
import dev.mascwa.pulse.feature.common.LcarsStatBlock
import dev.mascwa.pulse.feature.common.LcarsTimeChart
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.NeonChip
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/**
 * The heliophysics console.
 *
 * The repository pulls the whole free SWPC product suite; this is where it becomes readable. Six
 * instruments rather than one scrolling list, because the questions people actually bring here are
 * different questions: is anything happening (NOW), what is the Sun doing (SUN), can I see the
 * lights tonight (AURORA), how bad does it get (STORMS), what is shortwave like (RADIO), and what
 * has NOAA formally issued (ALERTS).
 *
 * Every reading is tappable for a plain-English explanation, and a missing feed drops its panel
 * rather than showing a zero — an em dash means "we do not know", never "there is none".
 */
private enum class SpaceTab(val label: String) {
    NOW("NOW"),
    SUN("SUN"),
    AURORA("AURORA"),
    STORMS("STORMS"),
    RADIO("RADIO"),
    ALERTS("ALERTS"),
}

@Composable
fun SpaceWeatherScreen(vm: SpaceWeatherViewModel, onBack: (() -> Unit)? = null) {
    PulseScaffold(
        title = "Space Weather",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(LcarsIcons.ArrowBack, "Back") }
        },
        actions = {
            IconButton(onClick = { vm.refresh() }) { Icon(LcarsIcons.Refresh, "Refresh") }
        },
    ) { innerPadding ->
        SpaceWeatherBody(vm, Modifier.padding(innerPadding))
    }
}

/** The console body, scaffold-free so it can also be hosted inside another screen. */
@Composable
fun SpaceWeatherBody(vm: SpaceWeatherViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = Pulse.colors
    var tab by remember { mutableStateOf(SpaceTab.NOW) }
    // Tap any metric -> plain-language explanation (title + Explainer list).
    var explainer by remember { mutableStateOf<Pair<String, List<Explainer>>?>(null) }
    val onExplain: (String, List<Explainer>) -> Unit = { title, items -> explainer = title to items }
    // A fresh scroll position per instrument: carrying one tab's offset into another lands the
    // reader halfway down a panel they have not seen.
    val listState = remember(tab) { LazyListState() }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SpaceTab.entries.forEach { t ->
                NeonChip(t.label, selected = t == tab, onClick = { tab = t })
            }
        }
        PullToRefreshBox(
            isRefreshing = state.loading && state.data != null,
            onRefresh = { vm.refresh() },
            modifier = Modifier.weight(1f),
        ) {
            when {
                state.isInitialLoading -> LoadingState()
                state.isError -> ErrorState(state.error ?: "Error", onRetry = { vm.refresh() })
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val sw = state.data
                    if (state.stale) item { StaleBanner(true) }
                    when (tab) {
                        SpaceTab.NOW -> nowTab(sw, c, onExplain)
                        SpaceTab.SUN -> sunTab(sw, c, onExplain)
                        SpaceTab.AURORA -> auroraTab(sw, c, onExplain)
                        SpaceTab.STORMS -> stormsTab(sw, c, onExplain)
                        SpaceTab.RADIO -> radioTab(sw, c, onExplain)
                        SpaceTab.ALERTS -> alertsTab(sw, c)
                    }
                    item { SourceNote() }
                }
            }
        }
    }

    explainer?.let { (title, items) ->
        ExplainerDialog(title, items, onDismiss = { explainer = null })
    }
}

// ---- NOW ---------------------------------------------------------------------------------------

private fun LazyListScope.nowTab(
    sw: SpaceWeather?,
    c: NightwirePalette,
    onExplain: (String, List<Explainer>) -> Unit,
) {
    item {
        val worst = maxOf(sw?.rLevel ?: 0, sw?.sLevel ?: 0, sw?.gLevel ?: 0)
        LcarsFrame(Modifier.fillMaxWidth(), accent = severityColor(worst, c)) {
            Column {
                Text("CONDITIONS", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
                Text(
                    sw?.headline ?: "—",
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    color = severityColor(worst, c),
                )
                Text(
                    "Tap any reading for a plain-English explanation.",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
    item { LcarsHeaderBar("NOAA scales now") }
    item { ScaleRow('R', sw?.rLevel, "Radio blackouts", c, onExplain) }
    item { ScaleRow('S', sw?.sLevel, "Radiation storms", c, onExplain) }
    item { ScaleRow('G', sw?.gLevel, "Geomagnetic storms", c, onExplain) }

    item { LcarsHeaderBar("Right now") }
    item {
        val kp = sw?.kp
        val stormy = (kp ?: 0.0) >= 5
        LcarsFrame(
            Modifier.fillMaxWidth().clickable(enabled = kp != null) {
                onExplain("Planetary K-index", listOf(SpaceWeatherExplainers.kp(kp!!)))
            },
            accent = if (stormy) c.magenta else c.accent,
        ) {
            Column {
                Text("PLANETARY K-INDEX", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
                LcarsGauge(
                    value = kp,
                    min = 0.0,
                    max = 9.0,
                    modifier = Modifier.fillMaxWidth().height(132.dp).padding(top = 6.dp),
                    bands = kpBands(c),
                    label = sw?.stormLevel ?: "—",
                    valueColor = if (stormy) c.magenta else c.accent,
                )
            }
        }
    }
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LcarsStatBlock(
                "Solar wind",
                sw?.solarWindSpeed?.let { "${it.toInt()} km/s" } ?: "—",
                Modifier.weight(1f).clickable(enabled = sw?.solarWindSpeed != null) {
                    onExplain("Solar wind", listOf(SpaceWeatherExplainers.solarWind(sw!!.solarWindSpeed!!)))
                },
            )
            LcarsStatBlock(
                "Bz (IMF)",
                sw?.bz?.let { fmt("%+.1f nT", it) } ?: "—",
                Modifier.weight(1f).clickable(enabled = sw?.bz != null) {
                    onExplain("Bz (IMF)", listOf(SpaceWeatherExplainers.bz(sw!!.bz!!)))
                },
                valueColor = if ((sw?.bz ?: 0.0) < -5) c.magenta else c.ink,
            )
        }
    }
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LcarsStatBlock(
                "X-ray flare",
                sw?.flareLabel ?: "quiet",
                Modifier.weight(1f).clickable {
                    onExplain("X-ray flux", listOf(SpaceWeatherExplainers.xrayFlux(sw?.xrayFlux)))
                },
                valueColor = flareColor(sw?.flareLabel, c),
            )
            LcarsStatBlock(
                "Aurora here",
                sw?.auroraProbabilityPct?.let { "$it%" } ?: "—",
                Modifier.weight(1f).clickable(enabled = sw?.auroraProbabilityPct != null) {
                    onExplain("Aurora chance", listOf(SpaceWeatherExplainers.aurora(sw!!.auroraProbabilityPct!!)))
                },
                valueColor = if ((sw?.auroraProbabilityPct ?: 0) >= 25) c.violet else c.ink,
            )
        }
    }
}

// ---- SUN ---------------------------------------------------------------------------------------

private fun LazyListScope.sunTab(
    sw: SpaceWeather?,
    c: NightwirePalette,
    onExplain: (String, List<Explainer>) -> Unit,
) {
    item {
        LcarsFrame(
            Modifier.fillMaxWidth().clickable {
                onExplain("X-ray flux", listOf(SpaceWeatherExplainers.xrayFlux(sw?.xrayFlux)))
            },
            accent = flareColor(sw?.flareLabel, c),
        ) {
            Column {
                Text("SOFT X-RAY FLUX", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
                Text(
                    sw?.flareLabel ?: "below A-class",
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 40.sp,
                    color = flareColor(sw?.flareLabel, c),
                )
                sw?.xrayFlux?.let {
                    Text(
                        fmt("%.2e W/m² · GOES 0.1-0.8 nm", it),
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink2,
                    )
                }
            }
        }
    }
    // A log axis, because flare classes are decades: each letter is ten times the last.
    val xray = sw?.xrayPoints.orEmpty().filter { it.v > 0.0 }
    if (xray.size >= 2) {
        item { LcarsHeaderBar("X-ray flux · last day", trailing = "A → X") }
        item {
            val logged = xray.map { it.t to log10(it.v) }
            val top = maxOf(-4.0, logged.maxOf { it.second } + 0.3)
            LcarsTimeChart(
                series = listOf(ChartSeries("X-ray", logged, flareColor(sw?.flareLabel, c), filled = true)),
                modifier = Modifier.fillMaxWidth().height(150.dp),
                bands = listOf(
                    ChartBand(-6.0, -5.0, c.amber),        // C class
                    ChartBand(-5.0, -4.0, c.magenta),      // M class
                    ChartBand(-4.0, top, c.negative),      // X class
                ),
                forceMin = -8.0,
                forceMax = top,
                // Label the axis with the class letter rather than a meaningless exponent.
                valueFormat = { v -> SolarActivity.flareClass(10.0.pow(v))?.letter?.toString() ?: "·" },
            )
        }
    }
    item { LcarsHeaderBar("Solar output") }
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LcarsStatBlock(
                "F10.7 radio flux",
                sw?.f107?.let { "${it.toInt()} sfu" } ?: "—",
                Modifier.weight(1f).clickable(enabled = sw?.f107 != null) {
                    onExplain("Solar radio flux", listOf(SpaceWeatherExplainers.solarFlux(sw!!.f107!!)))
                },
            )
            LcarsStatBlock(
                "Proton flux",
                sw?.protonFlux?.let { fmt("%.2g pfu", it) } ?: "—",
                Modifier.weight(1f).clickable(enabled = sw?.protonFlux != null) {
                    onExplain("Proton flux", listOf(SpaceWeatherExplainers.protonFlux(sw!!.protonFlux!!)))
                },
                valueColor = if ((sw?.sLevel ?: 0) > 0) c.amber else c.ink,
            )
        }
    }
    sw?.f107Mean?.let { mean ->
        item {
            Text(
                "90-day mean F10.7: ${mean.toInt()} sfu — the trend behind today's number.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            )
        }
    }

    val regions = sw?.regions.orEmpty()
    if (regions.isNotEmpty()) {
        item { LcarsHeaderBar("Sunspot groups", trailing = "${regions.size}") }
        // No key: these come off a feed, and a repeated region number would crash the list.
        items(regions) { region -> RegionCard(region, c) }
    }
}

// ---- AURORA ------------------------------------------------------------------------------------

private fun LazyListScope.auroraTab(
    sw: SpaceWeather?,
    c: NightwirePalette,
    onExplain: (String, List<Explainer>) -> Unit,
) {
    item {
        val pct = sw?.auroraProbabilityPct
        val bright = (pct ?: 0) >= 25
        LcarsFrame(
            Modifier.fillMaxWidth().clickable(enabled = pct != null) {
                onExplain("Aurora chance", listOf(SpaceWeatherExplainers.aurora(pct!!)))
            },
            accent = if (bright) c.violet else c.accent,
        ) {
            Column {
                Text("OVERHEAD PROBABILITY", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
                if (pct != null) {
                    LcarsGauge(
                        value = pct.toDouble(),
                        min = 0.0,
                        max = 100.0,
                        modifier = Modifier.fillMaxWidth().height(132.dp).padding(top = 6.dp),
                        bands = listOf(
                            ChartBand(15.0, 50.0, c.sky),
                            ChartBand(50.0, 100.0, c.violet),
                        ),
                        label = "at your location",
                        unit = "%",
                        valueColor = if (bright) c.violet else c.accent,
                    )
                } else {
                    Text(
                        "Grant location to get the NOAA OVATION probability for where you are.",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Text(
                    sw?.auroraChance ?: "—",
                    style = MaterialTheme.typography.titleMedium, color = c.sky,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
    // Bz is the single best short-term predictor, so it belongs on the aurora page, not buried.
    val bz = sw?.bzPoints.orEmpty()
    if (bz.size >= 2) {
        item { LcarsHeaderBar("IMF Bz", trailing = "southward drives aurora") }
        item {
            val lowest = bz.minOf { it.v }
            LcarsTimeChart(
                series = listOf(ChartSeries("Bz", bz.map { it.t to it.v }, c.sky)),
                modifier = Modifier.fillMaxWidth().height(140.dp),
                // Band only what the data actually reaches: a fixed -20 floor would squash a quiet
                // day into a sliver of the chart.
                bands = if (lowest < -5.0) listOf(ChartBand(lowest, -5.0, c.magenta)) else emptyList(),
                valueFormat = { fmt("%+.0f", it) },
            )
        }
        item {
            Text(
                "Below −5 nT the Sun's field is pointing south against Earth's and energy couples in.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            )
        }
    }
    kpChart(sw, c, "Kp · recent")
}

// ---- STORMS ------------------------------------------------------------------------------------

private fun LazyListScope.stormsTab(
    sw: SpaceWeather?,
    c: NightwirePalette,
    onExplain: (String, List<Explainer>) -> Unit,
) {
    item { LcarsHeaderBar("Worst in the last 24 hours") }
    val past = sw?.scales24h
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LcarsStatBlock("R", scaleText('R', past?.r), Modifier.weight(1f),
                valueColor = severityColor(past?.r ?: 0, c))
            LcarsStatBlock("S", scaleText('S', past?.s), Modifier.weight(1f),
                valueColor = severityColor(past?.s ?: 0, c))
            LcarsStatBlock("G", scaleText('G', past?.g), Modifier.weight(1f),
                valueColor = severityColor(past?.g ?: 0, c))
        }
    }

    val forecast = sw?.scaleForecast.orEmpty()
    if (forecast.isNotEmpty()) {
        item { LcarsHeaderBar("NOAA forecast", trailing = "${forecast.size} days") }
        // No key: two blank labels off a malformed feed would be a duplicate-key crash.
        items(forecast) { day -> ForecastCard(day, c, onExplain) }
    }

    kpChart(sw, c, "Kp · the geomagnetic record")

    val wind = sw?.windPoints.orEmpty()
    if (wind.size >= 2) {
        item { LcarsHeaderBar("Solar wind speed", trailing = "km/s") }
        item {
            LcarsTimeChart(
                series = listOf(ChartSeries("Speed", wind.map { it.t to it.v }, c.amber, filled = true)),
                modifier = Modifier.fillMaxWidth().height(140.dp),
                bands = listOf(ChartBand(500.0, wind.maxOf { it.v }.coerceAtLeast(501.0), c.amber)),
                valueFormat = { fmt("%.0f", it) },
            )
        }
    }
}

// ---- RADIO -------------------------------------------------------------------------------------

private fun LazyListScope.radioTab(
    sw: SpaceWeather?,
    c: NightwirePalette,
    onExplain: (String, List<Explainer>) -> Unit,
) {
    val muf = HfPropagation.mufDisplay(sw?.f107, sw?.kp)
    item {
        LcarsFrame(
            Modifier.fillMaxWidth().clickable(enabled = muf != null) {
                onExplain(
                    "Maximum usable frequency",
                    listOf(SpaceWeatherExplainers.maxUsableFrequency(muf!!.toDouble())),
                )
            },
        ) {
            Column {
                Text("SHORTWAVE CONDITIONS", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
                Text(
                    muf?.let { "MUF ~$it MHz" } ?: "—",
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 34.sp,
                    color = c.accent,
                )
                Text(
                    HfPropagation.summary(sw?.f107, sw?.kp, sw?.xrayFlux),
                    style = MaterialTheme.typography.bodySmall, color = c.ink2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    item {
        val r = sw?.rLevel ?: 0
        LcarsFrame(
            Modifier.fillMaxWidth().clickable { onExplain("Radio blackouts", listOf(SpaceWeatherExplainers.noaaScale('R', r))) },
            accent = severityColor(r, c),
        ) {
            Column {
                Text("RADIO BLACKOUT", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
                Text(
                    SolarActivity.scaleLabel('R', r),
                    style = MaterialTheme.typography.titleMedium, color = severityColor(r, c),
                )
                Text(
                    SolarActivity.effect('R', r),
                    style = MaterialTheme.typography.bodySmall, color = c.ink2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    item { LcarsHeaderBar("Band conditions", trailing = "day / night") }
    val report = HfPropagation.report(sw?.f107, sw?.kp, sw?.xrayFlux)
    items(report, key = { it.name }) { band -> BandRow(band, c, onExplain) }
    item {
        Text(
            "Modelled from F10.7, Kp and the X-ray flux — a guide to what should be open, not a " +
                "measurement of your own antenna.",
            fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
        )
    }
}

// ---- ALERTS ------------------------------------------------------------------------------------

private fun LazyListScope.alertsTab(sw: SpaceWeather?, c: NightwirePalette) {
    val alerts = sw?.alerts.orEmpty().distinctBy { it.title + it.issued }
    item { LcarsHeaderBar("Issued by NOAA", trailing = if (alerts.isEmpty()) null else "${alerts.size}") }
    if (alerts.isEmpty()) {
        item {
            Text(
                "No active space-weather alerts. NOAA issues these only when a watch, warning or " +
                    "alert threshold is actually crossed.",
                style = MaterialTheme.typography.bodyMedium, color = c.muted,
                modifier = Modifier.padding(4.dp),
            )
        }
    } else {
        items(alerts, key = { it.title + it.issued }) { a ->
            LcarsFrame(Modifier.fillMaxWidth(), accent = c.amber) {
                Column {
                    Text(a.title, style = MaterialTheme.typography.titleSmall, color = c.amber)
                    if (a.issued.isNotBlank()) {
                        Text(a.issued, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted)
                    }
                    Text(
                        a.message, style = MaterialTheme.typography.bodySmall, color = c.ink2,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

// ---- shared pieces -----------------------------------------------------------------------------

/** The Kp history chart, shared by the aurora and storm instruments. */
private fun LazyListScope.kpChart(sw: SpaceWeather?, c: NightwirePalette, title: String) {
    val points = kpPoints(sw)
    if (points.size < 2) return
    item { LcarsHeaderBar(title, trailing = "G1 at Kp 5") }
    item {
        LcarsTimeChart(
            series = listOf(ChartSeries("Kp", points, c.accent, filled = true)),
            modifier = Modifier.fillMaxWidth().height(150.dp),
            bands = kpBands(c),
            forceMin = 0.0,
            forceMax = 9.0,
            valueFormat = { fmt("%.0f", it) },
        )
    }
}

/**
 * Timestamped Kp when the feed carried it, otherwise the bare series spaced back from the update
 * time at NOAA's own three-hour cadence — a legacy cached blob has values but no clock.
 */
private fun kpPoints(sw: SpaceWeather?): List<Pair<Long, Double>> {
    val stamped = sw?.kpPoints.orEmpty()
    if (stamped.size >= 2) return stamped.map { it.t to it.v }
    val series = sw?.kpSeries.orEmpty()
    if (series.size < 2) return emptyList()
    val end = sw?.updatedEpochMs ?: System.currentTimeMillis()
    val step = 3 * 3600_000L
    return series.mapIndexed { i, v -> (end - (series.size - 1 - i) * step) to v }
}

private fun kpBands(c: NightwirePalette) = listOf(
    ChartBand(5.0, 6.0, c.amber),
    ChartBand(6.0, 7.0, c.magenta),
    ChartBand(7.0, 9.0, c.negative),
)

@Composable
private fun ScaleRow(
    prefix: Char,
    level: Int?,
    label: String,
    c: NightwirePalette,
    onExplain: (String, List<Explainer>) -> Unit,
) {
    val value = level ?: 0
    LcarsFrame(
        Modifier.fillMaxWidth().clickable {
            onExplain(label, listOf(SpaceWeatherExplainers.noaaScale(prefix, value)))
        },
        accent = severityColor(value, c),
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label.uppercase(), fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
                Text(
                    SolarActivity.scaleLabel(prefix, value),
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = severityColor(value, c),
                )
            }
            LcarsMeter(
                value = value.toDouble(),
                min = 0.0,
                max = 5.0,
                modifier = Modifier.fillMaxWidth().height(12.dp).padding(top = 6.dp),
                bands = listOf(ChartBand(1.0, 3.0, c.amber), ChartBand(3.0, 5.0, c.negative)),
                markerColor = severityColor(value, c),
            )
        }
    }
}

@Composable
private fun ForecastCard(
    day: ScaleForecast,
    c: NightwirePalette,
    onExplain: (String, List<Explainer>) -> Unit,
) {
    val worst = maxOf(day.r, day.s, day.g)
    LcarsFrame(
        Modifier.fillMaxWidth().clickable {
            onExplain(
                day.label.ifBlank { "Forecast" },
                listOf(
                    SpaceWeatherExplainers.noaaScale('R', day.r),
                    SpaceWeatherExplainers.noaaScale('S', day.s),
                    SpaceWeatherExplainers.noaaScale('G', day.g),
                ),
            )
        },
        accent = severityColor(worst, c),
    ) {
        Column {
            Text(
                day.label.ifBlank { "Forecast" },
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.ink,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LcarsStatBlock("R", scaleText('R', day.r), Modifier.weight(1f),
                    valueColor = severityColor(day.r, c))
                LcarsStatBlock("S", scaleText('S', day.s), Modifier.weight(1f),
                    valueColor = severityColor(day.s, c))
                LcarsStatBlock("G", scaleText('G', day.g), Modifier.weight(1f),
                    valueColor = severityColor(day.g, c))
            }
            val odds = buildList {
                day.rMinorProbPct?.let { add("R1-R2 $it%") }
                day.rMajorProbPct?.let { add("R3+ $it%") }
                day.sProbPct?.let { add("S1+ $it%") }
            }
            if (odds.isNotEmpty()) {
                Text(
                    "Chance: ${odds.joinToString(" · ")}",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun RegionCard(region: SolarRegion, c: NightwirePalette) {
    LcarsFrame(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "AR ${region.number}",
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.ink,
                )
                if (region.location.isNotBlank()) {
                    Text(region.location, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2)
                }
            }
            val detail = listOfNotNull(
                region.spotClass.takeIf { it.isNotBlank() }?.let { "class $it" },
                region.magClass.takeIf { it.isNotBlank() }?.let { "magnetic $it" },
                region.spotCount.takeIf { it > 0 }?.let { "$it spots" },
                region.area.takeIf { it > 0 }?.let { "area $it" },
            )
            if (detail.isNotEmpty()) {
                Text(
                    detail.joinToString(" · "),
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                "Flare odds: C ${region.cFlareProbPct}% · M ${region.mFlareProbPct}% · X ${region.xFlareProbPct}%",
                fontFamily = JetBrainsMono, fontSize = 10.sp,
                color = if (region.xFlareProbPct >= 10) c.magenta else c.ink2,
                modifier = Modifier.padding(top = 6.dp),
            )
            // NOAA's region report can lag by weeks, so the observation date is stated rather than
            // implied to be current.
            if (region.observedDate.isNotBlank()) {
                Text(
                    "Observed ${region.observedDate}",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.faint,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun BandRow(
    band: HfPropagation.BandReport,
    c: NightwirePalette,
    onExplain: (String, List<Explainer>) -> Unit,
) {
    LcarsFrame(
        Modifier.fillMaxWidth().clickable { onExplain(band.name, listOf(SpaceWeatherExplainers.band(band))) },
        padding = PaddingValues(start = 13.dp, end = 13.dp, top = 9.dp, bottom = 9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(
                    band.name,
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.ink,
                )
                Text(
                    fmt("%.1f MHz", band.megahertz),
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                QualityChip("DAY", band.day, c)
                QualityChip("NIGHT", band.night, c)
            }
        }
    }
}

@Composable
private fun QualityChip(label: String, quality: HfPropagation.Quality, c: NightwirePalette) {
    Column {
        Text(label, fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.faint)
        Text(
            quality.name,
            fontFamily = JetBrainsMono, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = qualityColor(quality, c),
        )
    }
}

@Composable
private fun SourceNote() {
    Text(
        "Source: NOAA Space Weather Prediction Center (keyless).",
        style = MaterialTheme.typography.labelSmall, color = Pulse.colors.muted,
        modifier = Modifier.padding(top = 6.dp),
    )
}

// ---- formatting --------------------------------------------------------------------------------

/** Locale.US throughout: these are numbers, and a comma-decimal device would render "1,3". */
private fun fmt(pattern: String, value: Double): String = String.format(Locale.US, pattern, value)

private fun scaleText(prefix: Char, level: Int?): String =
    if (level == null) "—" else SolarActivity.scaleToken(prefix, level)

private fun severityColor(level: Int, c: NightwirePalette): Color = when {
    level <= 0 -> c.accent
    level <= 2 -> c.amber
    level <= 3 -> c.magenta
    else -> c.negative
}

private fun flareColor(label: String?, c: NightwirePalette): Color = when (label?.firstOrNull()) {
    'X' -> c.negative
    'M' -> c.magenta
    'C' -> c.amber
    else -> c.accent
}

private fun qualityColor(q: HfPropagation.Quality, c: NightwirePalette): Color = when (q) {
    HfPropagation.Quality.GOOD -> c.positive
    HfPropagation.Quality.FAIR -> c.accent
    HfPropagation.Quality.POOR -> c.amber
    HfPropagation.Quality.CLOSED -> c.faint
}
