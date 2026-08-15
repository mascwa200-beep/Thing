package dev.mascwa.pulse.feature.sky

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.Geodesy
import dev.mascwa.pulse.core.telemetry.SatellitePasses
import dev.mascwa.pulse.data.orbital.NeoObject
import dev.mascwa.pulse.data.orbital.OrbitalData
import dev.mascwa.pulse.data.orbital.UpcomingLaunch
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.feature.common.LcarsIcons
import dev.mascwa.pulse.feature.common.LcarsSkyPlot
import dev.mascwa.pulse.feature.common.LcarsStatBlock
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.NeonChip
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.SkyPoint
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The observatory.
 *
 * Everything here is computed on-device from cached elements and closed-form astronomy, so the only
 * things that need a network are the asteroid list and the launch schedule. Pass times come from a
 * real SGP4 propagator against Celestrak element sets — before that existed the app said outright
 * that it could not predict passes rather than guess at them.
 */
private enum class SkyTab(val label: String) {
    TONIGHT("TONIGHT"),
    SATELLITES("SATELLITES"),
    CHART("SKY CHART"),
    SUN_MOON("SUN & MOON"),
    ASTEROIDS("ASTEROIDS"),
    LAUNCHES("LAUNCHES"),
}

@Composable
fun OrbitalScreen(vm: OrbitalViewModel, onBack: (() -> Unit)? = null) {
    PulseScaffold(
        title = "Observatory",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(LcarsIcons.ArrowBack, "Back") }
        },
        actions = {
            IconButton(onClick = { vm.refresh() }) { Icon(LcarsIcons.Refresh, "Refresh") }
        },
    ) { innerPadding ->
        OrbitalBody(vm, Modifier.padding(innerPadding))
    }
}

/** The observatory body, scaffold-free so it can also be hosted inside another screen. */
@Composable
fun OrbitalBody(vm: OrbitalViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tonight by vm.tonight.collectAsStateWithLifecycle()
    val passes by vm.passes.collectAsStateWithLifecycle()
    val passesLoading by vm.passesLoading.collectAsStateWithLifecycle()
    val tracked by vm.trackedCount.collectAsStateWithLifecycle()
    val launches by vm.launches.collectAsStateWithLifecycle()
    val site by vm.site.collectAsStateWithLifecycle()
    val c = Pulse.colors
    var tab by remember { mutableStateOf(SkyTab.TONIGHT) }
    val listState = remember(tab) { LazyListState() }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SkyTab.entries.forEach { t ->
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
                    val d = state.data
                    if (state.stale) item { StaleBanner(true) }
                    when (tab) {
                        SkyTab.TONIGHT -> tonightTab(tonight, passes, passesLoading, site != null, c)
                        SkyTab.SATELLITES ->
                            satellitesTab(passes, passesLoading, tracked, site != null, c)
                        SkyTab.CHART -> chartTab(d, tonight, c)
                        SkyTab.SUN_MOON -> sunMoonTab(tonight, site != null, c)
                        SkyTab.ASTEROIDS -> asteroidsTab(d, c)
                        SkyTab.LAUNCHES -> launchesTab(launches, c)
                    }
                    item { SourceNote(tab, c) }
                }
            }
        }
    }
}

// ---- TONIGHT -----------------------------------------------------------------------------------

private fun LazyListScope.tonightTab(
    tonight: OrbitalViewModel.Tonight?,
    passes: List<SatellitePasses.Pass>,
    passesLoading: Boolean,
    hasSite: Boolean,
    c: NightwirePalette,
) {
    if (!hasSite) {
        item { NeedsLocation(c) }
        return
    }
    val visible = passes.filter { it.isVisible }
    item {
        val dark = tonight?.darkNow == true
        LcarsFrame(Modifier.fillMaxWidth(), accent = if (dark) c.violet else c.accent) {
            Column {
                Text("SKY STATE", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
                Text(
                    if (dark) "Dark — good for observing" else "Too bright to observe",
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    color = if (dark) c.violet else c.accent,
                )
                tonight?.let {
                    Text(
                        "Sun ${it.sunAltitudeDeg.roundToInt()}° · Moon ${it.moonAltitudeDeg.roundToInt()}° " +
                            "· ${it.moon.emoji} ${(it.moon.illuminatedFraction * 100).roundToInt()}% lit",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LcarsStatBlock("Sunset", clockOrDash(tonight?.daylight?.sunset), Modifier.weight(1f))
            LcarsStatBlock("Full dark", clockOrDash(tonight?.daylight?.astronomicalDusk), Modifier.weight(1f))
            LcarsStatBlock("Sunrise", clockOrDash(tonight?.daylight?.sunrise), Modifier.weight(1f))
        }
    }
    item { LcarsHeaderBar("Visible passes", trailing = if (passesLoading) "computing" else "${visible.size}") }
    if (passesLoading && visible.isEmpty()) {
        item { Hint("Propagating the catalogue…", c) }
    } else if (visible.isEmpty()) {
        item {
            Hint(
                "Nothing bright is due over the next two days. A satellite is only visible when it " +
                    "is still in sunlight while you are already in darkness, which is a narrow " +
                    "window after dusk and before dawn.",
                c,
            )
        }
    } else {
        items(visible.take(6)) { pass -> PassCard(pass, c) }
    }
}

// ---- SATELLITES --------------------------------------------------------------------------------

private fun LazyListScope.satellitesTab(
    passes: List<SatellitePasses.Pass>,
    passesLoading: Boolean,
    tracked: Int,
    hasSite: Boolean,
    c: NightwirePalette,
) {
    if (!hasSite) {
        item { NeedsLocation(c) }
        return
    }
    item {
        LcarsHeaderBar(
            "Next 48 hours",
            trailing = if (passesLoading) "computing" else "$tracked tracked",
        )
    }
    if (passes.isEmpty()) {
        item {
            Hint(
                if (passesLoading) {
                    "Propagating the catalogue…"
                } else if (tracked == 0) {
                    "No element sets downloaded yet. Pull to refresh with a connection — pass times " +
                        "are only as good as the elements behind them, so none are shown without any."
                } else {
                    "No passes above 15° in the next two days."
                },
                c,
            )
        }
        return
    }
    items(passes) { pass -> PassCard(pass, c) }
}

@Composable
private fun PassCard(pass: SatellitePasses.Pass, c: NightwirePalette) {
    val accent = when (pass.kind) {
        SatellitePasses.PassKind.VISIBLE -> c.violet
        SatellitePasses.PassKind.DAYLIGHT -> c.amber
        SatellitePasses.PassKind.ECLIPSED -> c.muted
    }
    LcarsFrame(Modifier.fillMaxWidth(), accent = accent) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    pass.name.ifBlank { "Object ${pass.noradId}" },
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.ink,
                )
                Text(
                    pass.kind.name,
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = accent,
                )
            }
            Text(
                "${clockOrDash(pass.riseEpochMs)} → ${clockOrDash(pass.setEpochMs)} · ${pass.trackDescription}",
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LcarsStatBlock("Max height", "${pass.maxAltitudeDeg.roundToInt()}°", Modifier.weight(1f))
                LcarsStatBlock("Closest", "${pass.minRangeKm.roundToInt()} km", Modifier.weight(1f))
                LcarsStatBlock(
                    "Brightness",
                    pass.brightestMagnitude?.let { fmt("%+.1f", it) } ?: "—",
                    Modifier.weight(1f),
                    valueColor = if (pass.brightestMagnitude != null) c.violet else c.ink,
                )
            }
            Text(
                "Overhead for ${pass.durationMs / 60_000} min ${(pass.durationMs / 1000) % 60}s" +
                    if (pass.brightestMagnitude == null && pass.isVisible) {
                        " · brightness unknown for this object"
                    } else {
                        ""
                    },
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

// ---- SKY CHART ---------------------------------------------------------------------------------

private fun LazyListScope.chartTab(
    d: OrbitalData?,
    tonight: OrbitalViewModel.Tonight?,
    c: NightwirePalette,
) {
    item { LcarsHeaderBar("Looking up", trailing = "zenith at centre") }
    item {
        val points = buildList {
            tonight?.let { t ->
                add(
                    SkyPoint(
                        azimuthDeg = t.sunPosition.azimuthDeg,
                        altitudeDeg = t.sunAltitudeDeg,
                        label = "Sun",
                        color = c.amber,
                        radiusDp = 7.dp,
                    ),
                )
                add(
                    SkyPoint(
                        azimuthDeg = t.moonPosition.azimuthDeg,
                        altitudeDeg = t.moonAltitudeDeg,
                        label = "Moon",
                        color = c.ink,
                        radiusDp = 6.dp,
                        glyph = t.moon.emoji,
                    ),
                )
            }
            d?.planets.orEmpty().forEach { p ->
                add(
                    SkyPoint(
                        azimuthDeg = p.azimuthDeg,
                        altitudeDeg = p.altitudeDeg,
                        label = p.name,
                        color = c.sky,
                        radiusDp = 4.dp,
                    ),
                )
            }
        }
        LcarsSkyPlot(points, Modifier.fillMaxWidth().height(320.dp))
    }
    item {
        Hint(
            "The rim is the horizon and the centre is straight up, so this is the mirror of a paper " +
                "star chart — the way the sky actually looks when you tilt your head back.",
            c,
        )
    }
    val above = d?.planets.orEmpty().filter { it.aboveHorizon }
    item { LcarsHeaderBar("Planets up now", trailing = "${above.size}") }
    if (above.isEmpty()) {
        item { Hint("No naked-eye planets are above the horizon right now.", c) }
    } else {
        items(above) { p ->
            LcarsFrame(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(p.name, fontFamily = ChakraPetch, fontSize = 14.sp, color = c.ink)
                    Text(
                        "${p.altitudeDeg.roundToInt()}° up · ${cardinalOf(p.azimuthDeg)} · mag ${fmt("%.1f", p.magnitude)}",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink2,
                    )
                }
            }
        }
    }
}

// ---- SUN & MOON --------------------------------------------------------------------------------

private fun LazyListScope.sunMoonTab(
    tonight: OrbitalViewModel.Tonight?,
    hasSite: Boolean,
    c: NightwirePalette,
) {
    if (!hasSite || tonight == null) {
        item { NeedsLocation(c) }
        return
    }
    val day = tonight.daylight
    item { LcarsHeaderBar("The Sun") }
    if (day.polarDay || day.polarNight) {
        item {
            Hint(
                if (day.polarDay) {
                    "The Sun does not set at your latitude today."
                } else {
                    "The Sun does not rise at your latitude today."
                },
                c,
            )
        }
    }
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LcarsStatBlock("Sunrise", clockOrDash(day.sunrise), Modifier.weight(1f))
            LcarsStatBlock("Solar noon", clockOrDash(day.solarNoon), Modifier.weight(1f))
            LcarsStatBlock("Sunset", clockOrDash(day.sunset), Modifier.weight(1f))
        }
    }
    item { LcarsHeaderBar("Twilight", trailing = "dawn / dusk") }
    item { TwilightRow("Civil", day.civilDawn, day.civilDusk, "readable outdoors", c) }
    item { TwilightRow("Nautical", day.nauticalDawn, day.nauticalDusk, "horizon still visible at sea", c) }
    item { TwilightRow("Astronomical", day.astronomicalDawn, day.astronomicalDusk, "true dark", c) }
    day.daylightMinutes?.let { minutes ->
        item {
            Hint("Daylight today: ${minutes / 60}h ${minutes % 60}m.", c)
        }
    }

    item { LcarsHeaderBar("The Moon") }
    item {
        LcarsFrame(Modifier.fillMaxWidth()) {
            Column {
                Text(
                    "${tonight.moon.emoji}  ${tonight.moon.name}",
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = c.ink,
                )
                Text(
                    "${(tonight.moon.illuminatedFraction * 100).roundToInt()}% lit · " +
                        "${tonight.moon.ageDays.roundToInt()} days old · " +
                        (if (tonight.moon.waxing) "waxing" else "waning"),
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "${tonight.moon.distanceKm.roundToInt()} km away · ${tonight.moonAltitudeDeg.roundToInt()}° above the horizon",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LcarsStatBlock("Moonrise", clockOrDash(tonight.moonRise.riseEpochMs), Modifier.weight(1f))
            LcarsStatBlock("Moonset", clockOrDash(tonight.moonRise.setEpochMs), Modifier.weight(1f))
        }
    }
    item {
        Hint(
            "Computed on-device from the observer's position — no network, and it works in a valley " +
                "with no signal.",
            c,
        )
    }
}

@Composable
private fun TwilightRow(
    name: String,
    dawn: Long?,
    dusk: Long?,
    meaning: String,
    c: NightwirePalette,
) {
    LcarsFrame(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, fontFamily = ChakraPetch, fontSize = 14.sp, color = c.ink)
                Text(
                    "${clockOrDash(dawn)}  /  ${clockOrDash(dusk)}",
                    fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink2,
                )
            }
            Text(meaning, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted)
        }
    }
}

// ---- ASTEROIDS ---------------------------------------------------------------------------------

private fun LazyListScope.asteroidsTab(d: OrbitalData?, c: NightwirePalette) {
    val neos = d?.neos.orEmpty()
    item {
        LcarsHeaderBar(
            "Close approaches",
            trailing = if (neos.isEmpty()) null else "${neos.size}",
        )
    }
    if (d != null && d.neoHazardousCount > 0) {
        item {
            Hint(
                "${d.neoHazardousCount} of these are classed as potentially hazardous. That is a " +
                    "catalogue label about size and orbit, not a prediction that anything will hit.",
                c,
            )
        }
    }
    if (neos.isEmpty()) {
        item { Hint("No close approaches catalogued for today.", c) }
    } else {
        items(neos) { neo -> NeoCard(neo, c) }
    }
}

@Composable
private fun NeoCard(neo: NeoObject, c: NightwirePalette) {
    LcarsFrame(Modifier.fillMaxWidth(), accent = if (neo.hazardous) c.magenta else c.accent) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(neo.name, fontFamily = ChakraPetch, fontSize = 14.sp, color = c.ink)
                if (neo.hazardous) {
                    Text("⚠ HAZARD CLASS", fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.magenta)
                }
            }
            val detail = listOfNotNull(
                neo.diameterMetersMax?.let { "up to ${it.roundToInt()} m across" },
                neo.missDistanceKm?.let { "misses by ${fmt("%,.0f", it)} km" },
                neo.velocityKmh?.let { "${fmt("%,.0f", it)} km/h" },
            )
            if (detail.isNotEmpty()) {
                Text(
                    detail.joinToString(" · "),
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            neo.closeApproach?.let {
                Text(it, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted)
            }
        }
    }
}

// ---- LAUNCHES ----------------------------------------------------------------------------------

private fun LazyListScope.launchesTab(launches: List<UpcomingLaunch>, c: NightwirePalette) {
    item { LcarsHeaderBar("Upcoming launches", trailing = if (launches.isEmpty()) null else "${launches.size}") }
    if (launches.isEmpty()) {
        item { Hint("No launch schedule downloaded yet. Pull to refresh with a connection.", c) }
        return
    }
    items(launches) { launch -> LaunchCard(launch, c) }
}

@Composable
private fun LaunchCard(launch: UpcomingLaunch, c: NightwirePalette) {
    val go = launch.status.equals("Go", ignoreCase = true)
    LcarsFrame(Modifier.fillMaxWidth(), accent = if (go) c.positive else c.accent) {
        Column {
            Text(
                launch.name,
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.ink,
            )
            val where = listOfNotNull(
                launch.provider.takeIf { it.isNotBlank() },
                launch.pad.takeIf { it.isNotBlank() },
                launch.location.takeIf { it.isNotBlank() },
            )
            if (where.isNotEmpty()) {
                Text(
                    where.joinToString(" · "),
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    // A schedule known only to the month must not read as a precise time.
                    if (launch.timeIsFirm) dateTimeOrDash(launch.netEpochMs) else dateOrDash(launch.netEpochMs),
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
                )
                Text(
                    launch.status.ifBlank { "—" },
                    fontFamily = JetBrainsMono, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = if (go) c.positive else c.amber,
                )
            }
            if (!launch.timeIsFirm && launch.netPrecision.isNotBlank()) {
                Text(
                    "Scheduled to the ${launch.netPrecision.lowercase()} only — not a firm time yet.",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

// ---- shared ------------------------------------------------------------------------------------

@Composable
private fun NeedsLocation(c: NightwirePalette) {
    LcarsFrame(Modifier.fillMaxWidth(), accent = c.amber) {
        Column {
            Text("LOCATION NEEDED", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
            Text(
                "Rise and set times and satellite passes are different from every point on Earth, " +
                    "so there is nothing honest to show without knowing where you are.",
                style = MaterialTheme.typography.bodyMedium, color = c.ink2,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Hint(text: String, c: NightwirePalette) {
    Text(text, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
}

@Composable
private fun SourceNote(tab: SkyTab, c: NightwirePalette) {
    val source = when (tab) {
        SkyTab.SATELLITES, SkyTab.TONIGHT ->
            "Passes propagated on-device (SGP4) from Celestrak element sets."
        SkyTab.CHART, SkyTab.SUN_MOON ->
            "Sun and Moon computed on-device — no network needed."
        SkyTab.ASTEROIDS -> "Source: NASA NeoWs."
        SkyTab.LAUNCHES -> "Source: The Space Devs Launch Library (keyless)."
    }
    Text(
        source,
        style = MaterialTheme.typography.labelSmall, color = c.muted,
        modifier = Modifier.padding(top = 6.dp),
    )
}

// ---- formatting --------------------------------------------------------------------------------

/** Locale.US for the numeric patterns; the clock deliberately follows the device's own locale. */
private fun fmt(pattern: String, value: Double): String = String.format(Locale.US, pattern, value)

private fun clockOrDash(epochMs: Long?): String =
    epochMs?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) } ?: "—"

private fun dateTimeOrDash(epochMs: Long?): String =
    epochMs?.let { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(it)) } ?: "—"

private fun dateOrDash(epochMs: Long?): String =
    epochMs?.let { SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(it)) } ?: "—"

private fun cardinalOf(azimuthDeg: Double): String = Geodesy.cardinal(azimuthDeg)
