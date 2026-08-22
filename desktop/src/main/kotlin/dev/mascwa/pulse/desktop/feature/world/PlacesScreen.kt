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
import dev.mascwa.pulse.data.places.OverpassRepository
import dev.mascwa.pulse.data.places.Place
import dev.mascwa.pulse.data.places.PlaceCategory
import dev.mascwa.pulse.data.places.PlacesResult
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.settings.DesktopUnits
import dev.mascwa.pulse.desktop.settings.LocalUnits
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsChip
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.Pulse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlacesViewModel(
    private val scope: CoroutineScope,
    private val repository: OverpassRepository,
    private val settings: DesktopSettingsStore,
) {
    private val _category = MutableStateFlow(PlaceCategory.HOSPITAL)
    val category: StateFlow<PlaceCategory> = _category.asStateFlow()

    /**
     * ⚠️ One feed per category, built lazily and kept.
     *
     * A single feed re-fetching on every chip tap would throw away an answer that is good for six hours
     * — and these are Overpass queries, which are heavy and rate-limited. Keeping them means going back
     * to Hospitals is instant.
     */
    private val feeds = mutableMapOf<PlaceCategory, WorldFeed<PlacesResult>>()

    fun feedFor(category: PlaceCategory): WorldFeed<PlacesResult> = feeds.getOrPut(category) {
        WorldFeed(scope, settings) { lat, lon, force -> repository.fetch(category, lat, lon, force) }
    }

    fun select(category: PlaceCategory) {
        _category.value = category
    }
}

/**
 * The nearest hospital, shelter, food bank or comm tower, from OpenStreetMap.
 *
 * The whole point is that these are places you might have to reach in a hurry, so every row carries how
 * to get in touch — which is why the phone's version was fixed to stop discarding the website and email
 * fields a fifth of results carry instead of a phone number.
 */
@Composable
fun PlacesScreen(vm: PlacesViewModel, modifier: Modifier = Modifier) {
    val category by vm.category.collectAsState()
    val feed = vm.feedFor(category)
    val state: Async<PlacesResult> by feed.state.collectAsState()
    val located by feed.located.collectAsState()
    val c = Pulse.colors

    LaunchedEffect(category) { feed.ensureLoaded() }

    Column(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PlaceCategory.entries.forEach { cat ->
                LcarsChip(cat.title, selected = cat == category, onClick = { vm.select(cat) })
            }
        }

        WorldPanel(
            title = "Nearest help",
            feed = feed,
            state = state,
            located = located,
            trailing = state.data?.places?.size?.takeIf { it > 0 }?.let { "$it FOUND" },
            emptyMessage = "Nothing of this kind mapped within range.",
            isEmpty = { it.places.isEmpty() },
        ) { result ->
            if (result.truncated) {
                // Should never happen — the search narrows until the quota stops binding — but if it
                // does, these rows are an arbitrary slice and calling them "nearest" would be false.
                LcarsFrame(Modifier.fillMaxWidth(), accent = c.amber) {
                    Text(
                        "The map server capped this answer, so these are not necessarily the nearest.",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink,
                    )
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(result.places, key = { it.name + it.latitude + it.longitude }) { PlaceRow(it) }
                item {
                    Text(
                        "Searched ${DesktopUnits.longDistance(result.searchRadiusMeters / 1000.0, LocalUnits.current.miles)} · OpenStreetMap contributors",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
                        modifier = Modifier.padding(top = 10.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(place: Place) {
    val c = Pulse.colors
    LcarsFrame(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    place.name,
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = c.ink, modifier = Modifier.weight(1f),
                )
                Text(
                    "${km(place.distanceMeters)} ${compass(place.bearing)}",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
                )
            }
            val kindLine = buildList {
                // A&E or not is the single most consequential fact about a hospital in an emergency,
                // and it is a tag OSM carries on most of them.
                place.emergency?.takeIf { it.equals("yes", true) }?.let { add("HAS AN EMERGENCY DEPARTMENT") }
                place.kind?.let { add(it.replace('_', ' ')) }
                place.speciality?.let { add(it.replace(';', '/').replace('_', ' ')) }
            }
            if (kindLine.isNotEmpty()) {
                Text(
                    kindLine.joinToString(" · "),
                    fontFamily = JetBrainsMono, fontSize = 10.sp,
                    color = if (place.emergency.equals("yes", true)) c.positive else c.muted,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            place.address?.let {
                Text(
                    it,
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            val contact = listOfNotNull(place.phone, place.website, place.email)
            if (contact.isNotEmpty()) {
                Text(
                    contact.joinToString("  ·  "),
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.sky,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            place.openingHours?.let {
                Text(
                    it,
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/**
 * ⚠️ `@Composable` so it can read the reader's own unit switch, and no longer a fourth private copy
 * of the same formatter — Places, Radar and Safety each carried one, identically, and three copies
 * of one rule is how they drift.
 */
@Composable
private fun km(meters: Double): String = DesktopUnits.distance(meters, LocalUnits.current.miles)

private fun compass(deg: Double): String {
    val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return points[(((deg % 360.0) + 360.0) % 360.0 / 45.0).toInt() % 8]
}
