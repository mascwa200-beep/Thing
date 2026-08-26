package dev.mascwa.nutrition.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.nutrition.ui.SectionCard
import dev.mascwa.nutrition.ui.StatRow
import dev.mascwa.nutrition.ui.round
import dev.mascwa.pulse.core.telemetry.BodyTrend
import dev.mascwa.pulse.data.health.BodyStore
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
                text.trim().toDoubleOrNull()?.let { vm.recordWeighin(it / unit.perKg) }
                text = ""
            },
            enabled = text.trim().toDoubleOrNull() != null,
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
                text.trim().toDoubleOrNull()?.let { vm.recordMeasurement(kind, it) }
                text = ""
            },
            enabled = text.trim().toDoubleOrNull() != null,
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
