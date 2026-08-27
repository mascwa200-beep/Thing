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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.nutrition.ui.ChipRow
import dev.mascwa.nutrition.ui.ProgressRow
import dev.mascwa.nutrition.ui.SectionCard
import dev.mascwa.nutrition.ui.StatRow
import dev.mascwa.nutrition.ui.round
import dev.mascwa.pulse.core.telemetry.Body
import dev.mascwa.pulse.core.telemetry.MacroTargets
import dev.mascwa.pulse.core.telemetry.Micronutrients
import dev.mascwa.pulse.core.telemetry.NutrientGuides
import dev.mascwa.pulse.core.telemetry.NutrientSet
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.core.telemetry.PeriodCompare
import dev.mascwa.pulse.feature.health.HealthViewModel
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

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

    // ⚠️ Null, never a stand-in, when the plan has not been made. Two of the three guides below are
    // a share of this number, and substituting 2,000 would produce a fibre reference that looks
    // measured — `NutrientGuides.fibre` refuses for exactly that reason and this must not undo it.
    AlsoToday(
        eaten = state.eatenToday,
        targetKcal = (plan as? MacroTargets.Plan.Set)?.targets?.kcal,
        birthYear = state.profile.birthYear,
    )
    Vitamins(state.microsToday, state.profile.sex, state.profile.birthYear)
    Everything(state.extrasToday)
    Entries(vm, entries)
    RollUpCard(vm)
}

/**
 * The food log added up a day, a week or a month at a time.
 *
 * ⚠️ **A different question from the energy balance chart on Plan, which is why both exist.** That
 * one asks what the balance was and needs weigh-ins to answer; this one asks whether you are eating
 * more than you were, and works for somebody who has never owned a scale.
 *
 * ⚠️ **The bar is the bucket's MEAN, never its total.** The newest bucket is almost always a
 * part-week, and a total-height bar would show it collapsing every time somebody opened the page on
 * a Tuesday. The denominator is printed beside it for the same reason.
 *
 * ⚠️ Last, because it is the one thing here that is not about today. Everything above answers "how
 * am I doing right now"; this answers "how have I been", and it belongs after the question it
 * follows on from rather than in front of it.
 */
@Composable
private fun RollUpCard(vm: HealthViewModel) {
    val grain by vm.grain.collectAsStateWithLifecycle()
    val buckets by vm.rollUp.collectAsStateWithLifecycle()

    SectionCard("Standing back") {
        ChipRow(
            options = PeriodCompare.Grain.entries.map { it to it.label },
            selected = grain,
        ) { vm.setGrain(it) }

        val shown = buckets.filter { it.loggedDays > 0 }.takeLast(12)
        if (shown.isEmpty()) {
            Text(
                "Nothing logged over this stretch yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        val steps = remember(shown) { PeriodCompare.steps(shown) }
        val peak = shown.mapNotNull { it.mean }.maxOrNull() ?: 1.0
        shown.forEachIndexed { i, b ->
            val mean = b.mean ?: return@forEachIndexed
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                StatRow(
                    bucketLabel(b.startMs, grain),
                    "${round(mean)} kcal a day · ${b.loggedDays}/${b.days} days",
                )
                LinearProgressIndicator(
                    progress = { (mean / peak).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                steps.getOrNull(i)?.let { step ->
                    // ⚠️ A quarter of a snack either way is noise, not a trend, and a line under
                    // every single bucket saying so would bury the two that matter.
                    if (abs(step) >= 25.0) {
                        Text(
                            // The sign is written here rather than taken from `round(step)`, so
                            // that a rise carries a "+" — the same reason the fall carries the
                            // plain hyphen every other number in this app is formatted with.
                            (if (step > 0) "+" else "-") +
                                "${round(abs(step))} a day on the one before",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A bucket's date, at the coarseness it was bucketed by.
 *
 * ⚠️ The device's own locale and zone, because these are dates a person reads — the opposite of the
 * rule for a number crossing into another program, which is always `Locale.US`.
 */
private fun bucketLabel(startMs: Long, grain: PeriodCompare.Grain): String {
    val d = Date(startMs)
    return when (grain) {
        PeriodCompare.Grain.MONTH ->
            SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(d)
        else ->
            SimpleDateFormat("d MMM", Locale.getDefault()).format(d)
    }
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
        // ⚠️ Each row says which way its own number binds. Calories and carbohydrate are budgets;
        // protein and fat are floors the planner raises people UP to, so passing them is the point
        // rather than a fault. See `MacroTargets.Bound`.
        ProgressRow("Calories", eaten.kcal, t.kcal, "kcal", macro = MacroTargets.Macro.CALORIES)
        ProgressRow("Protein", eaten.proteinG, t.proteinG, "g", macro = MacroTargets.Macro.PROTEIN)
        ProgressRow("Fat", eaten.fatG, t.fatG, "g", macro = MacroTargets.Macro.FAT)
        ProgressRow("Carbohydrate", eaten.carbG, t.carbG, "g", macro = MacroTargets.Macro.CARBS)
    }
}

/**
 * Fibre, saturated fat, sodium and sugar — each against what it can honestly be measured against.
 *
 * ⚠️ **These were four bare totals, and a bare total is a number with no meaning.** "Sodium 3,400 mg"
 * tells a reader nothing they can act on; "3,400 of 2,000 mg · past the usual ceiling" does. Every
 * judgement here was already written and CI-tested in [NutrientGuides] — that fibre scales at 14 g
 * per 1,000 kcal, that saturated fat is a tenth of energy, that sodium's figure is an adult one, and
 * that total sugars cannot honestly be put against the added-sugars guideline — and this screen was
 * reading none of it. Same shape as the economic figure with no vintage and the market with no
 * session: the app held the meaning and showed only the number.
 *
 * ⚠️ **Nothing is ever lost when a guide cannot be stated.** [NutrientGuides.forDay] returns between
 * zero and three servings depending on facts about the reader, so rendering only what it returns
 * would make fibre disappear entirely for anyone whose plan has not been made — a total the app has
 * and does not show. Every nutrient keeps its row; the ones without a guide carry
 * [NutrientGuides.whyAbsent] in place of the comparison, which is a fact the reader can act on
 * rather than a silence.
 *
 * ⚠️ **The bar clamps at one and the words carry the overshoot**, which is the same remedy as the
 * macro rows above: `readout` says "3400 of 2000 mg" and `sentence` says "past the usual ceiling",
 * so past a LIMIT is stated twice in text. A colour would carry it on the LCARS side and cannot
 * here — this module takes the device's dynamic scheme, so no role above `primary` means anything
 * fixed. See `MacroTargets.Bound`.
 */
@Composable
private fun AlsoToday(eaten: NutritionDay.Nutrients, targetKcal: Int?, birthYear: Int) {
    // ⚠️ Nothing logged means these totals are zero because the day is empty, not because the food
    // had none of them — so the whole section is absent rather than showing four zeros and three
    // explanations on a day nobody has eaten on yet. The same gate is on the LCARS copy.
    if (eaten.kcal <= 0.0) return
    val year = Calendar.getInstance().get(Calendar.YEAR)
    // ⚠️ Indexed by the nutrient the CORE stamped, never by the display label. A label is prose and
    // a reworded one would silently leave a nutrient rendered twice or not at all.
    val stated = NutrientGuides.forDay(eaten, targetKcal, birthYear, year)
        .mapNotNull { s -> s.guide.nutrient?.let { it to s } }
        .toMap()

    SectionCard("Also today") {
        NutrientGuides.Nutrient.entries.forEach { n ->
            val serving = stated[n]
            if (serving == null) {
                StatRow(n.label, "${round(n.eatenIn(eaten))} ${n.unit}")
                NutrientGuides.whyAbsent(n, targetKcal, birthYear, year)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val g = serving.guide
                StatRow(n.label, g.readout(serving.eaten))
                LinearProgressIndicator(
                    progress = { g.fractionOf(serving.eaten).coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    NutrientGuides.sentence(g, serving.eaten),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The basis, because two of the three move with the calorie target and one does not
                // — and a figure that moves for a reason the screen has not given reads as a bug.
                Text(
                    "${g.basis} · ${g.source}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ⚠️ Sugar last and with no bar, which is the core's own refusal rather than an omission
        // here: both food sources publish TOTAL sugars and the guideline everybody quotes is about
        // ADDED sugars, which the data cannot separate. A bar would tell somebody eating fruit they
        // had breached a limit they had not gone near.
        StatRow("Sugars", "${round(eaten.sugarG)} g")
        Text(
            NutrientGuides.sugarNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
