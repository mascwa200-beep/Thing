package dev.mascwa.pulse.feature.sky

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.Eclipses
import dev.mascwa.pulse.core.telemetry.Ephemeris
import dev.mascwa.pulse.core.telemetry.MeteorShowers
import dev.mascwa.pulse.core.telemetry.SatellitePasses
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.data.orbital.LaunchRepository
import dev.mascwa.pulse.data.orbital.OrbitalData
import dev.mascwa.pulse.data.orbital.OrbitalRepository
import dev.mascwa.pulse.data.orbital.TleRepository
import dev.mascwa.pulse.data.orbital.UpcomingLaunch
import dev.mascwa.pulse.data.weather.LocationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything the sky is doing, from four independent sources.
 *
 * Each lives on its own flow rather than being folded into one [Async] payload. That is the shape
 * the space-weather screen had to be rewritten into after its aurora percentage kept being
 * clobbered: when a slow fetch and a fast one write the same object, whichever finishes last wins
 * and silently discards the other. Independent flows cannot race.
 */
class OrbitalViewModel(
    private val repo: OrbitalRepository,
    private val locationProvider: LocationProvider,
    private val tleRepository: TleRepository,
    private val launchRepository: LaunchRepository,
) : ViewModel() {

    /** Where the observer is. Passes and rise/set times are meaningless without it. */
    data class Site(val latitude: Double, val longitude: Double)

    /** The night ahead: when it gets dark, what the Moon is doing, and what flies over. */
    data class Tonight(
        val daylight: Ephemeris.DayLight,
        val moonRise: Ephemeris.RiseSet,
        val moon: Ephemeris.MoonPhase,
        /** Full horizontal positions, not just altitudes — the sky chart needs the azimuths too. */
        val sunPosition: Ephemeris.Horizontal,
        val moonPosition: Ephemeris.Horizontal,
    ) {
        val sunAltitudeDeg: Double get() = sunPosition.altitudeDeg
        val moonAltitudeDeg: Double get() = moonPosition.altitudeDeg

        /** Dark enough for satellites and faint objects. */
        val darkNow: Boolean get() = sunAltitudeDeg <= Ephemeris.Altitudes.CIVIL_TWILIGHT
    }

    private val _state = MutableStateFlow<Async<OrbitalData>>(Async(loading = true))
    val state: StateFlow<Async<OrbitalData>> = _state.asStateFlow()

    private val _site = MutableStateFlow<Site?>(null)
    val site: StateFlow<Site?> = _site.asStateFlow()

    private val _tonight = MutableStateFlow<Tonight?>(null)
    val tonight: StateFlow<Tonight?> = _tonight.asStateFlow()

    private val _passes = MutableStateFlow<List<SatellitePasses.Pass>>(emptyList())
    val passes: StateFlow<List<SatellitePasses.Pass>> = _passes.asStateFlow()

    private val _passesLoading = MutableStateFlow(false)
    val passesLoading: StateFlow<Boolean> = _passesLoading.asStateFlow()

    /** How many element sets the predictions were run against — 0 means nothing was downloaded. */
    private val _trackedCount = MutableStateFlow(0)
    val trackedCount: StateFlow<Int> = _trackedCount.asStateFlow()

    private val _launches = MutableStateFlow<List<UpcomingLaunch>>(emptyList())
    val launches: StateFlow<List<UpcomingLaunch>> = _launches.asStateFlow()

    /**
     * One shower, dated and seen from here.
     *
     * ⚠️ The three parts travel together because they are one answer. Handing the screen an
     * occurrence and letting it call [MeteorShowers.viewing] itself would put a second clock read in
     * the composition, so the peak countdown and the radiant altitude could describe two different
     * instants — the shape that has already produced two separate defects in this app.
     */
    data class ShowerNight(
        val occurrence: MeteorShowers.Occurrence,
        val viewing: MeteorShowers.Viewing,
        val advice: String,
    )

    private val _showers = MutableStateFlow<List<ShowerNight>>(emptyList())
    val showers: StateFlow<List<ShowerNight>> = _showers.asStateFlow()

    /**
     * An eclipse and what THIS place would see of it.
     *
     * ⚠️ Bundled for the same reason [ShowerNight] is: the eclipse, the local circumstances and the
     * sentence describing them must all be about one observer at one instant, and computing the
     * local half in the composition would take a second clock read.
     */
    data class EclipseNight(
        val eclipse: Eclipses.Eclipse,
        val local: Eclipses.Local,
        val advice: String,
    )

    private val _eclipses = MutableStateFlow<List<EclipseNight>>(emptyList())
    val eclipses: StateFlow<List<EclipseNight>> = _eclipses.asStateFlow()

    init { load(force = false) }

    fun refresh() = load(force = true)

    private fun load(force: Boolean) {
        viewModelScope.launch {
            // One GPS fix, then three independent branches off it. Resolving the location inside
            // each branch would mean three fixes for one refresh.
            val loc = resolveSite()
            if (loc != null) _site.value = loc
            launch { _state.load(force) { repo.fetch(loc?.latitude, loc?.longitude, it) } }
            if (loc != null) {
                launch { computeTonight(loc.latitude, loc.longitude) }
                launch { computeShowers(loc.latitude, loc.longitude) }
                launch { computeEclipses(loc.latitude, loc.longitude) }
                launch { loadPasses(loc, force) }
            }
        }
        // Launches do not depend on where you are.
        viewModelScope.launch {
            _launches.value = runCatching { launchRepository.upcoming(force).data }.getOrDefault(emptyList())
        }
    }

    private suspend fun resolveSite(): Site? {
        if (!locationProvider.hasPermission()) return null
        return runCatching { locationProvider.current() }.getOrNull()
            ?.let { Site(it.latitude, it.longitude) }
    }

    /** Sun and Moon geometry for the observer — pure maths, no network, so it never fails. */
    private suspend fun computeTonight(lat: Double, lon: Double) {
        val now = System.currentTimeMillis()
        // The device's own midnight, not UTC's. Rounding the epoch down to a multiple of a day
        // gives UTC midnight, which for anyone far from Greenwich would compute "today's" sunset
        // for the wrong day.
        val dayStart = runCatching {
            LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrDefault(now - (now % 86_400_000L))
        _tonight.value = withContext(Dispatchers.Default) {
            runCatching {
                Tonight(
                    daylight = Ephemeris.daylight(lat, lon, dayStart),
                    moonRise = Ephemeris.moonRiseSet(lat, lon, dayStart),
                    moon = Ephemeris.moonPhase(now),
                    sunPosition = Ephemeris.sunPosition(lat, lon, now),
                    moonPosition = Ephemeris.moonPosition(lat, lon, now),
                )
            }.getOrNull()
        }
    }

    /**
     * Which showers are on, and whether tonight is worth it from here.
     *
     * ⚠️ **One clock read, shared by both halves.** The peak countdown and the radiant's altitude
     * have to describe the same instant, and calling `System.currentTimeMillis()` twice is how they
     * come to disagree by however long the first computation took.
     *
     * Pure maths and no network, like [computeTonight] — the whole table is on the device, so this
     * works with the radio off. Off the main thread anyway: thirteen solar-longitude solves each run
     * a handful of Newton steps over the Sun's series, which is fast and not free.
     */
    private suspend fun computeShowers(lat: Double, lon: Double) {
        val now = System.currentTimeMillis()
        _showers.value = withContext(Dispatchers.Default) {
            runCatching {
                MeteorShowers.upcoming(now).map { occurrence ->
                    val viewing = MeteorShowers.viewing(occurrence.shower, lat, lon, now)
                    ShowerNight(occurrence, viewing, MeteorShowers.advice(occurrence, viewing))
                }
            }.getOrDefault(emptyList())
        }
    }

    /**
     * Every eclipse of the next two years, and what this place would see of each.
     *
     * ⚠️ **Off the main thread, and the horizon is a measured trade rather than a round number.**
     * The search minimises a Sun-to-Moon separation over a six-hourly scan, so it costs about 25 ms
     * a year on a desktop JVM — measured at 26/51/74/121 ms for one, two, three and five years,
     * returning 4/9/13/24 eclipses. Two years buys a list worth reading for half the cost of three,
     * and this runs on every refresh on hardware several times slower than the machine that
     * measured it. Local circumstances for the whole list add about 1 ms.
     *
     * No network at any point: the entire thing is closed-form astronomy over the device's clock,
     * so it works with the radio off.
     */
    private suspend fun computeEclipses(lat: Double, lon: Double) {
        val now = System.currentTimeMillis()
        _eclipses.value = withContext(Dispatchers.Default) {
            runCatching {
                Eclipses.upcoming(now, now + ECLIPSE_HORIZON_MS).map { e ->
                    val local = Eclipses.local(e, lat, lon)
                    EclipseNight(e, local, Eclipses.advice(e, local))
                }
            }.getOrDefault(emptyList())
        }
    }

    /**
     * Predict the next two days of passes over the observer.
     *
     * Runs off the main thread: propagating a hundred-odd satellites at a 30-second step is a few
     * hundred thousand SGP4 evaluations, which is fast but not free.
     */
    private suspend fun loadPasses(loc: Site, force: Boolean) {
        _passesLoading.value = true
        try {
            val elements = runCatching {
                tleRepository.elements(
                    listOf(TleRepository.Group.STATIONS, TleRepository.Group.VISUAL),
                    force,
                ).data
            }.getOrDefault(emptyList())
            _trackedCount.value = elements.size
            if (elements.isEmpty()) {
                _passes.value = emptyList()
                return
            }
            val site = SatellitePasses.Site(loc.latitude, loc.longitude)
            val from = System.currentTimeMillis()
            val to = from + 48 * 3600_000L
            _passes.value = withContext(Dispatchers.Default) {
                elements
                    .flatMap { element ->
                        runCatching {
                            SatellitePasses.passes(
                                elements = element,
                                site = site,
                                fromEpochMs = from,
                                toEpochMs = to,
                                minElevationDeg = MIN_ELEVATION_DEG,
                                limit = MAX_PASSES_PER_SATELLITE,
                            )
                        }.getOrDefault(emptyList())
                    }
                    // Visible ones first, then whatever is highest — a bright overhead pass is the
                    // thing worth walking outside for.
                    .sortedWith(
                        compareByDescending<SatellitePasses.Pass> { it.isVisible }
                            .thenBy { it.riseEpochMs },
                    )
                    .take(MAX_PASSES_SHOWN)
            }
        } finally {
            _passesLoading.value = false
        }
    }

    private companion object {
        /** Below this a pass is behind buildings and trees for most people. */
        const val MIN_ELEVATION_DEG = 15.0
        const val MAX_PASSES_PER_SATELLITE = 8
        const val MAX_PASSES_SHOWN = 60

        /** Two years of eclipses — see [computeEclipses] for why that number and not three. */
        const val ECLIPSE_HORIZON_MS = 2L * 365L * 86_400_000L
    }
}
