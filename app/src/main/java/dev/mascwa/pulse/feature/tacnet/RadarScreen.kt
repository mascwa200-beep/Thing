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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import dev.mascwa.pulse.feature.common.Sparkline
import dev.mascwa.pulse.ui.effects.rememberHaptics
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun RadarScreen(vm: RadarViewModel, onBack: (() -> Unit)? = null) {
    PulseScaffold(
        title = "RADSCOPE",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
        actions = {
            IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh", tint = Pip.bright) }
        },
    ) { innerPadding ->
        RadarBody(vm, Modifier.padding(innerPadding))
    }
}

/** The MAP feed body (RADSCOPE), scaffold-free for hosting as a PIP-BOY sub-tab. */
@Composable
fun RadarBody(vm: RadarViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val rangeKm by vm.rangeKm.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val needsPermission by vm.needsPermission.collectAsStateWithLifecycle()
    val altFilter by vm.altFilter.collectAsStateWithLifecycle()
    val milOnly by vm.milOnly.collectAsStateWithLifecycle()
    val emergOnly by vm.emergOnly.collectAsStateWithLifecycle()
    val countHistory by vm.countHistory.collectAsStateWithLifecycle()
    val sky by vm.sky.collectAsStateWithLifecycle()
    val online = LocalIsOnline.current
    val haptic = rememberHaptics()
    val onSelect: (String) -> Unit = { id -> haptic(HapticFeedbackType.TextHandleMove); vm.select(id) }

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

    when {
        state.isInitialLoading -> LoadingState(modifier)
        needsPermission && state.data == null -> PermissionPanel(modifier) {
            permLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
        state.isError -> ErrorState(state.error ?: "Error", { vm.refresh() }, modifier)
        else -> {
            val d = state.data ?: return
                val rangeM = rangeKm * 1000.0
                val filtered = d.contacts
                    .filter { it.distanceMeters <= rangeM }
                    .filter { passesFilter(it, altFilter, milOnly, emergOnly) }
                val airCount = filtered.count { it.kind == ContactKind.AIRCRAFT.name }
                LazyColumn(
                    modifier = modifier,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { RadarScope(filtered, rangeKm, rangeM, selected, online, onSelect = onSelect) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            vm.ranges.forEach { r ->
                                NeonChip("$r KM", selected = r == rangeKm, onClick = { haptic(HapticFeedbackType.LongPress); vm.setRange(r) })
                            }
                        }
                    }
                    item {
                        Column {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                NeonChip("ALL", altFilter == RadarViewModel.AltFilter.ALL, onClick = { haptic(HapticFeedbackType.LongPress); vm.setAltFilter(RadarViewModel.AltFilter.ALL) })
                                NeonChip("<10K", altFilter == RadarViewModel.AltFilter.LOW, onClick = { haptic(HapticFeedbackType.LongPress); vm.setAltFilter(RadarViewModel.AltFilter.LOW) })
                                NeonChip("10-30K", altFilter == RadarViewModel.AltFilter.MID, onClick = { haptic(HapticFeedbackType.LongPress); vm.setAltFilter(RadarViewModel.AltFilter.MID) })
                                NeonChip(">30K", altFilter == RadarViewModel.AltFilter.HIGH, onClick = { haptic(HapticFeedbackType.LongPress); vm.setAltFilter(RadarViewModel.AltFilter.HIGH) })
                            }
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NeonChip("MIL", milOnly, onClick = { haptic(HapticFeedbackType.LongPress); vm.toggleMil() })
                                NeonChip("EMERG", emergOnly, onClick = { haptic(HapticFeedbackType.LongPress); vm.toggleEmerg() })
                            }
                        }
                    }
                    item { StatusLine(d, filtered.size, airCount, countHistory) }
                    item { SkyPanel(sky) }
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                "// NO CONTACTS IN RANGE",
                                fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.sp,
                                color = Pip.dim, modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    } else {
                        items(filtered, key = { it.id }) { ct ->
                            Column {
                                ContactRow(ct, ct.id == selected) { onSelect(ct.id) }
                                // Tap a contact: its detail drops down right beneath the row.
                                if (ct.id == selected) ContactDetail(ct)
                            }
                        }
                    }
                    item {
                        Text(
                            "Live aircraft: ${d.source.ifBlank { "ADS-B" }} (keyless community ADS-B) · ISS: wheretheiss.at · quakes: USGS. " +
                                "Coverage depends on nearby feeders.",
                            style = MaterialTheme.typography.labelSmall, color = Pip.dim,
                            modifier = Modifier.padding(top = 8.dp),
                        )
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
    // Pip-Boy phosphor monochrome — variable names kept so the drawing code below is unchanged.
    val accent = Pip.bright
    val ring = Pip.grid
    val ringSoft = Pip.gridSoft
    val violet = Pip.glow      // ISS / orbital — brightest
    val amber = Pip.dim        // seismic — dim
    val magenta = Pip.alert    // emergency — the one off-green accent
    val positive = Pip.bright  // military — bright
    val backdrop = Pip.bg
    val cardinalN = Pip.alert.toArgb()
    val cardinalInk = Pip.dim.toArgb()
    val labelArgb = Pip.dim.toArgb()

    val transition = rememberInfiniteTransition(label = "radar")
    val sweep by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    val pulse by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    // Sweep-driven blips: a contact's drawn position is frozen until the sweep hand crosses its
    // bearing, then snaps to the latest data — so blips only refresh as the hand passes over them.
    // Plain (non-State) holders mutated in the draw pass: no recomposition, just per-frame redraw.
    val displayed = remember { LinkedHashMap<String, Contact>() }
    val lastSweep = remember { floatArrayOf(0f) }

    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.TopCenter) {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(contacts, rangeKm) {
                detectTapGestures { tap ->
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = minOf(size.width, size.height) / 2f * 0.9f
                    var best: String? = null
                    var bestD = Float.MAX_VALUE
                    // Hit-test against the visible (frozen) blip positions.
                    val src: Collection<Contact> = if (displayed.isEmpty()) contacts else displayed.values
                    src.forEach { ct ->
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

            // Refresh the frozen blip snapshots: a contact's drawn position only updates when the
            // sweep has just crossed its bearing (or on first sight); drop contacts no longer present.
            val prevSweep = lastSweep[0]
            val liveIds = HashSet<String>(contacts.size)
            contacts.forEach { ct ->
                liveIds.add(ct.id)
                if (ct.id !in displayed || sweepPassed(prevSweep, sweep, ct.bearingDeg.toFloat())) {
                    displayed[ct.id] = ct
                }
            }
            displayed.keys.retainAll(liveIds)
            lastSweep[0] = sweep

            // Blips (drawn from the frozen snapshots).
            displayed.values.forEach { ct ->
                val frac = (ct.distanceMeters / rangeM).toFloat().coerceIn(0f, 1f)
                val pos = polar(cx, cy, r, ct.bearingDeg, frac)
                val diff = (((sweep - ct.bearingDeg) % 360) + 360) % 360
                val ping = if (diff < 80) 1f - (diff / 80f).toFloat() else 0f
                val baseCol = when {
                    ct.emergency -> magenta
                    ct.kind == ContactKind.ISS.name -> violet
                    ct.kind == ContactKind.QUAKE.name -> amber
                    ct.military -> positive
                    else -> accent
                }
                val col = baseCol.copy(alpha = 0.4f + 0.6f * ping)
                if (ping > 0.05f) drawCircle(baseCol.copy(alpha = 0.16f * ping), 15f, pos)
                when (ct.kind) {
                    ContactKind.AIRCRAFT.name -> drawAircraft(pos, ct.trackDeg ?: 0.0, col)
                    ContactKind.QUAKE.name -> drawDiamond(pos, col)
                    else -> drawCircle(col, 5.5f, pos)
                }
                // Emergency squawk: always-on pulsing ring.
                if (ct.emergency) drawCircle(magenta.copy(alpha = 0.3f + 0.5f * pulse), 16f, pos, style = Stroke(2f))
                if (ct.id == selectedId) drawCircle(magenta, 14f, pos, style = Stroke(2f))
            }

            // CRT scanline texture + a soft phosphor edge glow (Pip-Boy tube feel).
            crtScanlines(Pip.bg.copy(alpha = 0.6f))
            drawCircle(Pip.bright.copy(alpha = 0.06f), r, center, style = Stroke(7f))

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
private fun StatusLine(d: RadarData, contacts: Int, aircraft: Int, history: List<Int>) {
    NeonPanel(Modifier.fillMaxWidth(), corners = true, borderColor = Pip.grid) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatCell("CONTACTS", "$contacts")
                StatCell("AIRCRAFT", "$aircraft")
                StatCell("SOURCE", d.source.ifBlank { "—" })
            }
            if (history.size >= 2) {
                Sparkline(
                    history.map { it.toDouble() },
                    Modifier.fillMaxWidth().height(26.dp).padding(top = 8.dp),
                    color = Pip.bright,
                )
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column {
        Text(label, fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 0.6.sp, color = Pip.dim)
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Pip.glow)
    }
}

/**
 * Pip-Boy sky & space-weather readout — the Moon, naked-eye planets above the
 * horizon (offline ephemeris, az/elevation) and the NOAA space-weather picture,
 * grouped into the same scope screen as the aircraft / ISS / quakes.
 */
@Composable
private fun SkyPanel(sky: RadarViewModel.SkyState) {
    NeonPanel(Modifier.fillMaxWidth(), corners = true, borderColor = Pip.grid) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("SKY", fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 2.sp, color = Pip.bright)

            sky.moon?.let { m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${m.emoji} ${m.phaseName.uppercase()}",
                        fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Pip.glow,
                    )
                    Text(
                        "${(m.illumination * 100).roundToInt()}% LIT",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = Pip.dim,
                    )
                }
            }

            if (sky.planets.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    sky.planets.take(5).forEach { p ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "${planetGlyph(p.name)} ${p.name.uppercase()}",
                                fontFamily = JetBrainsMono, fontSize = 11.sp, color = Pip.mid,
                            )
                            Text(
                                "${Geo.cardinal(p.azimuthDeg)} ${p.azimuthDeg.roundToInt()}° · ALT ${p.altitudeDeg.roundToInt()}° · m${"%.1f".format(p.magnitude)}",
                                fontFamily = JetBrainsMono, fontSize = 9.sp, color = Pip.dim,
                            )
                        }
                    }
                }
            } else {
                Text(
                    "// NO NAKED-EYE PLANETS ABOVE HORIZON",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = Pip.dim,
                )
            }
            // Space weather lives in the DATA tab now — removed from the MAP feed.
        }
    }
}

/** Astronomical symbol for a naked-eye planet (Pip-Boy contact glyph). */
private fun planetGlyph(name: String): String = when (name) {
    "Mercury" -> "☿"
    "Venus" -> "♀"
    "Mars" -> "♂"
    "Jupiter" -> "♃"
    "Saturn" -> "♄"
    else -> "✦"
}

@Composable
private fun ContactRow(ct: Contact, selected: Boolean, onClick: () -> Unit) {
    val tint = when {
        ct.emergency -> Pip.alert
        ct.kind == ContactKind.ISS.name -> Pip.glow
        ct.kind == ContactKind.QUAKE.name -> Pip.dim
        ct.military -> Pip.bright
        else -> Pip.mid
    }
    NeonPanel(
        Modifier.fillMaxWidth().clickable { onClick() },
        borderColor = if (selected || ct.emergency) Pip.alert else Pip.grid,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.padding(end = 8.dp)) {
                Text(
                    "${glyph(ct)} ${ct.label}",
                    fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Pip.glow,
                )
                Text(
                    tagLabel(ct), fontFamily = JetBrainsMono, fontSize = 8.sp,
                    letterSpacing = 1.sp, color = tint,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${Geo.formatDistance(ct.distanceMeters)} · ${Geo.cardinal(ct.bearingDeg)} ${ct.bearingDeg.roundToInt()}°",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = Pip.bright,
                )
                Text(
                    telemetryLine(ct), fontFamily = JetBrainsMono, fontSize = 9.sp, color = Pip.dim,
                )
            }
        }
    }
}

/** Pip-Boy contact glyph by kind. */
private fun glyph(ct: Contact): String = when {
    ct.emergency -> "⚠"
    ct.kind == ContactKind.ISS.name -> "⬡"
    ct.kind == ContactKind.QUAKE.name -> "◇"
    else -> "✈"
}

@Composable
private fun PermissionPanel(modifier: Modifier, onGrant: () -> Unit) {
    Column(modifier.padding(16.dp).fillMaxWidth()) {
        NeonPanel(Modifier.fillMaxWidth(), corners = true, borderColor = Pip.grid) {
            Column {
                Text("LOCATION REQUIRED", fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.5.sp, color = Pip.bright)
                Text(
                    "The radar centres on your live GPS position to plot aircraft, the ISS and nearby quakes by range and bearing.",
                    style = MaterialTheme.typography.bodyMedium, color = Pip.mid, modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "GRANT LOCATION",
                    fontFamily = JetBrainsMono, fontSize = 12.sp, color = Pip.bright,
                    modifier = Modifier.padding(top = 14.dp).fillMaxWidth().clickable { onGrant() },
                )
            }
        }
    }
}

private fun tagLabel(ct: Contact): String = when {
    ct.emergency -> "⚠ EMERG ${ct.squawk ?: ""}".trim()
    ct.kind == ContactKind.ISS.name -> "ORBITAL"
    ct.kind == ContactKind.QUAKE.name -> "SEISMIC"
    ct.military -> "AIRCRAFT · MIL"
    else -> "AIRCRAFT"
}

private fun passesFilter(
    ct: Contact,
    alt: RadarViewModel.AltFilter,
    milOnly: Boolean,
    emergOnly: Boolean,
): Boolean {
    if (emergOnly) return ct.emergency
    if (milOnly) return ct.military
    if (alt != RadarViewModel.AltFilter.ALL && ct.kind == ContactKind.AIRCRAFT.name) {
        val ft = (ct.altitudeM ?: return false) / 0.3048
        return when (alt) {
            RadarViewModel.AltFilter.LOW -> ft < 10_000
            RadarViewModel.AltFilter.MID -> ft in 10_000.0..30_000.0
            RadarViewModel.AltFilter.HIGH -> ft > 30_000
            RadarViewModel.AltFilter.ALL -> true
        }
    }
    return true
}

@Composable
private fun ContactDetail(ct: Contact) {
    NeonPanel(
        Modifier.fillMaxWidth(),
        corners = true,
        borderColor = if (ct.emergency) Pip.alert else Pip.grid,
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${glyph(ct)} ${ct.label}", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Pip.glow)
                Text(tagLabel(ct), fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp,
                    color = if (ct.emergency) Pip.alert else Pip.bright)
            }
            if (ct.emergency) {
                Text(
                    "EMERGENCY SQUAWK — ${squawkMeaning(ct.squawk)}",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = Pip.alert,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                DetailRow("Distance", Geo.formatDistance(ct.distanceMeters))
                DetailRow("Bearing", "${Geo.cardinal(ct.bearingDeg)} ${ct.bearingDeg.roundToInt()}°")
                ct.altitudeM?.let { DetailRow("Altitude", "${(it / 0.3048).roundToInt()} ft") }
                ct.groundSpeedKmh?.let { DetailRow("Ground speed", "${it.roundToInt()} km/h") }
                ct.verticalRateFpm?.let { DetailRow("Vertical rate", "%+d fpm".format(it)) }
                ct.trackDeg?.let { DetailRow("Heading", "${it.roundToInt()}°") }
                ct.squawk?.let { DetailRow("Squawk", it) }
                ct.category?.let { DetailRow("Category", it) }
                if (ct.detail.isNotBlank()) DetailRow("Ident", ct.detail)
                if (ct.military) DetailRow("Flag", "Military / state")
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 10.sp, color = Pip.dim)
        Text(value, fontFamily = JetBrainsMono, fontSize = 11.sp, color = Pip.glow)
    }
}

private fun squawkMeaning(squawk: String?): String = when (squawk) {
    "7500" -> "unlawful interference (hijack)"
    "7600" -> "radio failure"
    "7700" -> "general emergency"
    else -> "emergency"
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

/** True if the sweep angle moved past [target] going from [prev] to [cur] (degrees, wrapping at 360). */
private fun sweepPassed(prev: Float, cur: Float, target: Float): Boolean {
    val t = ((target % 360f) + 360f) % 360f
    return if (cur >= prev) t > prev && t <= cur else t > prev || t <= cur
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
