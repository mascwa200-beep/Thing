package dev.mascwa.pulse.feature.compass

import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import dev.mascwa.pulse.feature.common.LcarsIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.NavGuidance
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun CompassScreen(vm: CompassViewModel, onBack: (() -> Unit)? = null) {
    val reading by vm.reading.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val needsPermission by vm.needsPermission.collectAsStateWithLifecycle()
    val activeWp by vm.activeWaypoint.collectAsStateWithLifecycle()
    val c = Pulse.colors

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> vm.onPermissionResult(result.values.any { it }) }

    // Start/stop the sensor with the screen lifecycle.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.start()
                Lifecycle.Event.ON_PAUSE -> vm.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        vm.start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.stop()
        }
    }

    PulseScaffold(
        title = "Compass",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(LcarsIcons.ArrowBack, "Back")
            }
        },
    ) { innerPadding ->
        Column(
            Modifier.padding(innerPadding).padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val animated by animateFloatAsState(reading.trueAzimuth, label = "azimuth")
            val loc = location
            val wp = activeWp
            val wpBearing: Float? = if (loc != null && wp != null)
                Geo.bearingDegrees(loc.latitude, loc.longitude, wp.latitude, wp.longitude).toFloat() else null
            val wpDistance: Double? = if (loc != null && wp != null)
                Geo.distanceMeters(loc.latitude, loc.longitude, wp.latitude, wp.longitude) else null
            val sunAz: Float? = remember(loc) {
                if (loc != null) SunCalc.azimuth(loc.latitude, loc.longitude).toFloat() else null
            }
            val moonAz: Float? = remember(loc) {
                if (loc != null) MoonCalc.azimuth(loc.latitude, loc.longitude).toFloat() else null
            }

            Box(Modifier.fillMaxWidth(0.86f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                CompassDial(rotationDeg = -animated, waypointBearing = wpBearing, sunAz = sunAz, moonAz = moonAz)
                // Center read-out (fixed).
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${reading.trueAzimuth.roundToInt() % 360}°",
                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 40.sp, color = c.ink,
                    )
                    Text(Geo.cardinal(reading.trueAzimuth.toDouble()), fontFamily = JetBrainsMono,
                        fontSize = 14.sp, letterSpacing = 2.sp, color = c.accent)
                    Text("TRUE NORTH", fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 1.sp, color = c.muted)
                }
                // Fixed top pointer.
                Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    val p = Path().apply {
                        moveTo(size.width / 2 - 12, 6f); lineTo(size.width / 2 + 12, 6f)
                        lineTo(size.width / 2, 34f); close()
                    }
                    drawPath(p, c.magenta)
                }
            }

            if (wp != null && wpBearing != null && wpDistance != null) {
                NeonPanel(Modifier.fillMaxWidth(), borderColor = c.magenta) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("WAYPOINT", fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 1.sp, color = c.magenta)
                        Text(wp.label, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.ink)
                        Row2("Distance", Geo.formatDistance(wpDistance))
                        Row2("Bearing", "${Geo.cardinal(wpBearing.toDouble())} ${wpBearing.roundToInt()}°")
                        NavGuidance.turnHint(wpBearing.toDouble(), reading.trueAzimuth.toDouble())?.let { Row2("Turn", it) }
                    }
                }
            }

            if (!reading.hasSensor) {
                NeonPanel(Modifier.fillMaxWidth()) {
                    Text("No compass sensor detected on this device.",
                        style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                }
            }
            if (reading.accuracyLow) {
                NeonPanel(Modifier.fillMaxWidth(), borderColor = c.amber) {
                    Text("Low accuracy — wave the phone in a figure-8 to calibrate.",
                        style = MaterialTheme.typography.bodyMedium, color = c.amber)
                }
            }

            NeonPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row2("Magnetic", "${reading.magneticAzimuth.roundToInt() % 360}° ${Geo.cardinal(reading.magneticAzimuth.toDouble())}")
                    Row2("Declination", "${"%+.1f".format(reading.declination)}° (${if (reading.declination >= 0) "E" else "W"})")
                    sunAz?.let { Row2("Sun", "${Geo.cardinal(it.toDouble())} ${it.roundToInt()}°") }
                    moonAz?.let { Row2("Moon", "${Geo.cardinal(it.toDouble())} ${it.roundToInt()}°") }
                    if (loc != null) {
                        Row2("Latitude", "%.5f".format(loc.latitude))
                        Row2("Longitude", "%.5f".format(loc.longitude))
                        Row2("Location", loc.name)
                    } else if (needsPermission) {
                        Text(
                            "Grant location to compute true north (declination) and your coordinates.",
                            style = MaterialTheme.typography.bodyMedium, color = c.muted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            "GRANT LOCATION",
                            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.accent,
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
                                .clickable {
                                    permLauncher.launch(arrayOf(
                                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                    ))
                                },
                        )
                    }
                }
            }
            Text(
                "Declination uses the offline World Magnetic Model — works with no signal.",
                style = MaterialTheme.typography.labelSmall, color = c.muted,
            )
        }
    }
}

@Composable
private fun Row2(label: String, value: String) {
    val c = Pulse.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = c.ink)
    }
}

@Composable
private fun CompassDial(rotationDeg: Float, waypointBearing: Float?, sunAz: Float?, moonAz: Float?) {
    val c = Pulse.colors
    val tickColor = c.line.toArgb()
    val inkColor = c.ink.toArgb()
    val northColor = c.magenta.toArgb()
    val accent = c.accent
    Canvas(Modifier.fillMaxWidth(0.92f).aspectRatio(1f).rotate(rotationDeg)) {
        val cx = size.width / 2
        val cy = size.height / 2
        val r = size.minDimension / 2 * 0.92f
        // Outer ring.
        drawCircle(c.line, radius = r, center = Offset(cx, cy), style = Stroke(2f))
        drawCircle(accent.copy(alpha = 0.5f), radius = r * 0.78f, center = Offset(cx, cy), style = Stroke(1f))
        // Ticks every 15°.
        for (deg in 0 until 360 step 15) {
            val rad = Math.toRadians(deg.toDouble())
            val major = deg % 90 == 0
            val inner = if (major) r * 0.80f else r * 0.88f
            val sx = cx + (inner * sin(rad)).toFloat()
            val sy = cy - (inner * cos(rad)).toFloat()
            val ex = cx + (r * sin(rad)).toFloat()
            val ey = cy - (r * cos(rad)).toFloat()
            drawLine(if (major) accent else c.lineSoft, Offset(sx, sy), Offset(ex, ey), strokeWidth = if (major) 3f else 1.5f)
        }
        // Cardinal letters via native canvas (rotate with the dial).
        val paint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = r * 0.16f
        }
        val labelR = r * 0.66f
        val cardinals = listOf("N" to 0, "E" to 90, "S" to 180, "W" to 270)
        drawContext.canvas.nativeCanvas.apply {
            cardinals.forEach { (label, deg) ->
                val rad = Math.toRadians(deg.toDouble())
                val x = cx + (labelR * sin(rad)).toFloat()
                val y = cy - (labelR * cos(rad)).toFloat() + paint.textSize / 3
                paint.color = if (label == "N") northColor else if (label == "E" || label == "S" || label == "W") inkColor else tickColor
                drawText(label, x, y, paint)
            }
        }

        // Sun marker — amber dot near the rim (rotates with the dial → its true azimuth).
        sunAz?.let { sa ->
            val rad = Math.toRadians(sa.toDouble())
            val mr = r * 0.90f
            drawCircle(c.amber, r * 0.045f, Offset(cx + (mr * sin(rad)).toFloat(), cy - (mr * cos(rad)).toFloat()))
        }
        // Moon marker — pale cool dot near the rim (cooler than the amber sun).
        moonAz?.let { ma ->
            val rad = Math.toRadians(ma.toDouble())
            val mr = r * 0.90f
            val center = Offset(cx + (mr * sin(rad)).toFloat(), cy - (mr * cos(rad)).toFloat())
            drawCircle(c.sky.copy(alpha = 0.35f), r * 0.05f, center)
            drawCircle(c.sky, r * 0.04f, center, style = Stroke(2f))
        }
        // Waypoint needle — magenta line + dot to the tracked waypoint.
        waypointBearing?.let { wb ->
            val rad = Math.toRadians(wb.toDouble())
            val ex = cx + (r * 0.9f * sin(rad)).toFloat()
            val ey = cy - (r * 0.9f * cos(rad)).toFloat()
            drawLine(c.magenta.copy(alpha = 0.7f), Offset(cx, cy), Offset(ex, ey), 2.5f)
            drawCircle(c.magenta, r * 0.05f, Offset(ex, ey))
        }
    }
}

