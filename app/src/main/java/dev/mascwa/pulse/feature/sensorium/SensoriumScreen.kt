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
import dev.mascwa.pulse.core.telemetry.AudioRoute
import dev.mascwa.pulse.core.telemetry.DndFilter
import dev.mascwa.pulse.core.telemetry.EnvAnomaly
import dev.mascwa.pulse.core.telemetry.EnvReading
import dev.mascwa.pulse.core.telemetry.EventSeverity
import dev.mascwa.pulse.core.telemetry.LightState
import dev.mascwa.pulse.core.telemetry.Posture
import dev.mascwa.pulse.core.telemetry.PressureTrend
import dev.mascwa.pulse.core.telemetry.SenseContext
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
    val lookNote by vm.lookNote.collectAsStateWithLifecycle()
    val phone by vm.phone.collectAsStateWithLifecycle()

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
                        lookNote = lookNote,
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
                item { InstrumentsCard(fusion, reading) }
                item { PhoneCard(phone) }
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
    lookNote: String?,
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
        // ⚠️ The button used to set a flag and say nothing at all, and at CONSERVE or STANDDOWN that
        // flag could never be honoured — so on a phone low on battery it did precisely nothing, with
        // no way to tell that from a camera that had looked and seen nothing worth naming.
        if (lookNote != null) {
            Text(
                "◉ $lookNote",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink2,
                modifier = Modifier.padding(top = 6.dp),
            )
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
        FacetRow(
            "SOUNDSCAPE", reading.noise.name,
            when {
                reading.soundTags.isNotEmpty() -> reading.soundTags.joinToString(", ")
                // Heard, and nothing it had a word for. That is a real reading of a real room and
                // it used to be indistinguishable from a microphone nobody had opened.
                reading.heard -> "nothing recognisable — read by level"
                else -> "mic idle"
            },
        )
        // ⚠️ FOUR different silences now, and they used to render as one word. An unknown brightness
        // says which it is: no such sensor, a sensor that has not reported yet, the whole watch stood
        // down — or the front of the phone is against something, which is the one where the sensor
        // is working perfectly and its reading is simply not about the room.
        if (reading.light == LightState.UNKNOWN) {
            FacetRow(
                "LIGHT", "—",
                if (reading.covered) "covered — that reading is not the room"
                else whySilent(present?.light, "ambient-light sensor"),
            )
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
private fun InstrumentsCard(f: FusionSnapshot, reading: EnvReading) {
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
            // The measured loudness behind the SOUNDSCAPE word, which was computed from the capture
            // buffer and then thrown away. Not from the sensor snapshot: this one comes from the mic
            // sampler, so its silence means "no sip has landed", never "no such hardware".
            add(
                "SOUND LEVEL" to (
                    reading.soundDbfs?.let { "%.0f dBFS".format(java.util.Locale.US, it) }
                        ?: "waiting for a sip"
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

/**
 * What the phone knows about ITSELF, beside what the instruments know about the room.
 *
 * ⚠️ **Every row says something, and the ways of saying nothing are kept apart.** This is the same
 * rule the INSTRUMENTS card was rebuilt around: "not read" (the system service never answered),
 * "moving — cannot tell" (the accelerometer answered and refused) and a real value are three
 * different facts, and rendering the first two as a blank row makes them indistinguishable from each
 * other and from a sense that is working fine.
 */
@Composable
private fun PhoneCard(p: SenseContext) {
    val c = Pulse.colors
    Column(
        Modifier.fillMaxWidth().clip(lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.TopStart))
            .background(c.raise.copy(alpha = 0.5f)).padding(12.dp),
    ) {
        Text(
            "THIS PHONE", fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
            fontWeight = FontWeight.Bold, color = c.accent,
        )
        Text(
            p.describe(), fontFamily = ChakraPetch, fontSize = 15.sp, color = c.ink,
            modifier = Modifier.padding(top = 6.dp),
        )
        val unread = "not read"
        val rows = buildList {
            add(
                "SCREEN" to when (p.screenOn) {
                    null -> unread
                    true -> if (p.locked == true) "on, locked" else if (p.locked == false) "on, unlocked" else "on"
                    false -> "off"
                },
            )
            add(
                "LAST UNLOCK" to (
                    // ⚠️ Null here is "none seen since the watch started", not "never" — the service
                    // can begin while the phone is already in somebody's hand. Saying so beats a
                    // dash, which would read as "this phone has not been unlocked".
                    p.msSinceUnlock?.let { "${it / 1000L}s ago" } ?: "none seen since the watch started"
                    ),
            )
            add(
                "POSTURE" to when (p.posture) {
                    null -> unread
                    Posture.UNKNOWN -> "moving — cannot tell"
                    Posture.FACE_DOWN -> "face-down"
                    Posture.FACE_UP -> "face-up"
                    Posture.UPRIGHT -> "upright or held"
                },
            )
            add(
                "POWER" to when (p.charging) {
                    null -> unread
                    true -> "charging · ${p.power?.name?.lowercase() ?: "source unknown"}"
                    false -> "on battery"
                },
            )
            add(
                // Null below API 29, where the phone genuinely cannot say — see DeviceProbeReader.
                "THERMAL" to (p.thermal?.name?.lowercase() ?: "$unread (needs Android 10)"),
            )
            add(
                "AUDIO" to buildList {
                    if (p.onCall == true) add("on a call")
                    if (p.musicPlaying == true) add("playing")
                    p.route?.takeIf { it != AudioRoute.UNKNOWN }?.let { add(it.name.lowercase()) }
                }.joinToString(" · ").ifEmpty { if (p.route == null) unread else "idle" },
            )
            add("RINGER" to (p.ringer?.name?.lowercase() ?: unread))
            add(
                "DO NOT DISTURB" to when (p.dnd) {
                    null, DndFilter.UNKNOWN -> "the phone will not say"
                    DndFilter.OFF -> "off"
                    DndFilter.PRIORITY -> "priority only"
                    DndFilter.ALARMS_ONLY -> "alarms only"
                    DndFilter.TOTAL_SILENCE -> "total silence"
                },
            )
            add(
                "HOME NETWORK" to when (p.awayFromHome) {
                    // ⚠️ Null covers two situations and neither is "away": no home network is
                    // configured, or the SSID cannot be read — which needs location permission and is
                    // the ordinary case on GrapheneOS.
                    null -> "no home Wi-Fi set, or the name cannot be read"
                    true -> "away"
                    false -> "at home"
                },
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
