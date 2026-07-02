package dev.mascwa.pulse.data.game

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.GameLocation
import dev.mascwa.pulse.core.telemetry.GeoTracking
import dev.mascwa.pulse.core.telemetry.Transport
import dev.mascwa.pulse.core.telemetry.TransportMode
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

/**
 * The player's real-world footprint in the game: how far they've travelled (total + broken out by how they
 * got around — on foot / running / cycling / driving), vertical metres climbed, distinct ~111 m areas
 * explored, where they've been, and time played. Aggregate + on-device only.
 */
data class TravelStats(
    val distanceM: Double = 0.0,
    val placesVisited: Int = 0,
    val playMs: Long = 0L,
    val walkM: Long = 0L,
    val runM: Long = 0L,
    val cycleM: Long = 0L,
    val driveM: Long = 0L,
    val elevationM: Long = 0L,
    val cellsExplored: Int = 0,
)

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
        // Per-mode distance, vertical climb, and the coarse ~111 m cells explored. All defaulted → old saves
        // load with no transport/elevation/exploration history. On-device only.
        val walkM: Long = 0L,
        val runM: Long = 0L,
        val cycleM: Long = 0L,
        val driveM: Long = 0L,
        val elevationM: Long = 0L,
        val cells: List<String> = emptyList(),
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
    // Real-time tracking: distance by transport mode, vertical climb, and coarse cells explored.
    private var modeMeters: Map<TransportMode, Long> = emptyMap()
    private var elevationM = 0L
    private var cells = emptySet<String>()
    private var lastAltM: Double? = null   // previous altitude, for computing climb (not persisted)
    private var lastFixMs = 0L             // wall clock of the last committed fix, for a speed fallback (not persisted)

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
                modeMeters = mapOf(
                    TransportMode.WALK to stored.walkM.coerceAtLeast(0L),
                    TransportMode.RUN to stored.runM.coerceAtLeast(0L),
                    TransportMode.CYCLE to stored.cycleM.coerceAtLeast(0L),
                    TransportMode.DRIVE to stored.driveM.coerceAtLeast(0L),
                ).filterValues { it > 0L }
                elevationM = stored.elevationM.coerceAtLeast(0L)
                cells = stored.cells.toSet()
            }
            loaded = true
        }
        publishTravel()
    }

    private fun stableId(lat: Double, lon: Double): String = "%.5f,%.5f".format(lat, lon)

    private fun publishTravel() {
        _travel.value = TravelStats(
            distanceM = distanceM, placesVisited = placesVisited.size, playMs = playMs,
            walkM = Transport.distance(modeMeters, TransportMode.WALK),
            runM = Transport.distance(modeMeters, TransportMode.RUN),
            cycleM = Transport.distance(modeMeters, TransportMode.CYCLE),
            driveM = Transport.distance(modeMeters, TransportMode.DRIVE),
            elevationM = elevationM,
            cellsExplored = cells.size,
        )
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
    fun onLocation(
        lat: Double,
        lon: Double,
        accuracyM: Float? = null,
        speedMps: Double? = null,
        altitudeM: Double? = null,
    ) {
        scope.launch {
            ensureLoaded()
            // Distance accrual is the CI-tested pure filter — jitter never counts, real walking does.
            val res = TravelFilter.step(anchorLat, anchorLon, lat, lon, accuracyM?.toDouble())
            distanceM += res.addedM
            anchorLat = res.anchorLat
            anchorLon = res.anchorLon

            // Attribute any committed distance to a transport mode, using the fix's reported ground speed
            // (or a distance/time fallback when the fix didn't report one). STILL never accrues.
            val now = System.currentTimeMillis()
            if (res.addedM > 0) {
                val effSpeed = speedMps ?: run {
                    val dtSec = if (lastFixMs > 0L) (now - lastFixMs) / 1000.0 else 0.0
                    if (dtSec in 0.5..600.0) res.addedM / dtSec else null
                }
                // No usable speed but real movement → assume on foot (the most conservative moving mode).
                val mode = if (effSpeed != null) Transport.classify(effSpeed) else TransportMode.WALK
                modeMeters = Transport.accrue(modeMeters, mode, res.addedM.toLong())
            }
            lastFixMs = now

            // Vertical climb from real altitude changes (ascent only, above a noise floor).
            elevationM += GeoTracking.elevationGain(lastAltM, altitudeM)
            if (altitudeM != null) lastAltM = altitudeM

            // Exploration: count distinct ~111 m cells entered (capped so the on-device set stays bounded).
            if ((accuracyM == null || accuracyM <= TravelFilter.MAX_ACCURACY_M) && cells.size < MAX_CELLS) {
                cells = cells + GeoTracking.cellId(lat, lon)
            }

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
                modeMeters = emptyMap(); elevationM = 0L; cells = emptySet(); lastAltM = null; lastFixMs = 0L
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
        val snapshot = Stored(
            distanceM = distanceM, placesVisited = placesVisited.toList(), playMs = playMs,
            walkM = Transport.distance(modeMeters, TransportMode.WALK),
            runM = Transport.distance(modeMeters, TransportMode.RUN),
            cycleM = Transport.distance(modeMeters, TransportMode.CYCLE),
            driveM = Transport.distance(modeMeters, TransportMode.DRIVE),
            elevationM = elevationM,
            cells = cells.toList(),
        )
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
        const val MAX_CELLS = 5000 // bound the on-device exploration set (covers every CELLS_EXPLORED tier)
        const val FLUSH_DELAY_MS = 2_000L
    }
}
