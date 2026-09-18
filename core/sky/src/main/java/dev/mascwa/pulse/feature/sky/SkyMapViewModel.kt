package dev.mascwa.pulse.feature.sky

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.Ephemeris
import dev.mascwa.pulse.core.telemetry.PlanetDisc
import dev.mascwa.pulse.core.telemetry.ProperMotion
import dev.mascwa.pulse.core.telemetry.MilkyWay
import dev.mascwa.pulse.core.telemetry.SkyBudget
import dev.mascwa.pulse.core.telemetry.SkyPointing
import dev.mascwa.pulse.core.telemetry.SkyProjection
import dev.mascwa.pulse.core.telemetry.StarNames
import dev.mascwa.pulse.data.orbital.PlanetCalc
import dev.mascwa.pulse.data.sky.ConstellationCatalog
import dev.mascwa.pulse.data.sky.DeepSkyCatalog
import dev.mascwa.pulse.data.sky.MilkyWayCatalog
import dev.mascwa.pulse.data.sky.DeepStarCatalog
import dev.mascwa.pulse.data.sky.StarCatalog
import dev.mascwa.pulse.sky.SkyDeps
import dev.mascwa.pulse.sky.SkyPreferences
import dev.mascwa.pulse.sky.SkySite
import dev.mascwa.pulse.sky.ConstellationField
import dev.mascwa.pulse.sky.DeepSkyLayer
import dev.mascwa.pulse.sky.SkyFrame
import dev.mascwa.pulse.sky.StarField
import dev.mascwa.pulse.sky.StarLayer
import dev.mascwa.pulse.sky.SkyLines
import dev.mascwa.pulse.sky.ReferenceLines
import dev.mascwa.pulse.core.telemetry.ReferenceCircles
import dev.mascwa.pulse.sky.stepAlong
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
    private val constellationCatalog: ConstellationCatalog,
    private val deepSkyCatalog: DeepSkyCatalog,
    private val milkyWayCatalog: MilkyWayCatalog,
    /**
     * This handset's own sense of place and aim — the only two things here an application has to
     * supply, because they come from platform services rather than from a bundled catalogue.
     *
     * ⚠️ **An interface so BOTH applications drive one view model.** The alternative was a second
     * copy of eight hundred lines of state management in the standalone sky app, which is the
     * duplicated-definition drift this repository has corrected repeatedly. See [SkyDeps] for why
     * the two services behind it stay where they are rather than moving in here.
     */
    private val device: SkyDeps,
    /**
     * Whether the map opens following, remembered between launches.
     *
     * ⚠️ **Read HERE rather than by each application, so the rule that governs it exists once.**
     * "Read the stored answer, apply it at most once, write every change but never a teardown" is
     * four sentences of ordering that both applications would otherwise have to get right
     * separately — and the two of them reach this screen by completely different routes, one as its
     * whole reason for existing and one as a route among forty. See [SkyPreferences] for why it is
     * an interface rather than a defaulted lambda.
     */
    private val preferences: SkyPreferences,
    /**
     * What this handset can afford — see [SkyBudget].
     *
     * ⚠️ **Required rather than defaulted to full strength.** A default would compile everywhere,
     * read as wired, and quietly leave every weak phone on the flagship settings — the "default that
     * means do not do the thing" this repository has now shipped twice. Making it required means
     * forgetting is a compile error.
     *
     * ⚠️ **Passed in rather than probed here**, because measuring a handset is a platform act and
     * this module has no `Context`. Each application reads its own `DeviceProbeReader` and hands the
     * answer over, the same shape as [device] and [preferences].
     *
     * ⚠️ Fixed for the life of the view model, which is deliberate: the sensor rate is chosen when
     * following starts, and re-registering the listener because the phone warmed up for a minute
     * would be a visible jerk bought with very little. The pressure levers that DO want to move
     * live in `DeviceClass.Budget` and are read where they belong.
     */
    val budget: SkyBudget.Budget,
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
     * The constellations, cut for the field currently in view, or null until the asset is read.
     *
     * Mutable arrays like [deepField] and for the same reason, so a rebuild is announced through
     * [revision] rather than by handing Compose a new object.
     */
    var constellations: ConstellationField? = null
        private set

    /**
     * The galaxies, clusters and nebulae, or null until the asset is read.
     *
     * ⚠️ **Unlike [constellations] this is never rebuilt**, so it needs no [revision] bump of its
     * own beyond the one [openDeepSky] does on first load. A line has to be subdivided and how finely
     * depends on the field; a galaxy is a position and two axes, and nothing about it changes when
     * the view moves.
     */
    var deepSky: DeepSkyLayer? = null
        private set

    /**
     * The star-density raster the Milky Way is drawn from, or null until the asset is read.
     *
     * ⚠️ **Not a layer, and that is the point** — unlike [deepSky] there is nothing to precompute.
     * The raster is already indexed the way the draw pass reads it, so what the catalogue hands
     * over is the sixty-five kilobytes and the density a stored 255 stands for.
     */
    var milkyWay: MilkyWay.Raster? = null
        private set

    /**
     * The celestial equator and the ecliptic, built once and never rebuilt.
     *
     * ⚠️ **Unlike [constellations] these depend on no asset and on no location**, so they are ready
     * before anything is loaded and survive a catalogue that fails to open. They are also fixed in
     * the equatorial frame, so scrubbing the clock moves them exactly as it moves the stars — which
     * is to say, not at all in this frame, and entirely in the drawn one.
     *
     * ⚠️ The obliquity AND the epoch these are the circles of are read ONCE, at construction, and
     * that is deliberate rather than lazy. The obliquity drifts about 0.013 degrees a century;
     * precession, which carries them into the catalogue's frame, moves them about half an arcminute
     * a year — so across the day this map's time control offers the change is a seventh of an
     * arcsecond, smaller than any pixel on any screen. Rebuilding per scrub would be arithmetic
     * spent to move nothing.
     */
    val equatorLine = SkyLines(ReferenceCircles.ARCS * ReferenceCircles.PER_ARC, ReferenceCircles.ARCS)
    val eclipticLine = SkyLines(ReferenceCircles.ARCS * ReferenceCircles.PER_ARC, ReferenceCircles.ARCS)

    init {
        val built = System.currentTimeMillis()
        ReferenceLines.fill(equatorLine, null, built)
        ReferenceLines.fill(eclipticLine, Ephemeris.trueObliquityDeg(built), built)
    }

    /**
     * What the map draws over the stars.
     *
     * ⚠️ The celestial equator and the ecliptic ride this control too, even though they come from
     * arithmetic rather than from the constellation asset. The control is labelled **NO LINES**, and
     * a mode called that which leaves two lines right across the sky would be lying about itself.
     */
    enum class LinesMode {
        /** Stars alone — no figures, no borders, and neither reference circle. */
        NONE,

        /** The equator, the ecliptic, the 88 stick figures, and the popular asterisms behind them. */
        FIGURES,

        /** The figures, and the IAU borders that divide the whole sky between them. */
        FIGURES_AND_BORDERS,
        ;

        /** The next mode a tap on the one control moves to. */
        val next: LinesMode get() = entries[(ordinal + 1) % entries.size]
    }

    private val _linesMode = MutableStateFlow(LinesMode.FIGURES)
    val linesMode: StateFlow<LinesMode> = _linesMode.asStateFlow()

    /** Move to the next thing to draw over the stars. */
    fun cycleLines() {
        _linesMode.value = _linesMode.value.next
    }

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
        /**
         * How to draw this body as more than a dot. Null for stars, which have no resolvable size —
         * the nearest is under a fiftieth of a milliarcsecond across, so a star is a point at any
         * magnification this or any other telescope will ever reach.
         */
        val look: PlanetDisc.Appearance? = null,
    )

    enum class Kind { STAR, SUN, MOON, PLANET }

    /** What the map is pointed at. Owned here so it survives a rotation. */
    private val _view = MutableStateFlow(
        SkyProjection.View(azimuthDeg = 180.0, altitudeDeg = 35.0, fovDeg = 80.0),
    )
    val view: StateFlow<SkyProjection.View> = _view.asStateFlow()

    private val _site = MutableStateFlow<SkySite?>(null)
    val site: StateFlow<SkySite?> = _site.asStateFlow()

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

    init {
        load()
        // ⚠️ **In `init` rather than in a call the screen has to remember, and that is what makes
        // "at most once" free.** A composable would re-run its effect on every return to the screen
        // and on every rotation, so applying the default there needs a guard that survives both —
        // which is a flag on this object, which is this object doing it. Done here there is nothing
        // to guard: a view model is constructed once and outlives the composition either way.
        //
        // ⚠️ [applyPointing] rather than [setPointing], so the stored answer is not written straight
        // back. The two differ only in that, and the difference matters: on the LCARS side a write
        // is a read-modify-write of a settings record with well over a hundred fields, through a
        // Keystore cipher — paid on every open of the screen, to store a value that was just read
        // out of it. The refusal and the sensor registration are shared, which is the half that has
        // to be common.
        viewModelScope.launch {
            if (runCatching { preferences.followByDefault() }.getOrDefault(true)) applyPointing(true)
        }
    }

    fun refresh() = load()

    private fun load() {
        viewModelScope.launch {
            _loading.value = true
            try {
                val here = resolveSite()
                if (here != null) {
                    _site.value = here
                    // ⚠️ **Told here as well as in [startPointing], and the reason is an ordering
                    // that only appeared once the map began opening in pointing mode.** That method
                    // reads `_site` and finds it null when following starts before this load has
                    // resolved a position — which is now the ordinary case, because both run from
                    // `init`. Without this the compass would keep reporting MAGNETIC north for the
                    // life of the screen: as much as twenty degrees out in parts of the world, on a
                    // map whose whole claim is that it points at the real sky. It also covers a
                    // case that was never handled at all — a fix arriving, or moving far enough for
                    // a new one, while the map is already following.
                    //
                    // Harmless when not following: it stores a number, it starts nothing.
                    device.declinationAt(here.latitude, here.longitude, 0.0)
                }
                fillBrightStars()
                openDeepCatalogue()
                openConstellations()
                openDeepSky()
                openMilkyWay()
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
        // ⚠️ **Carried forward from J2000 by each star's own motion, and the deep catalogue is
        // carried from J2016 by `StarField`.** Both or neither: 12,602 Gaia records fall inside
        // this catalogue's magnitude limit, so they are the same stars drawn twice, and moving one
        // copy alone would put 1,339 of those pairs more than four pixels apart at the narrowest
        // field — the worst by 226. Measured over the real bundle, not estimated.
        val years = ProperMotion.yearsSince(StarCatalog.EPOCH_YEAR, System.currentTimeMillis())
        withContext(Dispatchers.Default) {
            brightStars.clear()
            brightStars.ensure(rows.size)
            val moved = DoubleArray(2)
            rows.forEach { s ->
                ProperMotion.carry(
                    s.rightAscensionDeg, s.declinationDeg,
                    s.pmRaMasPerYear, s.pmDecMasPerYear, years, moved,
                )
                brightStars.add(
                    moved[0], moved[1], s.magnitude.toFloat(),
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
     * Read the constellation asset, once for the life of the screen.
     *
     * ⚠️ No first cut here. The subdivision depends on the field of view and the view is not
     * settled until the canvas has been measured, so cutting now would build one set at whatever
     * field the screen happened to start at and immediately throw it away — see [refreshLines].
     */
    private suspend fun openConstellations() {
        if (constellations != null) return
        constellations = constellationCatalog.data()?.let { ConstellationField(it) }
    }

    /**
     * Read the deep-sky asset, once for the life of the screen.
     *
     * ⚠️ Bumps [revision] on success, which [openConstellations] does not need to: that one is
     * followed by a cut in `refreshLines` which bumps it, and this has nothing to follow it. Without
     * the bump twelve thousand objects would appear only on the next pan.
     */
    private suspend fun openDeepSky() {
        if (deepSky != null) return
        deepSky = deepSkyCatalog.layer()
        if (deepSky != null) _revision.value++
    }

    /**
     * Read the Milky Way raster, once for the life of the screen.
     *
     * ⚠️ Bumps [revision] for the same reason [openDeepSky] does — nothing follows it that would.
     */
    private suspend fun openMilkyWay() {
        if (milkyWay != null) return
        milkyWay = milkyWayCatalog.raster()
        if (milkyWay != null) _revision.value++
    }

    /**
     * Re-cut the constellations for the field now in view.
     *
     * ⚠️ Called from the screen on every view change, and cheap to call: [ConstellationField]
     * answers UNCHANGED unless the field has moved far enough to want a different subdivision,
     * which a pan never does and a pinch does a handful of times.
     */
    suspend fun refreshLines() {
        if (_linesMode.value == LinesMode.NONE) return
        val field = constellations ?: return
        val outcome = runCatching { field.update(_view.value.fovDeg) }.getOrNull()
        if (outcome == ConstellationField.Outcome.REBUILT) _revision.value++
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
            field.update(
                _view.value, viewport, here.latitude, here.longitude, at,
                // ⚠️ While the handset is aiming, the view's altitude is CLAMPED half a degree short
                // of the zenith and its centre is therefore wrong by up to that much when somebody
                // points straight up. At the quarter-degree field floor that is twice the whole
                // view, and the cone loaded would not contain what is drawn.
                centreOverride = if (_pointing.value) {
                    SkyFrame.centreOfPointing(pointForward, here.latitude, here.longitude, at)
                } else {
                    null
                },
            )
        }.getOrNull()
        if (outcome == StarField.Outcome.RELOADED) _revision.value++
    }

    private suspend fun resolveSite(): SkySite? = device.site()

    // ------------------------------------------------------- following the handset

    /**
     * Whether the map is following where the phone is aimed.
     *
     * ⚠️ **False at construction even though the map now OPENS following, and that is not the
     * default being ignored.** This flow says whether the sensor is actually being read, and at the
     * moment a view model is built nothing has registered a listener yet. Seeding it true would put
     * the chip in its FOLLOWING state over a sensor that is not running — a claim about the world
     * rather than a record of it, which is the same mistake [dev.mascwa.pulse.sky.SkyAttitude]
     * exists to make unrepresentable. The stored default is applied a moment later by [init], which
     * goes through [setPointing] and therefore starts the hardware.
     */
    private val _pointing = MutableStateFlow(false)
    val pointing: StateFlow<Boolean> = _pointing.asStateFlow()

    /**
     * Whether this handset can follow where it is pointed at all.
     *
     * Read from the hardware rather than inferred from a control that does nothing when pressed:
     * "there is no such sensor" and "the sensor has not reported yet" are different facts and only
     * one of them is permanent. Both screens read it from here so neither can answer differently.
     */
    val hasAttitudeSensor: Boolean get() = device.hasAttitudeSensor

    /** True when the magnetometer says it needs a figure-of-eight before it can be believed. */
    private val _needsCalibration = MutableStateFlow(false)
    val needsCalibration: StateFlow<Boolean> = _needsCalibration.asStateFlow()

    /** The standing hand-set correction to the handset's azimuth, in degrees. */
    private val _trimDeg = MutableStateFlow(0.0)
    val trimDeg: StateFlow<Double> = _trimDeg.asStateFlow()

    /**
     * Where the camera looks and which way is up the screen, east/north/up.
     *
     * ⚠️ **Mutable arrays read in the draw pass, exactly like [brightStars]**, and for the same
     * reason: the sensor fires many times a second and allocating a pair of vectors each time is
     * work spent to produce garbage. The screen reads them only while [pointing] is true, and the
     * pair is always a valid attitude — `SkyPointing.smooth` re-orthonormalises or keeps the newest
     * reading, so there is no state in which these two describe nothing.
     */
    val pointForward = doubleArrayOf(0.0, 1.0, 0.0)
    val pointUp = doubleArrayOf(0.0, 0.0, 1.0)

    /**
     * The screen's own frame in HORIZON coordinates while the handset is aiming, or null when it is
     * not — in which case the caller wants `SkyProjection.basisOf(view)` instead.
     *
     * ⚠️ **This exists so everything held in HORIZON coordinates reads the frame the STARS are drawn
     * in** — the horizon line, the four compass letters, the eight solar-system bodies, and a tap.
     * All four went through [view], while the stars come from `SkyFrame.ofPointing`, built from
     * these two vectors — so aimed near the zenith they were in a different frame from everything
     * they are drawn over, and a tap resolved somewhere the finger had not been. The planets are the
     * sharpest of the four: they are what somebody aiming the handset straight up is most likely to
     * be pointing at.
     *
     * ⚠️ **The ALTITUDE CLAMP is the whole of the difference, and the roll is not part of it.** My
     * first note here said the view "carries a roll sign this pair does not need", implying it
     * contributed; measured over a spread of attitudes the two roll conventions agree to
     * **0.000000000 screen units**, because `equivalentView` negating it is exactly what makes the
     * angle path match the vector path — which `SkyPointingTest` has always asserted. What does
     * differ is `SkyPointing.equivalentView` coercing the altitude to
     * [SkyProjection.MAX_ALTITUDE_DEG], leaving a fixed ANGULAR error of up to 0.5°:
     *
     * ```
     * aimed 89.9° up, where does a tap in the middle of the screen resolve to?
     *   field 120°  0.400°   under 1% of the screen
     *   field  20°  0.400°   2%
     *   field   1°  0.400°   40%
     *   field 0.25° 0.400°   160%  — further than the whole screen is wide
     * ```
     *
     * So it is invisible at a wide field and total at the narrowest, which is the shape of a defect
     * nobody notices until they zoom in on something overhead.
     *
     * ⚠️ **On the view model rather than at either call site**, because two copies of one frame
     * conversion is what `SkyFrame.catalogueOf` already exists to prevent — and here one caller is a
     * draw pass and the other a tap, which is exactly the pair least likely to be changed together.
     *
     * A basis is a cross product and two normalisations, so building it per frame costs nothing
     * worth caching; the stars' own basis beside it is rebuilt the same way for the same reason.
     */
    fun pointedHorizonBasis(fovDeg: Double): SkyProjection.Basis? {
        if (!_pointing.value) return null
        // Roll zero: it is already inside the screen-up, exactly as in SkyFrame.ofPointing, and
        // passing it again would apply it twice.
        return SkyProjection.basisOf(pointForward, pointUp[0], pointUp[1], pointUp[2], fovDeg, 0.0)
    }

    private var pointingJob: kotlinx.coroutines.Job? = null

    /**
     * Start or stop following the handset, and remember which.
     *
     * ⚠️ **A handset with no rotation-vector sensor is refused rather than obliged, and this is the
     * one guard that must not move to a call site.** Honouring the request there registers a
     * listener that can never fire, leaves this flow reading true, and — because [pan] declines to
     * drag while following — hands somebody a sky that cannot be turned by pointing, dragging or
     * anything else, with a chip reading FOLLOWING to explain it. Refusing here means no caller can
     * produce that state, whatever it asks for.
     *
     * ⚠️ **Call this ONLY for something the user did.** It records the answer, so anything that
     * stops following for a reason of its own must use [applyPointing] instead — see the note there
     * for what happens when it does not. [lookAt] is a legitimate caller: choosing to look north
     * instead of wherever the phone is pointed is a genuine change of mode, not a transient.
     */
    fun setPointing(on: Boolean) {
        if (!applyPointing(on)) return
        viewModelScope.launch { runCatching { preferences.setFollowByDefault(on) } }
    }

    /**
     * Change the mode without recording it, and say whether anything actually changed.
     *
     * ⚠️ **This exists because a caller that stops following is not always a person choosing to.**
     * `ReleaseTheSensorWhenNobodyIsLooking` in [SkyChart] turns following off on ON_STOP and on
     * leaving composition, and back on when the screen returns — sound behaviour that has nothing to
     * do with what the user wants next time. Routed through [setPointing] it would write "not
     * following" to storage on every background and on every navigation away, so the remembered
     * default would be spent by the first time somebody left the screen, and a process killed while
     * backgrounded would come back in drag mode for good.
     *
     * `internal` rather than private: the observer that needs it lives in this module beside the
     * view model, and neither application has any business reaching it. The visibility IS the rule.
     *
     * Returning false for a no-op is what stops a repeated press writing the same value every tap.
     */
    internal fun applyPointing(on: Boolean): Boolean {
        if (on && !device.hasAttitudeSensor) return false
        if (on == _pointing.value) return false
        _pointing.value = on
        if (on) startPointing() else stopPointing()
        return true
    }

    /**
     * Nudge the map's idea of north by a hand-set correction.
     *
     * ⚠️ **This exists because a phone magnetometer is good to a few degrees at best.** The offset
     * is kept rather than applied once, so it survives the next sensor sample; without that it would
     * be undone within about sixteen milliseconds and read as a control that does nothing.
     */
    fun nudge(dragDeg: Double) {
        _trimDeg.value = SkyPointing.addTrim(_trimDeg.value, dragDeg)
    }

    /** Forget the correction and trust the sensor again. */
    fun clearTrim() { _trimDeg.value = 0.0 }

    private fun startPointing() {
        device.startAttitude(budget.sensorPeriodUs)
        _site.value?.let { device.declinationAt(it.latitude, it.longitude, 0.0) }
        pointingJob = viewModelScope.launch {
            var first = true
            device.attitude.collect { r ->
                // ⚠️ Null is "nothing has measured this yet", which is a different fact from a
                // reading, and keeping them apart is what makes [first] below mean what it says.
                // The sensor wrapper this used to read published a seeded value carrying "the
                // hardware exists" with every angle at zero, so `first` spent itself on a
                // non-reading and the sky swept in from due north. See SkyAttitude.
                if (r == null) return@collect
                _needsCalibration.value = r.accuracyLow
                val a = SkyPointing.trimmed(
                    SkyPointing.Attitude(
                        azimuthDeg = r.trueAzimuthDeg,
                        altitudeDeg = r.altitudeDeg,
                        rollDeg = r.rollDeg,
                    ),
                    _trimDeg.value,
                )
                // ⚠️ The FIRST reading is taken whole. Blending it against the arrays' starting
                // values would swing the map in from due north over the first half second, which
                // reads as the sensor being wrong rather than as the picture arriving.
                val weight = if (first) 1.0 else budget.pointSmoothing
                SkyPointing.smooth(
                    pointForward, pointUp, a,
                    weight,
                    pointForward, pointUp,
                    // ⚠️ The screen-up is filtered harder as the aim nears straight up or down, and
                    // the look direction is NOT — damping the aim would make a deliberate sweep
                    // across the zenith lag the hand. Overhead, which way is up the screen is
                    // decided entirely by the handset's heading, which is the least trustworthy
                    // thing the sensor reports; near the horizon the same error is a degree or two
                    // of pan and barely visible. See SkyPointing.upAlpha.
                    upAlpha = SkyPointing.upAlpha(weight, a.altitudeDeg),
                )
                first = false
                // ⚠️ The angle view is kept in step because everything ELSE reads it — the field of
                // view, the constellation cut, the readout above the chart. The horizon and the tap
                // used to as well and no longer do: they go through [pointedHorizonBasis], because
                // this value's altitude is clamped and theirs must not be. What is left reading it
                // either wants only the field, or is a number for a person rather than a direction
                // to draw at.
                _view.value = SkyPointing.equivalentView(a, _view.value.fovDeg)
            }
        }
    }

    private fun stopPointing() {
        pointingJob?.cancel()
        pointingJob = null
        device.stopAttitude()
        _needsCalibration.value = false
    }

    override fun onCleared() {
        // ⚠️ Not conditional on [pointing]. A sensor listener outlives the screen if nobody
        // unregisters it, and `stop()` on a controller that was never started is a no-op.
        stopPointing()
        super.onCleared()
    }

    fun pan(dAzimuthDeg: Double, dAltitudeDeg: Double) {
        // Dragging is what the map does when it is not being aimed; while it is, a drag would fight
        // the next sensor sample and lose.
        if (_pointing.value) return
        _view.value = SkyProjection.pan(_view.value, dAzimuthDeg, dAltitudeDeg)
    }

    fun zoom(factor: Double) {
        _view.value = SkyProjection.zoom(_view.value, factor)
    }

    /**
     * Point the map at a compass direction, keeping the current tilt and field.
     *
     * ⚠️ Stops following the handset. Asking to look north while the phone is aimed south is a
     * contradiction, and honouring the tap for the sixteen milliseconds until the next sensor sample
     * would read as a control that does nothing.
     */
    fun lookAt(azimuthDeg: Double, altitudeDeg: Double = _view.value.altitudeDeg) {
        setPointing(false)
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
        // ⚠️ **Through the pointed frame while the handset is aiming, for the same reason the
        // horizon is.** [view] is the angle form of the aim and it is not the aim: its altitude is
        // clamped to [SkyProjection.MAX_ALTITUDE_DEG], so a tap taken through it resolved up to 0.4°
        // from where the finger was — under a percent of the screen at a wide field and 160% of it
        // at the narrowest, which is the measurement in `pointedHorizonBasis`. Falls back to the
        // view when the basis has no orientation, so a tap can never silently do nothing.
        val pointed = pointedHorizonBasis(v.fovDeg)
        val dir = DoubleArray(3)
        val (az, alt) = if (pointed != null && SkyProjection.unprojectUnit(screenX, screenY, pointed, dir)) {
            SkyPointing.azimuthOf(dir) to SkyPointing.altitudeOf(dir)
        } else {
            SkyProjection.unproject(screenX, screenY, v)
        }
        // The tolerance reads only the field, which both paths share, so it needs no branch.
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
            // ⚠️ Through the same boundary the frame is built with, so a tap resolves in exactly
            // the frame the stars are drawn in. Doing the conversion by hand here is what would put
            // the touch twenty arcminutes from the dot it landed on — invisible at a wide field and
            // the whole screen at the narrowest.
            val eq = SkyFrame.catalogueOf(alt, az, here.latitude, here.longitude, at)
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

    private fun horizonOf(layer: StarLayer, i: Int, here: SkySite, at: Long): Ephemeris.Horizontal {
        val dec = Math.toDegrees(kotlin.math.asin(layer.vz[i].coerceIn(-1.0, 1.0)))
        val ra = Math.toDegrees(kotlin.math.atan2(layer.vy[i], layer.vx[i]))
        // ⚠️ The exact inverse of what [identify] used to find this star, so the altitude reported
        // on the card is the altitude it is drawn at. `SkyFrame` holds both halves of that pair.
        return SkyFrame.horizonOf(ra, dec, here.latitude, here.longitude, at)
    }

    private fun namedStar(i: Int, here: SkySite, at: Long): Body? {
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
            // The Sun's place in equatorial coordinates, which every phased body needs: which way a
            // crescent points is entirely a question of where the Sun is from there.
            val sunEq = Ephemeris.sunEquatorial(at)

            val sun = Ephemeris.sunPosition(here.latitude, here.longitude, at)
            out += Body(
                sun.azimuthDeg, sun.altitudeDeg, -26.7, "Sun", Kind.SUN, detail = "The Sun",
                look = PlanetDisc.Appearance(
                    diameterDeg = PlanetDisc.apparentDiameterDeg(
                        PlanetDisc.equatorialRadiusKm(PlanetDisc.Body.SUN), sun.distanceKm,
                    ),
                    limbDarkened = true,
                ),
            )

            val moon = Ephemeris.moonPosition(here.latitude, here.longitude, at)
            val moonEq = Ephemeris.moonEquatorial(at)
            val phase = Ephemeris.moonPhase(at)
            out += Body(
                moon.azimuthDeg, moon.altitudeDeg, -12.0, "Moon", Kind.MOON,
                detail = "The Moon · ${phase.name} · ${(phase.illuminatedFraction * 100).roundToInt()}% lit",
                look = PlanetDisc.Appearance(
                    diameterDeg = PlanetDisc.apparentDiameterDeg(
                        PlanetDisc.equatorialRadiusKm(PlanetDisc.Body.MOON), moon.distanceKm,
                    ),
                    phaseAngleDeg = phase.phaseAngleDeg,
                    limb = stepAlong(
                        moon.azimuthDeg, moon.altitudeDeg,
                        PlanetDisc.brightLimbAngle(
                            sunEq.rightAscensionDeg, sunEq.declinationDeg,
                            moonEq.rightAscensionDeg, moonEq.declinationDeg,
                        ),
                    ),
                ),
            )

            runCatching { PlanetCalc.planetsNow(here.latitude, here.longitude, at) }
                .getOrDefault(emptyList())
                .forEach { p ->
                    out += Body(
                        p.azimuthDeg, p.altitudeDeg, p.magnitude, p.name, Kind.PLANET,
                        detail = "${p.name} · magnitude ${"%.1f".format(java.util.Locale.US, p.magnitude)}",
                        look = planetLook(p, sunEq, at),
                    )
                }
            out
        }
    }

    /**
     * How one planet looks right now, or null if this build knows no size for it.
     *
     * ⚠️ **A zero distance means "not stated", not "on top of the observer".** `PlanetCalc` defaults
     * both new fields to zero so cached entries still decode, so the guard here is what stops a
     * stale record being drawn as a planet filling the sky.
     */
    private fun planetLook(
        p: dev.mascwa.pulse.data.orbital.Planet,
        sunEq: Ephemeris.Equatorial,
        at: Long,
    ): PlanetDisc.Appearance? {
        val body = PLANET_BODIES[p.name] ?: return null
        if (p.distanceAu <= 0.0) return null
        val diameter = PlanetDisc.apparentDiameterDegAu(
            PlanetDisc.equatorialRadiusKm(body), p.distanceAu,
        )
        if (diameter <= 0.0) return null

        val limb = stepAlong(
            p.azimuthDeg, p.altitudeDeg,
            PlanetDisc.brightLimbAngle(
                sunEq.rightAscensionDeg, sunEq.declinationDeg,
                p.rightAscensionDeg, p.declinationDeg,
            ),
        )
        // The pole's position angle, and the equator ninety degrees from it. Only the two giants
        // have anything worth orienting — rings, moons and a visible oblateness — so nothing else
        // pays for the extra projections.
        val poleRa: Double
        val poleDec: Double
        when (body) {
            PlanetDisc.Body.SATURN -> {
                poleRa = PlanetDisc.SATURN_POLE_RA_DEG
                poleDec = PlanetDisc.SATURN_POLE_DEC_DEG
            }
            PlanetDisc.Body.JUPITER -> {
                poleRa = PlanetDisc.JUPITER_POLE_RA_DEG
                poleDec = PlanetDisc.JUPITER_POLE_DEC_DEG
            }
            else -> return PlanetDisc.Appearance(
                diameterDeg = diameter,
                phaseAngleDeg = p.phaseAngleDeg,
                limb = limb,
            )
        }
        val polePa = PlanetDisc.axisPositionAngle(
            poleRa, poleDec, p.rightAscensionDeg, p.declinationDeg,
        )
        return PlanetDisc.Appearance(
            diameterDeg = diameter,
            flattening = PlanetDisc.flattening(body),
            phaseAngleDeg = p.phaseAngleDeg,
            limb = limb,
            equator = stepAlong(p.azimuthDeg, p.altitudeDeg, polePa + 90.0),
            pole = stepAlong(p.azimuthDeg, p.altitudeDeg, polePa),
            rings = if (body == PlanetDisc.Body.SATURN) {
                PlanetDisc.rings(p.rightAscensionDeg, p.declinationDeg)
            } else {
                null
            },
            moons = if (body == PlanetDisc.Body.JUPITER) PlanetDisc.galileanMoons(at) else emptyList(),
        )
    }

    private fun starDetail(s: StarCatalog.Star): String {
        val name = s.name ?: "Unnamed star"
        val where = if (s.constellation.isBlank()) "" else " · in ${StarNames.constellation(s.constellation)}"
        val mag = " · magnitude ${"%.2f".format(java.util.Locale.US, s.magnitude)}"
        return name + where + mag
    }

    private companion object {
        /**
         * PlanetCalc names to the bodies this map can draw as a disc.
         *
         * ⚠️ Keyed by NAME because that is all `PlanetCalc.Planet` carries, and deliberately not a
         * `valueOf` on the uppercased string: a planet this map has no radius for must fall through
         * to a marker rather than throw, and the two vocabularies are free to diverge.
         */
        val PLANET_BODIES = mapOf(
            "Mercury" to PlanetDisc.Body.MERCURY,
            "Venus" to PlanetDisc.Body.VENUS,
            "Mars" to PlanetDisc.Body.MARS,
            "Jupiter" to PlanetDisc.Body.JUPITER,
            "Saturn" to PlanetDisc.Body.SATURN,
        )
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

        // ⚠️ The smoothing weight moved to [SkyBudget.FULL_SMOOTHING], because it can no longer be
        // one number: it is a fraction of ONE SAMPLE, so it has to be derived from how often samples
        // arrive or a slower device lags proportionally further behind the hand. The reasoning for
        // the reference value — stiffer than `CompassController`'s own 0.2 because the sensor
        // arrives unfiltered here, and blending the two DIRECTIONS rather than three angles so the
        // picture does not whip round near the zenith — lives there with it.
    }
}
