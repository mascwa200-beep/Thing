package dev.mascwa.pulse.feature.sky

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.SkyBudget
import dev.mascwa.pulse.core.telemetry.SkyProjection
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsChip
import dev.mascwa.pulse.feature.common.LcarsField
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.sky.DeepSkyColors
import dev.mascwa.pulse.sky.SkyCardText
import dev.mascwa.pulse.sky.SkyClock
import dev.mascwa.pulse.sky.SkyColors
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * The sky as a map you can move around in — this application's chrome around the shared chart.
 *
 * ⚠️ **The chart itself is not here.** It lives in `:core:sky` as [SkyChart], because the canvas IS
 * the engine and two copies of a star renderer is the drift that module exists to prevent. What is
 * left in this file is everything an LCARS console wants around it and a standalone star app would
 * not: the scaffold, the controls rail, the identify card, and the one function that says which of
 * this palette's inks each layer of the sky is drawn with.
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
    val offset by vm.offsetMs.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val missing by vm.catalogueMissing.collectAsStateWithLifecycle()
    val lines by vm.linesMode.collectAsStateWithLifecycle()
    val atmosphere by vm.atmosphere.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SkyChart(view, bodies, skyColours(c), vm)
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
                // The card describes the DRAWN sky: a body's altitude is held true, and the chart
                // lifts it by the air before drawing, so the card lifts it the same way. Read from
                // the collected switch so a press of the chip re-labels an open card.
                val drawnAlt = vm.apparentAltitudeDeg(body.altitudeDeg, atmosphere)
                // The wording is the view model's, shared with the standalone map's card; remembered
                // because the bodies flow re-emits twice a second and the lines only change on a
                // new tap or once the tap's rise-and-set enrichment lands.
                val lines = remember(body, drawnAlt) {
                    vm.cardLines(body, drawnAlt, TimeZone.getDefault())
                }
                IdentifyCard(body, lines, c, Modifier.align(Alignment.BottomCenter), vm::clearSelection)
            }
        }
        Controls(view, offset, lines, c, vm)
    }
}

/**
 * Which of this palette's inks the chart draws each layer of the sky with.
 *
 * ⚠️ **The whole of what this application hands the renderer**, and the reason [SkyColors] names
 * roles rather than hues: five of them resolve to the same colour here, and a different application
 * is free to pull them apart. The mapping is the only place the LCARS palette and the star chart
 * meet, so a re-theme is one function rather than twenty-eight scattered reads.
 *
 * ⚠️ **`positive` and `negative` are deliberately absent.** The palette's own KDoc says they carry
 * meaning elsewhere — a market moving up or down — and must never be borrowed for decoration, so the
 * green and the red are not available here however well they would read.
 */
@Composable
private fun skyColours(c: NightwirePalette): SkyColors = remember(c) {
    SkyColors(
        space = c.void,
        // One ink for every star and for the unresolved starlight between them, which is what the
        // Milky Way is: the glow and the dots have to be the same colour or the diffuse layer reads
        // as a haze in front of the sky rather than as the sky's own faintest stars.
        starlight = c.ink,
        moon = c.ink,
        sun = c.amber,
        planet = c.sky,
        figure = c.sky,
        asterism = c.violet,
        equator = c.muted,
        // Amber, because this is the road the Sun walks — and every planet within a few degrees of
        // it, which is the whole reason the line is worth drawing.
        ecliptic = c.amber,
        border = c.muted,
        label = c.muted,
        horizon = c.line,
        north = c.accent,
        // A supernova remnant takes the nebula's colour because it IS one: a shell of glowing gas,
        // drawn with the same lobed glow, and eleven of them in the whole catalogue. DeepSkyColors
        // keeps the two apart so another application can answer differently, not because this one
        // must.
        deepSky = DeepSkyColors(
            galaxy = c.magenta, // 10,792 of the 12,579 — worth the one hue nothing else here uses
            cluster = c.amber, // warm, because a cluster is made of stars
            nebula = c.violet,
            planetary = c.sky, // teal, which is what doubly-ionised oxygen actually looks like
            remnant = c.violet,
            dark = c.faint, // the dimmest ink in the palette, for the place with less light in it
            other = c.muted,
        ),
        grid = c.faint, // ruled over the whole sky, so the dimmest ink there is
        galactic = c.violet,
        constellationName = c.ink2,
        // The panel colour, which is the one dark this palette has that is not the void: the ground
        // has to be told from the dimmed sky it covers, and on an OLED two near-blacks are one black.
        ground = c.raise,
    )
}

// ---- chrome ------------------------------------------------------------------------------------

@Composable
private fun Controls(
    view: SkyProjection.View,
    offset: Long,
    lines: SkyMapViewModel.LinesMode,
    c: NightwirePalette,
    vm: SkyMapViewModel,
) {
    val at by vm.instant.collectAsStateWithLifecycle()
    val grid by vm.gridMode.collectAsStateWithLifecycle()
    val ground by vm.ground.collectAsStateWithLifecycle()
    val pointing by vm.pointing.collectAsStateWithLifecycle()
    val needsCalibration by vm.needsCalibration.collectAsStateWithLifecycle()
    val trim by vm.trimDeg.collectAsStateWithLifecycle()
    val deepest by vm.deepestMagnitude.collectAsStateWithLifecycle()
    val deepNote by vm.deepNote.collectAsStateWithLifecycle()
    val atmosphere by vm.atmosphere.collectAsStateWithLifecycle()
    // The phone's zone, read each composition rather than remembered: a single call, and a zone
    // changed in the system settings while this screen is open is honoured on the next frame.
    val zone = TimeZone.getDefault()

    // The typed date. This console has no Material picker, so the same thing the standalone map
    // does with two dialogs is a field here: DATE opens it seeded with the instant being drawn,
    // DRAW (or the keyboard's action key) parses it in the phone's zone, and a line that is not a
    // date the map can draw is refused with the reason rather than rolled into the nearest one.
    var editing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var rejected by remember { mutableStateOf(false) }
    fun apply() {
        val t = SkyClock.parseLocal(typed, zone)
        if (t == null) {
            rejected = true
        } else {
            vm.setInstant(t)
            editing = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        // ⚠️ WHEN is the absolute local date-time once scrubbed, not only the offset: the offset
        // says how far you have moved and the date says what the picture is OF, and after a few
        // presses of +1D the second is the one you have lost track of.
        Text(
            "Looking ${SkyCardText.cardinal(view.azimuthDeg)} · ${view.altitudeDeg.roundToInt()}° up · " +
                "${SkyProjection.formatFieldWidth(view.fovDeg)} across · " +
                SkyClock.whenLabel(at, offset, zone),
            c.ink2, JetBrainsMono, 10,
        )
        // ⚠️ **This screen never showed the catalogue's own note at all** — the standalone app has
        // rendered it since it was written and this one did not, so the LCARS map could silently
        // fall back to the eight-thousand-star bright list with nothing on screen to say so. Same
        // gap, one line up, as the field width that read "0°".
        deepNote?.let { Text(it, c.ink2, JetBrainsMono, 10) }
        // ⚠️ A different fact from the note above, which is why it is a second line: that one says
        // the file would not open, this one says you have zoomed past what is inside it. Null over
        // half the zoom range, which is the only reason it is worth reading when it appears.
        SkyProjection.depthNote(view.fovDeg, deepest)?.let { Text(it, c.ink2, JetBrainsMono, 10) }
        // ⚠️ Said rather than implied. A phone magnetometer is disturbed by whatever steel and
        // current happens to be nearby, and the sensor itself reports when it has stopped trusting
        // its own answer — a map that quietly points somewhere wrong is worse than one that admits
        // it. Only while following: dragging does not consult the compass at all.
        if (pointing && needsCalibration) {
            Text(
                "Compass unsure — sweep the phone in a figure of eight",
                c.amber, JetBrainsMono, 10,
            )
        }
        // ⚠️ A standing correction has to be visible, or it is a map quietly pointing somewhere
        // other than where the sensor says with nothing on screen to explain the difference.
        //
        // ⚠️ **Shown SIGNED and to a tenth, and both of those are corrections to how I first wrote
        // it.** `SkyPointing.addTrim` keeps the offset in [0, 360) because that is the range an
        // azimuth lives in — so a three-degree nudge to the west reads as 357, which is true and
        // useless. And rounding to whole degrees printed "Nudged 0°" for any correction under half a
        // degree, which a single drag of ten pixels produces: a line insisting there is a correction
        // while reporting none.
        if (pointing && trim != 0.0) {
            val signed = if (trim > 180.0) trim - 360.0 else trim
            Text("Nudged ${"%.1f".format(signed)}° off the compass", c.ink2, JetBrainsMono, 10)
        }
        // ⚠️ What this phone is doing differently, or nothing at all on a device with room — see
        // the standalone star app for the same line and the same reason.
        SkyBudget.describe(vm.budget)?.let { Text(it, c.ink2, JetBrainsMono, 10) }
        // ⚠️ **This screen never said this at all, and the standalone star app has since it was
        // written.** A handset with no rotation-vector sensor could press FOLLOW here, get a chip
        // reading FOLLOWING over a listener that can never fire, and — because dragging is declined
        // while following — a sky nothing could turn. The view model refuses that outright now; this
        // line is what makes the refusal legible rather than a control that silently does nothing.
        if (!vm.hasAttitudeSensor) {
            Text(
                "This phone has no rotation-vector sensor, so the map cannot follow where it is " +
                    "pointed. Everything else works: drag to look around.",
                c.ink2, JetBrainsMono, 10,
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (name, az) ->
                LcarsChip(name, selected = false, onClick = { vm.lookAt(az) })
            }
            LcarsChip("ZENITH", selected = false, onClick = { vm.lookAt(view.azimuthDeg, 85.0) })
            LcarsChip(
                if (pointing) "FOLLOWING" else "FOLLOW",
                selected = pointing,
                onClick = { vm.setPointing(!pointing) },
                enabled = vm.hasAttitudeSensor,
            )
            // ⚠️ A chip rather than a tap on the caption above it. That line is ten point text, which
            // is a readout and not a touch target; the screen's own idiom for something you press is
            // this row. Shown only when there is a correction to undo.
            if (pointing && trim != 0.0) {
                LcarsChip("UNDO NUDGE", selected = false, onClick = vm::clearTrim)
            }
            LcarsChip(
                linesLabel(lines),
                // Selected whenever anything is drawn, so the chip shows the state as well as the
                // next step — a control that only says what it will do leaves you guessing at what
                // is on when the sky is empty enough that you cannot tell by looking.
                selected = lines != SkyMapViewModel.LinesMode.NONE,
                onClick = vm::cycleLines,
            )
            // The grids answer a different question from the figures — what the sky is measured
            // against rather than what it is made of — so they are a second control.
            LcarsChip(gridLabel(grid), selected = grid != SkyMapViewModel.GridMode.NONE, onClick = vm::cycleGrid)
            // The air: refraction lifts everything near the horizon by up to half a degree, and
            // Stellarium draws it that way by default. Off is the geometric sky.
            LcarsChip("ATMOSPHERE", selected = atmosphere, onClick = { vm.setAtmosphere(!atmosphere) })
            // The ground: an opaque fill over the sky under your feet. Off by default, because this
            // map's own habit is to draw that sky dimmed rather than hide it.
            LcarsChip("GROUND", selected = ground, onClick = { vm.setGround(!ground) })
            LcarsChip("−", selected = false, onClick = { vm.zoom(1.0 / ZOOM_STEP) })
            LcarsChip("+", selected = false, onClick = { vm.zoom(ZOOM_STEP) })
        }
        // ⚠️ Four to a row, not eight to one: at 360 dp a slot is 79 dp and eight would be 40 —
        // narrower than "−10M" at this typeface once the button's own 32 dp of padding is taken
        // off, so the labels would ellipsise to a dash. The backward steps share a row with NOW and
        // the forward ones with DATE, which keeps each row reading in one direction.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LcarsButton("−1D", onClick = { vm.nudgeOffset(-SkyClock.DAY_MS) }, modifier = Modifier.weight(1f))
            LcarsButton("−1H", onClick = { vm.nudgeOffset(-SkyClock.HOUR_MS) }, modifier = Modifier.weight(1f))
            LcarsButton("−10M", onClick = { vm.nudgeOffset(-10 * SkyClock.MINUTE_MS) }, modifier = Modifier.weight(1f))
            LcarsButton("NOW", onClick = vm::resetOffset, modifier = Modifier.weight(1f))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = if (editing) 6.dp else 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LcarsButton("+10M", onClick = { vm.nudgeOffset(10 * SkyClock.MINUTE_MS) }, modifier = Modifier.weight(1f))
            LcarsButton("+1H", onClick = { vm.nudgeOffset(SkyClock.HOUR_MS) }, modifier = Modifier.weight(1f))
            LcarsButton("+1D", onClick = { vm.nudgeOffset(SkyClock.DAY_MS) }, modifier = Modifier.weight(1f))
            LcarsButton(
                "DATE",
                onClick = {
                    // Seeded with the drawn instant on OPENING, never re-seeded while open: the
                    // clock ticks twice a second and a field that rewrote itself under the cursor
                    // could not be typed into.
                    if (!editing) typed = SkyClock.formatEntry(at, zone)
                    rejected = false
                    editing = !editing
                },
                modifier = Modifier.weight(1f),
            )
        }
        if (editing) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LcarsField(
                    value = typed,
                    onValueChange = { typed = it; rejected = false },
                    modifier = Modifier.weight(1f),
                    placeholder = "YYYY-MM-DD HH:MM",
                    imeAction = ImeAction.Go,
                    onImeAction = { apply() },
                )
                LcarsButton("DRAW", onClick = { apply() })
            }
            if (rejected) {
                Text(
                    "Not a date this map can draw (${SkyClock.MIN_YEAR}–${SkyClock.MAX_YEAR}, " +
                        "as YYYY-MM-DD HH:MM)",
                    c.amber, JetBrainsMono, 10,
                )
            }
        }
    }
}

/**
 * What was tapped, and everything the map knows about it.
 *
 * @param lines the view model's [SkyMapViewModel.cardLines] — what it is, where it is drawn, its
 *   coordinates in both frames, its distance and size, and when it rises and sets today. The first
 *   is the body's own description when it has one and keeps the display face; the rest are figures
 *   and take the monospaced caption, so a column of coordinates lines up.
 */
@Composable
private fun IdentifyCard(
    body: SkyMapViewModel.Body,
    lines: List<String>,
    c: NightwirePalette,
    modifier: Modifier,
    onDismiss: () -> Unit,
) {
    LcarsFrame(modifier.fillMaxWidth().padding(12.dp)) {
        Column {
            Text(body.label ?: "Unnamed", c.ink, ChakraPetch, 17, bold = true)
            val described = body.detail.isNotBlank()
            lines.forEachIndexed { i, line ->
                if (i == 0 && described) {
                    Text(line, c.ink2, ChakraPetch, 12)
                } else {
                    Text(line, c.muted, JetBrainsMono, 10)
                }
            }
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

/** What the grid control says — the grid that IS drawn, by the coordinates it is ruled in. */
private fun gridLabel(mode: SkyMapViewModel.GridMode): String = when (mode) {
    SkyMapViewModel.GridMode.NONE -> "NO GRID"
    SkyMapViewModel.GridMode.EQUATORIAL -> "RA/DEC GRID"
    SkyMapViewModel.GridMode.HORIZON -> "ALT/AZ GRID"
    SkyMapViewModel.GridMode.BOTH -> "BOTH GRIDS"
}

private const val ZOOM_STEP = 1.4
