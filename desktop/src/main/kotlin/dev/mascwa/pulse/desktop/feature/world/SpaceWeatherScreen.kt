package dev.mascwa.pulse.desktop.feature.world

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import dev.mascwa.pulse.core.telemetry.HfPropagation
import dev.mascwa.pulse.core.telemetry.SolarActivity
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.data.space.SpaceWeather
import dev.mascwa.pulse.data.space.SpaceWeatherRepository
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsDataRow
import dev.mascwa.pulse.desktop.theme.ChartBand
import dev.mascwa.pulse.desktop.theme.ChartSeries
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsGauge
import dev.mascwa.pulse.desktop.theme.LcarsTimeChart
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.LcarsStatBlock
import dev.mascwa.pulse.desktop.theme.Pulse
import kotlinx.coroutines.CoroutineScope

class SpaceWeatherViewModel(
    scope: CoroutineScope,
    repository: SpaceWeatherRepository,
    settings: DesktopSettingsStore,
) {
    val feed = WorldFeed<SpaceWeather>(scope, settings) { lat, lon, force ->
        repository.fetch(force = force, lat = lat, lon = lon)
    }
}

/**
 * What the Sun is doing, and what it is doing to us.
 *
 * The same NOAA products the phone reads, through the same repository — the module they live in is
 * shared now, so there is no second copy of the parsing to drift. What differs is the arrangement: a
 * desktop window is wide, so the readings sit in rows of blocks rather than a single column.
 */
/**
 * A GOES long-channel flux in the class letters an operator actually uses.
 *
 * ⚠️ The letters ARE the decades — A is 1e-8, B 1e-7, C 1e-6, M 1e-5, X 1e-4 — which is why an axis
 * labelled in watts per square metre is unreadable and this is not merely prettier. Anything under
 * A-class is below what the instrument meaningfully resolves and says so rather than inventing a
 * letter for it.
 */
private fun flareClass(wattsPerM2: Double): String {
    if (!wattsPerM2.isFinite() || wattsPerM2 <= 0.0) return "—"
    val letters = listOf("A" to 1e-8, "B" to 1e-7, "C" to 1e-6, "M" to 1e-5, "X" to 1e-4)
    val band = letters.lastOrNull { wattsPerM2 >= it.second } ?: return "<A"
    // One decimal, which is how the class is actually written (M1.2, X2.8) — and the trailing ".0"
    // is dropped so a tick lands on "X1" rather than "X1.0".
    val mantissa = wattsPerM2 / band.second
    val text = String.format(java.util.Locale.US, "%.1f", mantissa)
    return band.first + text.removeSuffix(".0")
}

@Composable
fun SpaceWeatherScreen(vm: SpaceWeatherViewModel, modifier: Modifier = Modifier) {
    val state: Async<SpaceWeather> by vm.feed.state.collectAsState()
    val located by vm.feed.located.collectAsState()
    val c = Pulse.colors

    LaunchedEffect(Unit) { vm.feed.ensureLoaded() }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        WorldPanel(
            title = "Space weather",
            feed = vm.feed,
            state = state,
            located = located,
            trailing = state.data?.headline?.uppercase(),
        ) { sw ->
            // The three NOAA scales, which is the answer to "is anything happening" — and each carries
            // its own meaning line rather than a bare number, because R2 means nothing on its own.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                ScaleBlock("RADIO · R", sw.rLevel, Modifier.weight(1f))
                ScaleBlock("RADIATION · S", sw.sLevel, Modifier.weight(1f))
                ScaleBlock("GEOMAGNETIC · G", sw.gLevel, Modifier.weight(1f))
            }

            LcarsHeaderBar("Now", Modifier.padding(top = 12.dp))
            LcarsFrame(Modifier.fillMaxWidth()) {
                Column {
                    LcarsDataRow("Planetary Kp", sw.kp?.let { fmt1(it) } ?: "—")
                    LcarsDataRow("Storm level", sw.stormLevel)
                    LcarsDataRow("Aurora chance", sw.auroraChance)
                    sw.auroraProbabilityPct?.let {
                        // NOAA's OVATION model at this exact coordinate, which is why the screen wants
                        // one at all — the rest of this page is the same everywhere on Earth.
                        LcarsDataRow("Aurora here", "$it%", valueColor = if (it >= 20) c.positive else c.ink)
                    }
                    sw.solarWindSpeed?.let { LcarsDataRow("Solar wind", "${fmt0(it)} km/s") }
                    sw.bz?.let {
                        // Southward Bz is what lets the solar wind couple to Earth's field, so the sign
                        // is the whole reading and it is coloured rather than left as a number.
                        LcarsDataRow("IMF Bz", "${fmt1(it)} nT", valueColor = if (it < 0) c.amber else c.ink)
                    }
                }
            }

            // ⚠️ The instruments, not more rows. Kp is a bounded index and reads as a dial; the X-ray
            // flux runs over several decades and reads as a chart — and both series were being
            // fetched, parsed and shown as a single latest number.
            if (sw.kpPoints.size >= 2 || sw.kp != null) {
                LcarsHeaderBar("Geomagnetic", Modifier.padding(top = 12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LcarsGauge(
                        value = sw.kp,
                        min = 0.0,
                        max = 9.0,
                        modifier = Modifier.width(140.dp).height(110.dp),
                        // NOAA's own G-scale boundaries, so the dial says what the scale says.
                        bands = listOf(
                            ChartBand(5.0, 6.0, c.amber),
                            ChartBand(6.0, 7.0, c.amber),
                            ChartBand(7.0, 9.0, c.negative),
                        ),
                        label = "Kp",
                        valueColor = if ((sw.kp ?: 0.0) >= 5.0) c.amber else c.ink,
                    )
                    if (sw.kpPoints.size >= 2) {
                        LcarsTimeChart(
                            series = listOf(
                                ChartSeries("Kp", sw.kpPoints.map { it.t to it.v }, c.sky, filled = true),
                            ),
                            modifier = Modifier.weight(1f).height(110.dp),
                            bands = listOf(ChartBand(5.0, 9.0, c.amber)),
                            // Pinned to the scale's own range rather than the data's, so a quiet day
                            // looks quiet instead of being stretched to fill the box.
                            forceMin = 0.0,
                            forceMax = 9.0,
                            yTicks = 3,
                        )
                    }
                }
            }

            if (sw.xrayPoints.size >= 2) {
                LcarsHeaderBar("X-ray flux", Modifier.padding(top = 12.dp), trailing = "GOES · LONG")
                LcarsTimeChart(
                    series = listOf(
                        ChartSeries("Long", sw.xrayPoints.map { it.t to it.v }, c.amber),
                    ),
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    // ⚠️ Labelled in flare classes rather than in watts. The axis spans several
                    // decades, and "4e-06" is a number where "C4" is a fact somebody can act on.
                    valueFormat = { flareClass(it) },
                    yTicks = 4,
                )
            }

            LcarsHeaderBar("The Sun", Modifier.padding(top = 12.dp))
            LcarsFrame(Modifier.fillMaxWidth()) {
                Column {
                    LcarsDataRow("Flare class", sw.flareLabel ?: "Quiet")
                    sw.xrayFlux?.let { LcarsDataRow("X-ray flux (long)", exp(it) + " W/m²") }
                    sw.protonFlux?.let { LcarsDataRow("Protons ≥10 MeV", "${fmt1(it)} pfu") }
                    sw.f107?.let { f ->
                        val mean = sw.f107Mean?.let { " (90-day mean ${fmt0(it)})" }.orEmpty()
                        LcarsDataRow("F10.7 radio flux", "${fmt0(f)} sfu$mean")
                    }
                    // Shortwave propagation is the one consequence of all this that a person can act on,
                    // and the whole judgement lives in a tested core rather than being restated here.
                    //
                    // ⚠️ The third argument is the X-RAY FLUX, not the R level — the core derives the
                    // blackout itself, and handing it a 0-to-5 scale number would read as a flux of
                    // 2 W/m², which is roughly a hundred thousand times an X-class flare.
                    Text(
                        HfPropagation.summary(sw.f107, sw.kp, sw.xrayFlux),
                        fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 16.sp, color = c.ink2,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            if (sw.regions.isNotEmpty()) {
                LcarsHeaderBar(
                    "Sunspot groups",
                    Modifier.padding(top = 12.dp),
                    trailing = "${sw.regions.size}",
                )
                sw.regions.take(8).forEach { r ->
                    LcarsFrame(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                        Column {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "AR${r.number}",
                                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp, color = c.ink, modifier = Modifier.weight(1f),
                                )
                                // ⚠️ The observation date, always. This feed can lag by weeks, and a
                                // sunspot group presented without one reads as "on the disc right now".
                                Text(
                                    r.observedDate.ifBlank { "date unknown" },
                                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.faint,
                                )
                            }
                            Text(
                                listOfNotNull(
                                    r.location.ifBlank { null },
                                    r.spotClass.ifBlank { null },
                                    r.magClass.ifBlank { null },
                                    "${r.spotCount} spots".takeIf { r.spotCount > 0 },
                                ).joinToString(" · "),
                                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                            Text(
                                "Flare odds — C ${r.cFlareProbPct}% · M ${r.mFlareProbPct}% · X ${r.xFlareProbPct}%",
                                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink2,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
            }

            if (sw.alerts.isNotEmpty()) {
                LcarsHeaderBar("NOAA alerts", Modifier.padding(top = 12.dp), trailing = "${sw.alerts.size}")
                sw.alerts.take(6).forEach { a ->
                    LcarsFrame(Modifier.fillMaxWidth().padding(top = 3.dp), accent = c.amber) {
                        Column {
                            Text(
                                a.title,
                                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                                fontSize = 13.sp, color = c.ink,
                            )
                            Text(
                                a.issued,
                                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.faint,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            Text(
                                a.message.trim(),
                                fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 16.sp,
                                color = c.ink2, modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScaleBlock(label: String, level: Int, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    val letter = label.substringAfterLast("· ").trim().firstOrNull() ?: 'G'
    LcarsStatBlock(
        label = label,
        value = SolarActivity.scaleLabel(letter, level),
        modifier = modifier,
        // Quiet is not a warning. Colour appears only once something is actually happening.
        valueColor = when {
            level >= 4 -> c.negative
            level >= 2 -> c.amber
            level >= 1 -> c.ink
            else -> c.muted
        },
    )
}

// Locale.US throughout: these are numbers, and a comma decimal in a scientific reading is a defect
// this repository has already corrected in several places.
private fun fmt0(v: Double) = String.format(java.util.Locale.US, "%.0f", v)
private fun fmt1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
private fun exp(v: Double) = String.format(java.util.Locale.US, "%.2e", v)
