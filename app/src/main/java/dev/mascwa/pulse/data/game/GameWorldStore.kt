package dev.mascwa.pulse.data.game

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.GameLocation
import dev.mascwa.pulse.core.telemetry.GameLocations
import dev.mascwa.pulse.core.telemetry.LocationKind
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.data.places.OverpassRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.gameWorldDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_gameworld")

/** The player's real-world footprint in the game: how far they've walked, where they've been, time played. */
data class TravelStats(val distanceM: Double = 0.0, val placesVisited: Int = 0, val playMs: Long = 0L)

/**
 * The Pokémon-Go layer: turns real nearby shops (OpenStreetMap via [OverpassRepository]) into game
 * [GameLocation]s, and tracks the player's real-world travel — distance walked, distinct locations
 * reached, and time played. Travel stats are aggregate + **on-device only** (no raw GPS trace is stored
 * or sent — see the store's persisted blob: just three numbers + a set of location ids). Mirrors
 * ProfileStore (in-memory + Mutex + debounced flush).
 */
class GameWorldStore(
    private val context: Context,
    private val json: Json,
    private val overpass: OverpassRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class Stored(
        val distanceM: Double = 0.0,
        val placesVisited: List<String> = emptyList(),
        val playMs: Long = 0L,
    )

    private data class KindQuery(val kind: LocationKind, val id: String, val filter: String)

    private val queries = listOf(
        KindQuery(LocationKind.TRADER, "gw_trader", """["shop"~"^(supermarket|convenience|general|mall|department_store|greengrocer|kiosk)$"]"""),
        KindQuery(LocationKind.MEDIC, "gw_medic", """["amenity"~"^(pharmacy|hospital|clinic|doctors)$"]"""),
        KindQuery(LocationKind.FIXER, "gw_fixer", """["shop"~"^(hardware|doityourself|electronics|trade)$"]"""),
        KindQuery(LocationKind.BARKEEP, "gw_barkeep", """["amenity"~"^(bar|pub|cafe|restaurant|fast_food)$"]"""),
        KindQuery(LocationKind.OUTPOST, "gw_outpost", """["amenity"="fuel"]"""),
    )

    private val prefsKey = stringPreferencesKey("gameworld_json")
    private val mutex = Mutex()
    private var loaded = false
    private var flushJob: Job? = null

    // Travel state (authoritative in-memory).
    private var distanceM = 0.0
    private var placesVisited = emptySet<String>()
    private var playMs = 0L
    private var lastFix: Pair<Double, Double>? = null

    private val _locations = MutableStateFlow<List<GameLocation>>(emptyList())
    val locationsFlow: StateFlow<List<GameLocation>> = _locations.asStateFlow()

    private val _travel = MutableStateFlow(TravelStats())
    val travelFlow: StateFlow<TravelStats> = _travel.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanningFlow: StateFlow<Boolean> = _scanning.asStateFlow()

    private suspend fun ensureLoaded() {
        mutex.withLock {
            if (loaded) return@withLock
            val stored = context.gameWorldDataStore.data.first()[prefsKey]
                ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
            if (stored != null) {
                distanceM = stored.distanceM.coerceAtLeast(0.0)
                placesVisited = stored.placesVisited.toSet()
                playMs = stored.playMs.coerceAtLeast(0L)
            }
            loaded = true
        }
        publishTravel()
    }

    private fun stableId(lat: Double, lon: Double): String = "%.5f,%.5f".format(lat, lon)

    private fun publishTravel() {
        _travel.value = TravelStats(distanceM, placesVisited.size, playMs)
    }

    /** Fetch nearby real shops around [lat],[lon] and turn them into game locations (best-effort). */
    fun refresh(lat: Double, lon: Double) {
        scope.launch {
            ensureLoaded()
            _scanning.value = true
            val found = mutableListOf<GameLocation>()
            for (q in queries) {
                val places = runCatching {
                    overpass.fetch(q.id, q.filter, RADIUS_M, q.kind.title, lat, lon, force = false).data.places
                }.getOrNull().orEmpty()
                places.take(PER_KIND).forEach { p ->
                    found += GameLocation(stableId(p.latitude, p.longitude), p.name, q.kind, p.latitude, p.longitude)
                }
            }
            val deduped = found.distinctBy { it.id }
                .sortedBy { Geo.distanceMeters(lat, lon, it.lat, it.lon) }
                .take(MAX_LOCATIONS)
            _locations.value = deduped
            _scanning.value = false
            // A fresh scan at the current spot may already be "at" a location.
            onLocation(lat, lon)
        }
    }

    /** Feed a GPS fix: accumulate walked distance (noise/jump filtered) + mark any location reached. */
    fun onLocation(lat: Double, lon: Double) {
        scope.launch {
            ensureLoaded()
            lastFix?.let { (plat, plon) ->
                val d = Geo.distanceMeters(plat, plon, lat, lon)
                if (d in MIN_STEP_M..MAX_STEP_M) distanceM += d
            }
            lastFix = lat to lon
            _locations.value.forEach { loc ->
                if (loc.id !in placesVisited && Geo.distanceMeters(lat, lon, loc.lat, loc.lon) <= VISIT_RADIUS_M) {
                    placesVisited = placesVisited + loc.id
                }
            }
            publishTravel()
            scheduleFlush()
        }
    }

    /** Accumulate time spent playing (called on a timer while the game surface is visible). */
    fun addPlayTime(ms: Long) {
        if (ms <= 0) return
        scope.launch {
            ensureLoaded()
            playMs += ms
            publishTravel()
            scheduleFlush()
        }
    }

    /** Forget all travel history (aggregate stats + visited set). */
    fun clear() {
        flushJob?.cancel()
        scope.launch {
            mutex.withLock {
                distanceM = 0.0; placesVisited = emptySet(); playMs = 0L; lastFix = null
            }
            publishTravel()
            runCatching { context.gameWorldDataStore.edit { it.remove(prefsKey) } }
        }
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    private suspend fun flush() {
        val snapshot = Stored(distanceM, placesVisited.toList(), playMs)
        runCatching {
            context.gameWorldDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
        }
    }

    suspend fun flushNow() {
        flushJob?.cancel()
        flush()
    }

    private companion object {
        const val RADIUS_M = 2000
        const val PER_KIND = 6
        const val MAX_LOCATIONS = 24
        const val MIN_STEP_M = 3.0    // below this is GPS jitter
        const val MAX_STEP_M = 250.0  // above this is a fix jump, not walking
        const val VISIT_RADIUS_M = 60.0
        const val FLUSH_DELAY_MS = 2_000L
    }
}
