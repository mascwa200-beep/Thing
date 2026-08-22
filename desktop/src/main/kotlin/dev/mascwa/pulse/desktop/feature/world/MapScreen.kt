package dev.mascwa.pulse.desktop.feature.world

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.telemetry.Geodesy
import dev.mascwa.pulse.core.telemetry.Terminator
import dev.mascwa.pulse.core.telemetry.WebMercator
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.data.maps.MapLayerCatalog
import dev.mascwa.pulse.data.places.OverpassRepository
import dev.mascwa.pulse.data.places.PlaceCategory
import dev.mascwa.pulse.data.places.PlacesResult
import dev.mascwa.pulse.data.radar.ContactKind
import dev.mascwa.pulse.data.radar.RadarData
import dev.mascwa.pulse.data.radar.RadarRepository
import dev.mascwa.pulse.data.safety.SafetyRepository
import dev.mascwa.pulse.data.safety.SafetyResult
import dev.mascwa.pulse.data.safety.Severity
import dev.mascwa.pulse.desktop.map.TileStore
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.settings.LocalUnits
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsChip
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsGhostButton
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.Pulse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Which things are drawn on top of the ground.
 *
 * ⚠️ Every one of these is a network fetch, so all four start OFF and the feed behind a layer is not
 * asked for anything until somebody switches it on. Opening the map costs the basemap tiles and
 * nothing else.
 */
enum class MapLayer(val title: String, val blurb: String) {
    AIRCRAFT("Aircraft", "What is in the air within range"),
    INCIDENTS("Incidents", "Earthquakes, warnings and disasters"),
    HELP("Nearest help", "Hospitals, shelters, pharmacies"),
    NIGHT("Night", "Where the Sun has set, right now"),
}

/** How coordinates are written out. The same three the phone offers, and for the same reason. */
enum class CoordFormat { DECIMAL, DMS, MGRS }

class MapViewModel(
    private val scope: CoroutineScope,
    private val settings: DesktopSettingsStore,
    radar: RadarRepository,
    safety: SafetyRepository,
    private val overpass: OverpassRepository,
    cacheDir: java.io.File,
) {
    val tiles = TileStore(scope, cacheDir)

    /**
     * Where the view is looking. Separate from the settings coordinate on purpose: panning the map
     * is not the same act as telling the program where you live, and conflating them would rewrite
     * your home location every time you dragged.
     */
    private val _centre = MutableStateFlow(0.0 to 0.0)
    val centre: StateFlow<Pair<Double, Double>> = _centre.asStateFlow()

    private val _zoom = MutableStateFlow(11)
    val zoom: StateFlow<Int> = _zoom.asStateFlow()

    /** The settings coordinate, so the map can mark it and offer to go back to it. */
    private val _home = MutableStateFlow<Pair<Double, Double>?>(null)
    val home: StateFlow<Pair<Double, Double>?> = _home.asStateFlow()

    private val _basemap = MutableStateFlow(MapLayerCatalog.Basemap.TOPO)
    val basemap: StateFlow<MapLayerCatalog.Basemap> = _basemap.asStateFlow()

    private val _layers = MutableStateFlow(emptySet<MapLayer>())
    val layers: StateFlow<Set<MapLayer>> = _layers.asStateFlow()

    private val _format = MutableStateFlow(CoordFormat.DECIMAL)
    val format: StateFlow<CoordFormat> = _format.asStateFlow()

    val radarFeed = WorldFeed<RadarData>(scope, settings) { lat, lon, force -> radar.fetch(lat, lon, force) }
    val safetyFeed = WorldFeed<SafetyResult>(scope, settings) { lat, lon, force -> safety.fetch(lat, lon, force) }
    /**
     * ⚠️ Hospitals only, not every category the Places screen offers.
     *
     * That screen exists to let you work through shelters, food banks and comm towers one at a time;
     * a map layer called "nearest help" that fetched all five would issue five Overpass queries the
     * moment it was switched on, against a community server this project has already been told off
     * by once. The screen is where breadth belongs.
     */
    val helpFeed = WorldFeed<PlacesResult>(scope, settings) { lat, lon, force ->
        overpass.fetch(PlaceCategory.HOSPITAL, lat, lon, force)
    }

    fun start() {
        scope.launch {
            val here = settings.here() ?: return@launch
            _home.value = here
            // Only on the first load — otherwise returning to the map would throw away wherever you
            // had panned to, every time.
            if (_centre.value == 0.0 to 0.0) _centre.value = here
        }
    }

    fun panBy(dxPx: Double, dyPx: Double, tilePx: Int) {
        val z = _zoom.value
        val (lat, lon) = _centre.value
        val x = WebMercator.tileX(lon, z) - dxPx / tilePx
        val y = WebMercator.tileY(lat, z) - dyPx / tilePx
        // ⚠️ Latitude is clamped and longitude is not. Dragging past the pole should stop; dragging
        // past the antimeridian should carry on round, which is what the map actually does.
        val n = WebMercator.worldTiles(z).toDouble()
        _centre.value = WebMercator.latitudeAt(y.coerceIn(0.0, n), z) to
            WebMercator.normaliseLongitude(WebMercator.longitudeAt(x, z))
    }

    fun zoomBy(delta: Int) {
        val basemapMax = _basemap.value.maxZoom.toInt()
        _zoom.value = (_zoom.value + delta).coerceIn(MIN_ZOOM, basemapMax)
    }

    fun setBasemap(b: MapLayerCatalog.Basemap) {
        _basemap.value = b
        // A basemap that stops at zoom 14 cannot show you what zoom 17 was showing, so come out to
        // where it can rather than leaving a blank screen and no explanation.
        _zoom.value = _zoom.value.coerceAtMost(b.maxZoom.toInt())
        tiles.retryFailures()
    }

    fun toggle(layer: MapLayer) {
        val next = _layers.value.toMutableSet()
        if (!next.remove(layer)) {
            next.add(layer)
            // Fetch on the switch, not on open — see the note on [MapLayer].
            when (layer) {
                MapLayer.AIRCRAFT -> radarFeed.ensureLoaded()
                MapLayer.INCIDENTS -> safetyFeed.ensureLoaded()
                MapLayer.HELP -> helpFeed.ensureLoaded()
                MapLayer.NIGHT -> Unit // computed here; nothing to fetch
            }
        }
        _layers.value = next
    }

    fun setFormat(f: CoordFormat) {
        _format.value = f
    }

    fun goHome() {
        _home.value?.let { _centre.value = it }
    }

    fun refresh() {
        tiles.retryFailures()
        if (MapLayer.AIRCRAFT in _layers.value) radarFeed.refresh()
        if (MapLayer.INCIDENTS in _layers.value) safetyFeed.refresh()
        if (MapLayer.HELP in _layers.value) helpFeed.refresh()
    }

    companion object {
        const val MIN_ZOOM = 2
    }
}

/**
 * The map.
 *
 * ⚠️ Raster tiles on a canvas, not a vector renderer. MapLibre — what the phone draws with — is an
 * Android library with no desktop build, and the alternatives are large native dependencies. Tiles
 * over [WebMercator] are the plain, well-understood way to draw a map, and the arithmetic under it
 * is tested rather than trusted.
 *
 * ⚠️ **The attribution line at the foot is a licence obligation, not a courtesy.** OpenTopoMap is
 * CC-BY-SA and EOX's imagery is CC-BY; a map that drops the credit is using the data outside its
 * terms. It moves with the basemap because the terms do.
 */
@Composable
fun MapScreen(vm: MapViewModel, modifier: Modifier = Modifier) {
    val centre by vm.centre.collectAsState()
    val zoom by vm.zoom.collectAsState()
    val basemap by vm.basemap.collectAsState()
    val layers by vm.layers.collectAsState()
    val format by vm.format.collectAsState()
    val home by vm.home.collectAsState()
    val revision by vm.tiles.revision.collectAsState()
    val radar: Async<RadarData> by vm.radarFeed.state.collectAsState()
    val safety: Async<SafetyResult> by vm.safetyFeed.state.collectAsState()
    val help: Async<PlacesResult> by vm.helpFeed.state.collectAsState()
    val units = LocalUnits.current
    val c = Pulse.colors
    val measurer = rememberTextMeasurer()

    LaunchedEffect(Unit) { vm.start() }

    var sizePx by remember { mutableStateOf(0 to 0) }
    // The pointer's last position in the canvas, so the readout says what is under it rather than
    // what is in the middle — which is what a person actually wants to know off a map.
    var pointer by remember { mutableStateOf<Offset?>(null) }

    Column(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsHeaderBar("Map", Modifier.weight(1f), trailing = "Z$zoom")
            LcarsGhostButton("HOME", { vm.goHome() })
            LcarsGhostButton("REFRESH", { vm.refresh() })
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MapLayerCatalog.Basemap.entries.filter { it.tileUrl != null }.forEach { b ->
                LcarsChip(b.label, selected = b == basemap, onClick = { vm.setBasemap(b) })
            }
            Text("│", fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.faint)
            MapLayer.entries.forEach { l ->
                LcarsChip(l.title.uppercase(), selected = l in layers, onClick = { vm.toggle(l) })
            }
        }

        val tilePx = basemap.tileSize
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)
                .background(c.void)
                .onSizeChanged { sizePx = it.width to it.height }
                .pointerInput(tilePx) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        vm.panBy(drag.x.toDouble(), drag.y.toDouble(), tilePx)
                    }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            pointer = event.changes.lastOrNull()?.position
                            if (event.type == PointerEventType.Scroll) {
                                val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                // ⚠️ Scrolling DOWN is a positive delta and means zoom OUT, which is
                                // the way every map on this machine already behaves.
                                if (abs(dy) > 0.01f) vm.zoomBy(if (dy > 0) -1 else 1)
                            }
                        }
                    }
                },
        ) {
            // `revision` is read here so a tile landing redraws the canvas. It is not otherwise used,
            // and removing it would leave the map blank until something else happened to recompose.
            @Suppress("UNUSED_EXPRESSION") revision

            Canvas(Modifier.fillMaxSize()) {
                val vp = WebMercator.Viewport(
                    centreLat = centre.first,
                    centreLon = centre.second,
                    zoom = zoom,
                    widthPx = size.width.toDouble(),
                    heightPx = size.height.toDouble(),
                    tilePx = tilePx,
                )
                val template = basemap.tileUrl ?: return@Canvas

                for (t in WebMercator.tiles(vp)) {
                    val bmp = vm.tiles.get(template, t)
                    if (bmp == null) {
                        // A tile that has not arrived is a hole, and a hole is honest: drawing the
                        // one above it stretched would show ground that is not there.
                        drawRect(
                            color = c.raise,
                            topLeft = Offset(t.left.toFloat(), t.top.toFloat()),
                            size = androidx.compose.ui.geometry.Size(t.size.toFloat(), t.size.toFloat()),
                        )
                    } else {
                        drawImage(
                            image = bmp,
                            dstOffset = androidx.compose.ui.unit.IntOffset(
                                t.left.roundToInt(),
                                t.top.roundToInt(),
                            ),
                            dstSize = androidx.compose.ui.unit.IntSize(
                                t.size.roundToInt(),
                                t.size.roundToInt(),
                            ),
                        )
                    }
                }

                if (MapLayer.NIGHT in layers) drawNight(vp)
                if (MapLayer.HELP in layers) {
                    help.data?.places?.forEach {
                        pin(vp, it.latitude, it.longitude, c.sky, 4f)
                    }
                }
                if (MapLayer.INCIDENTS in layers) {
                    safety.data?.incidents?.forEach {
                        val col = when (runCatching { Severity.valueOf(it.severity) }.getOrNull()) {
                            Severity.EXTREME -> c.negative
                            Severity.HIGH -> c.negative
                            Severity.MODERATE -> c.amber
                            else -> c.muted
                        }
                        pin(vp, it.latitude, it.longitude, col, 5f)
                    }
                }
                if (MapLayer.AIRCRAFT in layers) {
                    radar.data?.contacts?.forEach {
                        val col = when (runCatching { ContactKind.valueOf(it.kind) }.getOrNull()) {
                            ContactKind.QUAKE -> c.amber
                            ContactKind.ISS -> c.positive
                            else -> c.accent
                        }
                        pin(vp, it.latitude, it.longitude, col, 3f)
                    }
                }
                home?.let { (lat, lon) -> drawHome(vp, lat, lon, c.positive) }

                drawScaleBar(vp, measurer, c.ink, c.void, units.miles)
            }
        }

        // ---- the readout -------------------------------------------------------------------------
        val read = pointer?.let { p ->
            val vp = WebMercator.Viewport(
                centre.first, centre.second, zoom,
                sizePx.first.toDouble(), sizePx.second.toDouble(), tilePx,
            )
            WebMercator.latitudeAtOffset(p.y.toDouble(), vp) to
                WebMercator.longitudeAtOffset(p.x.toDouble(), vp)
        } ?: centre

        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                describe(read.first, read.second, format),
                fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink,
                modifier = Modifier.weight(1f),
            )
            CoordFormat.entries.forEach { f ->
                LcarsChip(f.name, selected = f == format, onClick = { vm.setFormat(f) })
            }
        }

        LcarsFrame(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp)) {
            Column {
                Text(
                    basemap.attribution,
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.faint,
                )
                val busy = buildList {
                    if (MapLayer.AIRCRAFT in layers && radar.loading) add("aircraft")
                    if (MapLayer.INCIDENTS in layers && safety.loading) add("incidents")
                    if (MapLayer.HELP in layers && help.loading) add("nearest help")
                }
                if (busy.isNotEmpty()) {
                    Text(
                        "Fetching ${busy.joinToString(", ")}…",
                        fontFamily = ChakraPetch, fontSize = 10.sp, color = c.muted,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

// ---- drawing -------------------------------------------------------------------------------------

private fun DrawScope.pin(vp: WebMercator.Viewport, lat: Double, lon: Double, colour: Color, r: Float) {
    val x = WebMercator.offsetX(lon, vp).toFloat()
    val y = WebMercator.offsetY(lat, vp).toFloat()
    if (x < -r || y < -r || x > size.width + r || y > size.height + r) return
    drawCircle(colour, radius = r, center = Offset(x, y))
    drawCircle(Color.Black, radius = r, center = Offset(x, y), style = Stroke(width = 1f))
}

/** Where the machine believes it is: a ring rather than a dot, so it does not read as one more contact. */
private fun DrawScope.drawHome(vp: WebMercator.Viewport, lat: Double, lon: Double, colour: Color) {
    val x = WebMercator.offsetX(lon, vp).toFloat()
    val y = WebMercator.offsetY(lat, vp).toFloat()
    drawCircle(colour, radius = 7f, center = Offset(x, y), style = Stroke(width = 2f))
    drawCircle(colour, radius = 2f, center = Offset(x, y))
}

/**
 * The night side, as a wash rather than an outline.
 *
 * ⚠️ Sampled column by column rather than drawn as a polygon. The terminator is a curve on a sphere
 * and a filled path through its projected points is wrong near the poles, where the shadow reaches
 * right across the top of the map — a column test asks the question the shading actually answers:
 * has the Sun set here?
 */
private fun DrawScope.drawNight(vp: WebMercator.Viewport) {
    val now = System.currentTimeMillis()
    val step = 6f
    var x = 0f
    while (x < size.width) {
        val lon = WebMercator.longitudeAtOffset((x + step / 2).toDouble(), vp)
        var y = 0f
        while (y < size.height) {
            val lat = WebMercator.latitudeAtOffset((y + NIGHT_STEP / 2).toDouble(), vp)
            if (!Terminator.isDaylight(lat, lon, now)) {
                drawRect(
                    color = Color(0f, 0f, 0.08f, 0.42f),
                    topLeft = Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(step, NIGHT_STEP),
                )
            }
            y += NIGHT_STEP
        }
        x += step
    }
}

private const val NIGHT_STEP = 12f

/**
 * A scale bar, because a map without one is a picture.
 *
 * The length is chosen from a 1-2-5 sequence so the label is a round number — 500 m, 2 km, 50 miles —
 * rather than whatever a fixed pixel width happened to work out as.
 */
private fun DrawScope.drawScaleBar(
    vp: WebMercator.Viewport,
    measurer: TextMeasurer,
    ink: Color,
    shade: Color,
    miles: Boolean,
) {
    val mPerPx = WebMercator.metresPerPixel(vp.centreLat, vp.zoom, vp.tilePx)
    if (!mPerPx.isFinite() || mPerPx <= 0) return
    val unit = if (miles) 1609.344 else 1000.0
    val target = 140.0 * mPerPx / unit
    val nice = niceNumber(target)
    val px = (nice * unit / mPerPx).toFloat()
    if (!px.isFinite() || px < 10f || px > size.width) return

    val y = size.height - 22f
    val left = 14f
    drawRect(shade.copy(alpha = 0.75f), Offset(left - 6f, y - 16f), androidx.compose.ui.geometry.Size(px + 12f, 30f))
    drawLine(ink, Offset(left, y), Offset(left + px, y), strokeWidth = 2f)
    drawLine(ink, Offset(left, y - 5f), Offset(left, y + 5f), strokeWidth = 2f)
    drawLine(ink, Offset(left + px, y - 5f), Offset(left + px, y + 5f), strokeWidth = 2f)

    val label = "${trimScale(nice)} ${if (miles) "mi" else "km"}"
    val style = TextStyle(color = ink, fontSize = 10.sp, fontFamily = JetBrainsMono)
    drawText(measurer, label, topLeft = Offset(left, y - 15f), style = style)
}

/** 1, 2, 5, 10, 20, 50 … — the sequence every scale bar in the world uses. */
internal fun niceNumber(v: Double): Double {
    if (!v.isFinite() || v <= 0) return 1.0
    var mag = 1.0
    while (mag * 10 <= v) mag *= 10
    while (mag > v) mag /= 10
    return when {
        v >= mag * 5 -> mag * 5
        v >= mag * 2 -> mag * 2
        else -> mag
    }
}

private fun trimScale(v: Double): String =
    if (v >= 1) v.roundToInt().toString() else String.format(java.util.Locale.US, "%.2f", v)

private fun describe(lat: Double, lon: Double, f: CoordFormat): String = when (f) {
    CoordFormat.DECIMAL -> Geodesy.formatDecimal(lat, lon)
    CoordFormat.DMS -> Geodesy.formatDms(lat, lon)
    CoordFormat.MGRS -> Geodesy.toMgrs(lat, lon) ?: "outside the MGRS grid"
}
