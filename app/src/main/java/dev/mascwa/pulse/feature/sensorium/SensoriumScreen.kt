package dev.mascwa.pulse.feature.sensorium

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.EnvAnomaly
import dev.mascwa.pulse.core.telemetry.EnvReading
import dev.mascwa.pulse.core.telemetry.EventSeverity
import dev.mascwa.pulse.core.telemetry.LightState
import dev.mascwa.pulse.core.telemetry.PressureTrend
import dev.mascwa.pulse.core.telemetry.Sensorium
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.sensing.FusionSnapshot
import dev.mascwa.pulse.data.sensing.SensorsPresent
import dev.mascwa.pulse.data.sensing.SensoriumStore
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.LcarsIcons
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.lcarsBlockShape
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * The SENSORIUM scanner — the live environmental sweep: what the ship's senses read right now (fused
 * setting/motion/noise/light/company), what is UNUSUAL against your learned normal for this hour, the
 * last 48 h of sensed events, and the raw instrument strip. Arming state is shown honestly — ears and
 * eyes can only arm from a foreground app-open, and this screen IS one, so it offers the grant/arm
 * action whenever something is on standby.
 */
@Composable
fun SensoriumScreen(vm: SensoriumViewModel, onBack: (() -> Unit)? = null) {
    val c = Pulse.colors
    val ctx = LocalContext.current
    val reading by vm.reading.collectAsStateWithLifecycle()
    val anomalies by vm.anomalies.collectAsStateWithLifecycle()
    val normalLine by vm.normalLine.collectAsStateWithLifecycle()
    val micArmed by vm.micArmed.collectAsStateWithLifecycle()
    val camArmed by vm.camArmed.collectAsStateWithLifecycle()
    val level by vm.level.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val fusion by vm.fusion.collectAsStateWithLifecycle()
    val modelBytes by vm.modelBytes.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { vm.rearm(ctx) }

    // See refreshModelBytes: the classifiers usually land while this screen is open, so a figure
    // read once at construction would report zero for the rest of the session.
    LaunchedEffect(Unit) { vm.refreshModelBytes() }

    PulseScaffold(
        title = "Sensorium",
        onBack = onBack,
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    HeaderCard(
                        reading = reading, level = level, micArmed = micArmed, camArmed = camArmed,
                        onArm = {
                            val missing = buildList {
                                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) !=
                                    PackageManager.PERMISSION_GRANTED
                                ) add(Manifest.permission.CAMERA)
                                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) !=
                                    PackageManager.PERMISSION_GRANTED
                                ) add(Manifest.permission.RECORD_AUDIO)
                            }
                            if (missing.isEmpty()) vm.rearm(ctx)
                            else permissionLauncher.launch(missing.toTypedArray())
                        },
                        onLook = { vm.lookNow() },
                    )
                }
                item { FacetsCard(reading, fusion.present) }
                item { AnomalyCard(anomalies, normalLine) }
                item { InstrumentsCard(fusion) }
                item { StorageCard(modelBytes = modelBytes, onDiscard = { vm.discardModels(ctx) }) }
                item {
                    Text(
                        "SENSED EVENTS · LAST 48H",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.Bold, color = c.accent,
                        modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                    )
                }
                if (events.isEmpty()) {
                    item {
                        Text(
                            "Nothing sensed yet — events land here as the watch runs.",
                            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                            modifier = Modifier.padding(6.dp),
                        )
                    }
                } else {
                    items(events, key = { "${it.key}:${it.atMs}" }) { e -> EventRow(e) }
                }
            }
        }
    }
}

@Composable
private fun HeaderCard(
    reading: EnvReading,
    level: Sensorium.SenseLevel,
    micArmed: Boolean,
    camArmed: Boolean,
    onArm: () -> Unit,
    onLook: () -> Unit,
) {
    val c = Pulse.colors
    Column(
        Modifier.fillMaxWidth().clip(lcarsBlockShape(sweep = 8.dp, corner = LcarsCorner.TopStart))
            .background(c.accent.copy(alpha = 0.08f)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "◉ SENSORIUM", fontFamily = ChakraPetch, fontWeight = FontWeight.Black,
                fontSize = 14.sp, letterSpacing = 2.sp, color = c.accent,
            )
            Spacer(Modifier.weight(1f))
            Text(
                level.name, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp,
                color = if (level == Sensorium.SenseLevel.NOMINAL) c.positive else c.muted,
            )
        }
        Text(
            reading.describe(),
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = c.ink,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            ArmChip(if (micArmed) "EARS ARMED" else "EARS STANDBY", micArmed)
            ArmChip(if (camArmed) "EYES ARMED" else "EYES STANDBY", camArmed)
            Spacer(Modifier.weight(1f))
            if (!micArmed || !camArmed) {
                ActionChip("ARM", onArm)
            } else {
                ActionChip("▸ LOOK NOW", onLook)
            }
        }
    }
}

@Composable
private fun ArmChip(text: String, armed: Boolean) {
    val c = Pulse.colors
    Text(
        text, fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 0.8.sp,
        color = if (armed) c.positive else c.muted,
        modifier = Modifier.clip(lcarsBlockShape(sweep = 4.dp, corner = LcarsCorner.TopStart))
            .background((if (armed) c.positive else c.muted).copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun ActionChip(text: String, onClick: () -> Unit) {
    val c = Pulse.colors
    Text(
        text, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 11.sp,
        letterSpacing = 1.sp, color = c.accent,
        modifier = Modifier.clip(lcarsBlockShape(sweep = 4.dp, corner = LcarsCorner.TopStart))
            .background(c.accent.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun FacetsCard(reading: EnvReading, present: SensorsPresent?) {
    val c = Pulse.colors
    Column(
        Modifier.fillMaxWidth().clip(lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.TopStart))
            .background(c.raise.copy(alpha = 0.5f)).padding(12.dp),
    ) {
        FacetRow("SETTING", reading.setting.name, if (reading.seen) "seen" else "inferred")
        FacetRow("MOTION", reading.motion.name, null)
        FacetRow("SOUNDSCAPE", reading.noise.name, if (reading.heard) reading.soundTags.joinToString(", ") else "mic idle")
        // ⚠️ Three different silences, and they used to render as one word. An unknown brightness
        // says which it is: no such sensor on this phone, or a sensor that has not reported yet.
        if (reading.light == LightState.UNKNOWN) {
            FacetRow("LIGHT", "—", whySilent(present?.light, "ambient-light sensor"))
        } else {
            FacetRow("LIGHT", reading.light.name, null)
        }
        FacetRow("COMPANY", reading.social.name, null)
        // Same rule for the barometer, which plenty of phones also lack. The row used to simply not
        // exist, so "no barometer" and "the ring has not filled yet" were both a blank space.
        val trend = reading.pressureTrend
        if (trend != null) {
            FacetRow(
                "PRESSURE", trend.name,
                if (trend == PressureTrend.PLUNGING || trend == PressureTrend.FALLING) "weather may be turning" else null,
            )
        } else {
            FacetRow(
                "PRESSURE", "—",
                // The barometer's own extra case: present and reporting, but the 3 h ring is short.
                if (present?.pressure == true) "needs about an hour of history"
                else whySilent(present?.pressure, "barometer"),
            )
        }
        if (reading.sceneTags.isNotEmpty()) {
            Text(
                "⌁ " + reading.sceneTags.joinToString(" · "),
                fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * Why a sense has nothing to show — **three answers, never one blank**.
 *
 * ⚠️ `null` is "nobody has asked the hardware yet" (the sensing service is stood down), `false` is
 * "this phone does not have it", `true` is "it is there and has not reported". Before this, all
 * three rendered as a missing row, so a person on a phone with no barometer could not tell that
 * from a feature that was simply switched off. [what] names the instrument when there is room for
 * it; the compact instrument strip passes null and lets the row label carry that.
 */
private fun whySilent(has: Boolean?, what: String?): String = when (has) {
    null -> "sensing is not running"
    true -> "waiting for a reading"
    false -> if (what != null) "no $what on this phone" else "not on this phone"
}

@Composable
private fun FacetRow(label: String, value: String, note: String?) {
    val c = Pulse.colors
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = c.muted,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = c.ink)
        if (note != null) {
            Text(
                "  · $note", fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.ink2,
                maxLines = 1,
            )
        }
    }
}

/**
 * What the two classifiers are holding on disk, and a way to hand it back.
 *
 * ⚠️ **Silent when nothing is held, which is the ordinary state before the watch has ever run.** A
 * row reading "0 MB" beside a button that would free nothing is worse than no row: it invites the
 * tap and then appears broken. Once the models are down it says so, because until now they arrived
 * with nothing asked and nothing said, and 8 MB you cannot account for is more irritating than
 * 8 MB you can.
 */
@Composable
private fun StorageCard(modelBytes: Long, onDiscard: () -> Unit) {
    if (modelBytes <= 0L) return
    val c = Pulse.colors
    Column(
        Modifier.fillMaxWidth().clip(lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.TopStart))
            .background(c.raise.copy(alpha = 0.5f)).padding(12.dp),
    ) {
        Text(
            "ON THIS DEVICE",
            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
            fontWeight = FontWeight.Bold, color = c.accent,
        )
        Text(
            "Sound and scene classifiers: ${Formatters.megabytes(modelBytes)}. Fetched once, the " +
                "first time the watch ran, and kept so it works with no network.",
            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
            modifier = Modifier.padding(top = 4.dp),
        )
        LcarsButton(
            text = "STAND DOWN AND FREE ${Formatters.megabytes(modelBytes)}",
            onClick = onDiscard,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun AnomalyCard(anomalies: List<EnvAnomaly>, normalLine: String?) {
    val c = Pulse.colors
    val alertCol = Color(0xFFE0A21A)
    Column(
        Modifier.fillMaxWidth().clip(lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.TopStart))
            .background((if (anomalies.isEmpty()) c.raise.copy(alpha = 0.5f) else alertCol.copy(alpha = 0.12f)))
            .padding(12.dp),
    ) {
        Text(
            if (anomalies.isEmpty()) "AGAINST YOUR NORMAL" else "UNUSUAL RIGHT NOW",
            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
            fontWeight = FontWeight.Bold, color = if (anomalies.isEmpty()) c.accent else alertCol,
        )
        when {
            anomalies.isNotEmpty() -> anomalies.forEach { a ->
                Text(
                    "▴ ${a.metric.uppercase()} — ${a.text}",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            normalLine != null -> Text(
                "Everything reads normal. ${normalLine.replaceFirstChar { it.uppercase() }}.",
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
                modifier = Modifier.padding(top = 4.dp),
            )
            else -> Text(
                "Learning what normal looks like here — judgments begin once enough of this hour has been seen.",
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun InstrumentsCard(f: FusionSnapshot) {
    val c = Pulse.colors
    Column(
        Modifier.fillMaxWidth().clip(lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.TopStart))
            .background(c.raise.copy(alpha = 0.5f)).padding(12.dp),
    ) {
        Text(
            "INSTRUMENTS", fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
            fontWeight = FontWeight.Bold, color = c.accent,
        )
        // ⚠️ **Every sense gets a row, always.** These used to be dropped when the value was null,
        // so a phone with no barometer and a phone whose barometer had not reported yet and a phone
        // where the whole controller was never started all rendered identically: a missing line. A
        // row that says "no sensor" is a measurement of the hardware; a blank is an absence of one.
        val p = f.present
        fun silent(has: Boolean?) = whySilent(has, null)
        val rows = buildList {
            // ⚠️ MOTION reads the ACCELEROMETER flag rather than the value: `movement` defaults to
            // 0f, and 0f is also what a phone at rest genuinely measures, so the number alone can
            // never say whether anything is watching.
            add("MOTION" to if (p?.accelerometer == true) "%.3f".format(f.movement) else silent(p?.accelerometer))
            add("LIGHT" to (f.lightLux?.let { "${it.toInt()} lx" } ?: silent(p?.light)))
            add(
                "PRESSURE" to (
                    f.pressureHpa?.let {
                        val delta = f.pressureDeltaHpa
                            ?.let { d -> " (${if (d >= 0) "+" else ""}%.1f/3h)".format(d) } ?: ""
                        "%.1f hPa%s".format(it, delta)
                    } ?: silent(p?.pressure)
                    ),
            )
            add("MAG FIELD" to (f.magneticUt?.let { "${it.toInt()} µT" } ?: silent(p?.magnetometer)))
            add(
                "PROXIMITY" to (
                    f.proximityNear?.let { if (it) "covered" else "clear" } ?: silent(p?.proximity)
                    ),
            )
        }
        rows.forEach { (label, value) ->
            Row(Modifier.padding(top = 4.dp)) {
                Text(
                    label, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp,
                    color = c.muted, modifier = Modifier.padding(end = 10.dp),
                )
                Text(value, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink)
            }
        }
    }
}

@Composable
private fun EventRow(e: SensoriumStore.StoredEvent) {
    val c = Pulse.colors
    val col = when (e.severity) {
        EventSeverity.ALERT.name -> Color(0xFFE0331A)
        EventSeverity.NOTABLE.name -> Color(0xFFE0A21A)
        else -> c.muted
    }
    Row(
        Modifier.fillMaxWidth().clip(lcarsBlockShape(sweep = 4.dp, corner = LcarsCorner.TopStart))
            .background(c.raise.copy(alpha = 0.4f)).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                e.title, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp, color = col,
            )
            Text(e.detail, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink2)
        }
        Text(relative(e.atMs), fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted)
    }
}

private fun relative(atMs: Long): String {
    val mins = (System.currentTimeMillis() - atMs) / 60_000L
    return when {
        mins < 1 -> "now"
        mins < 60 -> "${mins}m ago"
        mins < 48 * 60 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}
