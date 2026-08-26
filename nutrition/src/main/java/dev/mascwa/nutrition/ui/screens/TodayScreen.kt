package dev.mascwa.nutrition.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import dev.mascwa.pulse.core.telemetry.Body
import dev.mascwa.pulse.core.telemetry.MacroTargets
import dev.mascwa.pulse.core.telemetry.Micronutrients
import dev.mascwa.pulse.core.telemetry.NutrientSet
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.feature.health.HealthViewModel
import java.text.DateFormat
import java.util.Calendar
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
    Vitamins(state.microsToday, state.profile.sex, state.profile.birthYear)
    Everything(state.extrasToday)
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

/**
 * The vitamins and minerals today's food actually reported, against published guidance.
 *
 * ⚠️ **Present, never complete.** Only about a quarter of product records carry calcium, so a total
 * drawn from one food in six is not the day's calcium — and a figure shown without that denominator
 * says it is. [Micronutrients.Day.caveat] supplies the denominator and is silent once a nutrient is
 * well covered, which is right: a line under every row is a line nobody reads.
 *
 * ⚠️ A nutrient nothing reported gets **no row at all**, never a zero. A dash-filled table of eight
 * would read as "you ate none of these today", which is a claim the data cannot make.
 */
@Composable
private fun Vitamins(day: Micronutrients.Day, sexName: String, birthYear: Int) {
    val present = Micronutrients.Micro.entries.filter { day[it] != null }
    if (present.isEmpty()) return

    val year = Calendar.getInstance().get(Calendar.YEAR)
    val age = if (birthYear > 0) year - birthYear else 0
    val sex = runCatching { Body.Sex.valueOf(sexName) }.getOrDefault(Body.Sex.UNSPECIFIED)

    SectionCard("Vitamins and minerals") {
        present.forEach { m ->
            val tally = day[m] ?: return@forEach
            val reference = Micronutrients.reference(m, sex, age)
            val guide = (reference as? Micronutrients.Reference.Amount)?.guide
            if (guide != null) {
                // The core writes the readout — "12 mg · 86% of 14 mg" — so this screen and the
                // LCARS one cannot phrase the same comparison two ways.
                StatRow(m.label, Micronutrients.readout(m, tally.total, guide))
                LinearProgressIndicator(
                    progress = { guide.fractionOf(tally.total).coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${guide.basis} \u00b7 ${guide.source}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // ⚠️ No bar where there is no figure to fill it against: an empty track beside a
                // number reads as "none of your allowance left", the opposite of the truth.
                StatRow(m.label, "${round(tally.total, 1)} ${m.unit}")
                (reference as? Micronutrients.Reference.None)?.let {
                    Text(
                        it.why,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // ⚠️ Not the error colour. This says "only three of today's eight foods recorded
            // calcium" — a statement about how complete the record is, which is the honesty this
            // app is built on. Painting it red makes an honest limitation read as a fault, and on a
            // food screen the reader will take the fault to be theirs.
            day.caveat(m)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Everything else the day's records happened to carry.
 *
 * ⚠️ **No percentage of anything**, unlike [Vitamins] above. Twenty-nine further nutrients are
 * declared and there is no reference intake this app can honestly state for most of them; inventing
 * one to fill the same shape would be the exact dishonesty the sparse layer exists to avoid. The
 * figure and where it came from is all there is to say.
 *
 * ⚠️ On an ordinary day this section is **absent entirely**, which is correct — the densest of the
 * twenty-nine is recorded on 5.7% of products and most are near 2%.
 */
@Composable
private fun Everything(day: NutrientSet.Day) {
    val present = NutrientSet.Nutrient.entries.filter { day[it] != null }
    if (present.isEmpty()) return
    SectionCard("Everything else recorded") {
        present.forEach { n ->
            val tally = day[n] ?: return@forEach
            StatRow(n.label, "${round(tally.total, 2)} ${n.unit.symbol}")
            day.caveat(n)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
