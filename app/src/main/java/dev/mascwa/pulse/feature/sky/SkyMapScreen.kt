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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.SkyProjection
import dev.mascwa.pulse.core.telemetry.StarNames
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsChip
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The sky as a map you can move around in.
 *
 * ⚠️ **The whole thing is one Canvas, and nothing about it is a widget.** A star chart is thousands
 * of dots that move together; expressing them as composables would mean thousands of layout nodes
 * recomposing on every drag. Drawing is the right primitive here, which is also why panning costs
 * only a projection per visible star and no recomposition of anything else.
 *
 * ⚠️ **What is drawn is decided by the zoom, not by a fixed list.** [SkyProjection.magnitudeLimit]
 * deepens as the field narrows, so a wide view shows the shapes people navigate by and a narrow one
 * fills in. Nothing outside the field is drawn at all.
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
        Controls(view, hours, c, vm)
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

    Canvas(
        Modifier
            .fillMaxSize()
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
        fun place(az: Double, alt: Double) = SkyProjection.project(az, alt, view).let {
            it to Offset(cx + (it.x * half).toFloat(), cy + (it.y * half).toFloat())
        }

        drawHorizon(view, c, half, cx, cy, cardinalPaint)

        val limit = SkyProjection.magnitudeLimit(view.fovDeg)
        bodies.forEach { b ->
            if (b.kind == SkyMapViewModel.Kind.STAR && b.magnitude > limit) return@forEach
            val (p, at) = place(b.azimuthDeg, b.altitudeDeg)
            if (!p.inField) return@forEach
            val below = b.altitudeDeg < 0

            when (b.kind) {
                SkyMapViewModel.Kind.STAR -> {
                    val r = starRadiusPx(b.magnitude, limit) * density
                    drawCircle(
                        color = starColour(b.colourIndex, c).let { if (below) it.copy(alpha = 0.25f) else it },
                        radius = r,
                        center = at,
                    )
                    // Only the ones with room to be read, or the chart turns into a wall of text.
                    if (b.label != null && b.magnitude <= limit - LABEL_HEADROOM) {
                        labelPaint.color = c.muted.toArgb()
                        labelPaint.textSize = 9f * density
                        drawContext.canvas.nativeCanvas.drawText(
                            b.label, at.x + r + 3f * density, at.y + 3f * density, labelPaint,
                        )
                    }
                }
                else -> {
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
                    drawCircle(if (below) colour.copy(alpha = 0.3f) else colour, r, at)
                    drawCircle(c.void, r * 0.35f, at, style = Stroke(width = 1f))
                    b.label?.let {
                        labelPaint.color = colour.toArgb()
                        labelPaint.textSize = 11f * density
                        drawContext.canvas.nativeCanvas.drawText(
                            it, at.x + r + 4f * density, at.y + 4f * density, labelPaint,
                        )
                    }
                }
            }
        }
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
    paint: Paint,
) {
    var previous: Offset? = null
    var az = view.azimuthDeg - 180.0
    while (az <= view.azimuthDeg + 180.0) {
        val p = SkyProjection.project(az, 0.0, view)
        val here = if (p.visible) Offset(cx + (p.x * half).toFloat(), cy + (p.y * half).toFloat()) else null
        // ⚠️ Only join points that are BOTH in the field. Without the check a segment leaving the
        // view is drawn to a point far off screen and the horizon gains a spike across the chart.
        if (previous != null && here != null && p.radius < HORIZON_CLIP) {
            drawLine(c.line, previous, here, strokeWidth = 1.5f * density)
        }
        previous = if (p.visible && p.radius < HORIZON_CLIP) here else null
        az += HORIZON_STEP_DEG
    }

    paint.textSize = 12f * density
    listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (name, azimuth) ->
        val p = SkyProjection.project(azimuth, 0.0, view)
        if (!p.inField) return@forEach
        paint.color = (if (name == "N") c.accent else c.muted).toArgb()
        drawContext.canvas.nativeCanvas.drawText(
            name,
            cx + (p.x * half).toFloat(),
            cy + (p.y * half).toFloat() + 18f * density,
            paint,
        )
    }
}

/**
 * How big to draw a star.
 *
 * ⚠️ Magnitude is a logarithmic scale running BACKWARDS — smaller is brighter, and each step of one
 * is about two and a half times the light. Drawing radius proportional to magnitude would make
 * Sirius a speck and the faintest stars enormous. This is an exponential in the other direction,
 * measured against the current cut-off so the faintest thing on screen is always about a pixel and
 * the brightest always stands out.
 */
private fun starRadiusPx(magnitude: Double, limit: Double): Float {
    val steps = (limit - magnitude).coerceAtLeast(0.0)
    return (0.7 + 0.55 * steps.pow(1.15)).toFloat().coerceAtMost(7f)
}

/**
 * Star colour from its B-V index.
 *
 * ⚠️ Real, not decorative: B-V is a measurement of how much bluer a star is in one filter than
 * another, and it maps almost directly onto what the eye sees. Rigel at −0.03 is blue-white,
 * Betelgeuse at +1.85 is visibly orange, and the sky looks wrong without it. A star with no measured
 * colour is drawn white rather than guessed at.
 *
 * ⚠️ **The table moved to [StarNames.colourArgb] and this is now a two-line adapter.** The companion
 * draws the same bundled catalogue, so a second copy of six colour bands would be the drifted
 * duplicate this project has corrected six times. The null case stays here on purpose: "no measured
 * colour" resolves to the drawing surface's own ink, which is a palette fact and belongs to the
 * platform rather than to a module with no UI dependency.
 */
private fun starColour(bv: Double?, c: NightwirePalette): Color =
    StarNames.colourArgb(bv)?.let { Color(it) } ?: c.ink

// ---- chrome ------------------------------------------------------------------------------------

@Composable
private fun Controls(
    view: SkyProjection.View,
    hours: Int,
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

/** A star this much brighter than the cut-off gets its name drawn beside it. */
private const val LABEL_HEADROOM = 2.2

/** Beyond this radius the horizon polyline is leaving the view and its segments are dropped. */
private const val HORIZON_CLIP = 2.0

/** Fine enough that the horizon reads as a smooth curve at any tilt. */
private const val HORIZON_STEP_DEG = 2.0

private const val ZOOM_STEP = 1.4
