package dev.mascwa.nutrition.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.nutrition.data.NutritionUpdates
import dev.mascwa.nutrition.ui.SectionCard
import dev.mascwa.nutrition.ui.StatRow
import dev.mascwa.nutrition.ui.round
import dev.mascwa.pulse.core.telemetry.Body
import dev.mascwa.pulse.core.telemetry.BodyTrend
import dev.mascwa.pulse.core.telemetry.Expenditure
import dev.mascwa.pulse.core.telemetry.MacroTargets
import dev.mascwa.pulse.feature.health.HealthViewModel
import kotlin.math.abs

/**
 * Who you are and where you are going — the handful of facts every target is derived from.
 *
 * ⚠️ **Nothing on this page is a target.** Targets are computed by `MacroTargets` from these inputs
 * plus what the log has measured, and shown on Today. Letting somebody type a calorie goal directly
 * would make the measurement pointless, which is the whole idea this app is built around.
 */
@Composable
fun PlanScreen(vm: HealthViewModel, updates: NutritionUpdates) {
    val state by vm.state.collectAsStateWithLifecycle()
    val p = state.profile

    SectionCard("About you") {
        NumberField("Height in cm", p.heightCm.takeIf { it > 0 }) { vm.setHeightCm(it) }
        NumberField("Year of birth", p.birthYear.takeIf { it > 0 }?.toDouble()) {
            vm.setBirthYear(it.toInt())
        }
        ChipRow(
            options = Body.Sex.entries.map { it to it.name.lowercase().replaceFirstChar(Char::uppercase) },
            selected = runCatching { Body.Sex.valueOf(p.sex) }.getOrDefault(Body.Sex.UNSPECIFIED),
        ) { vm.setSex(it) }
        Text(
            // ⚠️ The year rather than an age, and worth explaining where it is typed: an age is
            // right for twelve months and then quietly wrong for ever, drifting the resting rate
            // every calorie target sits on.
            "A year, not an age, so it stays right without you editing it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionCard("Where you are going") {
        NumberField("Goal weight in kg", p.goalKg.takeIf { it > 0 }) { vm.setGoalKg(it) }
        NumberField("Kilograms a week (negative loses)", p.ratePerWeekKg.takeIf { it != 0.0 }) {
            vm.setRatePerWeekKg(it)
        }
        ChipRow(
            options = MacroTargets.DietMode.entries.map { it to it.label },
            selected = runCatching { MacroTargets.DietMode.valueOf(p.dietMode) }
                .getOrDefault(MacroTargets.DietMode.BALANCED),
        ) { vm.setDietMode(it) }
    }

    SectionCard(
        "How active you are",
        subtitle = "Only used until there is enough of a log to measure it instead.",
    ) {
        ChipRow(
            options = Expenditure.Activity.entries.map { it to it.name.lowercase().replaceFirstChar(Char::uppercase) },
            selected = runCatching { Expenditure.Activity.valueOf(p.activity) }
                .getOrDefault(Expenditure.Activity.LIGHT),
        ) { vm.setActivity(it) }
    }

    SectionCard("What the app has worked out") {
        val e = state.expenditure
        StatRow("Burning", e?.let { "${round(it.kcal)} kcal a day" } ?: "Not enough yet")
        // ⚠️ Both halves stated, because they answer different questions: how much of the estimate
        // is measured rather than predicted, and how many days of log it rests on. A confident
        // figure from four days is not the same as the same figure from four weeks.
        StatRow("Measured", "${round(state.measuredShare * 100)}%")
        StatRow("Days logged", "${state.loggedDaysInWindow} of ${p.expenditureWindowDays}")
    }

    // ⚠️ **A wired capability with nowhere to reach it.** `setProteinGPerKg` is on the shared view
    // model, `MacroTargets` reads the override on every recomputation, and this app had no control
    // for it — so the split's own figure was the only one obtainable here.
    SectionCard(
        "Protein",
        subtitle = "Grams per kilogram of a healthy weight for your height, not of what you weigh now.",
    ) {
        val chosen = p.proteinGPerKg
        val fromMode = runCatching { MacroTargets.DietMode.valueOf(p.dietMode) }
            .getOrDefault(MacroTargets.DietMode.BALANCED).proteinGPerKg
        ChipRow(
            // ⚠️ Zero is not "no protein", it is "follow the split", which is why it is offered as a
            // named choice rather than left as an empty number field somebody would read as none.
            options = listOf(0.0 to "The split's own") + listOf(1.6, 2.0, 2.4).map { it to round(it, 1) },
            selected = listOf(0.0, 1.6, 2.0, 2.4).firstOrNull { abs(it - chosen) < 1e-6 } ?: 0.0,
        ) { vm.setProteinGPerKg(it) }
        Text(
            if (chosen > 0.0) {
                "Yours, whatever the split asks for. It would have asked for ${round(fromMode, 1)}."
            } else {
                "Following the split you picked, which asks for ${round(fromMode, 1)}."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    UpdateCard(updates)

    AboutCard()

    SectionCard("Units") {
        ChipRow(
            options = BodyTrend.MassUnit.entries.map { it to it.label },
            selected = runCatching { BodyTrend.MassUnit.valueOf(p.massUnit) }
                .getOrDefault(BodyTrend.MassUnit.KG),
        ) { vm.setMassUnit(it) }
    }
}

/**
 * ⚠️ **Committed when the field loses focus, not on every keystroke.** Each setter writes to disk
 * and the profile flows straight back into this composable, so committing per character would race
 * the incoming value and fight whoever is typing — a defect the LCARS side of this feature already
 * hit and solved the same way.
 */
@Composable
private fun NumberField(label: String, value: Double?, onCommit: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.let { round(it, 1).removeSuffix(".0") } ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { f -> if (!f.isFocused) text.trim().toDoubleOrNull()?.let(onCommit) },
    )
}

/** A pick-one row. Scrolls sideways because five activity levels do not fit a phone. */
@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onPick(value) },
                label = { Text(label) },
            )
        }
    }
}
