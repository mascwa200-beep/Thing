package dev.mascwa.pulse.feature.sky

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.Astrology
import dev.mascwa.pulse.core.telemetry.Eclipses
import dev.mascwa.pulse.core.telemetry.Comets
import dev.mascwa.pulse.core.telemetry.Ephemeris
import dev.mascwa.pulse.core.telemetry.ProperMotion
import dev.mascwa.pulse.core.telemetry.MeteorShowers
import dev.mascwa.pulse.core.telemetry.Occultations
import dev.mascwa.pulse.core.telemetry.SatellitePasses
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.data.orbital.LaunchRepository
import dev.mascwa.pulse.data.orbital.OrbitalData
import dev.mascwa.pulse.data.orbital.PlanetCalc
import dev.mascwa.pulse.data.orbital.OrbitalRepository
import dev.mascwa.pulse.data.orbital.TleRepository
import dev.mascwa.pulse.data.orbital.UpcomingLaunch
import dev.mascwa.pulse.data.sky.StarCatalog
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
    private val starCatalog: StarCatalog,
    private val cometRepository: dev.mascwa.pulse.data.orbital.CometRepository,
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

    /** One body's place along the ecliptic, in both conventions, and which way it is going. */
    data class ZodiacBody(
        val name: String,
        val longitudeDeg: Double,
        val tropical: Astrology.Sign,
        val sidereal: Astrology.Sign,
        val retrograde: Boolean,
    )

    /**
     * The sky as the zodiac divides it — real positions, traditional labels.
     *
     * ⚠️ The ascendant is nullable and stays that way all the way to the screen: it is the only
     * thing here that needs to know where the observer is standing, and at the poles it does not
     * exist at all rather than merely being unknown.
     */
    data class Zodiac(
        val bodies: List<ZodiacBody>,
        val aspects: List<Astrology.Aspect>,
        val ascendantDeg: Double?,
        val midheavenDeg: Double,
        val ayanamsaDeg: Double,
    )

    private val _zodiac = MutableStateFlow<Zodiac?>(null)
    val zodiac: StateFlow<Zodiac?> = _zodiac.asStateFlow()

    /** One occultation, with what this place gets of it and what to do about it. */
    data class Hiding(
        val event: Occultations.Event,
        val local: Occultations.Local,
        val advice: String,
    )

    private val _occultations = MutableStateFlow<List<Hiding>>(emptyList())
    val occultations: StateFlow<List<Hiding>> = _occultations.asStateFlow()

    /**
     * A comet worth pointing at, with where to point.
     *
     * ⚠️ [Comets.Sighting.magnitude] is a prediction from a fitted brightness law, not a
     * measurement, and comets are famously bad at obeying it. The card says so; this type carries
     * the number without editorialising, and [advice] is where the honesty lives.
     */
    data class Comet(
        val sighting: Comets.Sighting,
        val horizontal: Ephemeris.Horizontal,
        val advice: String,
    ) {
        /** Above the horizon with a bit of margin, so it is not lost in haze. */
        val up: Boolean get() = horizontal.altitudeDeg > 10.0
    }

    private val _comets = MutableStateFlow<List<Comet>>(emptyList())
    val comets: StateFlow<List<Comet>> = _comets.asStateFlow()

    private val _cometsLoading = MutableStateFlow(false)
    val cometsLoading: StateFlow<Boolean> = _cometsLoading.asStateFlow()

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
                launch { computeZodiac(loc.latitude, loc.longitude) }
                launch { computeOccultations(loc.latitude, loc.longitude) }
                launch { computeComets(loc.latitude, loc.longitude, force) }
                launch { loadPasses(loc, force) }
            }
        }
        // Launches do not depend on where you are.
        viewModelScope.launch {
            _launches.value = runCatching { launchRepository.upcoming(force).data }.getOrDefault(emptyList())
        }
    }

    /**
     * Which comets are worth looking for, and where to look.
     *
     * ⚠️ **The fetch and the arithmetic are deliberately separated.** The catalogue is 160 kB over
     * the network and lives for a week; the positions have to be recomputed every time this screen
     * opens, because a comet moves. Folding the two together would either refetch far too often or
     * show a stale position, and the second is worse — it looks right.
     *
     * ⚠️ **Off the main thread, all of it.** Nine hundred and fifty-seven orbits is nine hundred
     * and fifty-seven Kepler solves, each with a three-pass light-time iteration on top. It is a
     * few milliseconds in total, which is fine on a desk and is exactly the sort of thing this
     * project's potato pass exists to keep off the frame clock.
     */
    private suspend fun computeComets(lat: Double, lon: Double, force: Boolean) {
        _cometsLoading.value = true
        try {
            val catalogue = runCatching { cometRepository.elements(force).data }.getOrDefault(emptyList())
            if (catalogue.isEmpty()) {
                _comets.value = emptyList()
                return
            }
            val now = System.currentTimeMillis()
            _comets.value = withContext(Dispatchers.Default) {
                runCatching {
                    Comets.visible(catalogue, now, limit = COMET_LIMIT).map { s ->
                        Comet(
                            sighting = s,
                            horizontal = Ephemeris.toHorizontal(s.equatorial, lat, lon, now),
                            advice = Comets.describe(s),
                        )
                    }
                }.getOrDefault(emptyList())
            }
        } finally {
            _cometsLoading.value = false
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
     * When the Moon passes in front of something, and whether it does so over this place.
     *
     * ⚠️ **Star positions come from the bundled catalogue, precessed** — not from coordinates typed
     * in here. A J2000 catalogue position is twenty-two arcminutes from where the star is now, most
     * of the Moon's radius, so [Ephemeris.precessFromJ2000] is not optional; and taking them from
     * the same asset the sky chart draws from is what stops two features disagreeing about where
     * Aldebaran is. A star the catalogue does not name is simply absent rather than guessed at.
     *
     * ⚠️ **Planets are handed [PlanetCalc]'s own answer and its own error budget.** Its positions
     * are within three arcminutes of JPL across fifty years, measured — good enough to say an
     * occultation happens, not good enough to call one at the limb, which is exactly what
     * [Occultations.Local.grazing] is for.
     *
     * ⚠️ **The observer's latitude is passed to [PlanetCalc] only because it insists on one.** What
     * is read back is right ascension and declination, which do not depend on it — a
     * `:core:feeds` test asserts that two sites give bit-identical values.
     *
     * No network at any point, like the eclipses beside it.
     */
    private suspend fun computeOccultations(lat: Double, lon: Double) {
        val now = System.currentTimeMillis()
        val stars = runCatching { starCatalog.all() }.getOrDefault(emptyList())
        _occultations.value = withContext(Dispatchers.Default) {
            runCatching {
                val targets = ArrayList<Occultations.Target>()
                // ⚠️ One scratch pair per target rather than one shared by all of them: each lambda
                // below is called repeatedly by the search, so a single array would be written by
                // whichever target ran last. Allocated inside the loop for that reason.

                for (name in OCCULTABLE_STARS) {
                    val s = stars.firstOrNull { it.name == name } ?: continue
                    val here = DoubleArray(2)
                    targets += Occultations.Target(
                        name = name,
                        kind = Occultations.Kind.STAR,
                        magnitude = s.magnitude,
                        positionUncertaintyDeg = STAR_UNCERTAINTY_DEG,
                    ) { ms ->
                        // ⚠️ **Its own motion FIRST, then precession — and without the first the
                        // answer breaks this search's own stated budget.** `STAR_UNCERTAINTY_DEG`
                        // is two arcseconds, measured for the precession rotation against DE421 at
                        // a fixed J2000 position; the star's own motion is a separate error on top,
                        // and over the 26.7 years since J2000 it is **6.6 arcseconds for Regulus
                        // and 5.3 for Aldebaran** — three times the budget, on two of the five
                        // targets. Measured from the catalogue's own columns.
                        //
                        // The order is not interchangeable: proper motion is stated in the
                        // catalogue's own J2000 frame, so it has to be applied there, and
                        // `precessFromJ2000` takes a J2000 position by contract.
                        ProperMotion.carry(
                            s.rightAscensionDeg, s.declinationDeg,
                            s.pmRaMasPerYear, s.pmDecMasPerYear,
                            ProperMotion.yearsSince(StarCatalog.EPOCH_YEAR, ms),
                            here,
                        )
                        Ephemeris.precessFromJ2000(here[0], here[1], ms)
                    }
                }

                for (name in Occultations.OCCULTABLE_PLANETS) {
                    targets += Occultations.Target(
                        name = name,
                        kind = Occultations.Kind.PLANET,
                        // The magnitude changes through the year; the card reads the live one.
                        magnitude = 0.0,
                        positionUncertaintyDeg = PLANET_UNCERTAINTY_DEG,
                    ) { ms ->
                        PlanetCalc.planetsNow(lat, lon, ms).firstOrNull { it.name == name }?.let {
                            Ephemeris.Equatorial(it.rightAscensionDeg, it.declinationDeg, 1.0)
                        }
                    }
                }

                Occultations.upcoming(now, now + OCCULTATION_HORIZON_MS, targets).map { e ->
                    val local = Occultations.local(e, lat, lon)
                    Hiding(e, local, Occultations.advice(e, local))
                }
            }.getOrDefault(emptyList())
        }
    }

    /**
     * Where the seven classical bodies are along the ecliptic, and what tradition calls it.
     *
     * ⚠️ **The positions are the app's ordinary, measured ones** — the same [Ephemeris] that agrees
     * with JPL to a few arcseconds, and [PlanetCalc] for the planets. Nothing here is computed
     * differently because it is going to be labelled with a zodiac sign; the labelling is the only
     * part that is tradition, and [Astrology] documents that at length.
     *
     * ⚠️ **One clock read, shared by every body and by the retrograde comparison.** Calling for the
     * time twice is how the aspect between two bodies comes to describe two different instants —
     * the shape that has already produced two separate defects in this app.
     */
    private suspend fun computeZodiac(lat: Double, lon: Double) {
        val now = System.currentTimeMillis()
        val later = now + Astrology.RETROGRADE_BASELINE_MS
        _zodiac.value = withContext(Dispatchers.Default) {
            runCatching {
                val longitudes = LinkedHashMap<String, Double>()
                val bodies = ArrayList<ZodiacBody>()

                fun add(name: String, lonNow: Double, lonLater: Double) {
                    longitudes[name] = lonNow
                    bodies += ZodiacBody(
                        name = name,
                        longitudeDeg = lonNow,
                        tropical = Astrology.signOf(lonNow),
                        sidereal = Astrology.siderealSignOf(lonNow, now),
                        retrograde = Astrology.isRetrograde(lonNow, lonLater),
                    )
                }

                add(
                    "Sun",
                    Ephemeris.sunApparentLongitudeDeg(now),
                    Ephemeris.sunApparentLongitudeDeg(later),
                )
                add(
                    "Moon",
                    Ephemeris.eclipticLongitudeOf(Ephemeris.moonEquatorial(now), now),
                    Ephemeris.eclipticLongitudeOf(Ephemeris.moonEquatorial(later), later),
                )
                // The observer's position changes nothing about a geocentric longitude, but
                // PlanetCalc needs one to compute at all, so it gets the real one.
                val soon = PlanetCalc.planetsNow(lat, lon, later).associateBy { it.name }
                for (p in PlanetCalc.planetsNow(lat, lon, now)) {
                    val thenP = soon[p.name] ?: continue
                    add(
                        p.name,
                        Ephemeris.eclipticLongitudeOf(
                            Ephemeris.Equatorial(p.rightAscensionDeg, p.declinationDeg, 1.0), now,
                        ),
                        Ephemeris.eclipticLongitudeOf(
                            Ephemeris.Equatorial(thenP.rightAscensionDeg, thenP.declinationDeg, 1.0),
                            later,
                        ),
                    )
                }

                Zodiac(
                    bodies = bodies,
                    aspects = Astrology.aspects(longitudes),
                    ascendantDeg = Astrology.ascendantDeg(now, lat, lon),
                    midheavenDeg = Astrology.midheavenDeg(now, lon),
                    ayanamsaDeg = Astrology.ayanamsaDeg(now),
                )
            }.getOrNull()
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

    /**
     * ⚠️ `internal` rather than `private` so `OccultationTargetsTest` can read [OCCULTABLE_STARS]
     * and check every name against the real bundled catalogue. A star name that stops resolving
     * produces no error and no crash — just a tab that quietly never mentions that star again — so
     * the list and the asset have to be checkable together.
     */
    internal companion object {

        /**
         * How many comets the tab lists.
         *
         * ⚠️ Not a performance limit — solving all 957 costs a few milliseconds. It is an editorial
         * one: [Comets.visible] already refuses anything too faint to see or too close to the Sun
         * to look at, and beyond a dozen the list stops being a thing somebody reads and becomes a
         * thing they scroll past.
         */
        internal const val COMET_LIMIT = 12

        /** Below this a pass is behind buildings and trees for most people. */
        const val MIN_ELEVATION_DEG = 15.0
        const val MAX_PASSES_PER_SATELLITE = 8
        const val MAX_PASSES_SHOWN = 60

        /** Two years of eclipses — see [computeEclipses] for why that number and not three. */
        const val ECLIPSE_HORIZON_MS = 2L * 365L * 86_400_000L

        /**
         * ⚠️ **These moved into [Occultations] and these five names are aliases, not copies.**
         *
         * The companion runs the same search over the same bundled catalogue, so a second statement
         * of five star names, two measured uncertainties and a window would be free to drift — and
         * the uncertainty pair is the worst of them to lose, since it is what decides whether an
         * occultation is called or refused. The reasoning for each is on the core's own declarations.
         *
         * They stay as names here because `StarCatalogTargetsTest` and this file's own call sites
         * refer to them, and an alias costs nothing.
         */
        const val OCCULTATION_HORIZON_MS = Occultations.HORIZON_MS
        val OCCULTABLE_STARS = Occultations.OCCULTABLE_STARS
        const val STAR_UNCERTAINTY_DEG = Occultations.STAR_UNCERTAINTY_DEG
        const val PLANET_UNCERTAINTY_DEG = Occultations.PLANET_UNCERTAINTY_DEG
    }
}
