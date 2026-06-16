package dev.mascwa.pulse.feature.tacnet

import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.connectivity.LocalIsOnline
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.data.radar.Contact
import dev.mascwa.pulse.data.radar.ContactKind
import dev.mascwa.pulse.data.radar.RadarData
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.NeonChip
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun RadarScreen(vm: RadarViewModel, onBack: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val rangeKm by vm.rangeKm.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val needsPermission by vm.needsPermission.collectAsStateWithLifecycle()
    val online = LocalIsOnline.current
    val c = Pulse.colors

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> vm.onPermissionResult(result.values.any { it }) }

    // Auto-refresh while on screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.startAuto()
                Lifecycle.Event.ON_PAUSE -> vm.stopAuto()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        vm.startAuto()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.stopAuto()
        }
    }

    PulseScaffold(
        title = "Radar",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
        actions = {
            IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh", tint = c.accent) }
        },
    ) { innerPadding ->
        when {
            state.isInitialLoading -> LoadingState(Modifier.padding(innerPadding))
            needsPermission && state.data == null -> PermissionPanel(Modifier.padding(innerPadding)) {
                permLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }
            state.isError -> ErrorState(state.error ?: "Error", { vm.refresh() }, Modifier.padding(innerPadding))
            else -> {
                val d = state.data ?: return@PulseScaffold
                val rangeM = rangeKm * 1000.0
                val inRange = d.contacts.filter { it.distanceMeters <= rangeM }
                val airCount = inRange.count { it.kind == ContactKind.AIRCRAFT.name }
                LazyColumn(
                    modifier = Modifier.padding(innerPadding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { RadarScope(inRange, rangeKm, rangeM, selected, online, onSelect = vm::select) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            vm.ranges.forEach { r ->
                                NeonChip("$r KM", selected = r == rangeKm, onClick = { vm.setRange(r) })
                            }
                        }
                    }
                    item { StatusLine(d, inRange.size, airCount) }
                    if (inRange.isEmpty()) {
                        item {
                            Text(
                                "// NO CONTACTS IN RANGE",
                                fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.sp,
                                color = c.muted, modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    } else {
                        items(inRange, key = { it.id }) { ct ->
                            ContactRow(ct, ct.id == selected) { vm.select(ct.id) }
                        }
                    }
                    item {
                        Text(
                            "Live aircraft: ${d.source.ifBlank { "ADS-B" }} (keyless community ADS-B) · ISS: wheretheiss.at · quakes: USGS. " +
                                "Coverage depends on nearby feeders.",
                            style = MaterialTheme.typography.labelSmall, color = c.muted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RadarScope(
    contacts: List<Contact>,
    rangeKm: Int,
    rangeM: Double,
    selectedId: String?,
    online: Boolean,
    onSelect: (String) -> Unit,
) {
    val c = Pulse.colors
    val accent = c.accent
    val ring = c.line
    val ringSoft = c.lineSoft
    val violet = c.violet
    val amber = c.amber
    val magenta = c.magenta
    val backdrop = c.void
    val cardinalN = c.magenta.toArgb()
    val cardinalInk = c.ink2.toArgb()
    val labelArgb = c.muted.toArgb()

    val transition = rememberInfiniteTransition(label = "radar")
    val sweep by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )

    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.TopCenter) {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(contacts, rangeKm) {
                detectTapGestures { tap ->
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = minOf(size.width, size.height) / 2f * 0.9f
                    var best: String? = null
                    var bestD = Float.MAX_VALUE
                    contacts.forEach { ct ->
                        val frac = (ct.distanceMeters / rangeM).toFloat().coerceIn(0f, 1f)
                        val pos = polar(cx, cy, r, ct.bearingDeg, frac)
                        val dd = (pos - tap).getDistance()
                        if (dd < bestD) { bestD = dd; best = ct.id }
                    }
                    best?.let { if (bestD < 72f) onSelect(it) }
                }
            },
        ) {
            val cx = size.width / 2
            val cy = size.height / 2
            val r = size.minDimension / 2 * 0.9f
            val center = Offset(cx, cy)

            drawCircle(backdrop, r, center)
            // Range rings.
            for (i in 1..4) {
                drawCircle(
                    if (i == 4) ring else ringSoft, r * i / 4f, center,
                    style = Stroke(if (i == 4) 1.6f else 1f),
                )
            }
            // Crosshair.
            drawLine(ringSoft, Offset(cx - r, cy), Offset(cx + r, cy), 1f)
            drawLine(ringSoft, Offset(cx, cy - r), Offset(cx, cy + r), 1f)
            // Bearing ticks every 30°.
            for (deg in 0 until 360 step 30) {
                val rad = Math.toRadians(deg.toDouble())
                val inner = r * 0.96f
                drawLine(
                    ringSoft,
                    Offset(cx + (inner * sin(rad)).toFloat(), cy - (inner * cos(rad)).toFloat()),
                    Offset(cx + (r * sin(rad)).toFloat(), cy - (r * cos(rad)).toFloat()),
                    1.5f,
                )
            }
            // Sweep phosphor trail.
            val segments = 26
            for (k in 0 until segments) {
                val a = sweep - k * (72.0 / segments)
                val rad = Math.toRadians(a)
                val alpha = (1f - k / segments.toFloat()) * 0.16f
                drawLine(
                    accent.copy(alpha = alpha), center,
                    Offset(cx + (r * sin(rad)).toFloat(), cy - (r * cos(rad)).toFloat()),
                    strokeWidth = 6f,
                )
            }
            // Leading sweep line + hub.
            val sr = Math.toRadians(sweep.toDouble())
            drawLine(accent, center, Offset(cx + (r * sin(sr)).toFloat(), cy - (r * cos(sr)).toFloat()), 2.5f)
            drawCircle(accent, 3.5f, center)

            // Blips.
            contacts.forEach { ct ->
                val frac = (ct.distanceMeters / rangeM).toFloat().coerceIn(0f, 1f)
                val pos = polar(cx, cy, r, ct.bearingDeg, frac)
                val diff = (((sweep - ct.bearingDeg) % 360) + 360) % 360
                val ping = if (diff < 80) 1f - (diff / 80f).toFloat() else 0f
                val baseCol = when (ct.kind) {
                    ContactKind.ISS.name -> violet
                    ContactKind.QUAKE.name -> amber
                    else -> accent
                }
                val col = baseCol.copy(alpha = 0.4f + 0.6f * ping)
                if (ping > 0.05f) drawCircle(baseCol.copy(alpha = 0.16f * ping), 15f, pos)
                when (ct.kind) {
                    ContactKind.AIRCRAFT.name -> drawAircraft(pos, ct.trackDeg ?: 0.0, col)
                    ContactKind.QUAKE.name -> drawDiamond(pos, col)
                    else -> drawCircle(col, 5.5f, pos)
                }
                if (ct.id == selectedId) drawCircle(magenta, 14f, pos, style = Stroke(2f))
            }

            // Cardinal letters + ring distance labels (native canvas, unrotated).
            val pC = Paint().apply {
                isAntiAlias = true; textAlign = Paint.Align.CENTER; textSize = r * 0.075f
            }
            val pL = Paint().apply {
                isAntiAlias = true; textAlign = Paint.Align.LEFT; textSize = r * 0.05f; color = labelArgb
            }
            drawContext.canvas.nativeCanvas.apply {
                listOf("N" to 0, "E" to 90, "S" to 180, "W" to 270).forEach { (lab, deg) ->
                    val rad = Math.toRadians(deg.toDouble())
                    val rr = r * 0.87f
                    val x = cx + (rr * sin(rad)).toFloat()
                    val y = cy - (rr * cos(rad)).toFloat() + pC.textSize / 3
                    pC.color = if (lab == "N") cardinalN else cardinalInk
                    drawText(lab, x, y, pC)
                }
                for (i in 1..4) {
                    val ry = cy - r * i / 4f
                    drawText("${rangeKm * i / 4}", cx + 5f, ry + pL.textSize, pL)
                }
            }
        }
        if (!online) {
            Text(
                "// LINK LOST — LAST PICTURE",
                fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.5.sp,
                color = magenta, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun StatusLine(d: RadarData, contacts: Int, aircraft: Int) {
    NeonPanel(Modifier.fillMaxWidth(), corners = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatCell("CONTACTS", "$contacts")
            StatCell("AIRCRAFT", "$aircraft")
            StatCell("SOURCE", d.source.ifBlank { "—" })
        }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    val c = Pulse.colors
    Column {
        Text(label, fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 0.6.sp, color = c.muted)
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = c.ink)
    }
}

@Composable
private fun ContactRow(ct: Contact, selected: Boolean, onClick: () -> Unit) {
    val c = Pulse.colors
    val tint = when (ct.kind) {
        ContactKind.ISS.name -> c.violet
        ContactKind.QUAKE.name -> c.amber
        else -> c.accent
    }
    NeonPanel(
        Modifier.fillMaxWidth().clickable { onClick() },
        borderColor = if (selected) c.magenta else c.lineSoft,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.padding(end = 8.dp)) {
                Text(ct.label, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.ink)
                Text(
                    kindLabel(ct.kind), fontFamily = JetBrainsMono, fontSize = 8.sp,
                    letterSpacing = 1.sp, color = tint,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${Geo.formatDistance(ct.distanceMeters)} · ${Geo.cardinal(ct.bearingDeg)} ${ct.bearingDeg.roundToInt()}°",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.accent,
                )
                Text(
                    telemetryLine(ct), fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                )
            }
        }
    }
}

@Composable
private fun PermissionPanel(modifier: Modifier, onGrant: () -> Unit) {
    val c = Pulse.colors
    Column(modifier.padding(16.dp).fillMaxWidth()) {
        NeonPanel(Modifier.fillMaxWidth(), corners = true) {
            Column {
                Text("LOCATION REQUIRED", fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.5.sp, color = c.accent)
                Text(
                    "The radar centres on your live GPS position to plot aircraft, the ISS and nearby quakes by range and bearing.",
                    style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "GRANT LOCATION",
                    fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.accent,
                    modifier = Modifier.padding(top = 14.dp).fillMaxWidth().clickable { onGrant() },
                )
            }
        }
    }
}

private fun kindLabel(kind: String): String = when (kind) {
    ContactKind.ISS.name -> "ORBITAL"
    ContactKind.QUAKE.name -> "SEISMIC"
    else -> "AIRCRAFT"
}

private fun telemetryLine(ct: Contact): String = when (ct.kind) {
    ContactKind.QUAKE.name -> ct.detail.ifBlank { "—" }
    ContactKind.ISS.name -> buildString {
        ct.altitudeM?.let { append("${(it / 1000).roundToInt()} km") }
        ct.groundSpeedKmh?.let { if (isNotEmpty()) append(" · "); append("${it.roundToInt()} km/h") }
    }.ifBlank { "—" }
    else -> buildString {
        ct.altitudeM?.let { append("${(it / 0.3048).roundToInt()} ft") }
        ct.groundSpeedKmh?.let { if (isNotEmpty()) append(" · "); append("${it.roundToInt()} km/h") }
        if (ct.detail.isNotBlank()) { if (isNotEmpty()) append(" · "); append(ct.detail) }
    }.ifBlank { "—" }
}

private fun polar(cx: Float, cy: Float, r: Float, bearingDeg: Double, frac: Float): Offset {
    val rad = Math.toRadians(bearingDeg)
    return Offset(cx + (r * frac * sin(rad)).toFloat(), cy - (r * frac * cos(rad)).toFloat())
}

private fun DrawScope.drawAircraft(pos: Offset, trackDeg: Double, color: Color) {
    val nose = Math.toRadians(trackDeg)
    val left = Math.toRadians(trackDeg + 140)
    val right = Math.toRadians(trackDeg - 140)
    val path = Path().apply {
        moveTo(pos.x + (8f * sin(nose)).toFloat(), pos.y - (8f * cos(nose)).toFloat())
        lineTo(pos.x + (6f * sin(left)).toFloat(), pos.y - (6f * cos(left)).toFloat())
        lineTo(pos.x + (6f * sin(right)).toFloat(), pos.y - (6f * cos(right)).toFloat())
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawDiamond(pos: Offset, color: Color) {
    val s = 5.5f
    val path = Path().apply {
        moveTo(pos.x, pos.y - s)
        lineTo(pos.x + s, pos.y)
        lineTo(pos.x, pos.y + s)
        lineTo(pos.x - s, pos.y)
        close()
    }
    drawPath(path, color)
}
