package dev.mascwa.pulse.desktop.feature.world

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.telemetry.SafetyCoverage
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.safety.Incident
import dev.mascwa.pulse.data.safety.SafetyRepository
import dev.mascwa.pulse.data.safety.SafetyResult
import dev.mascwa.pulse.data.safety.Severity
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.Pulse
import kotlinx.coroutines.CoroutineScope

class SafetyViewModel(
    scope: CoroutineScope,
    repository: SafetyRepository,
    settings: DesktopSettingsStore,
) {
    val feed = WorldFeed<SafetyResult>(scope, settings) { lat, lon, force ->
        repository.fetch(lat, lon, force)
    }
}

/**
 * What is going wrong near here — earthquakes, major disasters, official weather warnings, and
 * recorded street crime where it is published.
 *
 * ⚠️ The coverage line at the bottom is the important part of this screen, not a footnote. Two of the
 * four sources are national: weather warnings come from the US National Weather Service and street
 * crime from the England-and-Wales police feed, so outside those countries they return nothing *by
 * construction*. An empty page that just says "no incidents" would be a claim the app cannot support.
 */
@Composable
fun SafetyScreen(vm: SafetyViewModel, modifier: Modifier = Modifier) {
    val state: Async<SafetyResult> by vm.feed.state.collectAsState()
    val located by vm.feed.located.collectAsState()
    val c = Pulse.colors

    LaunchedEffect(Unit) { vm.feed.ensureLoaded() }

    Column(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        WorldPanel(
            title = "Nearby danger",
            feed = vm.feed,
            state = state,
            located = located,
            trailing = state.data?.incidents?.size?.takeIf { it > 0 }?.let { "$it REPORTED" },
        ) { result ->
            if (result.incidents.isEmpty()) {
                LcarsFrame(Modifier.fillMaxWidth()) {
                    Column {
                        Text(
                            "Nothing reported near you right now.",
                            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                            fontSize = 14.sp, color = c.ink,
                        )
                        Text(
                            coverageLine(result),
                            fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 16.sp,
                            color = c.muted, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(result.incidents.sortedBy { it.distanceMeters }, key = { it.id }) {
                        IncidentRow(it)
                    }
                    item {
                        Text(
                            coverageLine(result),
                            fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 15.sp,
                            color = c.faint, modifier = Modifier.padding(top = 10.dp, bottom = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IncidentRow(incident: Incident) {
    val c = Pulse.colors
    val severity = runCatching { Severity.valueOf(incident.severity) }.getOrNull()
    val accent = when (severity) {
        Severity.EXTREME -> c.negative
        Severity.HIGH -> c.negative
        Severity.MODERATE -> c.amber
        else -> c.accent
    }
    LcarsFrame(Modifier.fillMaxWidth(), accent = accent) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    incident.title,
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = c.ink, modifier = Modifier.weight(1f),
                )
                Text(
                    "${km(incident.distanceMeters)} ${compass(incident.bearing)}",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
                )
            }
            val facts = buildList {
                severity?.let { add(it.name.lowercase().replaceFirstChar(Char::uppercase)) }
                incident.magnitude?.let { m ->
                    add("M${String.format(java.util.Locale.US, "%.1f", m)}${incident.magType?.let { " $it" }.orEmpty()}")
                }
                incident.depthKm?.let { add("${it.toInt()} km deep") }
                if (incident.tsunami) add("TSUNAMI")
                incident.pagerAlert?.let { add("PAGER $it") }
                incident.feltReports?.takeIf { it > 0 }?.let { add("$it felt reports") }
                incident.timing?.let { add(it) }
                add(incident.source)
                incident.timeEpochMs.takeIf { it > 0 }?.let { add(Formatters.relativeTime(it)) }
            }
            Text(
                facts.joinToString(" · "),
                fontFamily = JetBrainsMono, fontSize = 10.sp,
                color = if (incident.tsunami) c.negative else c.muted,
                modifier = Modifier.padding(top = 3.dp),
            )
            // ⚠️ The issuing agency's own words on what to DO. It arrives on nearly every weather
            // warning and was discarded until recently — in a safety feature, of all places.
            incident.instruction?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it.trim(),
                    fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 16.sp, color = c.ink,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            incident.areaDescription?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it.trim(),
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * What was actually checked, and what does not operate where you are.
 *
 * ⚠️ Both sentences come from `SafetyCoverage`, which is tested and which the phone already words this
 * way. Writing a second version here is precisely the duplicated-definition mistake this repository has
 * corrected several times: two screens describing the same coverage in different words is worse than
 * either wording alone.
 *
 * The result carries the states as plain strings because the module they cross has no dependency on the
 * enum; decoding back to it at the boundary is the whole conversion.
 */
private fun coverageLine(result: SafetyResult): String {
    val states = result.sourceStates.mapNotNull { (source, availability) ->
        val s = runCatching { SafetyCoverage.Source.valueOf(source) }.getOrNull() ?: return@mapNotNull null
        val a = runCatching { SafetyCoverage.Availability.valueOf(availability) }.getOrNull()
            ?: return@mapNotNull null
        s to a
    }.toMap()
    val checked = SafetyCoverage.describeChecked(states)
    val silence = SafetyCoverage.explainSilence(states)
    return listOfNotNull(checked, silence).joinToString(" ")
        .ifBlank { "Checked earthquakes, major disasters, weather alerts and street crime." }
}

private fun km(meters: Double): String =
    if (meters < 1000) "${meters.toInt()} m" else String.format(java.util.Locale.US, "%.1f km", meters / 1000.0)

private fun compass(deg: Double): String {
    val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return points[(((deg % 360.0) + 360.0) % 360.0 / 45.0).toInt() % 8]
}
