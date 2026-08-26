package dev.mascwa.nutrition.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.nutrition.ui.SectionCard
import dev.mascwa.nutrition.ui.StatRow
import dev.mascwa.pulse.core.telemetry.Habits
import dev.mascwa.pulse.feature.health.HealthViewModel

/**
 * How consistently the record has been kept, and what that means for the numbers on Plan.
 *
 * ⚠️ **No habit here is a checkbox, and that is the whole design.** Every streak is derived from a
 * record the app already keeps, because the coached targets are measured FROM the calorie log — so
 * "how consistently am I logging" is a statement about how far the figure on Plan can be trusted. A
 * self-reported version would be a comfortable lie about the one number this app exists to produce.
 */
@Composable
fun HabitsScreen(vm: HealthViewModel) {
    StepSensor(vm)
    StepsCard(vm)
    StreaksCard(vm)
    RecordCard(vm)
}

// ------------------------------------------------------------------------------------- the steps

/**
 * ⚠️ **Only while this tab is open, and that costs nothing.** `TYPE_STEP_COUNTER` is maintained by
 * the hardware whether or not anybody is listening, so the total is already right the moment
 * somebody looks. A listener registered for the life of the process would hold the sensor open for
 * a number nobody is reading.
 *
 * ⚠️ The permission is asked for here rather than at startup. Without ACTIVITY_RECOGNITION the
 * sensor delivers no events at all on API 29 and later, so this is both the honest place to ask and
 * the one screen where the request has a visible reason.
 */
@Composable
private fun StepSensor(vm: HealthViewModel) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            android.os.Build.VERSION.SDK_INT < 29 ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACTIVITY_RECOGNITION,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) {
        if (!granted && android.os.Build.VERSION.SDK_INT >= 29) {
            ask.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    if (!granted) return
    val vmRef = rememberUpdatedState(vm)

    // ⚠️ `DisposableEffect`, because a registered `SensorEventListener` that is never unregistered
    // outlives the screen. A `LaunchedEffect` would register it and never take it down.
    //
    // ⚠️ **The raw reading is passed through untouched.** The counter is cumulative since the last
    // BOOT, and turning it into a daily total — including deciding that a lower reading means the
    // phone restarted — is `Habits.steps`, which is tested. Subtracting anything here would be a
    // second definition of "today's steps" living in a composable.
    DisposableEffect(granted) {
        val sensors = context.getSystemService(SensorManager::class.java)
        val counter = sensors?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                event.values.firstOrNull()?.let { vmRef.value.onSteps(it.toLong()) }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (counter != null) {
            sensors.registerListener(listener, counter, SensorManager.SENSOR_DELAY_NORMAL)
        }
        onDispose { if (counter != null) sensors.unregisterListener(listener) }
    }
}

@Composable
private fun StepsCard(vm: HealthViewModel) {
    val steps by vm.steps.collectAsStateWithLifecycle()

    SectionCard("On foot") {
        // ⚠️ Null is "cannot tell", not zero. Showing 0 to somebody who has walked all morning
        // because the permission was refused, or because the phone has no pedometer, is worse than
        // saying the count is not available.
        Text(
            Habits.describe(steps)
                ?: if (steps == null) "No step count — this phone's pedometer is not reporting."
                else "Nothing much yet today.",
            style = MaterialTheme.typography.titleMedium,
        )
        if (steps?.partial == true) {
            Text(
                "The phone restarted, so the steps before that are gone — the counter begins again " +
                    "from zero and nothing recorded the old total.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ----------------------------------------------------------------------------------- the streaks

@Composable
private fun StreaksCard(vm: HealthViewModel) {
    val habits by vm.habits.collectAsStateWithLifecycle()

    SectionCard(
        "Kept up",
        subtitle = "A run that ended yesterday is still going — today is not over yet.",
    ) {
        Habits.Habit.entries.forEach { h ->
            val s = habits[h]
            val current = s?.current ?: 0
            val longest = s?.longest ?: 0
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                StatRow(
                    h.label,
                    when (current) {
                        0 -> "—"
                        1 -> "1 day"
                        else -> "$current days"
                    },
                    emphasis = current > 0,
                )
                Text(
                    // ⚠️ The core's own description of what the habit even is, rather than a second
                    // wording here that would drift from it. The best-ever run is mentioned only
                    // when it beats the current one — "best so far: 3 days" beside a run of three is
                    // the same fact printed twice.
                    h.blurb + if (longest > current) " Best so far: $longest days." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------ the record

/**
 * ⚠️ **The one dataset in this app that cannot be refetched.** Markets, weather and food records all
 * come back from a server; a year of weigh-ins and nine thousand meals exist on exactly one phone.
 * That is why an export button is a feature rather than a nicety.
 */
@Composable
private fun RecordCard(vm: HealthViewModel) {
    val busy by vm.exporting.collectAsStateWithLifecycle()
    val status by vm.exportStatus.collectAsStateWithLifecycle()

    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) vm.exportRecord(uri) }

    // ⚠️ `*/*` rather than a list of types, and that is not laziness. A zip arrives as
    // application/zip, application/x-zip-compressed or octet-stream depending on which app wrote it,
    // and a CSV as text/csv or text/plain — a picker that filters on type greys out the very file
    // this app produced, on some devices only. The importer identifies what it has by reading it.
    val open = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.importRecord(uri) }

    SectionCard("Your record") {
        Text(
            "Every entry, every day's totals, every weigh-in and every measurement, as four " +
                "spreadsheets in one zip. It is yours; nothing here is sent anywhere.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { save.launch("nutrition.zip") },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text(if (busy) "Gathering…" else "Export") }
            OutlinedButton(
                onClick = { open.launch(arrayOf("*/*")) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text(if (busy) "Working…" else "Import") }
        }
        Text(
            "Import reads a zip this app wrote, or a single sheet out of one — including one written " +
                "by the larger app this came from. Entries you already have are recognised and left " +
                "alone, so importing the same file twice changes nothing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (busy) {
            // Said out loud because it genuinely takes a while: it opens every month of the log at
            // once, which is exactly what the log's sharding exists to avoid doing.
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "Reading the whole log — on a long record this takes a moment.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
