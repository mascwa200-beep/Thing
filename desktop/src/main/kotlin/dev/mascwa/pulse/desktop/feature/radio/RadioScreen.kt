package dev.mascwa.pulse.desktop.feature.radio

import androidx.compose.foundation.clickable
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
import dev.mascwa.pulse.data.radio.DEFAULT_STATIONS
import dev.mascwa.pulse.data.radio.RadioBrowserRepository
import dev.mascwa.pulse.data.radio.RadioStation
import dev.mascwa.pulse.desktop.feature.world.here
import dev.mascwa.pulse.desktop.radio.RadioPlayer
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsBusyBar
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsGhostButton
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.LcarsTextField
import dev.mascwa.pulse.desktop.theme.Pulse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How a list of stations is doing, so an empty one can say which kind of empty it is. */
enum class ListStatus { IDLE, LOADING, READY, FAILED, NO_LOCATION }

data class RadioState(
    val favourites: List<RadioStation> = emptyList(),
    val local: List<RadioStation> = emptyList(),
    val localStatus: ListStatus = ListStatus.IDLE,
    val results: List<RadioStation> = emptyList(),
    val searchStatus: ListStatus = ListStatus.IDLE,
    val query: String = "",
)

class RadioViewModel(
    private val scope: CoroutineScope,
    private val settings: DesktopSettingsStore,
    private val directory: RadioBrowserRepository,
    val player: RadioPlayer,
) {
    private val _state = MutableStateFlow(RadioState())
    val state: StateFlow<RadioState> = _state.asStateFlow()

    private var localJob: Job? = null
    private var searchJob: Job? = null

    val curated: List<RadioStation> = DEFAULT_STATIONS

    fun load() {
        scope.launch {
            _state.value = _state.value.copy(
                favourites = runCatching { settings.current().favoriteRadio }.getOrDefault(emptyList()),
            )
        }
        if (_state.value.localStatus == ListStatus.IDLE) loadLocal()
    }

    /**
     * Stations near this machine.
     *
     * ⚠️ Requires a coordinate, which a tower PC cannot obtain — it comes from settings. Reporting
     * that as its own state rather than as an empty list is the difference between "nothing is
     * broadcasting near you" and "nobody has told this machine where it is".
     */
    fun loadLocal() {
        localJob?.cancel()
        localJob = scope.launch {
            _state.value = _state.value.copy(localStatus = ListStatus.LOADING)
            val here = settings.here()
            if (here == null) {
                _state.value = _state.value.copy(local = emptyList(), localStatus = ListStatus.NO_LOCATION)
                return@launch
            }
            val country = runCatching { settings.current().countryCode }.getOrNull()
            val found = runCatching {
                directory.localStations(here.first, here.second, country, state = null)
            }.getOrNull()
            _state.value = _state.value.copy(
                local = found.orEmpty(),
                localStatus = if (found == null) ListStatus.FAILED else ListStatus.READY,
            )
        }
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    fun search() {
        val q = _state.value.query.trim()
        searchJob?.cancel()
        if (q.isBlank()) {
            _state.value = _state.value.copy(results = emptyList(), searchStatus = ListStatus.IDLE)
            return
        }
        searchJob = scope.launch {
            _state.value = _state.value.copy(searchStatus = ListStatus.LOADING)
            val found = runCatching { directory.searchStations(q) }.getOrNull()
            _state.value = _state.value.copy(
                results = found.orEmpty(),
                searchStatus = if (found == null) ListStatus.FAILED else ListStatus.READY,
            )
        }
    }

    /**
     * ⚠️ Matched with [RadioStation.sameStation], never by whole-object equality. A directory result
     * and the copy saved as a favourite differ in fields that drift — the click count, the codec
     * string — so `contains` would report a starred station as unstarred and starring it again would
     * add a duplicate rather than removing it.
     */
    fun isFavourite(station: RadioStation): Boolean =
        _state.value.favourites.any { it.sameStation(station) }

    fun toggleFavourite(station: RadioStation) {
        scope.launch {
            settings.update { s ->
                val existing = s.favoriteRadio.filter { !it.sameStation(station) }
                s.copy(
                    favoriteRadio = if (existing.size == s.favoriteRadio.size) existing + station
                    else existing,
                )
            }
            _state.value = _state.value.copy(
                favourites = runCatching { settings.current().favoriteRadio }.getOrDefault(emptyList()),
            )
        }
    }
}

@Composable
fun RadioScreen(vm: RadioViewModel, modifier: Modifier = Modifier) {
    val state: RadioState by vm.state.collectAsState()
    val playing by vm.player.state.collectAsState()
    val c = Pulse.colors

    LaunchedEffect(Unit) { vm.load() }

    Column(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        LcarsHeaderBar(
            "Radio",
            trailing = when (playing.status) {
                RadioPlayer.Status.PLAYING -> "ON AIR"
                RadioPlayer.Status.CONNECTING -> "TUNING"
                RadioPlayer.Status.ERROR -> "FAULT"
                RadioPlayer.Status.IDLE -> null
            },
        )
        LcarsBusyBar(
            active = playing.status == RadioPlayer.Status.CONNECTING,
            modifier = Modifier.fillMaxWidth(),
        )

        // The tuner: what is on, and how to stop it.
        playing.station?.let { s ->
            LcarsFrame(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                accent = if (playing.status == RadioPlayer.Status.ERROR) c.negative else c.positive,
            ) {
                Column {
                    Text(
                        s.name,
                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        color = c.ink,
                    )
                    Text(
                        describe(s),
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    playing.detail?.let {
                        Text(
                            it,
                            fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 16.sp,
                            color = c.amber,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (playing.status != RadioPlayer.Status.ERROR) {
                        LcarsGhostButton("STOP", { vm.player.stop() }, Modifier.padding(top = 8.dp))
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsTextField(
                label = "Find a station",
                value = state.query,
                onValueChange = vm::setQuery,
                placeholder = "by name",
                modifier = Modifier.weight(1f),
            )
            LcarsGhostButton("SEARCH", { vm.search() })
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (state.searchStatus != ListStatus.IDLE) {
                item { LcarsHeaderBar("Results", trailing = "${state.results.size}") }
                emptyNote(state.searchStatus, state.results.isEmpty(), "Nothing matched that name.")
                    ?.let { note -> item { Note(note) } }
                items(state.results, key = { "r_" + it.streamUrl }) { StationRow(it, vm, playing) }
            }

            item { LcarsHeaderBar("Near you", Modifier.padding(top = 10.dp), trailing = "${state.local.size}") }
            emptyNote(
                state.localStatus,
                state.local.isEmpty(),
                "Nothing is broadcasting near here that the directory knows about.",
            )?.let { note -> item { Note(note) } }
            items(state.local, key = { "l_" + it.streamUrl }) { StationRow(it, vm, playing) }

            if (state.favourites.isNotEmpty()) {
                item { LcarsHeaderBar("Starred", Modifier.padding(top = 10.dp)) }
                items(state.favourites, key = { "f_" + it.streamUrl }) { StationRow(it, vm, playing) }
            }

            item { LcarsHeaderBar("Always on", Modifier.padding(top = 10.dp)) }
            items(vm.curated, key = { "c_" + it.streamUrl }) { StationRow(it, vm, playing) }

            item {
                Text(
                    "Directory by the Radio Browser community · the always-on stations are SomaFM, " +
                        "which is listener-supported and free to stream.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 15.sp, color = c.faint,
                    modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
                )
            }
        }
    }
}

/**
 * What an empty list means. Null when there is nothing to explain — either the list has content, or
 * it is still loading and the busy bar already says so.
 */
private fun emptyNote(status: ListStatus, empty: Boolean, nothingFound: String): String? = when {
    !empty -> null
    status == ListStatus.LOADING || status == ListStatus.IDLE -> null
    status == ListStatus.NO_LOCATION ->
        "This machine does not know where it is. Open SETTINGS and either let it guess from your " +
            "connection or type a latitude and longitude."
    status == ListStatus.FAILED -> "The station directory did not answer. Try again in a moment."
    else -> nothingFound
}

@Composable
private fun Note(text: String) {
    LcarsFrame(Modifier.fillMaxWidth(), accent = Pulse.colors.muted) {
        Text(
            text,
            fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 16.sp, color = Pulse.colors.muted,
        )
    }
}

@Composable
private fun StationRow(station: RadioStation, vm: RadioViewModel, playing: RadioPlayer.State) {
    val c = Pulse.colors
    val onAir = playing.station?.sameStation(station) == true &&
        playing.status == RadioPlayer.Status.PLAYING
    val starred = vm.isFavourite(station)
    LcarsFrame(
        Modifier.fillMaxWidth().clickable { vm.player.toggle(station) },
        accent = if (onAir) c.positive else c.accent,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    station.name,
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = if (onAir) c.positive else c.ink,
                )
                Text(
                    describe(station),
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                if (starred) "★" else "☆",
                fontFamily = JetBrainsMono, fontSize = 15.sp,
                color = if (starred) c.amber else c.muted,
                modifier = Modifier
                    .clickable { vm.toggleFavourite(station) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * The line under a station's name.
 *
 * ⚠️ Carries the codec and the bit rate, which the directory supplies and which used to be parsed
 * and dropped. They matter together — 64 kbps of AAC and 64 kbps of MP3 do not sound alike — and on
 * this machine the codec also decides whether the station can play at all.
 */
private fun describe(station: RadioStation): String = listOfNotNull(
    station.band.takeIf { it.isNotBlank() },
    station.language.takeIf { it.isNotBlank() },
    station.codec.takeIf { it.isNotBlank() }?.uppercase(),
    station.kbps.takeIf { it > 0 }?.let { "$it kbps" },
).joinToString(" · ").ifBlank { "no details given" }
