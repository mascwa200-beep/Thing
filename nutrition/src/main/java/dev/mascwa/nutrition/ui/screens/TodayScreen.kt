package dev.mascwa.nutrition.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.mascwa.nutrition.ui.ProgressRow
import dev.mascwa.nutrition.ui.SectionCard
import dev.mascwa.nutrition.ui.StatRow
import dev.mascwa.nutrition.ui.round
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.MacroTargets
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.feature.health.HealthViewModel
import java.text.DateFormat
import java.util.Date

/**
 * What you ate on the day being shown, against what the plan asks for.
 *
 * ⚠️ **Every figure here is computed by the shared cores, not by this file.** `state.plan` comes out
 * of `MacroTargets`, `state.eatenToday` out of the log — so this screen and the LCARS one cannot
 * disagree about a calorie target, which they would within a week if either did its own arithmetic.
 */
@Composable
fun TodayScreen(vm: HealthViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val day by vm.shownDay.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()

    DayBar(vm, day)

    val plan = state.plan
    when (plan) {
        is MacroTargets.Plan.Set -> Targets(plan.targets, state.eatenToday)
        is MacroTargets.Plan.Refused -> SectionCard("No targets yet") {
            // ⚠️ The core's own sentence, not one written here. It says exactly which fact is
            // missing -- a height, a weigh-in -- and rewording it would mean maintaining a second
            // list of reasons that drifts from the first.
            Text(plan.sentence, style = MaterialTheme.typography.bodyMedium)
        }
        null -> SectionCard("No targets yet") {
            Text(
                "Fill in your height, birth year and goal on Plan, then record a weight on Body.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    Eaten(state.eatenToday)
    Entries(vm, entries)
}

@Composable
private fun DayBar(vm: HealthViewModel, day: Long) {
    val today = vm.todayStartMs()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { vm.showDay(vm.dayPlus(day, -1)) }) { Text("Earlier") }
        Text(
            if (day == today) "Today" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(day)),
            style = MaterialTheme.typography.titleSmall,
        )
        // ⚠️ Disabled rather than hidden on today, so the control does not move under the thumb as
        // you page backwards and forwards.
        TextButton(onClick = { vm.showDay(vm.dayPlus(day, 1)) }, enabled = day < today) { Text("Later") }
    }
}

@Composable
private fun Targets(t: MacroTargets.Targets, eaten: NutritionDay.Nutrients) {
    SectionCard("Against your plan") {
        ProgressRow("Calories", eaten.kcal, t.kcal, "kcal")
        ProgressRow("Protein", eaten.proteinG, t.proteinG, "g")
        ProgressRow("Fat", eaten.fatG, t.fatG, "g")
        ProgressRow("Carbohydrate", eaten.carbG, t.carbG, "g")
    }
}

@Composable
private fun Eaten(eaten: NutritionDay.Nutrients) {
    SectionCard("Also today") {
        StatRow("Fibre", "${round(eaten.fibreG)} g")
        StatRow("Sugars", "${round(eaten.sugarG)} g")
        StatRow("Saturated fat", "${round(eaten.satFatG)} g")
        StatRow("Sodium", "${round(eaten.sodiumMg)} mg")
    }
}

@Composable
private fun Entries(vm: HealthViewModel, entries: List<NutritionDay.Entry>) {
    SectionCard(
        if (entries.isEmpty()) "Nothing logged" else "${entries.size} logged",
        subtitle = if (entries.isEmpty()) "Add food on the Log tab." else null,
    ) {
        // ⚠️ Grouped by meal in the enum's own order rather than by the time each was added: a
        // breakfast entered at nine in the evening still belongs with breakfast, and sorting by
        // clock time would scatter a day somebody caught up on all at once.
        NutritionDay.Meal.entries.forEach { meal ->
            val inMeal = entries.filter { it.meal == meal }
            if (inMeal.isEmpty()) return@forEach
            Text(
                meal.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            inMeal.forEach { e ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.fillMaxWidth(0.72f)) {
                        Text(e.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${round(e.grams)} g · ${round(e.nutrients.kcal)} kcal",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { vm.removeEntry(e.id) }) { Text("Remove") }
                }
            }
            HorizontalDivider()
        }
    }
}
