package dev.mascwa.pulse.desktop.feature.world

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
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
import dev.mascwa.pulse.core.telemetry.Comets
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.data.orbital.CometRepository
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
import dev.mascwa.pulse.desktop.theme.LcarsSkyPlot
import dev.mascwa.pulse.desktop.theme.SkyPoint
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
    comets: CometRepository,
    settings: DesktopSettingsStore,
) {
    val sky = WorldFeed<OrbitalData>(scope, settings) { lat, lon, force ->
        orbital.fetch(lat, lon, force)
    }

    /**
     * ⚠️ **Launches used to go through [WorldFeed] and that was a real defect, not a tidy shortcut.**
     * The comment here said a rocket leaves from where it leaves from and that the lambda simply
     * ignores the coordinate it is handed — true of the lambda, and false of the class around it,
     * which resolves a location first and returns early without one. So on a machine where nobody
     * had typed a place the launch list never loaded at all, exactly contradicting the sentence
     * explaining why it was safe. [OpenFeed] is what those two comments always described.
     */
    val launches = OpenFeed(scope) { force -> launches.upcoming(force) }

    /**
     * Comets, for the same reason and with a sharper one behind it.
     *
     * Where a comet is depends on the date alone — only whether it clears your horizon needs a site,
     * and this machine has no horizon it can measure anyway. The phone's tab makes the same choice
     * deliberately: withholding the whole list over a detail is withholding most of the answer.
     *
     * ⚠️ The catalogue is fetched and the orbits are solved in the same step, so the positions are
     * computed when the page loads rather than when the file was last downloaded — that file lives
     * for a week, and a week-old comet position would be badly wrong while looking perfectly fine.
     */
    val comets = OpenFeed(scope) { force ->
        val fetched = comets.elements(force)
        Fetched(
            data = Comets.visible(fetched.data, System.currentTimeMillis(), limit = COMET_LIMIT),
            fromCache = fetched.fromCache,
            timestampEpochMs = fetched.timestampEpochMs,
        )
    }

    private companion object {
        /** Enough to be worth reading; [Comets.visible] has already dropped what cannot be seen. */
        const val COMET_LIMIT = 10
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
    val comets: Async<List<Comets.Sighting>> by vm.comets.state.collectAsState()
    val c = Pulse.colors

    LaunchedEffect(Unit) {
        vm.sky.ensureLoaded()
        vm.launches.ensureLoaded()
        vm.comets.ensureLoaded()
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
                // ⚠️ The altitude and azimuth were being computed and printed as two numbers per row.
                // Two numbers do not tell you where to look; a polar plot does, which is the whole
                // reason the phone draws one. North is up and east is RIGHT — the mirror of a paper
                // star chart, because you are looking up rather than down.
                //
                // ⚠️ Planets only, deliberately. The station is the brightest thing this page could
                // point at, but a look angle is not the same fact as a sub-point: plotting it needs
                // an observer-relative altitude and azimuth, and this view model computes neither —
                // it reports where the station is over the Earth, not where to look for it from here.
                // Drawing it would mean inventing a sighting, so it stays off until the pass search
                // the phone runs is ported.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LcarsSkyPlot(
                        points = visible.map { p ->
                            SkyPoint(
                                azimuthDeg = p.azimuthDeg,
                                altitudeDeg = p.altitudeDeg,
                                label = p.name.take(3),
                                // Brighter is bigger, which is what the eye is looking for.
                                color = c.amber,
                                radiusDp = if (p.magnitude < 0.0) 4.dp else 3.dp,
                            )
                        },
                        modifier = Modifier.size(SKY_PLOT).padding(top = 3.dp),
                    )
                    LcarsFrame(Modifier.weight(1f)) {
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

        // Comets and launches both sit outside the coordinate-bound panel above, with their own
        // state, because neither depends on where this machine is.
        LcarsHeaderBar("Comets worth a look", Modifier.padding(top = 16.dp))
        val visibleComets = comets.data.orEmpty()
        if (visibleComets.isEmpty()) {
            LcarsFrame(Modifier.fillMaxWidth()) {
                Text(
                    if (comets.loading) {
                        "Reading the Minor Planet Center's catalogue…"
                    } else {
                        "Nothing bright enough is far enough from the Sun to look at. That is the " +
                            "ordinary state of affairs rather than a fault."
                    },
                    fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted,
                )
            }
        } else {
            visibleComets.forEach { CometRow(it) }
            Text(
                "Magnitudes are predictions from a fitted brightness law, not measurements — a comet " +
                    "two magnitudes off its own forecast is completely ordinary.",
                fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 15.sp, color = c.faint,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

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

/** One comet: what it should look like, and where it is in the solar system. */
@Composable
private fun CometRow(sighting: Comets.Sighting) {
    val c = Pulse.colors
    val m = sighting.magnitude
    // Colour says what you would need to see it, which is the question the list answers.
    val accent = when {
        m == null -> c.muted
        m <= 6.0 -> c.violet
        m <= 10.0 -> c.accent
        else -> c.muted
    }
    LcarsFrame(Modifier.fillMaxWidth().padding(top = 3.dp), accent = accent) {
        Column {
            Text(
                sighting.designation,
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.ink,
            )
            Text(
                Comets.describe(sighting),
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink,
                modifier = Modifier.padding(top = 3.dp),
            )
            Text(
                listOf(
                    m?.let { "magnitude ${String.format(java.util.Locale.US, "%.1f", it)}" }
                        ?: "brightness not stated",
                    "${String.format(java.util.Locale.US, "%.2f", sighting.heliocentricAu)} AU from the Sun",
                    "${sighting.elongationDeg.toInt()}° from it in the sky",
                ).joinToString(" · "),
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
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

/** Square, because a polar plot that is not square is an ellipse and lies about the sky. */
private val SKY_PLOT = 168.dp
