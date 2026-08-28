package dev.mascwa.pulse.feature.sky

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.Ephemeris
import dev.mascwa.pulse.core.telemetry.SkyProjection
import dev.mascwa.pulse.core.telemetry.StarNames
import dev.mascwa.pulse.data.orbital.PlanetCalc
import dev.mascwa.pulse.data.sky.StarCatalog
import dev.mascwa.pulse.data.weather.LocationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The state behind the full-sky map: where you are, when it is, and what is above you.
 *
 * ## The one performance decision that matters
 *
 * ⚠️ **Equatorial coordinates are converted to the horizon ONCE per change of time or place, never
 * per frame.** 8,404 stars is a trivial amount of arithmetic done once and a ruinous amount done
 * sixty times a second while somebody drags. Panning and zooming touch only [SkyProjection], which
 * is a handful of multiplications per visible star and runs in the draw pass. So the expensive step
 * is tied to the thing that rarely changes and the cheap one to the thing that changes constantly.
 *
 * ## Time
 *
 * ⚠️ **A scrubbed time is an OFFSET from now, not a frozen instant.** Freezing would mean the map
 * stops while you look at it and the "now" button restores a moment that has since passed; an offset
 * of zero is live, and every other offset tracks alongside real time. It is also the honest model
 * for the question people actually ask — "what will this look like in four hours?"
 */
class SkyMapViewModel(
    private val catalog: StarCatalog,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    /** A star or a solar-system body, already in horizon coordinates for the current instant. */
    data class Body(
        val azimuthDeg: Double,
        val altitudeDeg: Double,
        val magnitude: Double,
        val label: String?,
        val kind: Kind,
        /** B-V colour index for a star; null for everything else. */
        val colourIndex: Double? = null,
        /** Only for the identify card — the full name, the constellation, the rest. */
        val detail: String = "",
    )

    enum class Kind { STAR, SUN, MOON, PLANET }

    data class Site(val latitude: Double, val longitude: Double)

    /** What the map is pointed at. Owned here so it survives a rotation. */
    private val _view = MutableStateFlow(
        SkyProjection.View(azimuthDeg = 180.0, altitudeDeg = 35.0, fovDeg = 80.0),
    )
    val view: StateFlow<SkyProjection.View> = _view.asStateFlow()

    private val _site = MutableStateFlow<Site?>(null)
    val site: StateFlow<Site?> = _site.asStateFlow()

    private val _bodies = MutableStateFlow<List<Body>>(emptyList())
    val bodies: StateFlow<List<Body>> = _bodies.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Hours from now. Zero is live. */
    private val _hourOffset = MutableStateFlow(0)
    val hourOffset: StateFlow<Int> = _hourOffset.asStateFlow()

    private val _selected = MutableStateFlow<Body?>(null)
    val selected: StateFlow<Body?> = _selected.asStateFlow()

    /** True when the catalogue came back empty, which means the bundled asset could not be read. */
    private val _catalogueMissing = MutableStateFlow(false)
    val catalogueMissing: StateFlow<Boolean> = _catalogueMissing.asStateFlow()

    init { load() }

    fun refresh() = load()

    private fun load() {
        viewModelScope.launch {
            _loading.value = true
            try {
                val here = resolveSite()
                if (here != null) _site.value = here
                rebuild()
            } finally {
                _loading.value = false
            }
        }
    }

    private suspend fun resolveSite(): Site? {
        if (!locationProvider.hasPermission()) return null
        return runCatching { locationProvider.current() }.getOrNull()
            ?.let { Site(it.latitude, it.longitude) }
    }

    fun pan(dAzimuthDeg: Double, dAltitudeDeg: Double) {
        _view.value = SkyProjection.pan(_view.value, dAzimuthDeg, dAltitudeDeg)
    }

    fun zoom(factor: Double) {
        _view.value = SkyProjection.zoom(_view.value, factor)
    }

    /** Point the map at a compass direction, keeping the current tilt and field. */
    fun lookAt(azimuthDeg: Double, altitudeDeg: Double = _view.value.altitudeDeg) {
        _view.value = _view.value.copy(azimuthDeg = azimuthDeg, altitudeDeg = altitudeDeg)
    }

    fun setHourOffset(hours: Int) {
        if (hours == _hourOffset.value) return
        _hourOffset.value = hours.coerceIn(-MAX_HOURS, MAX_HOURS)
        viewModelScope.launch { rebuild() }
    }

    fun clearSelection() { _selected.value = null }

    /**
     * Answer a tap.
     *
     * ⚠️ **Hit-tested in ANGLE, not in screen distance.** A fingertip covers a fixed number of
     * screen units whatever the zoom, so a fixed screen tolerance would grab a whole constellation
     * at a wide field and miss the star you touched at a narrow one. [SkyProjection.degreesPerUnit]
     * converts the one into the other, and the search is over the same separation function the map
     * draws with.
     *
     * ⚠️ Brighter wins a tie rather than nearer. Two stars within a finger's width of each other are
     * both "what you meant"; the one you can actually see is the answer.
     */
    fun identify(screenX: Double, screenY: Double) {
        val v = _view.value
        val (az, alt) = SkyProjection.unproject(screenX, screenY, v)
        val toleranceDeg = SkyProjection.degreesPerUnit(v) * TAP_RADIUS_FRACTION
        val hit = _bodies.value
            .asSequence()
            .filter { SkyProjection.separationDeg(az, alt, it.azimuthDeg, it.altitudeDeg) <= toleranceDeg }
            .minByOrNull { it.magnitude }
        _selected.value = hit
    }

    /**
     * Recompute every position for the current instant and place.
     *
     * ⚠️ Runs off the main thread, and everything it touches is pure arithmetic over a bundled file,
     * so it works with the radio off and cannot fail for want of a network. Without a location it
     * produces nothing at all rather than picking a plausible one — a sky drawn for somewhere you
     * are not is worse than an empty screen that says why.
     */
    private suspend fun rebuild() {
        val here = _site.value ?: return
        val at = System.currentTimeMillis() + _hourOffset.value * 3_600_000L
        val stars = catalog.all()
        _catalogueMissing.value = stars.isEmpty()

        _bodies.value = withContext(Dispatchers.Default) {
            val out = ArrayList<Body>(stars.size + 8)
            stars.forEach { s ->
                val h = Ephemeris.toHorizontal(
                    Ephemeris.Equatorial(s.rightAscensionDeg, s.declinationDeg, 0.0),
                    here.latitude, here.longitude, at,
                )
                out += Body(
                    azimuthDeg = h.azimuthDeg,
                    altitudeDeg = h.altitudeDeg,
                    magnitude = s.magnitude,
                    label = s.shortName,
                    kind = Kind.STAR,
                    colourIndex = s.colourIndex,
                    detail = starDetail(s),
                )
            }

            val sun = Ephemeris.sunPosition(here.latitude, here.longitude, at)
            out += Body(sun.azimuthDeg, sun.altitudeDeg, -26.7, "Sun", Kind.SUN, detail = "The Sun")

            val moon = Ephemeris.moonPosition(here.latitude, here.longitude, at)
            val phase = Ephemeris.moonPhase(at)
            out += Body(
                moon.azimuthDeg, moon.altitudeDeg, -12.0, "Moon", Kind.MOON,
                detail = "The Moon · ${phase.name} · ${(phase.illuminatedFraction * 100).roundToInt()}% lit",
            )

            runCatching { PlanetCalc.planetsNow(here.latitude, here.longitude, at) }
                .getOrDefault(emptyList())
                .forEach { p ->
                    out += Body(
                        p.azimuthDeg, p.altitudeDeg, p.magnitude, p.name, Kind.PLANET,
                        detail = "${p.name} · magnitude ${"%.1f".format(java.util.Locale.US, p.magnitude)}",
                    )
                }
            out
        }
    }

    private fun starDetail(s: StarCatalog.Star): String {
        val name = s.name ?: "Unnamed star"
        val where = if (s.constellation.isBlank()) "" else " · in ${StarNames.constellation(s.constellation)}"
        val mag = " · magnitude ${"%.2f".format(java.util.Locale.US, s.magnitude)}"
        return name + where + mag
    }

    private companion object {
        /** A day either way. Further and the scrubber stops being a scrubber. */
        const val MAX_HOURS = 24

        /**
         * A tap grabs anything within this fraction of the half-field.
         *
         * At an 80° field that is about 2.4° — roughly a fingertip, and a little under the distance
         * between the two stars at the end of the Plough's handle.
         */
        const val TAP_RADIUS_FRACTION = 0.06
    }
}
