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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.mascwa.pulse.core.telemetry.SkyProjection
import dev.mascwa.pulse.feature.sky.SkyChart
import dev.mascwa.pulse.feature.sky.SkyMapViewModel
import kotlin.math.roundToInt

/**
 * The whole screen: the sky, and the least chrome that can drive it.
 *
 * ⚠️ **No top bar, deliberately.** A title bar would take sixty-four density-independent pixels of
 * sky to say the name of an application with one screen in it. What is left is the chart, a readout
 * of where it is looking, and two rows of controls.
 *
 * @param hasAttitudeSensor whether this phone has a rotation-vector sensor at all. ⚠️ Passed in
 *   rather than guessed from a silent FOLLOW control: a chip that does nothing when pressed and a
 *   chip that says the hardware is absent are very different things, and only one of them is honest.
 */
@Composable
fun StarMapScreen(vm: SkyMapViewModel, hasAttitudeSensor: Boolean) {
    val view by vm.view.collectAsStateWithLifecycle()
    val bodies by vm.bodies.collectAsStateWithLifecycle()
    val site by vm.site.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val missing by vm.catalogueMissing.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()

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
                IdentifyCard(body, Modifier.align(Alignment.BottomCenter), vm::clearSelection)
            }
        }
        Controls(view, vm, hasAttitudeSensor)
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

@Composable
private fun IdentifyCard(
    body: SkyMapViewModel.Body,
    modifier: Modifier,
    onDismiss: () -> Unit,
) {
    Card(modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(body.label ?: "Unnamed", style = MaterialTheme.typography.titleMedium)
            if (body.detail.isNotBlank()) {
                Text(body.detail, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "${body.altitudeDeg.roundToInt()}° up · ${cardinal(body.azimuthDeg)} " +
                    "(${body.azimuthDeg.roundToInt()}°)" +
                    if (body.altitudeDeg < 0) " · below the horizon" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
) {
    val hours by vm.hourOffset.collectAsStateWithLifecycle()
    val lines by vm.linesMode.collectAsStateWithLifecycle()
    val pointing by vm.pointing.collectAsStateWithLifecycle()
    val needsCalibration by vm.needsCalibration.collectAsStateWithLifecycle()
    val trim by vm.trimDeg.collectAsStateWithLifecycle()
    val deepNote by vm.deepNote.collectAsStateWithLifecycle()

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
        Text(
            "Looking ${cardinal(view.azimuthDeg)} · ${view.altitudeDeg.roundToInt()}° up · " +
                "${view.fovDeg.roundToInt()}° across · ${whenLabel(hours)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            AssistChip(onClick = { vm.zoom(1.0 / ZOOM_STEP) }, label = { Text("−") })
            AssistChip(onClick = { vm.zoom(ZOOM_STEP) }, label = { Text("+") })
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextButton({ vm.setHourOffset(hours - 1) }, Modifier.weight(1f)) { Text("−1H") }
            TextButton({ vm.setHourOffset(0) }, Modifier.weight(1f)) { Text("NOW") }
            TextButton({ vm.setHourOffset(hours + 1) }, Modifier.weight(1f)) { Text("+1H") }
            TextButton({ vm.setHourOffset(hours + 6) }, Modifier.weight(1f)) { Text("+6H") }
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

private const val ZOOM_STEP = 1.4
