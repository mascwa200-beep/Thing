package dev.mascwa.sky

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.SkyBudget
import dev.mascwa.pulse.core.telemetry.SkyProjection
import dev.mascwa.pulse.feature.sky.SkyChart
import dev.mascwa.pulse.feature.sky.SkyMapViewModel
import dev.mascwa.pulse.sky.SkyCardText
import dev.mascwa.pulse.sky.SkyClock
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * The whole screen: the sky, and the least chrome that can drive it.
 *
 * ⚠️ **No top bar, deliberately.** A title bar would take sixty-four density-independent pixels of
 * sky to say the name of an application with one screen in it. What is left is the chart, a readout
 * of where it is looking, and two rows of controls.
 *
 * @param container this application's whole dependency bundle. ⚠️ Taken whole rather than as four
 *   parameters, matching the standalone nutrition app's `NutritionApp(vm, container)`: everything
 *   here beyond the map is the updater, the token and the fault reporter, and threading each of
 *   them separately through [Controls] would be four arguments that always travel together.
 */
@Composable
fun StarMapScreen(vm: SkyMapViewModel, container: SkyContainer) {
    val view by vm.view.collectAsStateWithLifecycle()
    val bodies by vm.bodies.collectAsStateWithLifecycle()
    val site by vm.site.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val missing by vm.catalogueMissing.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val atmosphere by vm.atmosphere.collectAsStateWithLifecycle()

    // ⚠️ Whether this phone has a rotation-vector sensor at all, read from the hardware rather than
    // guessed from a silent FOLLOW control: a chip that does nothing when pressed and a chip that
    // says the hardware is absent are very different things, and only one of them is honest.
    //
    // ⚠️ Asked of the view model rather than of the hardware directly, even though the answer comes
    // from the same place. The view model refuses to follow without it, so a screen reading its own
    // copy could disagree with the object doing the refusing — and the LCARS map, which had no such
    // check at all, now reads the same property.
    val hasAttitudeSensor = vm.hasAttitudeSensor

    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) {
        AboutSheet(
            container.updates,
            container.settings,
            container.crashUploader,
            onDismiss = { showAbout = false },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SkyChart(view, bodies, skyColours(), vm)
            when {
                site == null && !loading -> LocationNotice(vm, Modifier.align(Alignment.Center))
                missing -> Notice(
                    "The bundled star catalogue could not be read, so only the Sun, Moon and " +
                        "planets are drawn. This is a fault in the build, not something you did.",
                    Modifier.align(Alignment.Center),
                )
                loading -> Notice("Placing the stars…", Modifier.align(Alignment.Center))
            }
            selected?.let { body ->
                // The card describes the DRAWN sky: a body's altitude is held true, and the chart
                // lifts it by the air before drawing, so the card lifts it the same way. Read from
                // the collected switch so a press of the chip re-labels an open card.
                val drawnAlt = vm.apparentAltitudeDeg(body.altitudeDeg, atmosphere)
                // The wording is the view model's, shared with the LCARS card; remembered because
                // the bodies flow re-emits twice a second and the lines only change on a new tap
                // or once the tap's rise-and-set enrichment lands.
                val lines = remember(body, drawnAlt) {
                    vm.cardLines(body, drawnAlt, TimeZone.getDefault())
                }
                IdentifyCard(body, lines, Modifier.align(Alignment.BottomCenter), vm::clearSelection)
            }
        }
        Controls(view, vm, hasAttitudeSensor, onAbout = { showAbout = true })
    }
}

/**
 * Why there is no sky yet, told apart.
 *
 * ⚠️ **Two causes wearing one symptom, which is the shape this repository keeps finding.** `site`
 * is null both when the location permission was never granted and when it was granted but this
 * phone holds no recent fix — and the second is common on a handset that has not opened anything
 * location-aware since it was last restarted. One message covering both would send somebody to a
 * permission screen where the switch is already on, and leave the actual remedy unsaid.
 */
@Composable
private fun LocationNotice(vm: SkyMapViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasLocationPermission(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        // ⚠️ Only on a grant. Re-running the load after a refusal would return null again for the
        // same reason, replacing this notice with an identical one and teaching nothing.
        if (ok) vm.refresh()
    }

    Card(modifier.padding(24.dp), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp)) {
            if (!granted) {
                Text(
                    "The map needs to know where you are — the sky over one place is not the sky " +
                        "over another.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "An approximate position is enough: a kilometre of error moves the sky by " +
                        "under a hundredth of a degree, so this asks only for the coarse one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(
                    onClick = { ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("ALLOW LOCATION") }
            } else {
                Text(
                    "Location is allowed, but this phone is not holding a recent position.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "It reads the last fix the system already has rather than switching the radio " +
                        "on for one. Opening a maps app once is usually enough to produce one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(
                    onClick = vm::refresh,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("LOOK AGAIN") }
            }
        }
    }
}

@Composable
private fun Notice(text: String, modifier: Modifier) {
    Card(modifier.padding(24.dp)) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * What was tapped, and everything the map knows about it.
 *
 * @param lines the view model's [SkyMapViewModel.cardLines] — what it is, where it is drawn, its
 *   coordinates in both frames, its distance and size, and when it rises and sets today. The first
 *   is the body's own description when it has one, and is set a size larger for that reason; the
 *   rest are figures, read in the caption style.
 */
@Composable
private fun IdentifyCard(
    body: SkyMapViewModel.Body,
    lines: List<String>,
    modifier: Modifier,
    onDismiss: () -> Unit,
) {
    Card(modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(body.label ?: "Unnamed", style = MaterialTheme.typography.titleMedium)
            val described = body.detail.isNotBlank()
            lines.forEachIndexed { i, line ->
                if (i == 0 && described) {
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 4.dp)) {
                Text("CLOSE")
            }
        }
    }
}

@Composable
private fun Controls(
    view: SkyProjection.View,
    vm: SkyMapViewModel,
    hasAttitudeSensor: Boolean,
    onAbout: () -> Unit,
) {
    val offset by vm.offsetMs.collectAsStateWithLifecycle()
    val at by vm.instant.collectAsStateWithLifecycle()
    val lines by vm.linesMode.collectAsStateWithLifecycle()
    val grid by vm.gridMode.collectAsStateWithLifecycle()
    val ground by vm.ground.collectAsStateWithLifecycle()
    val pointing by vm.pointing.collectAsStateWithLifecycle()
    val needsCalibration by vm.needsCalibration.collectAsStateWithLifecycle()
    val trim by vm.trimDeg.collectAsStateWithLifecycle()
    val deepNote by vm.deepNote.collectAsStateWithLifecycle()
    val deepest by vm.deepestMagnitude.collectAsStateWithLifecycle()
    val atmosphere by vm.atmosphere.collectAsStateWithLifecycle()
    // The phone's zone, read each composition rather than remembered: it is a single call, and a
    // zone changed in the system settings while this screen is open should be honoured on the next
    // frame rather than on the next launch.
    val zone = TimeZone.getDefault()

    var picking by remember { mutableStateOf(false) }
    if (picking) {
        DateTimeDialog(
            initial = at,
            zone = zone,
            onPick = { vm.setInstant(it); picking = false },
            onDismiss = { picking = false },
        )
    }

    // ⚠️ **Only while following, and that is the whole justification.** Somebody holding the phone
    // up at the sky is not touching the screen, so the display blanks after whatever the system
    // timeout is — in the one mode where the map is being read continuously. Dragging is touching,
    // which already resets the timer, so a flag held all the time would be spending battery to fix
    // a problem that does not exist there.
    val root = LocalView.current
    DisposableEffect(pointing) {
        root.keepScreenOn = pointing
        onDispose { root.keepScreenOn = false }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        // ⚠️ WHEN is the absolute local date-time once scrubbed, not only the offset: the offset says
        // how far you have moved and the date says what the picture is OF, and after a few presses
        // of +1D the second is the one you have lost track of.
        Text(
            "Looking ${SkyCardText.cardinal(view.azimuthDeg)} · ${view.altitudeDeg.roundToInt()}° up · " +
                "${SkyProjection.formatFieldWidth(view.fovDeg)} across · " +
                SkyClock.whenLabel(at, offset, zone),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // ⚠️ A different fact from `deepNote` below, which is why it is its own line: that one says
        // the catalogue would not open, this one says you have zoomed past what is inside it. Null
        // over half the zoom range, which is the only reason it is worth reading when it appears.
        SkyProjection.depthNote(view.fovDeg, deepest)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // What the deep catalogue actually managed — how many stars are on screen, or why none are.
        // ⚠️ Surfaced rather than swallowed: a map drawing eight thousand stars and a map drawing
        // three million look similar at a wide field, and only one of them has the deep tier open.
        deepNote?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // ⚠️ Said rather than implied. A phone magnetometer is disturbed by whatever steel and
        // current happens to be nearby, and the sensor reports when it has stopped trusting its own
        // answer — a map pointing quietly somewhere wrong is worse than one that admits it.
        if (pointing && needsCalibration) {
            Text(
                "Compass unsure — sweep the phone in a figure of eight",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // ⚠️ Signed and to a tenth. The trim is kept in [0, 360) because that is the range an azimuth
        // lives in, so a three-degree nudge to the west reads as 357 — true and useless; and rounding
        // to whole degrees prints "Nudged 0°" for any correction under half a degree, which one drag
        // of ten pixels produces.
        if (pointing && trim != 0.0) {
            val signed = if (trim > 180.0) trim - 360.0 else trim
            Text(
                "Nudged ${"%.1f".format(signed)}° off the compass",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // ⚠️ What this phone is doing differently, or nothing at all on a device with room. The
        // tiering is otherwise entirely invisible — a map that quietly draws a softer Milky Way and
        // follows at fifteen frames a second reads as the app being worse rather than as it being
        // adapted, which is a bad trade for one line of text.
        SkyBudget.describe(vm.budget)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!hasAttitudeSensor) {
            Text(
                "This phone has no rotation-vector sensor, so the map cannot follow where it is " +
                    "pointed. Everything else works: drag to look around.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (name, az) ->
                AssistChip(onClick = { vm.lookAt(az) }, label = { Text(name) })
            }
            AssistChip(
                onClick = { vm.lookAt(view.azimuthDeg, 85.0) },
                label = { Text("ZENITH") },
            )
            FilterChip(
                selected = pointing,
                onClick = { vm.setPointing(!pointing) },
                label = { Text(if (pointing) "FOLLOWING" else "FOLLOW") },
                enabled = hasAttitudeSensor,
            )
            if (pointing && trim != 0.0) {
                AssistChip(onClick = vm::clearTrim, label = { Text("UNDO NUDGE") })
            }
            FilterChip(
                // Selected whenever anything is drawn, so the chip shows the state as well as the
                // next step — a control that only says what it will do leaves you guessing at what
                // is on when the sky is empty enough that you cannot tell by looking.
                selected = lines != SkyMapViewModel.LinesMode.NONE,
                onClick = vm::cycleLines,
                label = { Text(linesLabel(lines)) },
            )
            // The grids answer a different question from the figures — what the sky is measured
            // against rather than what it is made of — so they are a second control, not two more
            // stops on the lines one.
            FilterChip(
                selected = grid != SkyMapViewModel.GridMode.NONE,
                onClick = vm::cycleGrid,
                label = { Text(gridLabel(grid)) },
            )
            // The air: refraction lifts everything near the horizon by up to half a degree, and
            // Stellarium draws it that way by default. Off is the geometric sky, for the person who
            // wants to compare against a catalogue rather than against the window.
            FilterChip(
                selected = atmosphere,
                onClick = { vm.setAtmosphere(!atmosphere) },
                label = { Text("ATMOSPHERE") },
            )
            // The ground: an opaque fill over the sky under your feet. Off by default, because this
            // map's own habit is to draw that sky dimmed rather than hide it.
            FilterChip(
                selected = ground,
                onClick = { vm.setGround(!ground) },
                label = { Text("GROUND") },
            )
            AssistChip(onClick = { vm.zoom(1.0 / ZOOM_STEP) }, label = { Text("−") })
            AssistChip(onClick = { vm.zoom(ZOOM_STEP) }, label = { Text("+") })
            // ⚠️ Last in the row on purpose. This is a scrolling strip and the chips before it are
            // used while looking at the sky; the build number and the token are opened about twice
            // in the life of an install, so it takes the position that has to be scrolled to.
            AssistChip(onClick = onAbout, label = { Text("ABOUT") })
        }
        // ⚠️ Four to a row, not eight to one: at 360 dp a slot is 79 dp, and eight would be 40 —
        // narrower than "−10M" in this typeface once the button's own padding is taken off, so the
        // labels would clip. The backward steps share a row with NOW and the forward ones with the
        // picker, which keeps each row reading in one direction.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TimeButton("−1D", Modifier.weight(1f)) { vm.nudgeOffset(-SkyClock.DAY_MS) }
            TimeButton("−1H", Modifier.weight(1f)) { vm.nudgeOffset(-SkyClock.HOUR_MS) }
            TimeButton("−10M", Modifier.weight(1f)) { vm.nudgeOffset(-10 * SkyClock.MINUTE_MS) }
            TimeButton("NOW", Modifier.weight(1f)) { vm.resetOffset() }
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TimeButton("+10M", Modifier.weight(1f)) { vm.nudgeOffset(10 * SkyClock.MINUTE_MS) }
            TimeButton("+1H", Modifier.weight(1f)) { vm.nudgeOffset(SkyClock.HOUR_MS) }
            TimeButton("+1D", Modifier.weight(1f)) { vm.nudgeOffset(SkyClock.DAY_MS) }
            TimeButton("DATE", Modifier.weight(1f)) { picking = true }
        }
    }
}

/** One of the eight time controls: a text button with its padding pulled in so four fit a row. */
@Composable
private fun TimeButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    TextButton(onClick, modifier, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(text) }
}

/**
 * Pick a date, then a time, in this phone's zone.
 *
 * Two steps because Material has a date picker and a time picker and no combined one; the date
 * comes first because it is the half more likely to be what somebody came here to change. Both
 * pickers open on the instant the map is already drawing, so a person who only wants to move the
 * time does not first have to find today.
 *
 * ⚠️ The date picker's answer is UTC midnight of the chosen calendar day — see
 * [SkyClock.pickerDateOf] — so it is read back through [SkyClock.pickerFields] and joined to the
 * time in [zone] by [SkyClock.localInstant], never added to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeDialog(
    initial: Long,
    zone: TimeZone,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val fields = remember(initial) { SkyClock.localFields(initial, zone) }
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = SkyClock.pickerDateOf(initial, zone),
        yearRange = SkyClock.MIN_YEAR..SkyClock.MAX_YEAR,
    )
    val timeState = rememberTimePickerState(
        initialHour = fields[3],
        initialMinute = fields[4],
        is24Hour = true,
    )
    var choosingTime by remember { mutableStateOf(false) }

    if (!choosingTime) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = { choosingTime = true },
                    enabled = dateState.selectedDateMillis != null,
                ) { Text("NEXT · TIME") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
        ) {
            DatePicker(state = dateState)
        }
    } else {
        BasicAlertDialog(onDismissRequest = onDismiss) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.padding(24.dp)) {
                    Text("Time", style = MaterialTheme.typography.labelLarge)
                    TimePicker(state = timeState, modifier = Modifier.padding(top = 16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { choosingTime = false }) { Text("BACK") }
                        TextButton(onClick = {
                            dateState.selectedDateMillis?.let { day ->
                                val d = SkyClock.pickerFields(day)
                                onPick(
                                    SkyClock.localInstant(
                                        d[0], d[1], d[2], timeState.hour, timeState.minute, zone,
                                    ),
                                )
                            }
                        }) { Text("DRAW") }
                    }
                }
            }
        }
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

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
