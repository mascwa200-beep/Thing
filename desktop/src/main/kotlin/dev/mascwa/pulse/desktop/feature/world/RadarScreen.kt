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
import dev.mascwa.pulse.data.radar.Contact
import dev.mascwa.pulse.data.radar.ContactKind
import dev.mascwa.pulse.data.radar.RadarData
import dev.mascwa.pulse.data.radar.RadarRepository
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsChip
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.Pulse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which contacts the list is showing. Aircraft and earthquakes arrive together and read nothing alike. */
enum class RadarFilter(val title: String) {
    ALL("Everything"),
    AIRCRAFT("Aircraft"),
    QUAKES("Earthquakes"),
}

class RadarViewModel(
    scope: CoroutineScope,
    repository: RadarRepository,
    settings: DesktopSettingsStore,
) {
    val feed = WorldFeed<RadarData>(scope, settings) { lat, lon, force ->
        repository.fetch(lat, lon, force)
    }

    private val _filter = MutableStateFlow(RadarFilter.ALL)
    val filter: StateFlow<RadarFilter> = _filter.asStateFlow()

    fun select(f: RadarFilter) {
        _filter.value = f
    }
}

/**
 * What is in the sky and under the ground around here.
 *
 * ⚠️ A LIST, not a scope. The phone draws a sweeping radar face because a phone screen is a circle's
 * worth of space and a finger is an imprecise pointer; on a desktop the same information reads far
 * better as rows you can scan, and the fields that make an aircraft interesting — registration,
 * operator, what it is actually doing — do not fit on a blip.
 */
@Composable
fun RadarScreen(vm: RadarViewModel, modifier: Modifier = Modifier) {
    val state: Async<RadarData> by vm.feed.state.collectAsState()
    val located by vm.feed.located.collectAsState()
    val filter by vm.filter.collectAsState()
    val c = Pulse.colors

    LaunchedEffect(Unit) { vm.feed.ensureLoaded() }

    Column(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        WorldPanel(
            title = "Radar",
            feed = vm.feed,
            state = state,
            located = located,
            trailing = state.data?.let { "${it.aircraftCount()} AIRCRAFT · ${it.source.uppercase()}" },
            emptyMessage = "Nothing within range right now.",
            isEmpty = { it.contacts.isEmpty() },
        ) { data ->
            // An aircraft declaring an emergency leads, unconditionally. It is the one thing on this
            // page that could matter to somebody, and burying it in distance order would be absurd.
            val emergencies = data.emergencies()
            if (emergencies.isNotEmpty()) {
                LcarsFrame(Modifier.fillMaxWidth(), accent = c.negative) {
                    Column {
                        Text(
                            "DECLARED EMERGENCY",
                            fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp,
                            color = c.negative,
                        )
                        emergencies.forEach { e ->
                            Text(
                                "${e.label} — ${e.declaredEmergency ?: "squawk ${e.squawk}"} · " +
                                    "${km(e.distanceMeters)} ${compass(e.bearingDeg)}",
                                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                                fontSize = 14.sp, color = c.ink, modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = if (emergencies.isEmpty()) 0.dp else 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RadarFilter.entries.forEach { f ->
                    LcarsChip(f.title, selected = f == filter, onClick = { vm.select(f) })
                }
            }

            if (data.estimatedCount() > 0) {
                // Multilateration puts an aircraft roughly where it is. Saying so once beats marking
                // every affected row, and leaving it unsaid presents an estimate as a report.
                Text(
                    "${data.estimatedCount()} position${if (data.estimatedCount() == 1) "" else "s"} " +
                        "estimated by ground stations rather than self-reported",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            val shown = data.contacts
                .filter {
                    when (filter) {
                        RadarFilter.ALL -> true
                        RadarFilter.AIRCRAFT -> it.kind == ContactKind.AIRCRAFT.name
                        RadarFilter.QUAKES -> it.kind == ContactKind.QUAKE.name
                    }
                }
                .sortedBy { it.distanceMeters }

            LazyColumn(
                Modifier.fillMaxSize().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(shown, key = { it.id }) { ContactRow(it) }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: Contact) {
    val c = Pulse.colors
    val accent = when (contact.kind) {
        ContactKind.QUAKE.name -> c.amber
        ContactKind.ISS.name -> c.sky
        else -> if (contact.military) c.violet else c.accent
    }
    LcarsFrame(Modifier.fillMaxWidth(), accent = accent) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    contact.label,
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = c.ink, modifier = Modifier.weight(1f),
                )
                Text(
                    "${km(contact.distanceMeters)} ${compass(contact.bearingDeg)}",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
                )
            }
            // The core already words this — registration, type, operator, or the quake's place. It is
            // the reason all those ADS-B fields are parsed, so it is what the row leads with.
            Text(
                contact.identityLine,
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                modifier = Modifier.padding(top = 3.dp),
            )
            val facts = buildList {
                contact.altitudeM?.let { add("${(it * 3.28084).toInt()} ft") }
                contact.groundSpeedKmh?.let { add("${it.toInt()} km/h") }
                contact.verticalRateFpm?.takeIf { it != 0 }?.let {
                    add(if (it > 0) "climbing $it fpm" else "descending ${-it} fpm")
                }
                contact.selectedAltitudeFt?.let { add("cleared $it ft") }
                if (contact.onGround) add("on the ground")
                if (contact.coasting) add("coasting — no recent position")
                contact.depthKm?.let { add("${it.toInt()} km deep") }
                contact.magType?.let { add(it) }
                if (contact.tsunami) add("TSUNAMI")
                contact.feltReports?.takeIf { it > 0 }?.let { add("$it felt reports") }
            }
            if (facts.isNotEmpty()) {
                Text(
                    facts.joinToString(" · "),
                    fontFamily = JetBrainsMono, fontSize = 10.sp,
                    color = if (contact.tsunami) c.negative else c.ink2,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

private fun km(meters: Double): String =
    if (meters < 1000) "${meters.toInt()} m" else String.format(java.util.Locale.US, "%.1f km", meters / 1000.0)

/** Eight points is as precise as a bearing needs to be when you are reading it off a list. */
private fun compass(deg: Double): String {
    val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val idx = (((deg % 360.0) + 360.0) % 360.0 / 45.0).toInt() % 8
    return points[idx]
}
