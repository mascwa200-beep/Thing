package dev.mascwa.pulse.feature.sky

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.DeepSky
import dev.mascwa.pulse.core.telemetry.PlanetDisc
import dev.mascwa.pulse.core.telemetry.SkyProjection
import dev.mascwa.pulse.core.telemetry.StarGlyph
import dev.mascwa.pulse.sky.LineBatch
import dev.mascwa.pulse.sky.MilkyWayGlow
import dev.mascwa.pulse.sky.SkyColors
import dev.mascwa.pulse.sky.SkyFrame
import dev.mascwa.pulse.sky.SkyLines
import dev.mascwa.pulse.sky.SkyRenderer
import dev.mascwa.pulse.sky.StarBatches
import dev.mascwa.pulse.sky.collectLines
import dev.mascwa.pulse.sky.collectStars
import dev.mascwa.pulse.sky.drawDeepSky
import dev.mascwa.pulse.sky.drawLineBatch
import dev.mascwa.pulse.sky.drawMilkyWay
import dev.mascwa.pulse.sky.drawSolarSystemBody
import dev.mascwa.pulse.sky.drawStarBatches
import dev.mascwa.pulse.sky.drawStarGlow
import dev.mascwa.pulse.sky.measurePixelsPerDegree
import dev.mascwa.pulse.sky.stepAlong
import kotlin.math.cos
import kotlin.math.sin

/**
 * The chart itself: the sky as a map you can move around in, and the whole of what two applications
 * share.
 *
 * ⚠️ **The one composable in this module, and it is here rather than in each application because the
 * canvas IS the engine.** Everything above it — a title bar, the controls, the identify card, a
 * notice when there is no location — is chrome, differs between the LCARS console and the standalone
 * star app, and stays with whichever one is drawing. Everything below it is arithmetic. This is the
 * seam, and the only thing crossing it in the other direction is [SkyColors].
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
 *
 * @param view the current aim and field. Passed in rather than collected here because every caller
 *   already holds it — the readout above the chart is built from the same value, and collecting it
 *   twice would be two subscriptions to one flow.
 * @param bodies the Sun, Moon and planets, in horizon coordinates.
 * @param colors every ink this draws with. See [SkyColors] for why it is thirteen roles.
 */
@Composable
fun SkyChart(
    view: SkyProjection.View,
    bodies: List<SkyMapViewModel.Body>,
    colors: SkyColors,
    vm: SkyMapViewModel,
    modifier: Modifier = Modifier,
) {
    // ⚠️ Read here rather than passed in, because the two arrays it selects are read in the DRAW
    // pass and are not Compose state. What re-runs the frame is `view`, which the pointing collector
    // rewrites on every sensor sample, so the arrays are always read at the same moment as the
    // angles they were computed alongside.
    val pointing by vm.pointing.collectAsStateWithLifecycle()
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
    // ⚠️ Remembered, like the star buckets, because it owns a Bitmap — see MilkyWayGlow. A fresh
    // one per frame would allocate and free a bitmap sixty times a second.
    val milkyWay = remember { MilkyWayGlow() }

    // ⚠️ Its OWN Paint, not the star one, and the skew is exactly why. Oblique is how every printed
    // chart tells a nebula's name from a star's, but `labelPaint` is shared with the star labels and
    // the planet labels, and neither of those resets a skew it never set — so borrowing it would
    // silently tilt every name on the map.
    val deepSkyPaint = remember {
        Paint().apply { isAntiAlias = true; textAlign = Paint.Align.LEFT; textSkewX = -0.22f }
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

    ReleaseTheSensorWhenNobodyIsLooking(vm)

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
        modifier
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
                        val dx = -pan.x / half * perUnit
                        // ⚠️ `vm.pointing.value`, NOT the `pointing` collected above. A
                        // `pointerInput(Unit)` block is started once and never restarted, so it
                        // would capture whatever the flag was at first composition — false — and
                        // this branch could never be taken. Reading the flow is what makes it
                        // current, and it is what the line above already does for the view.
                        if (vm.pointing.value) {
                            // ⚠️ **Nudge-to-align, and it exists because a phone magnetometer is
                            // good to a few degrees at best.** Drag until a star you can actually
                            // see sits where the map draws it. The same sign as panning, so the
                            // gesture feels identical whether it is moving the chart or correcting
                            // the aim; the vertical component is dropped, because the altitude comes
                            // from gravity, which is not in doubt.
                            vm.nudge(dx)
                        } else {
                            vm.pan(dx, pan.y / half * perUnit)
                        }
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
        drawRect(colors.space)
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
        // ⚠️ Two ways to build one frame, and the pointed one is not merely `of` with a roll. `of`
        // crosses the look direction with the observer's ZENITH, which is the zero vector when the
        // two coincide — aim the handset straight up and the map would draw nothing. `ofPointing`
        // crosses it with the screen's own up, which is perpendicular by construction.
        val frame = when {
            here == null -> null
            pointing -> SkyFrame.ofPointing(
                vm.pointForward, vm.pointUp, view.fovDeg, here.latitude, here.longitude, at,
            )
            else -> SkyFrame.of(view, here.latitude, here.longitude, at)
        }

        // ⚠️ **First, before even the horizon.** It is unresolved starlight, so every star, every
        // galaxy and every constellation line on this map is in front of it — and so is the chrome.
        // Drawn last it would be a haze OVER the sky instead of the sky's own background.
        if (frame != null) {
            vm.milkyWay?.let { drawMilkyWay(it, frame, view, milkyWay, colors.starlight) }
        }

        drawHorizon(view, colors, half, cx, cy, viewport, cardinalPaint)

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
                    lineBatch, linePaint, colors.equator, REFERENCE_WIDTH_DP, EQUATOR_ALPHA,
                )
                drawSkyLineSet(
                    vm.eclipticLine, frame, viewport, coneCos, coneSin, half, cx, cy,
                    lineBatch, linePaint, colors.ecliptic, REFERENCE_WIDTH_DP, ECLIPTIC_ALPHA,
                )

                if (shapes != null) {
                    if (linesMode == SkyMapViewModel.LinesMode.FIGURES_AND_BORDERS) {
                        drawSkyLineSet(
                            shapes.boundaries, frame, viewport, coneCos, coneSin, half, cx, cy,
                            lineBatch, linePaint, colors.border, BORDER_WIDTH_DP, BORDER_ALPHA,
                        )
                    }
                    drawSkyLineSet(
                        shapes.asterisms, frame, viewport, coneCos, coneSin, half, cx, cy,
                        lineBatch, linePaint, colors.asterism, ASTERISM_WIDTH_DP, ASTERISM_ALPHA,
                    )
                    drawSkyLineSet(
                        shapes.figures, frame, viewport, coneCos, coneSin, half, cx, cy,
                        lineBatch, linePaint, colors.figure, FIGURE_WIDTH_DP, FIGURE_ALPHA,
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
                deepSkyPaint.color = colors.label.toArgb()
                deepSkyPaint.textSize = 9f * density
                drawDeepSky(
                    deepSkyLayer, frame, viewport, DeepSky.magnitudeLimit(view.fovDeg),
                    view.fovDeg, half, cx, cy, colors.deepSky,
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
            drawStarBatches(below, starPaint, colors.starlight, SkyRenderer.BELOW_HORIZON_ALPHA)
            drawStarBatches(above, starPaint, colors.starlight)

            drawStarGlow(vm.brightStars, frame, viewport, limit, half, cx, cy, colors.starlight)
            if (deep != null) {
                drawStarGlow(deep, frame, viewport, limit, half, cx, cy, colors.starlight)
            }

            // ⚠️ The bright layer only, because the deep one has no names at all — it is Gaia source
            // identifiers, which nobody has ever called anything.
            drawStarLabels(vm, frame, viewport, limit, half, cx, cy, colors.label, labelPaint)
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
                SkyMapViewModel.Kind.SUN -> colors.sun
                SkyMapViewModel.Kind.MOON -> colors.moon
                else -> colors.planet
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
                shadow = colors.space,
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
 * Stand the rotation-vector sensor down while this chart is not being looked at.
 *
 * ⚠️ **Without this, FOLLOW keeps a fused sensor running at `SENSOR_DELAY_GAME` — fifty samples a
 * second — for as long as the process lives, with the phone in a pocket.** [SkyMapViewModel.onCleared]
 * stops it, but a view model is NOT cleared by an application being backgrounded, so the drain is
 * indefinite and invisible. Both applications have the shape, which is why the fix is here in the
 * one composable they share rather than in either screen.
 *
 * ⚠️ **Two different routes out, answered differently on purpose.**
 *  - ON_STOP is "I will be right back on this screen": the mode is REMEMBERED and restored on
 *    ON_START, so returning re-arms the sensor and the map snaps to where the phone is now aimed.
 *    That snap is correct rather than a compromise — [SkyMapViewModel] takes the first reading after
 *    a restart whole rather than blending it, precisely so a stale aim is never shown.
 *  - Leaving composition is "I have left the map", which in the LCARS console means pushing another
 *    destination while this one stays in the back stack, view model and all. The sensor is stopped
 *    and the mode is NOT remembered: coming back to a map that silently resumed aiming would be a
 *    control acting without being pressed. The standalone application has no navigation, so only the
 *    first route exists there.
 *
 * ⚠️ `rememberSaveable`, not `remember`. A rotation destroys the activity — ON_STOP fires, the wish
 * is recorded, then the composition is discarded — while the view model survives. Plain `remember`
 * would lose the wish on exactly the transition where the sensor is guaranteed to have been stopped,
 * so FOLLOW would switch itself off every time the handset was turned.
 *
 * ⚠️ Registering an observer replays the lifecycle up to its current state, so ON_START arrives
 * immediately on first composition — harmless, because the wish starts false.
 *
 * ⚠️ **[SkyMapViewModel.applyPointing], never `setPointing`, and getting that wrong would silently
 * retire the map's own default.** `setPointing` records the answer so it survives a launch; every
 * call below is the screen going away or coming back, which is not a decision about what should
 * happen next time. Through the recording path, backgrounding the app or pushing any other
 * destination would write "not following" — so the first time somebody left the map, opening
 * horizon-locked would stop happening for good, on a path that compiles and looks correct.
 */
@Composable
private fun ReleaseTheSensorWhenNobodyIsLooking(vm: SkyMapViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumeFollowing by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    resumeFollowing = vm.pointing.value
                    vm.applyPointing(false)
                }
                Lifecycle.Event.ON_START -> {
                    if (resumeFollowing) {
                        resumeFollowing = false
                        vm.applyPointing(true)
                    }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // ⚠️ Not remembered — see above. And harmless after ON_STOP, which has already set it
            // false, so the two paths cannot fight over one flag.
            vm.applyPointing(false)
        }
    }
}

/**
 * One set of lines — the figures, the borders, the asterisms, or a reference circle.
 *
 * ⚠️ One shared buffer refilled between sets rather than four. They are drawn one after another and
 * each `drawLines` copies what it needs, so the buffer is free the moment the call returns.
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
    batch.reset()
    collectLines(lines, frame, viewport, coneCos, coneSin, halfPx, centreX, centreY, batch)
    drawLineBatch(batch, paint, colour, widthDp.dp.toPx(), alpha)
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
private fun DrawScope.drawStarLabels(
    vm: SkyMapViewModel,
    frame: SkyFrame,
    viewport: SkyProjection.Viewport,
    limit: Double,
    halfPx: Float,
    centreX: Float,
    centreY: Float,
    colour: Color,
    paint: Paint,
) {
    val layer = vm.brightStars
    paint.color = colour.toArgb()
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
    colors: SkyColors,
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
            drawLine(colors.horizon, previous, here, strokeWidth = 1.5f * density)
        }
        previous = here
        az += HORIZON_STEP_DEG
    }

    paint.textSize = 12f * density
    listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (name, azimuth) ->
        val p = SkyProjection.project(azimuth, 0.0, view)
        if (!p.onScreen(viewport)) return@forEach
        paint.color = (if (name == "N") colors.north else colors.label).toArgb()
        drawContext.canvas.nativeCanvas.drawText(
            name,
            cx + (p.x * half).toFloat(),
            cy + (p.y * half).toFloat() + 18f * density,
            paint,
        )
    }
}

/**
 * The horizon's own edge margin, far wider than [SkyRenderer.EDGE_MARGIN]: it is a line, so the
 * canvas can clip it for us, and carrying it well past the edge is what stops it stopping short of
 * the screen.
 */
private const val HORIZON_MARGIN = 1.0

/** Fine enough that the horizon reads as a smooth curve at any tilt. */
private const val HORIZON_STEP_DEG = 2.0

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
 *
 * ⚠️ Weights and alphas, not colours: how assertive a line is belongs to the chart, because it
 * follows from what the line MEANS, and that does not change between one application and another.
 * The hues do change, which is why they arrive in [SkyColors] instead.
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
