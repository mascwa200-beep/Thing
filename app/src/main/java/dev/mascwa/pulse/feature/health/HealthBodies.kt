package dev.mascwa.pulse.feature.health

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.BodyTrend
import dev.mascwa.pulse.core.telemetry.Expenditure
import dev.mascwa.pulse.core.telemetry.MacroTargets
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.data.health.BodyStore
import dev.mascwa.pulse.feature.common.ChakraPetch
import dev.mascwa.pulse.feature.common.ChartSeries
import dev.mascwa.pulse.feature.common.JetBrainsMono
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsChip
import dev.mascwa.pulse.feature.common.LcarsDataRow
import dev.mascwa.pulse.feature.common.LcarsField
import dev.mascwa.pulse.feature.common.LcarsFillRow
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.feature.common.LcarsStatBlock
import dev.mascwa.pulse.feature.common.LcarsTimeChart
import dev.mascwa.pulse.ui.theme.Pulse
import kotlin.math.abs
import kotlin.math.roundToInt

private val Pad = PaddingValues(13.dp)

// =================================================================================== MACROS

/** Where today stands against the plan. The page somebody opens to answer "can I eat this?". */
@Composable
fun MacrosBody(vm: HealthViewModel, state: HealthViewModel.State) {
    val c = Pulse.colors
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = Pad, verticalArrangement = Arrangement.spacedBy(11.dp)) {
        item { Notice(vm) }

        val plan = state.plan
        if (plan is MacroTargets.Plan.Refused) {
            item { NotYet(plan.sentence) }
            return@LazyColumn
        }
        val targets = state.targets
        if (targets == null) {
            item {
                NotYet(
                    "No targets yet. " + expenditureLine(state) +
                        " Weigh in and log what you eat, and this fills itself in.",
                )
            }
            return@LazyColumn
        }
        val eaten = state.eatenToday
        val left = state.remaining!!

        item {
            LcarsFrame(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(
                        if (left.overKcal) "${-left.kcal} OVER" else "${left.kcal} LEFT",
                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 34.sp,
                        color = if (left.overKcal) c.negative else c.accent,
                    )
                    Text(
                        "of ${targets.kcal} calories — ${eaten.kcal.roundToInt()} logged",
                        fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink2,
                    )
                    MacroBar(eaten.kcal, targets.kcal.toDouble(), c.accent)
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MacroTile("PROTEIN", eaten.proteinG, targets.proteinG, c.positive, Modifier.weight(1f))
                MacroTile("FAT", eaten.fatG, targets.fatG, c.amber, Modifier.weight(1f))
                MacroTile("CARBS", eaten.carbG, targets.carbG, c.sky, Modifier.weight(1f))
            }
        }

        val set = plan as? MacroTargets.Plan.Set
        if (set != null && set.adjustments.isNotEmpty()) {
            item { LcarsHeaderBar("WHAT WAS HELD BACK") }
            items(set.adjustments) { adj ->
                LcarsFrame(Modifier.fillMaxWidth(), accent = c.amber) {
                    Text(adj.sentence, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2, lineHeight = 16.sp)
                }
            }
        }

        item {
            Text(
                MacroTargets.DISCLAIMER,
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 7.dp),
            )
        }
    }
}

@Composable
private fun MacroTile(label: String, eaten: Double, target: Int, tint: Color, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    val left = target - eaten.roundToInt()
    LcarsFrame(modifier, padding = PaddingValues(start = 11.dp, end = 11.dp, top = 9.dp, bottom = 9.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.8.sp, color = c.muted)
            Text(
                "${eaten.roundToInt()}",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 19.sp,
                color = if (left < 0) c.negative else tint,
            )
            Text("of $target g", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
            MacroBar(eaten, target.toDouble(), tint)
        }
    }
}

/**
 * How much of a target is spent.
 *
 * ⚠️ Overshoot is drawn as a full bar in the warning colour rather than a bar past its end. A
 * proportional overrun would need the bar to keep growing off the tile, and the number above it already
 * says exactly how far over — the bar's job is the glance, not the measurement.
 */
@Composable
private fun MacroBar(eaten: Double, target: Double, tint: Color) {
    val c = Pulse.colors
    val frac = if (target > 0.0) (eaten / target).coerceIn(0.0, 1.0).toFloat() else 0f
    val over = target > 0.0 && eaten > target
    LcarsFillRow(
        segments = listOf(
            (if (over) 1f else frac) to (if (over) c.negative else tint),
            (if (over) 0f else 1f - frac) to c.raise,
        ),
        modifier = Modifier.fillMaxWidth().height(6.dp),
        gap = 1.5.dp,
    )
}

// =================================================================================== INTAKE

/**
 * The log itself.
 *
 * ⚠️ Only quick-add for now, and the surface says so rather than implying a search box is coming in a
 * moment. The food database is a later slice; typing four numbers off a label is the path that never
 * needs one and never stops working, so it is the one that ships first.
 */
@Composable
fun IntakeBody(vm: HealthViewModel, state: HealthViewModel.State) {
    val c = Pulse.colors
    val entries by vm.entries.collectAsStateWithLifecycle()
    var meal by remember { mutableStateOf(NutritionDay.Meal.BREAKFAST) }
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var carb by remember { mutableStateOf("") }

    fun reset() {
        name = ""; kcal = ""; protein = ""; fat = ""; carb = ""
    }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = Pad, verticalArrangement = Arrangement.spacedBy(11.dp)) {
        item { Notice(vm) }
        item {
            LcarsFrame(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(
                        "QUICK ADD",
                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.accent,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NutritionDay.Meal.entries.forEach { m ->
                            LcarsChip(m.label, meal == m, { meal = m })
                        }
                    }
                    LcarsField(name, { name = it }, placeholder = "What was it?")
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        NumberCell("KCAL", kcal, { kcal = it }, Modifier.weight(1.2f))
                        NumberCell("P", protein, { protein = it }, Modifier.weight(1f))
                        NumberCell("F", fat, { fat = it }, Modifier.weight(1f))
                        NumberCell("C", carb, { carb = it }, Modifier.weight(1f))
                    }
                    val energy = kcal.toDoubleOrNull()
                    LcarsButton(
                        text = "LOG IT",
                        enabled = energy != null && energy > 0.0,
                        onClick = {
                            vm.quickAdd(
                                name = name,
                                kcal = energy ?: 0.0,
                                proteinG = protein.toDoubleOrNull() ?: 0.0,
                                fatG = fat.toDoubleOrNull() ?: 0.0,
                                carbG = carb.toDoubleOrNull() ?: 0.0,
                                meal = meal,
                            )
                            reset()
                        },
                    )
                    // ⚠️ Only shown once there is something to disagree with. A warning that appears
                    // while somebody is still typing the second field is noise, and they learn to
                    // ignore it before it ever means anything.
                    val typed = NutritionDay.Nutrients(
                        kcal = energy ?: 0.0,
                        proteinG = protein.toDoubleOrNull() ?: 0.0,
                        fatG = fat.toDoubleOrNull() ?: 0.0,
                        carbG = carb.toDoubleOrNull() ?: 0.0,
                    )
                    if (protein.isNotBlank() && fat.isNotBlank() && carb.isNotBlank() &&
                        NutritionDay.energyLooksWrong(typed)
                    ) {
                        Text(
                            "Those macros come to ${NutritionDay.energyFromMacros(typed).roundToInt()} calories, " +
                                "not ${energy?.roundToInt()}. Worth a second look — a per-100-gram figure against a " +
                                "smaller portion is the usual cause.",
                            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber, lineHeight = 14.sp,
                        )
                    }
                }
            }
        }

        item { LcarsHeaderBar("TODAY", trailing = NutritionDay.summarise(state.eatenToday)) }

        if (entries.isEmpty()) {
            item { NotYet("Nothing logged yet today.") }
        } else {
            NutritionDay.byMeal(entries).forEach { (m, totals) ->
                item(key = "meal-${m.name}") {
                    Text(
                        "${m.label.uppercase()} · ${totals.kcal.roundToInt()} kcal",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 0.8.sp, color = c.muted,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
                items(entries.filter { it.meal == m }, key = { it.id }) { e ->
                    EntryRow(e) { vm.removeEntry(e.id) }
                }
            }
        }
    }
}

@Composable
private fun NumberCell(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.8.sp, color = c.muted)
        LcarsField(
            value = value,
            onValueChange = { onChange(it.filter { ch -> ch.isDigit() || ch == '.' }.take(6)) },
            placeholder = "0",
            showClear = false,
        )
    }
}

@Composable
private fun EntryRow(e: NutritionDay.Entry, onRemove: () -> Unit) {
    val c = Pulse.colors
    LcarsFrame(Modifier.fillMaxWidth(), padding = PaddingValues(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    e.name, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${e.nutrients.proteinG.roundToInt()} P · ${e.nutrients.fatG.roundToInt()} F · " +
                        "${e.nutrients.carbG.roundToInt()} C",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                )
            }
            Text(
                "${e.nutrients.kcal.roundToInt()}",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.accent,
                modifier = Modifier.padding(end = 11.dp),
            )
            Text(
                "REMOVE",
                fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.8.sp, color = c.negative,
                modifier = Modifier.clickable { onRemove() }.padding(4.dp),
            )
        }
    }
}

// ===================================================================================== BODY

/** The scale, the trend, and how sure the trend is of itself. */
@Composable
fun BodyBody(vm: HealthViewModel, state: HealthViewModel.State) {
    val c = Pulse.colors
    val weighins by vm.weighins.collectAsStateWithLifecycle()
    var entry by remember { mutableStateOf("") }
    val unit = state.unit

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = Pad, verticalArrangement = Arrangement.spacedBy(11.dp)) {
        item { Notice(vm) }
        item {
            LcarsFrame(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(
                        "WEIGH IN — ${unit.label.uppercase()}",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.9.sp, color = c.muted,
                    )
                    LcarsField(
                        value = entry,
                        onValueChange = { entry = it.filter { ch -> ch.isDigit() || ch == '.' }.take(6) },
                        placeholder = "0.0",
                    )
                    LcarsButton(
                        text = "RECORD",
                        enabled = entry.toDoubleOrNull()?.let { it > 0.0 } == true,
                        onClick = {
                            // Stored in kilograms always; the unit is a display choice, and converting
                            // at the boundary is what stops a pound ever reaching a core.
                            entry.toDoubleOrNull()?.let { vm.recordWeighin(it / unit.perKg) }
                            entry = ""
                        },
                    )
                    Text(
                        "A second reading on the same day replaces the first — that is a correction, not " +
                            "two data points.",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp,
                    )
                }
            }
        }

        val trend = state.trend
        if (trend !is BodyTrend.Trend.Estimated) {
            item { NotYet("No weigh-ins yet — the trend starts with the first one.") }
            return@LazyColumn
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                LcarsStatBlock(
                    "TREND",
                    fmt(trend.latest.trendKg * unit.perKg) + " " + unit.label,
                    Modifier.weight(1f),
                )
                LcarsStatBlock(
                    "SCALE SAID",
                    fmt(trend.latest.observedKg * unit.perKg) + " " + unit.label,
                    Modifier.weight(1f),
                    valueColor = c.ink2,
                )
            }
        }
        item {
            LcarsFrame(Modifier.fillMaxWidth()) {
                Text(
                    BodyTrend.rateSentence(trend.latest, unit, trend.hasRate),
                    fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink, lineHeight = 17.sp,
                )
            }
        }

        if (trend.points.size >= 3) {
            item {
                LcarsHeaderBar("TREND", trailing = "${trend.points.size} readings")
            }
            item {
                // Two series on purpose: the readings are the evidence and the trend is the reading of
                // it, and a chart that showed only the smooth line would be asking to be trusted rather
                // than showing its working.
                LcarsTimeChart(
                    series = listOf(
                        ChartSeries(
                            label = "Scale",
                            points = trend.points.map { it.atMs to it.observedKg * unit.perKg },
                            color = c.muted,
                        ),
                        ChartSeries(
                            label = "Trend",
                            points = trend.points.map { it.atMs to it.trendKg * unit.perKg },
                            color = c.accent,
                        ),
                    ),
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    valueFormat = { fmt(it) },
                )
            }
        }

        item { LcarsHeaderBar("MEASUREMENTS") }
        item { Measurements(vm) }

        item { LcarsHeaderBar("READINGS") }
        items(weighins.asReversed().take(40), key = { it.atMs }) { w ->
            LcarsDataRow(
                label = relativeDay(w.atMs),
                value = fmt(w.kg * unit.perKg) + " " + unit.label,
            )
        }
        if (weighins.size > 40) {
            item {
                Text(
                    "Showing the most recent 40 of ${weighins.size}. Nothing is ever discarded.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                )
            }
        }
    }
}

// ==================================================================================== COACH

/** The plan: where you are going, how fast, and what the app has actually measured about you. */
@Composable
fun CoachBody(vm: HealthViewModel, state: HealthViewModel.State) {
    val c = Pulse.colors
    val p = state.profile
    val unit = state.unit

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = Pad, verticalArrangement = Arrangement.spacedBy(11.dp)) {
        item { Notice(vm) }
        item {
            LcarsFrame(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        "WHAT YOU BURN",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.9.sp, color = c.muted,
                    )
                    Text(
                        expenditureLine(state),
                        fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink, lineHeight = 17.sp,
                    )
                    if (state.expenditure != null && state.measuredShare > 0.0) {
                        LcarsFillRow(
                            segments = listOf(
                                state.measuredShare.toFloat() to c.positive,
                                (1f - state.measuredShare.toFloat()) to c.raise,
                            ),
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            gap = 1.5.dp,
                        )
                        Text(
                            "${(state.measuredShare * 100).roundToInt()}% of that is measured from your own " +
                                "record; the rest is still the formula. It moves on its own as the measurement " +
                                "tightens — there is no switch.",
                            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp,
                        )
                    }
                }
            }
        }

        item { LcarsHeaderBar("PACE") }
        item {
            val maxLoss = state.person?.let { MacroTargets.maxLossPerWeekKg(it.kg) } ?: 1.0
            val maxGain = state.person?.let { MacroTargets.maxGainPerWeekKg(it.kg) } ?: 0.5
            val choices = listOf(-1.0, -0.75, -0.5, -0.25, 0.0, 0.25, 0.5)
                .filter { it >= -maxLoss - 1e-9 && it <= maxGain + 1e-9 }
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEach { r ->
                        LcarsChip(
                            text = when {
                                r == 0.0 -> "Hold"
                                r < 0 -> "−${fmt(-r * unit.perKg)}"
                                else -> "+${fmt(r * unit.perKg)}"
                            },
                            selected = abs(p.ratePerWeekKg - r) < 1e-6,
                            onClick = { vm.setRatePerWeekKg(r) },
                        )
                    }
                }
                Text(
                    "Per week. Anything faster than about one per cent of your bodyweight costs muscle " +
                        "rather than fat, so it is not offered.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp,
                )
            }
        }

        item { LcarsHeaderBar("HOW TO SPLIT IT") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MacroTargets.DietMode.entries.take(3).forEach { m ->
                    LcarsChip(m.label, p.dietMode == m.name, { vm.setDietMode(m) })
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MacroTargets.DietMode.entries.drop(3).forEach { m ->
                    LcarsChip(m.label, p.dietMode == m.name, { vm.setDietMode(m) })
                }
            }
        }

        item { LcarsHeaderBar("HOW ACTIVE, ROUGHLY") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Expenditure.Activity.entries.forEach { a ->
                    val on = p.activity == a.name
                    LcarsFrame(
                        Modifier.fillMaxWidth().clickable { vm.setActivity(a) },
                        accent = if (on) c.accent else c.line,
                        padding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        Text(
                            a.label,
                            fontFamily = JetBrainsMono, fontSize = 11.sp,
                            color = if (on) c.accent else c.ink2,
                        )
                    }
                }
                Text(
                    "Only used until the measurement takes over, and it is coarse by nature — which is " +
                        "the whole reason this app measures instead.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp,
                )
            }
        }

        val set = state.plan as? MacroTargets.Plan.Set
        if (set != null) {
            item { LcarsHeaderBar("THE PLAN") }
            item {
                LcarsFrame(Modifier.fillMaxWidth()) {
                    Text(
                        MacroTargets.sentence(set),
                        fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink, lineHeight = 17.sp,
                    )
                }
            }
            val goal = p.goalKg
            if (goal > 0.0 && state.person != null) {
                val weeks = MacroTargets.weeksToGoal(state.person.kg, goal, set.effectiveRatePerWeekKg)
                item {
                    LcarsDataRow(
                        label = "To ${fmt(goal * unit.perKg)} ${unit.label}",
                        value = weeks?.let { "about ${it.roundToInt()} weeks" } ?: "not at this pace",
                    )
                }
            }
        }
    }
}

// =================================================================================== HABITS

/**
 * ⚠️ Says what it will be rather than pretending to be it.
 *
 * A tab that renders an empty chart and a zero streak looks broken; a tab that says which slice builds
 * it is merely unfinished, which is what it is.
 */
@Composable
fun HabitsBody() {
    val c = Pulse.colors
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = Pad, verticalArrangement = Arrangement.spacedBy(11.dp)) {
        item {
            NotYet(
                "Habits, streaks and the step count are not built yet. This will hold the daily things " +
                    "worth keeping up — logging, weighing, hitting protein — and the activity signal that " +
                    "feeds what you burn.",
            )
        }
        item {
            Text(
                "Nothing here is hidden behind a purchase or an account. It simply has not been written.",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp,
            )
        }
    }
}

/**
 * Waist, hips, the rest.
 *
 * ⚠️ Worth having beside the scale rather than instead of it, and worth saying why: body weight can sit
 * still for weeks while the tape keeps moving, which is the commonest reason somebody who is making real
 * progress concludes they are not and stops.
 */
@Composable
private fun Measurements(vm: HealthViewModel) {
    val c = Pulse.colors
    var kind by remember { mutableStateOf(BodyStore.MeasureKind.WAIST) }
    var cm by remember { mutableStateOf("") }
    LcarsFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BodyStore.MeasureKind.entries.take(3).forEach { k ->
                    LcarsChip(k.label, kind == k, { kind = k })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BodyStore.MeasureKind.entries.drop(3).forEach { k ->
                    LcarsChip(k.label, kind == k, { kind = k })
                }
            }
            LcarsField(
                value = cm,
                onValueChange = { cm = it.filter { ch -> ch.isDigit() || ch == '.' }.take(5) },
                placeholder = "centimetres",
            )
            LcarsButton(
                text = "RECORD ${kind.label.uppercase()}",
                enabled = cm.toDoubleOrNull()?.let { it > 0.0 } == true,
                onClick = {
                    cm.toDoubleOrNull()?.let { vm.recordMeasurement(kind, it) }
                    cm = ""
                },
            )
            val latest by vm.measurements.collectAsStateWithLifecycle()
            latest.forEach { (k, m) ->
                LcarsDataRow(label = k.label, value = fmt(m.cm) + " cm · " + relativeDay(m.atMs))
            }
            if (latest.isEmpty()) {
                Text(
                    "None yet. A tape measure catches what the scale misses.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                )
            }
        }
    }
}

// =================================================================================== shared

/** One decimal for a mass, which is all a scale is worth and all a person reads. */
internal fun fmt(v: Double): String =
    if (!v.isFinite()) "—" else String.format(java.util.Locale.US, "%.1f", v)

/** "today" / "yesterday" / "12 days ago" — the same phrasing the rest of the app uses for a record. */
internal fun relativeDay(atMs: Long): String {
    val days = ((System.currentTimeMillis() - atMs) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "Today"
        days == 1 -> "Yesterday"
        days < 7 -> "$days days ago"
        days < 14 -> "A week ago"
        days < 60 -> "${days / 7} weeks ago"
        else -> "${days / 30} months ago"
    }
}
