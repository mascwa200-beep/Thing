package dev.mascwa.pulse.feature.survive

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.Animal
import dev.mascwa.pulse.core.telemetry.AnimalHabitats
import dev.mascwa.pulse.core.telemetry.DangerLevel
import dev.mascwa.pulse.core.telemetry.Habitat
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.lcarsBlockShape
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The offline WILDLIFE map — the animals that inhabit the region you're standing in, drawn as a
 * location-centred, clickable danger heat-map (green = calm … red = deadly), with full field detail
 * (identify · behaviour · what to do · first aid) for each. No network, no map tiles: a GPS fix + the
 * deterministic [AnimalHabitats] classifier. Regional, not a live tracker (see the honesty note in-screen).
 */
@Composable
fun HabitatScreen(vm: HabitatViewModel, onBack: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> vm.onPermissionResult(result.values.any { it }) }

    PulseScaffold(
        title = "Wildlife",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when {
                state.needsPermission -> PermissionPrompt {
                    permLauncher.launch(arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    ))
                }
                state.loading && state.habitat == null -> LoadingState()
                state.error != null && state.habitat == null -> ErrorState(state.error ?: "Error", onRetry = { vm.refresh() })
                state.habitat != null -> HabitatContent(state, onSelect = vm::select, onRetry = vm::refresh)
                else -> LoadingState()
            }
        }
    }
}

@Composable
private fun HabitatContent(state: HabitatUiState, onSelect: (String?) -> Unit, onRetry: () -> Unit) {
    val c = Pulse.colors
    val habitat = state.habitat ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
    ) {
        // Header — where you are + estimated biome.
        Text(
            state.placeName ?: "Your position",
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.ink,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "${habitat.biome.label} · ${habitat.continent.label}",
            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.accent,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            habitat.biome.blurb,
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
            modifier = Modifier.padding(top = 3.dp),
        )
        DangerBanner(habitat.dangerLevel())

        // The heat map.
        Text(
            "◢ REGIONAL WILDLIFE HEAT MAP",
            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold,
            color = c.accent, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
        )
        HabitatScope(habitat, state.selectedId, onSelect)
        DangerLegend()
        Text(
            "You are at the centre. Each zone is a species known to inhabit this region — brighter/redder = " +
                "more dangerous. Tap a zone (or a card) for what it is and what to do. Positions are illustrative, " +
                "not live animal sightings; the biome is estimated offline from your coordinates.",
            fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            modifier = Modifier.padding(top = 6.dp),
        )

        // The species list.
        Text(
            "SPECIES IN THIS REGION · ${habitat.animals.size}",
            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold,
            color = c.accent, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        habitat.animals.forEach { a ->
            AnimalCard(a, expanded = state.selectedId == a.id, onClick = { onSelect(a.id) })
            Spacer(Modifier.height(8.dp))
        }
        Text(
            "Survival reference, not medical advice. In any real emergency, get professional help. Works fully offline.",
            fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
    }
}

/** The tappable, location-centred danger heat-map, drawn entirely on a Canvas (offline, no tiles). */
@Composable
private fun HabitatScope(habitat: Habitat, selectedId: String?, onSelect: (String?) -> Unit) {
    val c = Pulse.colors
    val accent = c.accent
    val raise = c.raise
    val ink = c.ink
    val scopeShape = lcarsBlockShape(sweep = 8.dp, corner = LcarsCorner.TopStart)
    Box(
        Modifier.fillMaxWidth().height(300.dp)
            .clip(scopeShape)
            .background(raise.copy(alpha = 0.35f))
            .border(1.dp, accent.copy(alpha = 0.4f), scopeShape),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            val dots = remember(habitat, wPx, hPx) { computeDots(habitat.animals, wPx, hPx) }
            Canvas(
                Modifier.fillMaxSize().pointerInput(dots) {
                    detectTapGestures { pos ->
                        val hit = dots.minByOrNull { hypot((it.center.x - pos.x).toDouble(), (it.center.y - pos.y).toDouble()) }
                        if (hit != null) {
                            val d = hypot((hit.center.x - pos.x).toDouble(), (hit.center.y - pos.y).toDouble()).toFloat()
                            onSelect(if (d <= maxOf(hit.blobR, 44f)) hit.id else null)
                        }
                    }
                },
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val scopeR = (minOf(size.width, size.height) / 2f) * 0.9f
                // Range rings + axes (scale/topographic feel).
                listOf(0.3f, 0.55f, 0.8f).forEach { f ->
                    drawCircle(accent.copy(alpha = 0.12f), scopeR * f, Offset(cx, cy), style = Stroke(1f))
                }
                drawLine(accent.copy(alpha = 0.10f), Offset(cx - scopeR, cy), Offset(cx + scopeR, cy), 1f)
                drawLine(accent.copy(alpha = 0.10f), Offset(cx, cy - scopeR), Offset(cx, cy + scopeR), 1f)
                // Heat blobs (additive).
                dots.forEach { d ->
                    val brush = Brush.radialGradient(
                        colors = listOf(d.color.copy(alpha = 0.55f), d.color.copy(alpha = 0f)),
                        center = d.center, radius = d.blobR,
                    )
                    drawCircle(brush = brush, radius = d.blobR, center = d.center, blendMode = BlendMode.Plus)
                }
                // Territory cores + selection ring.
                dots.forEach { d ->
                    drawCircle(d.color, d.coreR, d.center)
                    if (d.id == selectedId) drawCircle(ink, d.blobR * 0.6f, d.center, style = Stroke(2f))
                }
                // User marker (you are here) at centre.
                drawCircle(accent, scopeR * 0.035f + 3f, Offset(cx, cy))
                drawCircle(ink, scopeR * 0.015f + 1.5f, Offset(cx, cy))
            }
            // The selected zone read-out ("this is the bear area").
            val sel = habitat.animals.firstOrNull { it.id == selectedId }
            if (sel != null) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(8.dp)
                        .clip(lcarsBlockShape(sweep = 4.dp, corner = LcarsCorner.TopStart))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        "${sel.name} · ${AnimalHabitats.dangerLabel(sel.danger)}",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = dangerColor(sel.danger),
                    )
                }
            }
            Text(
                "◉ YOU",
                fontFamily = JetBrainsMono, fontSize = 8.sp, color = accent,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
            )
        }
    }
}

/** Legend: the danger colour ramp. */
@Composable
private fun DangerLegend() {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(0 to "Calm", 2 to "Caution", 3 to "Danger", 4 to "Deadly").forEach { (d, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(lcarsBlockShape(sweep = 2.dp, corner = LcarsCorner.TopStart)).background(dangerColor(d)))
                Text(label, fontFamily = JetBrainsMono, fontSize = 9.sp,
                    color = Pulse.colors.muted, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun DangerBanner(level: DangerLevel) {
    val c = Pulse.colors
    val col = when (level) {
        DangerLevel.CALM -> dangerColor(0); DangerLevel.LOW -> dangerColor(1)
        DangerLevel.MODERATE -> dangerColor(2); DangerLevel.HIGH -> dangerColor(3); DangerLevel.SEVERE -> dangerColor(4)
    }
    Row(
        Modifier.padding(top = 8.dp).clip(lcarsBlockShape(sweep = 4.dp, corner = LcarsCorner.TopStart))
            .background(col.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(col))
        Text(
            "WILDLIFE THREAT · ${level.label.uppercase()}",
            fontFamily = JetBrainsMono, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
            color = col, modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** A species card — header always shown; the field detail expands when selected/tapped. */
@Composable
private fun AnimalCard(a: Animal, expanded: Boolean, onClick: () -> Unit) {
    val c = Pulse.colors
    val col = dangerColor(a.danger)
    LcarsFrame(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(lcarsBlockShape(sweep = 2.dp, corner = LcarsCorner.TopStart)).background(col))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(a.name, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.ink)
                    Text(
                        "${a.kind.label} · ${AnimalHabitats.dangerLabel(a.danger)}${if (a.hostile) " · will threaten you" else ""}",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = col,
                    )
                }
                Text(if (expanded) "▾" else "▸", fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.accent)
            }
            if (expanded) {
                Field("IDENTIFY", a.identify)
                Field("BEHAVIOUR", a.behavior)
                Field("IF YOU MEET IT", a.ifEncountered)
                a.firstAid?.let { Field("FIRST AID", it) }
            }
        }
    }
}

@Composable
private fun Field(label: String, body: String) {
    val c = Pulse.colors
    Column(Modifier.padding(top = 8.dp)) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = c.accent)
        Text(body, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit) {
    val c = Pulse.colors
    LcarsFrame(Modifier.fillMaxWidth().padding(16.dp)) {
        Column {
            Text("Location needed", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.ink)
            Text("Grant location so the wildlife map can read the biome you're actually in. Works offline after that.",
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted, modifier = Modifier.padding(top = 4.dp))
            Text(
                "▸ GRANT LOCATION",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp, color = c.accent,
                modifier = Modifier.padding(top = 10.dp).border(1.dp, c.accent).clickable { onGrant() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

// ---- offline heat-map geometry (pure, deterministic; seeded by animal id) ----

private class HabDot(
    val id: String,
    val center: Offset,
    val blobR: Float,
    val coreR: Float,
    val color: Color,
    val danger: Int,
)

private fun computeDots(animals: List<Animal>, w: Float, h: Float): List<HabDot> {
    if (animals.isEmpty() || w <= 0f || h <= 0f) return emptyList()
    val cx = w / 2f
    val cy = h / 2f
    val scopeR = (minOf(w, h) / 2f) * 0.9f
    val n = animals.size
    val tau = (2.0 * PI).toFloat()
    return animals.mapIndexed { i, a ->
        val hsh = a.id.hashCode()
        val jitter = ((hsh % 24) - 12).toFloat() * (PI.toFloat() / 180f)
        val angle = (i.toFloat() / n) * tau + jitter - (PI.toFloat() / 2f)
        val distFrac = 0.30f + (abs(hsh / 7) % 100) / 100f * 0.52f
        val r = scopeR * distFrac
        HabDot(
            id = a.id,
            center = Offset(cx + cos(angle) * r, cy + sin(angle) * r),
            blobR = scopeR * (0.16f + a.danger * 0.028f),
            coreR = maxOf(4f, scopeR * 0.028f),
            color = dangerColor(a.danger),
            danger = a.danger,
        )
    }
}

/** The danger colour ramp for the heat-map (fixed colours so danger reads regardless of theme palette). */
private fun dangerColor(danger: Int): Color = when (danger.coerceIn(0, 4)) {
    0 -> Color(0xFF35C46A)   // calm green
    1 -> Color(0xFF9FCC34)   // yellow-green
    2 -> Color(0xFFE0B21A)   // amber
    3 -> Color(0xFFE0721A)   // orange
    else -> Color(0xFFE0331A) // red
}
