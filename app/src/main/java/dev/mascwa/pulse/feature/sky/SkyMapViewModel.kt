package dev.mascwa.pulse.feature.sky

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.Ephemeris
import dev.mascwa.pulse.core.telemetry.SkyProjection
import dev.mascwa.pulse.core.telemetry.StarNames
import dev.mascwa.pulse.data.orbital.PlanetCalc
import dev.mascwa.pulse.data.sky.DeepStarCatalog
import dev.mascwa.pulse.data.sky.StarCatalog
import dev.mascwa.pulse.data.weather.LocationProvider
import dev.mascwa.pulse.sky.StarField
import dev.mascwa.pulse.sky.StarLayer
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
    private val deepCatalog: DeepStarCatalog,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    /**
     * The bright catalogue, held in EQUATORIAL coordinates and filled exactly once.
     *
     * ⚠️ **This is what stops the clock costing anything.** Held in horizon coordinates every star's
     * position changes continuously as the Earth turns, so scrubbing the time by an hour meant
     * converting all 8,404 again — which is what [rebuild] used to do, and what it no longer does.
     * An equatorial position does not move at all, and the whole of the rotation lives in the two
     * vectors [dev.mascwa.pulse.sky.SkyFrame] rebuilds per frame.
     */
    val brightStars = StarLayer(BRIGHT_CAPACITY)

    /**
     * The same stars in catalogue order, so a tap can name what it hit.
     *
     * ⚠️ Index-for-index with [brightStars] — filled in one pass, so slot `i` in the layer is entry
     * `i` here. The layer carries numbers and the renderer wants nothing else; a name is a question
     * only a tap asks.
     */
    private var brightRows: List<StarCatalog.Star> = emptyList()

    /**
     * The catalogued name of a bright star, or null if it has none.
     *
     * ⚠️ The renderer draws labels, so it needs this — but a name is the ONE thing the layer
     * deliberately does not hold, because carrying eight thousand strings through a loop that runs
     * every frame is exactly the cost the layer exists to avoid. Asked per label instead, of which
     * there are at most about seventeen on screen ([dev.mascwa.pulse.core.telemetry.StarGlyph
     * .LABEL_HEADROOM]).
     */
    fun brightLabel(i: Int): String? = brightRows.getOrNull(i)?.shortName

    /** The deep catalogue's stars for the region in view, or null until the asset opens. */
    var deepField: StarField? = null
        private set

    /**
     * Bumped whenever the drawn contents change under the canvas.
     *
     * ⚠️ [StarField] is mutable arrays rather than immutable state, deliberately — the whole point
     * is not to allocate per frame. So nothing tells Compose that a reload happened, and without
     * this the new stars would appear only on the next pan. A counter is the cheapest thing that
     * does: one Int, read in the draw pass.
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Why the deep catalogue is unavailable or degraded, in words. Null when all is well. */
    private val _deepNote = MutableStateFlow<String?>(null)
    val deepNote: StateFlow<String?> = _deepNote.asStateFlow()

    /**
     * How faint the map may draw — the depth of the deepest catalogue actually open.
     *
     * ⚠️ **The renderer has to be told this, and not telling it is a defect this shipped with.**
     * [SkyProjection.magnitudeLimit]'s `deepest` defaults to [SkyProjection.NAKED_EYE_LIMIT], so a
     * call site that omits it silently refuses to draw anything fainter than 6.5 — however deep the
     * file is. Measured over the real 3,087,821-star catalogue at a fifteen-degree field: 31,529
     * stars loaded, **123 drawn**. The loader had passed the real depth since it was written; only
     * the drawing side had not, which is why every test was green.
     *
     * ⚠️ It starts at [SkyProjection.NAKED_EYE_LIMIT] and that is not a placeholder — it is the
     * honest depth of the bright catalogue, which is what draws before the deep one opens and all
     * that draws if it never does.
     */
    private val _deepest = MutableStateFlow(SkyProjection.NAKED_EYE_LIMIT)
    val deepestMagnitude: StateFlow<Double> = _deepest.asStateFlow()

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
                fillBrightStars()
                openDeepCatalogue()
                rebuild()
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Convert the bright catalogue into drawable vectors, once for the life of the screen.
     *
     * ⚠️ Once, not per rebuild — that is the change. These positions are equatorial and equatorial
     * positions do not move, so neither the clock nor the scrubber invalidates them.
     */
    private suspend fun fillBrightStars() {
        if (brightStars.count > 0) return
        val rows = catalog.all()
        _catalogueMissing.value = rows.isEmpty()
        if (rows.isEmpty()) return
        withContext(Dispatchers.Default) {
            brightStars.clear()
            brightStars.ensure(rows.size)
            rows.forEach { s ->
                brightStars.add(
                    s.rightAscensionDeg, s.declinationDeg, s.magnitude.toFloat(),
                    // ⚠️ B−V, because that is what THIS catalogue measured. The deep one measured
                    // Gaia's bp_rp, and the two scales do not share a zero point — see
                    // StarNames.colourArgbFromBpRp. Each source keeps its own measurement.
                    StarNames.bandFromBv(s.colourIndex),
                )
            }
        }
        brightRows = rows
    }

    private suspend fun openDeepCatalogue() {
        if (deepField != null) return
        val opened = deepCatalog.opened()
        if (opened != null) {
            val field = StarField(opened.reader)
            deepField = field
            // ⚠️ The one place the renderer learns it may go deeper than the naked eye. Raised
            // rather than assigned: a catalogue SHALLOWER than the bright one must not make the map
            // draw less than it already could.
            _deepest.value = maxOf(_deepest.value, field.deepestMagnitude)
        }
        _deepNote.value = deepCatalog.note()
    }

    /**
     * Load whatever the deep catalogue holds for the region now in view.
     *
     * ⚠️ Called from the screen on every view change, and cheap to call: [StarField] answers
     * UNCHANGED without touching the file whenever the region it already holds still covers the
     * view, which is most pans. Only a genuine reload bumps [revision].
     *
     * @param at the instant the screen is drawing for, passed in rather than read here so the
     *   region loaded and the sky drawn are the same moment. They would differ by seconds at worst
     *   and the loaded region is deliberately generous, but two clocks for one frame is the kind of
     *   thing that is only ever wrong somewhere inconvenient.
     */
    suspend fun refreshDeep(viewport: SkyProjection.Viewport, at: Long) {
        val field = deepField ?: return
        val here = _site.value ?: return
        val outcome = runCatching {
            field.update(_view.value, viewport, here.latitude, here.longitude, at)
        }.getOrNull()
        if (outcome == StarField.Outcome.RELOADED) _revision.value++
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

        // The Sun, the Moon and the planets: eight things, still in horizon coordinates.
        var best = _bodies.value
            .asSequence()
            .filter { SkyProjection.separationDeg(az, alt, it.azimuthDeg, it.altitudeDeg) <= toleranceDeg }
            .minByOrNull { it.magnitude }

        val here = _site.value
        if (here != null) {
            // ⚠️ **The stars are hit-tested in EQUATORIAL space**, which converts the ONE point the
            // finger touched rather than the eight thousand it might have touched. The old version
            // searched a list that had been converted to the horizon on every rebuild; that list no
            // longer holds stars, and this is both cheaper and the reason it can go.
            val at = System.currentTimeMillis() + _hourOffset.value * 3_600_000L
            val eq = Ephemeris.toEquatorial(
                Ephemeris.Horizontal(alt, az, 0.0), here.latitude, here.longitude, at,
            )
            val t = SkyProjection.equatorialVector(eq.rightAscensionDeg, eq.declinationDeg)
            // ⚠️ A dot product against a precomputed cosine, NOT an angle. The file's own warning
            // about `acos` near 1.0 is about RECOVERING an angle, where the significant figures are
            // lost; comparing against a threshold has no such problem — at the narrowest field the
            // error works out at a fraction of a microarcsecond.
            val cosTolerance = kotlin.math.cos(Math.toRadians(toleranceDeg))
            val star = nearestStar(brightStars, t, cosTolerance)
            val deep = deepField?.layer?.let { nearestStar(it, t, cosTolerance) }

            val starBody = star?.let { i -> namedStar(i, here, at) }
            val deepBody = deep?.let { i ->
                val layer = deepField!!.layer
                val h = horizonOf(layer, i, here, at)
                Body(
                    h.azimuthDeg, h.altitudeDeg, layer.magnitude[i].toDouble(),
                    label = "Unnamed star", kind = Kind.STAR,
                    detail = "No catalogued name · magnitude " +
                        "%.2f".format(java.util.Locale.US, layer.magnitude[i]),
                )
            }
            // Brighter wins, as it always has: two things within a fingertip are both "what you
            // meant", and the one you can actually see is the answer.
            best = listOfNotNull(best, starBody, deepBody).minByOrNull { it.magnitude }
        }
        _selected.value = best
    }

    /** The brightest star in the layer within the tolerance, or null. */
    private fun nearestStar(layer: StarLayer, target: DoubleArray, cosTolerance: Double): Int? {
        var best = -1
        var bestMagnitude = Float.MAX_VALUE
        for (i in 0 until layer.count) {
            val dot = target[0] * layer.vx[i] + target[1] * layer.vy[i] + target[2] * layer.vz[i]
            if (dot < cosTolerance) continue
            if (layer.magnitude[i] < bestMagnitude) {
                bestMagnitude = layer.magnitude[i]
                best = i
            }
        }
        return if (best >= 0) best else null
    }

    private fun horizonOf(layer: StarLayer, i: Int, here: Site, at: Long): Ephemeris.Horizontal {
        val dec = Math.toDegrees(kotlin.math.asin(layer.vz[i].coerceIn(-1.0, 1.0)))
        val ra = Math.toDegrees(kotlin.math.atan2(layer.vy[i], layer.vx[i]))
        return Ephemeris.toHorizontal(
            Ephemeris.Equatorial(ra, dec, 0.0), here.latitude, here.longitude, at,
        )
    }

    private fun namedStar(i: Int, here: Site, at: Long): Body? {
        val row = brightRows.getOrNull(i) ?: return null
        val h = horizonOf(brightStars, i, here, at)
        return Body(
            azimuthDeg = h.azimuthDeg,
            altitudeDeg = h.altitudeDeg,
            magnitude = row.magnitude,
            label = row.shortName,
            kind = Kind.STAR,
            colourIndex = row.colourIndex,
            detail = starDetail(row),
        )
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

        _bodies.value = withContext(Dispatchers.Default) {
            val out = ArrayList<Body>(8)
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
        /**
         * Sized for the bright catalogue exactly, so filling it never reallocates.
         *
         * ⚠️ [StarLayer.ensure] throws the old arrays away rather than copying, which is right for
         * the deep field — it refills from scratch every load — and would be wasteful here, where
         * the fill happens once. A capacity that fits the file avoids the question entirely.
         */
        const val BRIGHT_CAPACITY = 8_704

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
