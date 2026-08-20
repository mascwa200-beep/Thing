package dev.mascwa.pulse.desktop.feature.world

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.telemetry.WeatherComfort
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.data.weather.WeatherData
import dev.mascwa.pulse.data.weather.WeatherRepository
import dev.mascwa.pulse.data.weather.WeatherCode
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsDataRow
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.LcarsStatBlock
import dev.mascwa.pulse.desktop.theme.Pulse
import kotlinx.coroutines.CoroutineScope

class WeatherViewModel(
    scope: CoroutineScope,
    repository: WeatherRepository,
    private val settings: DesktopSettingsStore,
) {
    val feed = WorldFeed<WeatherData>(scope, settings) { lat, lon, force ->
        // The place's own name where one was entered, and the bare coordinate where it was not —
        // rather than inventing a label for somewhere the machine only knows numerically.
        val label = settings.current().placeLabel.ifBlank { "Here" }
        repository.fetch(lat, lon, label, force)
    }
}

/**
 * The forecast, and what it will actually feel like.
 *
 * ⚠️ The comfort indices are the point, and each of them returns null outside the range it was fitted
 * for. That gating is a feature: a mild, still, clear day shows the plain temperature and nothing else,
 * by design rather than by omission. Nothing here restates them — they come from the tested core, so
 * the phone and this machine cannot disagree about whether it is dangerous outside.
 */
@Composable
fun WeatherScreen(vm: WeatherViewModel, modifier: Modifier = Modifier) {
    val state: Async<WeatherData> by vm.feed.state.collectAsState()
    val located by vm.feed.located.collectAsState()
    val c = Pulse.colors

    LaunchedEffect(Unit) { vm.feed.ensureLoaded() }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        WorldPanel(
            title = "Weather",
            feed = vm.feed,
            state = state,
            located = located,
            trailing = state.data?.locationName?.uppercase(),
        ) { wd ->
            val now = wd.current
            if (now != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    LcarsStatBlock(
                        "NOW",
                        now.temperature?.let { "${it.toInt()}${wd.tempUnitSymbol}" } ?: "—",
                        Modifier.weight(1f),
                    )
                    LcarsStatBlock("CONDITIONS", WeatherCode.describe(now.weatherCode), Modifier.weight(1f))
                    LcarsStatBlock(
                        "WIND",
                        now.windSpeed?.let { "${it.toInt()} ${wd.windUnitSymbol}" } ?: "—",
                        Modifier.weight(1f),
                    )
                }

                // ⚠️ The comfort read comes from the CANONICAL fields the repository carries beside the
                // display ones. The indices are defined in Celsius and km/h; converting here would mean
                // a second conversion that could drift from the first.
                val feels = WeatherComfort.compactFeelsLike(
                    now.temperatureC,
                    now.humidity,
                    now.windKmh,
                    wd.tempUnitSymbol,
                )
                if (feels != null) {
                    LcarsFrame(Modifier.fillMaxWidth().padding(top = 10.dp), accent = c.amber) {
                        Text(
                            feels,
                            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                            color = c.ink,
                        )
                    }
                }

                LcarsHeaderBar("Right now", Modifier.padding(top = 12.dp))
                LcarsFrame(Modifier.fillMaxWidth()) {
                    Column {
                        now.apparentTemperature?.let {
                            LcarsDataRow("Feels like", "${it.toInt()}${wd.tempUnitSymbol}")
                        }
                        now.humidity?.let { LcarsDataRow("Humidity", "${it.toInt()}%") }
                        now.dewPointC?.let { dp ->
                            // The dew point is what actually decides whether air feels muggy, and the
                            // core puts that judgement in words rather than leaving a bare number.
                            val words = WeatherComfort.mugginess(dp)
                            LcarsDataRow("Dew point", "${dp.toInt()}°C${words?.let { " · $it" }.orEmpty()}")
                        }
                        now.windGust?.let { LcarsDataRow("Gusts", "${it.toInt()} ${wd.windUnitSymbol}") }
                        now.pressure?.let { LcarsDataRow("Pressure", "${it.toInt()} hPa") }
                        now.cloudCover?.let { LcarsDataRow("Cloud", "${it.toInt()}%") }
                        now.visibility?.let { LcarsDataRow("Visibility", "${(it / 1000).toInt()} km") }
                    }
                }
            }

            if (wd.daily.isNotEmpty()) {
                LcarsHeaderBar("The week", Modifier.padding(top = 12.dp))
                wd.daily.take(7).forEach { day ->
                    LcarsFrame(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                day.dateIso,
                                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                                modifier = Modifier.weight(0.8f),
                            )
                            Text(
                                WeatherCode.describe(day.weatherCode),
                                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
                                modifier = Modifier.weight(1.4f),
                            )
                            Text(
                                listOfNotNull(
                                    day.precipProbabilityMax?.takeIf { it > 0 }?.let { "$it% rain" },
                                    day.uvIndexMax?.takeIf { it >= 3 }?.let { "UV ${it.toInt()}" },
                                ).joinToString(" · "),
                                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${day.tempMin?.toInt() ?: "—"} / ${day.tempMax?.toInt() ?: "—"}" +
                                    wd.tempUnitSymbol,
                                fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink,
                            )
                        }
                    }
                }
            }

            wd.airQuality?.let { air ->
                LcarsHeaderBar("Air", Modifier.padding(top = 12.dp))
                LcarsFrame(Modifier.fillMaxWidth()) {
                    Column {
                        air.europeanAqi?.let { LcarsDataRow("European index", "$it") }
                        air.usAqi?.let { LcarsDataRow("US index", "$it") }
                        air.pm25?.let { LcarsDataRow("PM2.5", "${it.toInt()} µg/m³") }
                        air.pm10?.let { LcarsDataRow("PM10", "${it.toInt()} µg/m³") }
                    }
                }
            }

            Text(
                "Open-Meteo · ${wd.timezone}",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
                modifier = Modifier.padding(top = 10.dp, bottom = 16.dp),
            )
        }
    }
}
