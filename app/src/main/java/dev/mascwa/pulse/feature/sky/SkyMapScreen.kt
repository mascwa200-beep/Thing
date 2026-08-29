package dev.mascwa.pulse.feature.sky

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.DeepSky
import dev.mascwa.pulse.core.telemetry.PlanetDisc
import dev.mascwa.pulse.core.telemetry.SkyProjection
import dev.mascwa.pulse.core.telemetry.StarGlyph
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsChip
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.sky.DeepSkyColors
import dev.mascwa.pulse.sky.LineBatch
import dev.mascwa.pulse.sky.MilkyWayGlow
import dev.mascwa.pulse.sky.SkyFrame
import dev.mascwa.pulse.sky.SkyLines
import dev.mascwa.pulse.sky.SkyRenderer
import dev.mascwa.pulse.sky.stepAlong
import dev.mascwa.pulse.sky.measurePixelsPerDegree
import dev.mascwa.pulse.sky.drawSolarSystemBody
import dev.mascwa.pulse.sky.StarBatches
import dev.mascwa.pulse.sky.collectLines
import dev.mascwa.pulse.sky.collectStars
import dev.mascwa.pulse.sky.drawDeepSky
import dev.mascwa.pulse.sky.drawLineBatch
import dev.mascwa.pulse.sky.drawMilkyWay
import dev.mascwa.pulse.sky.drawStarBatches
import dev.mascwa.pulse.sky.drawStarGlow
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The sky as a map you can move around in.
 *
 * ⚠️ **The whole thing is one Canvas, and nothing about it is a widget.** A star chart is thousands
 * of dots that move together; expressing them as composables would mean thousands of layout nodes
 * recomposing on every drag. Drawing is the right primitive here, which is also why panning costs
 * only a projection per visible star and no recomposition of anything else.
 *
 * ⚠️ **What is drawn is decided by the zoom, not by a fixed list.** [SkyProjection.magnitudeLimit]
 * deepens as the field narrows, so a wide view shows a full naked-eye sky and a narrow one fills in
 * as far as the catalogue goes. That is also what bounds the work: the number of stars actually
 * drawn stays in the low thousands however deep the file is.
 *
 * ⚠️ **The sky fills the screen, and getting that wrong is what this screen shipped with.** The
 * field of view is normalised to the *short* dimension, and the draw loop asked
 * [SkyProjection.Screen.inField] — a radius against that circle — what to draw. On a portrait phone
 * the result was a disc the width of the screen with dead black bands above and below, which is
 * exactly how it was reported. [SkyProjection.Screen.onScreen] is the predicate a renderer wants;
 * `inField` is for tap tolerances.
 */
@Composable
fun SkyMapScreen(vm: SkyMapViewModel, onBack: (() -> Unit)? = null) {
    PulseScaffold(title = "Sky map", onBack = onBack, rail = false) { innerPadding ->
        SkyMapBody(vm, Modifier.padding(innerPadding))
    }
}

@Composable
private fun SkyMapBody(vm: SkyMapViewModel, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    val view by vm.view.collectAsStateWithLifecycle()
    val bodies by vm.bodies.collectAsStateWithLifecycle()
    val site by vm.site.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val hours by vm.hourOffset.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val missing by vm.catalogueMissing.collectAsStateWithLifecycle()
    val lines by vm.linesMode.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SkyCanvas(view, bodies, c, vm)
            when {
                site == null && !loading -> Notice(
                    "The map needs to know where you are — the sky over one place is not the sky " +
                        "over another. Grant location and reopen this screen.",
                    c,
                    Modifier.align(Alignment.Center),
                )
                missing -> Notice(
                    "The bundled star catalogue could not be read, so only the Sun, Moon and " +
                        "planets are drawn. This is a fault in the build, not something you did.",
                    c,
                    Modifier.align(Alignment.Center),
                )
                loading -> Notice("Placing the stars…", c, Modifier.align(Alignment.Center))
            }
            selected?.let { body ->
                IdentifyCard(body, c, Modifier.align(Alignment.BottomCenter), vm::clearSelection)
            }
        }
        Controls(view, hours, lines, c, vm)
    }
}

// ---- the chart ---------------------------------------------------------------------------------

@Composable
private fun SkyCanvas(
    view: SkyProjection.View,
    bodies: List<SkyMapViewModel.Body>,
    c: NightwirePalette,
    vm: SkyMapViewModel,
) {
    val labelPaint = remember { Paint().apply { isAntiAlias = true; textAlign = Paint.Align.LEFT } }
    val cardinalPaint = remember { Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER } }
    // ⚠️ One Paint and two bucket sets, kept across frames. The renderer is built so that drawing a
    // sky allocates nothing at all; making these per frame would put the allocation back.
    val starPaint = remember { Paint() }
    val above = remember { StarBatches() }
    val below = remember { StarBatches() }
    // ⚠️ One segment buffer, refilled per frame and reused, for the same reason. A busy sky is tens
    // of thousands of floats; allocating that sixty times a second is the frame.
    val linePaint = remember { Paint() }
    val lineBatch = remember { LineBatch() }
    // ⚠️ Its OWN Paint, not the star one, and the skew is exactly why. Oblique is how every printed
    // chart tells a nebula's name from a star's, but `labelPaint` is shared with the star labels and
    // the planet labels, and neither of those resets a skew it never set — so borrowing it would
    // silently tilt every name on the map.
    // ⚠️ Remembered, like the star buckets, because it owns a Bitmap — see MilkyWayGlow. A fresh
    // one per frame would allocate and free a bitmap sixty times a second.
    val milkyWay = remember { MilkyWayGlow() }

    val deepSkyPaint = remember {
        Paint().apply { isAntiAlias = true; textAlign = Paint.Align.LEFT; textSkewX = -0.22f }
    }
    // ⚠️ **`positive` and `negative` are deliberately absent.** The palette's own KDoc says they
    // carry meaning elsewhere — a market moving up or down — and must never be borrowed for
    // decoration, so the green and the red are not available here however well they would read.
    //
    // A supernova remnant takes the nebula's colour because it IS one: a shell of glowing gas, drawn
    // with the same lobed glow, and eleven of them in the whole catalogue. `DeepSkyColors` keeps the
    // two apart so the standalone app can answer differently, not because this one must.
    val deepSkyColours = remember(c) {
        DeepSkyColors(
            galaxy = c.magenta, // 10,792 of the 12,579 — worth the one hue nothing else here uses
            cluster = c.amber, // warm, because a cluster is made of stars
            nebula = c.violet,
            planetary = c.sky, // teal, which is what doubly-ionised oxygen actually looks like
            remnant = c.violet,
            dark = c.faint, // the dimmest ink in the palette, for the place with less light in it
            other = c.muted,
        )
    }

    val site by vm.site.collectAsStateWithLifecycle()
    val linesMode by vm.linesMode.collectAsStateWithLifecycle()
    val hours by vm.hourOffset.collectAsStateWithLifecycle()
    val deepRevision = vm.revision.collectAsStateWithLifecycle()
    // ⚠️ How faint the catalogue actually goes. Omitting it is not a default, it is a cut at the
    // naked-eye limit — see SkyMapViewModel.deepestMagnitude for what that measured.
    val deepest by vm.deepestMagnitude.collectAsStateWithLifecycle()

    // ⚠️ Held rather than read in the draw pass, so the sky does not creep while somebody pans. The
    // instant only moves when the scrubber does, which is what the existing map has always done.
    val at = remember(hours, site) { System.currentTimeMillis() + hours * 3_600_000L }
    var surface by remember { mutableStateOf(IntSize.Zero) }

    // Bring the deep catalogue up to date for wherever the view has got to. Cheap when the region
    // already held still covers the view, which is most pans — see StarField.update.
    LaunchedEffect(view, site, at, surface, linesMode) {
        if (surface.width > 0 && surface.height > 0) {
            vm.refreshDeep(
                SkyProjection.viewportOf(surface.width.toDouble(), surface.height.toDouble()), at,
            )
        }
        // ⚠️ Unconditional on the surface, unlike the deep catalogue: the constellation cut depends
        // on the field of view alone, which is known before the canvas has been measured.
        vm.refreshLines()
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged { surface = it }
            // ⚠️ Two separate pointer handlers, because a transform gesture consumes everything it
            // sees. Combining them into one would make a tap read as a zero-distance drag and the
            // identify card would never open.
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val half = minOf(size.width, size.height) / 2f
                    if (half > 0f) {
                        val perUnit = SkyProjection.degreesPerUnit(vm.view.value)
                        // Screen y grows downward and altitude grows upward, hence the sign.
                        vm.pan(-pan.x / half * perUnit, pan.y / half * perUnit)
                    }
                    if (gestureZoom != 1f) vm.zoom(gestureZoom.toDouble())
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val half = minOf(size.width, size.height) / 2f
                    if (half <= 0f) return@detectTapGestures
                    vm.identify(
                        ((offset.x - size.width / 2f) / half).toDouble(),
                        ((offset.y - size.height / 2f) / half).toDouble(),
                    )
                }
            },
    ) {
        drawRect(c.void)
        val half = size.minDimension / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f
        // ⚠️ **The sky fills the rectangle; it is not a disc inside one.** The declared field is
        // normalised to the SHORT screen dimension, so on a portrait phone everything above and
        // below the middle third of the screen lies outside it — and asking `inField` what to draw,
        // which is what this did until it was reported, produced a literal circle with dead black
        // bands. The question a renderer asks is whether a point is on the SURFACE.
        val viewport = SkyProjection.viewportOf(size.width.toDouble(), size.height.toDouble())

        // ⚠️ **Built here rather than inside the block below, so the Milky Way can be drawn under
        // the horizon line.** Two vectors rebuilt per frame; the stars themselves never move. See
        // SkyFrame. Hoisting it is what avoids a second `SkyFrame.of` for the glow — the two would
        // agree today and be free to stop agreeing later.
        val here = site
        val frame = if (here != null) SkyFrame.of(view, here.latitude, here.longitude, at) else null

        // ⚠️ **First, before even the horizon.** It is unresolved starlight, so every star, every
        // galaxy and every constellation line on this map is in front of it — and so is the chrome.
        // Drawn last it would be a haze OVER the sky instead of the sky's own background.
        if (frame != null) {
            vm.milkyWay?.let { drawMilkyWay(it, frame, view, milkyWay, c.ink) }
        }

        drawHorizon(view, c, half, cx, cy, viewport, cardinalPaint)

        val limit = SkyProjection.magnitudeLimit(view.fovDeg, deepest)
        if (frame != null) {
            // ⚠️ **Read HERE, in the draw pass, and that is the entire point of the counter.** The
            // star layers are mutable arrays — deliberately, so a frame allocates nothing — which
            // means Compose has no way to know a reload happened. Reading the revision inside the
            // draw lambda makes this draw depend on it, so new stars appear the moment they land
            // rather than on whatever pan happens next.
            deepRevision.value

            // ⚠️ Under the stars, on purpose. A constellation line is a note about the stars, so a
            // line drawn over one puts a stroke through the thing it is pointing at.
            val shapes = vm.constellations
            if (linesMode != SkyMapViewModel.LinesMode.NONE) {
                // ⚠️ The angle to the screen CORNER, not the half-field — culling on the half-field
                // would throw away lines that are plainly visible at the top and bottom of a
                // portrait phone. See SkyProjection.coneRadiusDeg.
                val cone = Math.toRadians(SkyProjection.coneRadiusDeg(view.fovDeg, viewport))
                val coneCos = cos(cone)
                val coneSin = sin(cone)

                // ⚠️ The two reference circles are drawn OUTSIDE the `shapes != null` guard, because
                // they depend on no asset at all — they are two lines of trigonometry. Putting them
                // inside would mean a failed constellation file silently took the ecliptic with it,
                // which is the layer a planet-hunter would miss most.
                drawSkyLineSet(
                    vm.equatorLine, frame, viewport, coneCos, coneSin, half, cx, cy,
                    lineBatch, linePaint, c.muted, REFERENCE_WIDTH_DP, EQUATOR_ALPHA,
                )
                // Amber, because this is the road the Sun walks — and every planet within a few
                // degrees of it, which is the whole reason the line is worth drawing.
                drawSkyLineSet(
                    vm.eclipticLine, frame, viewport, coneCos, coneSin, half, cx, cy,
                    lineBatch, linePaint, c.amber, REFERENCE_WIDTH_DP, ECLIPTIC_ALPHA,
                )

                if (shapes != null) {
                    if (linesMode == SkyMapViewModel.LinesMode.FIGURES_AND_BORDERS) {
                        drawSkyLineSet(
                            shapes.boundaries, frame, viewport, coneCos, coneSin, half, cx, cy,
                            lineBatch, linePaint, c.muted, BORDER_WIDTH_DP, BORDER_ALPHA,
                        )
                    }
                    drawSkyLineSet(
                        shapes.asterisms, frame, viewport, coneCos, coneSin, half, cx, cy,
                        lineBatch, linePaint, c.violet, ASTERISM_WIDTH_DP, ASTERISM_ALPHA,
                    )
                    drawSkyLineSet(
                        shapes.figures, frame, viewport, coneCos, coneSin, half, cx, cy,
                        lineBatch, linePaint, c.sky, FIGURE_WIDTH_DP, FIGURE_ALPHA,
                    )
                }
            }

            // ⚠️ Over the lines and under the stars, and both halves matter. A border drawn across
            // Andromeda would cut the galaxy in two; a cluster's own member stars have to sit on top
            // of its glow, because that is the thing being pointed at.
            //
            // ⚠️ **Its OWN cut, not the star `limit` above.** Deep-sky counts rise about 1.7-fold
            // per magnitude where star counts rise 2.8-fold, so feeding this population the star law
            // gives a cut far too shallow to draw anything at a wide field — see
            // DeepSky.magnitudeLimit, which carries its own constants for exactly that reason.
            vm.deepSky?.let { deepSkyLayer ->
                deepSkyPaint.color = c.muted.toArgb()
                deepSkyPaint.textSize = 9f * density
                drawDeepSky(
                    deepSkyLayer, frame, viewport, DeepSky.magnitudeLimit(view.fovDeg),
                    view.fovDeg, half, cx, cy, deepSkyColours,
                ) { lx, ly, name ->
                    drawContext.canvas.nativeCanvas.drawText(
                        name, lx + 5f * density, ly + 3f * density, deepSkyPaint,
                    )
                }
            }

            above.reset()
            below.reset()
            val deep = vm.deepField?.layer
            collectStars(vm.brightStars, frame, viewport, limit, half, cx, cy, above, below)
            if (deep != null) collectStars(deep, frame, viewport, limit, half, cx, cy, above, below)

            // ⚠️ Below the horizon FIRST, so a star that is up is never painted over by one that is
            // not. Both catalogues share the two bucket sets, so this is a few dozen calls for the
            // whole sky however many stars it holds — see SkyRenderer.
            drawStarBatches(below, starPaint, c.ink, SkyRenderer.BELOW_HORIZON_ALPHA)
            drawStarBatches(above, starPaint, c.ink)

            drawStarGlow(vm.brightStars, frame, viewport, limit, half, cx, cy, c.ink)
            if (deep != null) drawStarGlow(deep, frame, viewport, limit, half, cx, cy, c.ink)

            // ⚠️ The bright layer only, because the deep one has no names at all — it is Gaia source
            // identifiers, which nobody has ever called anything.
            drawStarLabels(vm, frame, viewport, limit, half, cx, cy, c, labelPaint)
        }

        // The Sun, the Moon and the planets: eight things, still in horizon coordinates because
        // that is what the ephemeris answers and eight conversions a rebuild costs nothing.
        bodies.forEach { b ->
            val p = SkyProjection.project(b.azimuthDeg, b.altitudeDeg, view)
            if (!p.onScreen(viewport, SkyRenderer.EDGE_MARGIN)) return@forEach
            val screen = Offset(cx + (p.x * half).toFloat(), cy + (p.y * half).toFloat())
            val isBelow = b.altitudeDeg < 0
            val r = when (b.kind) {
                SkyMapViewModel.Kind.SUN -> 9f
                SkyMapViewModel.Kind.MOON -> 8f
                else -> 5f
            } * density
            val colour = when (b.kind) {
                SkyMapViewModel.Kind.SUN -> c.amber
                SkyMapViewModel.Kind.MOON -> c.ink
                else -> c.sky
            }
            // ⚠️ One projection for every direction the renderer needs, taken with the SAME
            // projection the body itself went through — which is what lets the renderer measure
            // orientations rather than assume them. See PlanetDisc.Appearance.
            val project: (PlanetDisc.SkyPoint) -> Offset? = { pt ->
                val q = SkyProjection.project(pt.azimuthDeg, pt.altitudeDeg, view)
                if (q.visible) Offset(cx + (q.x * half).toFloat(), cy + (q.y * half).toFloat()) else null
            }
            // Measured a degree at a time rather than divided out of the field, because the
            // projection is not linear and a body low down sits where that matters most.
            val perDegree = measurePixelsPerDegree(
                screen, project(stepAlong(b.azimuthDeg, b.altitudeDeg, 0.0)),
            )
            drawSolarSystemBody(
                look = b.look,
                centre = screen,
                markerRadiusPx = r,
                pixelsPerDegree = perDegree,
                colour = colour,
                shadow = c.void,
                alpha = if (isBelow) BELOW_HORIZON_BODY_ALPHA else 1f,
                project = project,
            )
            b.label?.let {
                labelPaint.color = colour.toArgb()
                labelPaint.textSize = 11f * density
                // The label clears whatever was actually drawn, which for a zoomed-in Sun is a great
                // deal more than the marker it replaced.
                val edge = maxOf(r, ((b.look?.diameterDeg ?: 0.0) / 2.0).toFloat() * perDegree)
                drawContext.canvas.nativeCanvas.drawText(
                    it, screen.x + edge + 4f * density, screen.y + 4f * density, labelPaint,
                )
            }
        }
    }
}

/**
 * The names, for the handful of stars with room for one.
 *
 * ⚠️ **A third pass over the layer rather than a branch inside the collect loop, and it is cheap for
 * a reason worth knowing.** [StarGlyph.labels] is a comparison against a float already in a
 * contiguous array, so the loop rejects eight thousand stars in the time it takes to walk the array
 * once — and what survives is at most about seventeen, measured. Folding it into `collectStars`
 * would put a name lookup and a text draw inside the one loop that has to stay tight, and would drag
 * a catalogue of strings into a module that holds only numbers.
 */
private fun DrawScope.drawSkyLineSet(
    lines: SkyLines,
    frame: SkyFrame,
    viewport: SkyProjection.Viewport,
    coneCos: Double,
    coneSin: Double,
    halfPx: Float,
    centreX: Float,
    centreY: Float,
    batch: LineBatch,
    paint: Paint,
    colour: Color,
    widthDp: Float,
    alpha: Float,
) {
    // ⚠️ One shared buffer refilled between sets rather than three. They are drawn one after another
    // and each `drawLines` copies what it needs, so the buffer is free the moment the call returns.
    batch.reset()
    collectLines(lines, frame, viewport, coneCos, coneSin, halfPx, centreX, centreY, batch)
    drawLineBatch(batch, paint, colour, widthDp.dp.toPx(), alpha)
}

private fun DrawScope.drawStarLabels(
    vm: SkyMapViewModel,
    frame: SkyFrame,
    viewport: SkyProjection.Viewport,
    limit: Double,
    halfPx: Float,
    centreX: Float,
    centreY: Float,
    c: NightwirePalette,
    paint: Paint,
) {
    val layer = vm.brightStars
    paint.color = c.muted.toArgb()
    paint.textSize = 9f * density
    for (i in 0 until layer.count) {
        val m = layer.magnitude[i].toDouble()
        if (!StarGlyph.labels(m, limit)) continue
        val name = vm.brightLabel(i) ?: continue
        val p = SkyProjection.projectUnit(layer.vx[i], layer.vy[i], layer.vz[i], frame.basis)
        if (!p.onScreen(viewport, SkyRenderer.EDGE_MARGIN)) continue
        val r = StarGlyph.bandRadiusDp(StarGlyph.sizeBand(m, limit)).dp.toPx()
        drawContext.canvas.nativeCanvas.drawText(
            name,
            centreX + (p.x * halfPx).toFloat() + r + 3f * density,
            centreY + (p.y * halfPx).toFloat() + 3f * density,
            paint,
        )
    }
}

/**
 * The horizon line and the compass letters on it.
 *
 * ⚠️ **The horizon is drawn as a polyline sampled in azimuth, not as a straight line.** It only
 * looks straight when you are looking level at it; tilt up and it curves away, which is the whole
 * reason a projection was needed. Drawing it straight would be the giveaway that the map is a flat
 * picture rather than a window.
 */
private fun DrawScope.drawHorizon(
    view: SkyProjection.View,
    c: NightwirePalette,
    half: Float,
    cx: Float,
    cy: Float,
    viewport: SkyProjection.Viewport,
    paint: Paint,
) {
    var previous: Offset? = null
    var az = view.azimuthDeg - 180.0
    while (az <= view.azimuthDeg + 180.0) {
        val p = SkyProjection.project(az, 0.0, view)
        // ⚠️ Only join points that are BOTH drawable. Without the check a segment leaving the view
        // is drawn to a point far off screen and the horizon gains a spike across the chart.
        //
        // ⚠️ The bound is the viewport with a **generous** margin, not the old fixed radius. A line
        // is the one thing worth carrying past the edge — the canvas clips it, and dropping the
        // segment instead leaves the horizon stopping short of the screen edge with a visible gap.
        // What the bound is really guarding against is the far side of the sky, where a stereographic
        // projection runs away to infinity.
        val here = if (p.onScreen(viewport, HORIZON_MARGIN)) {
            Offset(cx + (p.x * half).toFloat(), cy + (p.y * half).toFloat())
        } else {
            null
        }
        if (previous != null && here != null) {
            drawLine(c.line, previous, here, strokeWidth = 1.5f * density)
        }
        previous = here
        az += HORIZON_STEP_DEG
    }

    paint.textSize = 12f * density
    listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (name, azimuth) ->
        val p = SkyProjection.project(azimuth, 0.0, view)
        if (!p.onScreen(viewport)) return@forEach
        paint.color = (if (name == "N") c.accent else c.muted).toArgb()
        drawContext.canvas.nativeCanvas.drawText(
            name,
            cx + (p.x * half).toFloat(),
            cy + (p.y * half).toFloat() + 18f * density,
            paint,
        )
    }
}

// ---- chrome ------------------------------------------------------------------------------------

@Composable
private fun Controls(
    view: SkyProjection.View,
    hours: Int,
    lines: SkyMapViewModel.LinesMode,
    c: NightwirePalette,
    vm: SkyMapViewModel,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(
            "Looking ${cardinal(view.azimuthDeg)} · ${view.altitudeDeg.roundToInt()}° up · " +
                "${view.fovDeg.roundToInt()}° across · ${whenLabel(hours)}",
            c.ink2, JetBrainsMono, 10,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (name, az) ->
                LcarsChip(name, selected = false, onClick = { vm.lookAt(az) })
            }
            LcarsChip("ZENITH", selected = false, onClick = { vm.lookAt(view.azimuthDeg, 85.0) })
            LcarsChip(
                linesLabel(lines),
                // Selected whenever anything is drawn, so the chip shows the state as well as the
                // next step — a control that only says what it will do leaves you guessing at what
                // is on when the sky is empty enough that you cannot tell by looking.
                selected = lines != SkyMapViewModel.LinesMode.NONE,
                onClick = vm::cycleLines,
            )
            LcarsChip("−", selected = false, onClick = { vm.zoom(1.0 / ZOOM_STEP) })
            LcarsChip("+", selected = false, onClick = { vm.zoom(ZOOM_STEP) })
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LcarsButton("−1H", onClick = { vm.setHourOffset(hours - 1) }, modifier = Modifier.weight(1f))
            LcarsButton("NOW", onClick = { vm.setHourOffset(0) }, modifier = Modifier.weight(1f))
            LcarsButton("+1H", onClick = { vm.setHourOffset(hours + 1) }, modifier = Modifier.weight(1f))
            LcarsButton("+6H", onClick = { vm.setHourOffset(hours + 6) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun IdentifyCard(
    body: SkyMapViewModel.Body,
    c: NightwirePalette,
    modifier: Modifier,
    onDismiss: () -> Unit,
) {
    LcarsFrame(modifier.fillMaxWidth().padding(12.dp)) {
        Column {
            Text(body.label ?: "Unnamed", c.ink, ChakraPetch, 17, bold = true)
            Text(body.detail, c.ink2, ChakraPetch, 12)
            Text(
                "${body.altitudeDeg.roundToInt()}° up · ${cardinal(body.azimuthDeg)} " +
                    "(${body.azimuthDeg.roundToInt()}°)" +
                    if (body.altitudeDeg < 0) " · below the horizon" else "",
                c.muted, JetBrainsMono, 10,
            )
            LcarsButton("CLOSE", onClick = onDismiss, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun Notice(text: String, c: NightwirePalette, modifier: Modifier = Modifier) {
    LcarsFrame(modifier.padding(24.dp)) {
        Text(text, c.ink2, ChakraPetch, 13)
    }
}

@Composable
private fun Text(
    text: String,
    colour: Color,
    family: androidx.compose.ui.text.font.FontFamily,
    size: Int,
    bold: Boolean = false,
) = androidx.compose.material3.Text(
    text,
    fontFamily = family,
    fontSize = size.sp,
    color = colour,
    fontWeight = if (bold) FontWeight.Bold else null,
)

/**
 * What the one lines control says.
 *
 * ⚠️ Names what is DRAWN, not what the tap will do. A chip reading "BORDERS" while showing figures
 * is the shape of control that has to be pressed to find out what it means.
 */
private fun linesLabel(mode: SkyMapViewModel.LinesMode): String = when (mode) {
    SkyMapViewModel.LinesMode.NONE -> "NO LINES"
    SkyMapViewModel.LinesMode.FIGURES -> "FIGURES"
    SkyMapViewModel.LinesMode.FIGURES_AND_BORDERS -> "+ BORDERS"
}

private fun whenLabel(hours: Int): String = when {
    hours == 0 -> "now"
    hours > 0 -> "+${hours}h"
    else -> "${hours}h"
}

private val CARDINALS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

private fun cardinal(azimuthDeg: Double): String {
    var d = azimuthDeg % 360.0
    if (d < 0) d += 360.0
    return CARDINALS[((d + 22.5) / 45.0).toInt() % 8]
}

/**
 * The horizon's own edge margin, far wider than [SkyRenderer.EDGE_MARGIN]: it is a line, so the
 * canvas can clip it for us, and carrying it well past the edge is what stops it stopping short of
 * the screen.
 */
private const val HORIZON_MARGIN = 1.0

/** Fine enough that the horizon reads as a smooth curve at any tilt. */
private const val HORIZON_STEP_DEG = 2.0

private const val ZOOM_STEP = 1.4

/**
 * How the three kinds of line are told apart.
 *
 * ⚠️ **By hue and weight rather than by dashes**, and the reason is the same one the radar screen
 * gives for glyph-and-brightness: a dashed line is drawn as many short segments, which multiplies
 * the count of an already large batch, and at a phone's line widths it reads as a rendering fault
 * rather than as a style.
 *
 * The figures are the point of the display, so they are the brightest; the borders are reference
 * furniture over the whole sky at once and would drown everything at the same weight.
 */
private const val FIGURE_WIDTH_DP = 1.1f
private const val FIGURE_ALPHA = 0.55f
private const val ASTERISM_WIDTH_DP = 1.0f
private const val ASTERISM_ALPHA = 0.34f
private const val BORDER_WIDTH_DP = 0.9f
private const val BORDER_ALPHA = 0.22f

/**
 * The equator and the ecliptic: thin, and fainter than anything they cross.
 *
 * ⚠️ They run right across the sky, so they are the one layer that CANNOT afford to be assertive —
 * a reference line brighter than the constellation it passes through stops being a reference and
 * starts being the picture. The ecliptic is given slightly more weight than the equator because it
 * is the one people actually use: it says where to look for a planet.
 */
private const val REFERENCE_WIDTH_DP = 0.8f
private const val EQUATOR_ALPHA = 0.18f
private const val ECLIPTIC_ALPHA = 0.26f

/**
 * How faint a Sun, Moon or planet goes once it has set.
 *
 * The same 0.3 the fixed markers always used. It is kept rather than removed because a body below
 * the horizon is still worth showing — knowing that Jupiter is under your feet rather than absent is
 * half of what a sky map is for — and dimming is how the map says so.
 */
private const val BELOW_HORIZON_BODY_ALPHA = 0.3f
