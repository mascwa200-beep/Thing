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
import dev.mascwa.pulse.core.telemetry.LaunchWindow
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.data.orbital.LaunchRepository
import dev.mascwa.pulse.data.orbital.OrbitalData
import dev.mascwa.pulse.data.orbital.OrbitalRepository
import dev.mascwa.pulse.data.orbital.UpcomingLaunch
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.settings.DesktopUnits
import dev.mascwa.pulse.desktop.settings.LocalUnits
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsDataRow
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.LcarsStatBlock
import dev.mascwa.pulse.desktop.theme.Pulse
import kotlinx.coroutines.CoroutineScope
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ObservatoryViewModel(
    scope: CoroutineScope,
    orbital: OrbitalRepository,
    launches: LaunchRepository,
    settings: DesktopSettingsStore,
) {
    val sky = WorldFeed<OrbitalData>(scope, settings) { lat, lon, force ->
        orbital.fetch(lat, lon, force)
    }

    /**
     * ⚠️ Launches ignore the coordinate entirely — a rocket leaves from where it leaves from — but they
     * still go through [WorldFeed] so the screen has one shape rather than two. The lambda simply does
     * not use what it is handed, which is honest and costs nothing.
     */
    val launches = WorldFeed<List<UpcomingLaunch>>(scope, settings) { _, _, force ->
        launches.upcoming(force)
    }
}

/**
 * The sky above this machine: where the station is, when the Sun and Moon do what they do, what is
 * passing close, and what is going up next.
 */
@Composable
fun ObservatoryScreen(vm: ObservatoryViewModel, modifier: Modifier = Modifier) {
    val sky: Async<OrbitalData> by vm.sky.state.collectAsState()
    val located by vm.sky.located.collectAsState()
    val launches: Async<List<UpcomingLaunch>> by vm.launches.state.collectAsState()
    val c = Pulse.colors

    LaunchedEffect(Unit) {
        vm.sky.ensureLoaded()
        vm.launches.ensureLoaded()
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        WorldPanel(
            title = "Observatory",
            feed = vm.sky,
            state = sky,
            located = located,
        ) { data ->
            data.iss?.let { iss ->
                LcarsHeaderBar("The station")
                LcarsFrame(Modifier.fillMaxWidth()) {
                    Column {
                        LcarsDataRow("Sub-point", "${deg(iss.latitude)}, ${deg(iss.longitude)}")
                        LcarsDataRow("Altitude", DesktopUnits.longDistance(iss.altitudeKm, LocalUnits.current.miles))
                        // ⚠️ Propagated here from a stored element set rather than fetched. The ground
                        // point moves 416 km a minute, so a position from a five-minute cache is not a
                        // position at all — which is why the phone's version stopped fetching it.
                        Text(
                            "Propagated from the current orbital elements.",
                            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            LcarsHeaderBar("Sun and Moon", Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                LcarsStatBlock("SUNRISE", clock(data.sun?.sunriseEpochMs), Modifier.weight(1f))
                LcarsStatBlock("SUNSET", clock(data.sun?.sunsetEpochMs), Modifier.weight(1f))
                LcarsStatBlock(
                    "MOON",
                    "${data.moon.emoji} ${data.moon.phaseName}",
                    Modifier.weight(1f),
                )
            }
            data.sun?.dayLengthSec?.let { secs ->
                Text(
                    "Daylight ${secs / 3600}h ${(secs % 3600) / 60}m · " +
                        "Moon ${(data.moon.illumination * 100).toInt()}% lit",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            val visible = data.planets.filter { it.aboveHorizon }
            if (visible.isNotEmpty()) {
                LcarsHeaderBar(
                    "Planets up now",
                    Modifier.padding(top = 12.dp),
                    trailing = "${visible.size} OF ${data.planets.size}",
                )
                LcarsFrame(Modifier.fillMaxWidth()) {
                    Column {
                        visible.sortedBy { it.magnitude }.forEach { p ->
                            LcarsDataRow(
                                p.name,
                                "${p.altitudeDeg.toInt()}° up, ${compass(p.azimuthDeg)} · " +
                                    "mag ${String.format(java.util.Locale.US, "%.1f", p.magnitude)}",
                            )
                        }
                    }
                }
            }

            if (data.neosUnavailable) {
                // Not the same as "nothing is coming". The catalogue is key-gated and the shared demo
                // key is heavily rate-limited, so an absent answer has to say it is absent.
                LcarsFrame(Modifier.fillMaxWidth().padding(top = 12.dp), accent = c.amber) {
                    Text(
                        "The near-Earth object catalogue did not answer, so nothing here can be said " +
                            "about close approaches today.",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 16.sp, color = c.ink,
                    )
                }
            } else if (data.neos.isNotEmpty()) {
                LcarsHeaderBar(
                    "Passing close",
                    Modifier.padding(top = 12.dp),
                    trailing = if (data.neoHazardousCount > 0) "${data.neoHazardousCount} FLAGGED" else null,
                )
                // One read for the card. `listOfNotNull` below is not an inline composable scope, so
                // reading the local inside it would not compile — hoist, exactly as this repo's
                // cross-module smart-cast fix does.
                val miles = LocalUnits.current.miles
                data.neos.take(8).forEach { neo ->
                    LcarsFrame(
                        Modifier.fillMaxWidth().padding(top = 3.dp),
                        accent = if (neo.hazardous) c.amber else c.accent,
                    ) {
                        Column {
                            Text(
                                neo.name,
                                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                                fontSize = 13.sp, color = c.ink,
                            )
                            Text(
                                listOfNotNull(
                                    neo.diameterMetersMax?.let { "up to ${DesktopUnits.distance(it, miles)} across" },
                                    neo.missDistanceKm?.let { "misses by ${DesktopUnits.longDistance(it, miles)}" },
                                    // Converted with the rest rather than left in km/h: a card that
                                    // quotes the miss in miles and the speed in kilometres is harder
                                    // to read than one that picks a system and keeps it.
                                    neo.velocityKmh?.let {
                                        if (miles) "${thousands(it / 1.609344)} mph" else "${thousands(it)} km/h"
                                    },
                                    neo.closeApproachEpochMs?.let { stamp(it) } ?: neo.closeApproach,
                                ).joinToString(" · "),
                                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                            if (neo.hazardous) {
                                // "Potentially hazardous" is a catalogue classification about size and
                                // orbit, not a forecast. Saying which it is costs one line.
                                Text(
                                    "Catalogued as potentially hazardous — a size-and-orbit " +
                                        "classification, not a prediction that it will hit anything.",
                                    fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 15.sp,
                                    color = c.amber, modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Launches sit outside the coordinate-bound panel above, with their own state, because they are
        // the one thing on this page that does not depend on where you are.
        LcarsHeaderBar("Next off the pad", Modifier.padding(top = 16.dp))
        val upcoming = launches.data.orEmpty()
        if (upcoming.isEmpty()) {
            LcarsFrame(Modifier.fillMaxWidth()) {
                Text(
                    if (launches.loading) "Checking the manifest…" else "No launches listed.",
                    fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted,
                )
            }
        } else {
            upcoming.take(8).forEach { LaunchRow(it) }
        }
        Text(
            "Launch Library · NASA · NOAA · Celestrak",
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
            modifier = Modifier.padding(top = 10.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun LaunchRow(launch: UpcomingLaunch) {
    val c = Pulse.colors
    LcarsFrame(Modifier.fillMaxWidth().padding(top = 3.dp)) {
        Column {
            Text(
                launch.name,
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.ink,
            )
            Text(
                listOfNotNull(
                    launch.provider.ifBlank { null },
                    launch.location.ifBlank { null },
                    launch.orbit.ifBlank { null },
                ).joinToString(" · "),
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                modifier = Modifier.padding(top = 3.dp),
            )
            // ⚠️ How well the time is known, and how much room the flight has, are DIFFERENT questions.
            // `netPrecision` answers only the first, so a Starlink launch can carry a T-0 quoted to the
            // second inside a four-hour window — which is why the width is stated separately.
            val t0 = launch.netEpochMs?.let { stamp(it) } ?: "date to be confirmed"
            val firmness = if (launch.timeIsFirm) t0 else "$t0 (approximate)"
            val window = LaunchWindow.widthMs(launch.windowStartMs, launch.windowEndMs)
                ?.takeIf { LaunchWindow.isMeaningful(launch.windowStartMs, launch.windowEndMs) }
                ?.let { " · window ${LaunchWindow.describeWidth(it)}" }
                .orEmpty()
            Text(
                firmness + window,
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.sky,
                modifier = Modifier.padding(top = 4.dp),
            )
            launch.statusDetail.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 15.sp, color = c.faint,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

// ⚠️ Local zone throughout. Rendering a UTC clock time next to local ones is a mistake this repository
// has already made twice — once in the observatory's own "tonight" geometry and once in the day plan.
//
// ⚠️ `@Composable`, and the pattern comes from the reader's own switch. The two formatters were fixed
// 24-hour constants, so the Settings page's "12-hour clock" was written to disk and read by nothing —
// the only screen on this machine that prints a clock time ignored it.
@Composable
private fun clock(epochMs: Long?): String {
    val fmt = DesktopUnits.clock(LocalUnits.current.twelveHourClock)
    return epochMs?.let { fmt.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "—"
}

@Composable
private fun stamp(epochMs: Long): String =
    DesktopUnits.stamp(LocalUnits.current.twelveHourClock)
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private fun deg(v: Double) = String.format(java.util.Locale.US, "%.2f°", v)
private fun thousands(v: Double) = String.format(java.util.Locale.US, "%,.0f", v)

private fun compass(deg: Double): String {
    val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return points[(((deg % 360.0) + 360.0) % 360.0 / 45.0).toInt() % 8]
}
