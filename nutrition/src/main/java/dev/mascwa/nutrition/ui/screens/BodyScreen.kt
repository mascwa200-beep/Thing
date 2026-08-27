package dev.mascwa.nutrition.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.mascwa.nutrition.ui.ChipRow
import dev.mascwa.nutrition.ui.SectionCard
import dev.mascwa.nutrition.ui.StatRow
import dev.mascwa.nutrition.ui.round
import dev.mascwa.pulse.core.telemetry.BodyTrend
import dev.mascwa.pulse.core.telemetry.Decimals
import dev.mascwa.pulse.core.telemetry.PeriodCompare
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.health.BodyStore
import dev.mascwa.pulse.data.health.HealthConnectBridge
import dev.mascwa.pulse.feature.health.HealthViewModel
import java.text.DateFormat
import java.util.Date

/**
 * What the scale said, and what it means.
 *
 * ⚠️ **The trend, not the last reading, is the number that matters** — and it is not computed here.
 * `BodyTrend` runs a filter over every weigh-in because day-to-day weight is mostly water, and both
 * this app and the LCARS one read the same estimate for the same reason.
 */
/**
 * How long the goal is, at the rate you are actually moving.
 *
 * ⚠️ **Absent entirely when no goal is set, rather than saying so.** Somebody maintaining has not
 * asked the question, and "no goal weight set" printed under every weigh-in is the kind of line that
 * teaches people to stop reading the card. Once a goal IS set every answer is shown, including the
 * refusals — a missing date with no explanation reads as a fault rather than as the honest reply.
 */
@Composable
private fun GoalCountdown(state: HealthViewModel.State) {
    if (state.profile.goalKg <= 0.0) return
    val unit = massUnitOf(state.profile.massUnit)
    StatRow("Goal", "${round(state.profile.goalKg * unit.perKg, 1)} ${unit.label}")
    Text(
        state.goalProjection.sentence,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun BodyScreen(vm: HealthViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val weighins by vm.weighins.collectAsStateWithLifecycle()
    val measurements by vm.measurements.collectAsStateWithLifecycle()
    val unit = massUnitOf(state.profile.massUnit)

    SectionCard("Weight") {
        when (val t = state.trend) {
            is BodyTrend.Trend.Estimated -> {
                StatRow("Trend", BodyTrend.trendSentence(t.latest, unit), emphasis = true)
                // ⚠️ `hasRate` gates this, and the sentence carries a give-or-take of its own. A
                // rate quoted from too few readings would state a direction the data cannot support.
                StatRow("Change", BodyTrend.rateSentence(t.latest, unit, t.hasRate))
                GoalCountdown(state)
            }
            is BodyTrend.Trend.TooLittle -> Text(
                t.sentence,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        WeighinEntry(vm, unit)
    }

    if (weighins.isNotEmpty()) {
        SectionCard("Recent readings") {
            weighins.sortedByDescending { it.atMs }.take(12).forEach { w ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(w.atMs)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${round(w.kg * unit.perKg, 1)} ${unit.label}")
                        TextButton(onClick = { vm.removeWeighin(w.atMs) }) { Text("Remove") }
                    }
                }
            }
        }
    }

    LookBackCard(vm)

    SectionCard(
        "Measurements",
        subtitle = "Only the newest of each is kept, so correcting one replaces it.",
    ) {
        BodyStore.MeasureKind.entries.forEach { kind ->
            val m = measurements[kind]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(kind.label, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m?.let { "${round(it.cm, 1)} cm" } ?: "—")
                    if (m != null) {
                        TextButton(onClick = { vm.removeMeasurement(kind, m.atMs) }) { Text("Clear") }
                    }
                }
            }
        }
        MeasurementEntry(vm)
    }

    ProgressPhotos(vm)
    HealthConnect(vm)
}

/**
 * What weight and every recorded measurement have done since a date you pick.
 *
 * ⚠️ **Weight is compared on the smoothed trend, never on two raw weigh-ins.** A single reading
 * carries a couple of pounds of water either way, so picking the one nearest each end of a stretch
 * can report a gain in the middle of a real loss — see `HealthViewModel.lookBack`, which is where the
 * decision is made so that both applications answer the question the same way.
 *
 * ⚠️ **A refusal prints as a refusal**, in the core's own words. A kind with only one reading near
 * the window says so rather than reporting a change of exactly zero, which reads as "you held
 * steady" — a very different thing to tell somebody.
 */
@Composable
private fun LookBackCard(vm: HealthViewModel) {
    val look by vm.look.collectAsStateWithLifecycle()
    val changes by vm.lookBack.collectAsStateWithLifecycle()

    SectionCard("What has changed") {
        ChipRow(
            options = HealthViewModel.Look.entries.map {
                it to it.label.lowercase().replaceFirstChar(Char::uppercase)
            },
            selected = look,
        ) { vm.setLook(it) }

        if (changes.isEmpty()) {
            Text(
                "Weigh in, or add a measurement below, and this fills in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        changes.forEach { change ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    change.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    PeriodCompare.sentence(change),
                    style = MaterialTheme.typography.bodyMedium,
                    // ⚠️ Down is not good and up is not bad — somebody putting muscle on wants both
                    // to climb, and this theme takes the device's dynamic colours anyway, so a hue
                    // could not carry a meaning even if there were one to carry. The direction is in
                    // the words; the colour only separates a reading from a refusal.
                    color = if (change.known) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun WeighinEntry(vm: HealthViewModel, unit: BodyTrend.MassUnit) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Weight in ${unit.label}") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(0.62f),
        )
        Button(
            onClick = {
                // ⚠️ Divided back into kilograms before it is stored. Everything downstream is
                // kilograms and the unit is a display choice; storing whatever the field said would
                // make a pound reading indistinguishable from a very light day.
                Decimals.parse(text)?.let { vm.recordWeighin(it / unit.perKg) }
                text = ""
            },
            enabled = Decimals.parse(text) != null,
        ) { Text("Record") }
    }
}

@Composable
private fun MeasurementEntry(vm: HealthViewModel) {
    var kind by remember { mutableStateOf(BodyStore.MeasureKind.WAIST) }
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = {
            val all = BodyStore.MeasureKind.entries
            kind = all[(all.indexOf(kind) + 1) % all.size]
        }) { Text(kind.label) }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("cm") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(0.5f),
        )
        Button(
            onClick = {
                Decimals.parse(text)?.let { vm.recordMeasurement(kind, it) }
                text = ""
            },
            enabled = Decimals.parse(text) != null,
        ) { Text("Save") }
    }
}

/**
 * The stored unit name as the core's own type.
 *
 * ⚠️ `HealthSettings` keeps it as a String because every field there is a serialization key and an
 * enum-typed one would make an unrecognised value fail the whole blob's decode. Falling back to
 * kilograms is right rather than merely safe: everything is stored in kilograms, so the fallback
 * shows the number as it is actually held.
 */
private fun massUnitOf(name: String): BodyTrend.MassUnit =
    runCatching { BodyTrend.MassUnit.valueOf(name) }.getOrDefault(BodyTrend.MassUnit.KG)

/**
 * Photographs of yourself over time, which the scale cannot show.
 *
 * ⚠️ **App-private, never the camera roll**, and the panel says so as well as saying the cost of the
 * same decision: uninstalling takes them with it. They live in `filesDir/progress/` rather than the
 * cache, so Android cannot reclaim a twelve-week comparison's "before" at some arbitrary point.
 *
 * ⚠️ **Reserving a slot is not recording it.** `reservePhoto` makes the file and hands back a Uri;
 * `photoTaken` is the half that records, and only after the capture actually returned true. A
 * cancelled capture otherwise leaves an index row pointing at a zero-byte file, which the store's
 * load-time sweep cannot catch because the file genuinely exists.
 */
@Composable
private fun ProgressPhotos(vm: HealthViewModel) {
    val photos by vm.photos.collectAsStateWithLifecycle()
    val bytes by vm.photoBytes.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<String?>(null) }

    // ⚠️ ONE launcher, created unconditionally and never inside a branch — the shape the LCARS side
    // of this feature already settled on. A `rememberLauncherForActivityResult` that exists in some
    // compositions and not others is fragile in a file no local gate can type-check.
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val id = pending
        pending = null
        // ⚠️ The false branch is not nothing. A cancelled capture leaves the file the camera app
        // already created, with no index row pointing at it — so it is invisible to every sweep
        // that looks for rows, and it used to stay on the disk for ever.
        if (id != null) { if (ok) vm.photoTaken(id) else vm.photoCancelled(id) }
    }

    SectionCard(
        "Photographs",
        subtitle = "Kept inside this app only — never the camera roll. Uninstalling removes them.",
    ) {
        Button(onClick = {
            val reserved = vm.reservePhoto()
            if (reserved != null) {
                pending = reserved.first
                runCatching { capture.launch(reserved.second) }
            }
        }) { Text("Take one") }

        if (photos.isEmpty()) {
            Text(
                "Nothing yet. One a fortnight in the same light says more than the scale does.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        // ⚠️ The size is printed only when there is something to print, so "not measured yet" can
        // never render as "0.0 MB". This is the one thing in the app that grows on disk unbounded,
        // which is exactly why it is stated rather than left to be discovered.
        Text(
            "${photos.size} kept · ${Formatters.megabytes(bytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(photos, key = { it.id }) { photo ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(
                        model = vm.photoUri(photo.id),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(96.dp).clip(MaterialTheme.shapes.medium),
                    )
                    Text(
                        DateFormat.getDateInstance(DateFormat.SHORT).format(Date(photo.atMs)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { vm.forgetPhoto(photo.id) }) { Text("Delete") }
                }
            }
        }
    }
}

/**
 * Weigh-ins from a scale or another app, and yours published back.
 *
 * ⚠️ **Behind a capability check, and it is re-read on every call rather than cached.** Health
 * Connect can be installed, updated or removed while this app is alive, and a status decided once
 * leaves the panel greyed out after somebody has just done the thing it told them to.
 *
 * ⚠️ **Weight and steps only.** A permission absent from the manifest cannot be requested however
 * the code asks, so those three entries ARE the reach — and the panel says so before offering the
 * button rather than after.
 *
 * ⚠️ ONE launcher, unconditionally, for the reason [ProgressPhotos] gives. The contract needs no
 * provider to construct — it only describes an intent — so the gate lives on the launch.
 */
@Composable
private fun HealthConnect(vm: HealthViewModel) {
    val bridge = remember { vm.healthConnect() }
    val status by vm.syncStatus.collectAsStateWithLifecycle()
    val availability = bridge.availability()
    var granted by remember { mutableStateOf(false) }

    val ask = rememberLauncherForActivityResult(remember { bridge.permissionContract() }) { result ->
        granted = result.containsAll(bridge.permissions)
    }
    LaunchedEffect(availability) {
        granted = availability is HealthConnectBridge.Availability.Ready && bridge.hasAll()
    }

    SectionCard("Health Connect") {
        when (availability) {
            // ⚠️ The bridge's own sentence, not one written here. It names which fact is missing,
            // and a second wording of the same reasons would drift from the first.
            is HealthConnectBridge.Availability.Missing -> Text(
                availability.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HealthConnectBridge.Availability.UpdateNeeded -> Text(
                "Health Connect is installed but too old to talk to. Updating it from your app " +
                    "store is all this needs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            HealthConnectBridge.Availability.Ready -> {
                Text(
                    if (granted) {
                        "Connected. Weigh-ins recorded by a scale or another app can be brought in, " +
                            "and readings typed here are published back."
                    } else {
                        "Available on this phone. Weight and steps only — nothing about food, sleep " +
                            "or exercise is asked for."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (granted) {
                    Button(onClick = { vm.importFromHealthConnect() }) { Text("Bring in new weigh-ins") }
                } else {
                    Button(onClick = { runCatching { ask.launch(bridge.permissions) } }) {
                        Text("Allow weight and steps")
                    }
                }
            }
        }
        // ⚠️ A failure sets the sync status rather than the general notice, so it stays on the panel
        // it belongs to instead of flashing past in a snackbar somewhere else.
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
