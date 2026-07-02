package dev.mascwa.pulse.data.game

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.GameLocation
import dev.mascwa.pulse.core.telemetry.TravelFilter
import dev.mascwa.pulse.core.telemetry.WorldSite
import dev.mascwa.pulse.core.telemetry.WorldSites
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

    // Each query's [category] is a token WorldSites.typeFor classifies into a SiteType — so one place →
    // one wasteland site (and, for trade sites, a GameLocation shop derived from the SiteType's shopKind).
    private data class SiteQuery(val category: String, val id: String, val filter: String)

    private val queries = listOf(
        // Safe / trade sites (also surface as GameLocation shops).
        SiteQuery("supermarket", "gw_trader", """["shop"~"^(supermarket|convenience|general|mall|department_store|greengrocer|kiosk)$"]"""),
        SiteQuery("pharmacy", "gw_medic", """["amenity"~"^(pharmacy|hospital|clinic|doctors)$"]"""),
        SiteQuery("hardware", "gw_fixer", """["shop"~"^(hardware|doityourself|electronics|trade)$"]"""),
        SiteQuery("pub", "gw_barkeep", """["amenity"~"^(bar|pub|cafe|restaurant|fast_food)$"]"""),
        SiteQuery("fuel", "gw_outpost", """["amenity"="fuel"]"""),
        // Danger / exploration sites (wasteland-only — tribes, gangs, monster dens, vaults, ruins).
        SiteQuery("leisure=park", "gw_tribe", """["leisure"~"^(park|nature_reserve)$"]"""),
        SiteQuery("landuse=industrial", "gw_gang", """["landuse"~"^(industrial)$"]"""),
        SiteQuery("natural=water", "gw_monster", """["natural"~"^(water|wood|wetland)$"]"""),
        SiteQuery("military=bunker", "gw_vault", """["military"~"^(bunker)$"]"""),
        SiteQuery("historic=ruins", "gw_ruins", """["historic"~"^(ruins|archaeological_site)$"]"""),
    )

    private val prefsKey = stringPreferencesKey("gameworld_json")
    private val mutex = Mutex()
    private var loaded = false
    private var flushJob: Job? = null

    // Travel state (authoritative in-memory).
    private var distanceM = 0.0
    private var placesVisited = emptySet<String>()
    private var playMs = 0L
    // The last position we *committed* distance from. Distance only accrues once the player moves clear of
    // the GPS-uncertainty circle around this anchor — so standing still (jitter) never adds distance.
    private var anchorLat: Double? = null
    private var anchorLon: Double? = null

    private val _locations = MutableStateFlow<List<GameLocation>>(emptyList())
    val locationsFlow: StateFlow<List<GameLocation>> = _locations.asStateFlow()

    // The geo-gated wasteland: every scanned real place as a WorldSite (settlements/tribes/gang camps/monster
    // dens/vaults/ruins/shops). The trade sites also appear in [locationsFlow] as GameLocations (shop layer).
    private val _sites = MutableStateFlow<List<WorldSite>>(emptyList())
    val sitesFlow: StateFlow<List<WorldSite>> = _sites.asStateFlow()

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
            val sites = mutableListOf<WorldSite>()
            for (q in queries) {
                val type = WorldSites.typeFor(q.category)
                val places = runCatching {
                    overpass.fetch(q.id, q.filter, RADIUS_M, type.label, lat, lon, force = false).data.places
                }.getOrNull().orEmpty()
                places.take(PER_KIND).forEach { p ->
                    sites += WorldSites.siteFor(stableId(p.latitude, p.longitude), p.latitude, p.longitude, q.category)
                }
            }
            val dedupedSites = sites.distinctBy { it.id }
                .sortedBy { Geo.distanceMeters(lat, lon, it.lat, it.lon) }
                .take(MAX_SITES)
            _sites.value = dedupedSites
            // Trade sites also drive the existing shop layer (GameLocation) so nothing downstream breaks.
            _locations.value = dedupedSites.mapNotNull { s ->
                s.type.shopKind?.let { GameLocation(s.id, s.name, it, s.lat, s.lon) }
            }.take(MAX_LOCATIONS)
            _scanning.value = false
            // A fresh scan at the current spot may already be "at" a location.
            onLocation(lat, lon)
        }
    }

    /**
     * Feed a GPS fix: accrue walked distance only for *real* movement, and mark any location reached.
     * [accuracyM] is the fix's horizontal accuracy (metres) — the key to not counting a stationary phone as
     * walking. A fix must clear an uncertainty-sized radius from the last committed anchor before it counts,
     * so GPS wander while you sit still adds nothing; genuine walking (which leaves that radius) does.
     */
    fun onLocation(lat: Double, lon: Double, accuracyM: Float? = null) {
        scope.launch {
            ensureLoaded()
            // Distance accrual is the CI-tested pure filter — jitter never counts, real walking does.
            val res = TravelFilter.step(anchorLat, anchorLon, lat, lon, accuracyM?.toDouble())
            distanceM += res.addedM
            anchorLat = res.anchorLat
            anchorLon = res.anchorLon

            // Only trust a reasonably accurate fix to mark a location "reached".
            if (accuracyM == null || accuracyM <= TravelFilter.MAX_ACCURACY_M) {
                _locations.value.forEach { loc ->
                    if (loc.id !in placesVisited && Geo.distanceMeters(lat, lon, loc.lat, loc.lon) <= VISIT_RADIUS_M) {
                        placesVisited = placesVisited + loc.id
                    }
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
                distanceM = 0.0; placesVisited = emptySet(); playMs = 0L; anchorLat = null; anchorLon = null
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
        const val MAX_SITES = 32 // more types than shops → a slightly larger cap for the full site set
        const val VISIT_RADIUS_M = 60.0
        const val FLUSH_DELAY_MS = 2_000L
    }
}
