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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.Body
import dev.mascwa.pulse.core.telemetry.BodyTrend
import dev.mascwa.pulse.core.telemetry.Expenditure
import dev.mascwa.pulse.core.telemetry.MacroTargets
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.data.health.BodyStore
import dev.mascwa.pulse.feature.common.ChartSeries
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsChip
import dev.mascwa.pulse.feature.common.LcarsDataRow
import dev.mascwa.pulse.feature.common.LcarsField
import dev.mascwa.pulse.feature.common.LcarsFillRow
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.feature.common.LcarsStatBlock
import dev.mascwa.pulse.feature.common.LcarsTimeChart
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
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

    // ⚠️ Read HERE, not inside the LazyColumn. A LazyColumn's content is a `LazyListScope.() -> Unit`
    // — an ordinary lambda, not a composable one — so `collectAsStateWithLifecycle()` inside it is a
    // compile error rather than something that merely works oddly. Every composable read the list
    // needs is hoisted into the composable that owns it.
    val day by vm.shownDay.collectAsStateWithLifecycle()
    val isToday = day == vm.todayStartMs()

    fun reset() {
        name = ""; kcal = ""; protein = ""; fat = ""; carb = ""
    }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = Pad, verticalArrangement = Arrangement.spacedBy(11.dp)) {
        item { Notice(vm) }
        item { DayStepper(vm) }
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

        item {
            LcarsHeaderBar(
                if (isToday) "TODAY" else relativeDay(day).uppercase(),
                trailing = NutritionDay.summarise(state.eatenToday),
            )
        }

        if (entries.isEmpty()) {
            item {
                NotYet(
                    if (isToday) "Nothing logged yet today." else "Nothing was logged that day.",
                )
            }
            // ⚠️ Offered only on an EMPTY day, and it is the difference between a shortcut and a
            // duplication bug. copyDay appends; on a day that already has entries the fastest way to
            // log a routine becomes the fastest way to log it twice, and the calorie total — which is
            // what the expenditure measurement reads — would be quietly doubled for that day.
            item { CopyDay(vm, day) }
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

/**
 * Which day the log is showing.
 *
 * ⚠️ It goes back and it does not go past today. Logging a meal into next Thursday is never what
 * somebody meant, and worse than useless here: [Expenditure] reads the log over a trailing window and
 * treats an absent day as unknown rather than zero, so a future day carrying entries would enter that
 * window as a real observation the moment the calendar reached it.
 */
@Composable
private fun DayStepper(vm: HealthViewModel) {
    val c = Pulse.colors
    val day by vm.shownDay.collectAsStateWithLifecycle()
    val today = vm.todayStartMs()
    val forward = vm.dayPlus(day, 1)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StepButton("◀", enabled = true) { vm.showDay(vm.dayPlus(day, -1)) }
        Text(
            relativeDay(day).uppercase(),
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            color = if (day == today) c.accent else c.ink,
            modifier = Modifier.weight(1f).padding(horizontal = 11.dp),
        )
        if (day != today) {
            Text(
                "TODAY",
                fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.8.sp, color = c.muted,
                modifier = Modifier.clickable { vm.showDay(today) }.padding(7.dp),
            )
        }
        StepButton("▶", enabled = forward <= today) { vm.showDay(vm.dayStartOf(forward)) }
    }
}

@Composable
private fun StepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Pulse.colors
    Text(
        glyph,
        fontFamily = JetBrainsMono, fontSize = 13.sp,
        color = if (enabled) c.accent else c.line,
        modifier = Modifier
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

/** Repeat a previous day onto this one, which is how a routine gets logged in one tap. */
@Composable
private fun CopyDay(vm: HealthViewModel, day: Long) {
    val c = Pulse.colors
    LcarsFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(
                "OR REPEAT A DAY",
                fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.9.sp, color = c.muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(1, 2, 7).forEach { back ->
                    val source = vm.dayPlus(day, -back.toLong())
                    LcarsChip(
                        text = when (back) {
                            1 -> "Day before"
                            7 -> "A week back"
                            else -> "$back days back"
                        },
                        selected = false,
                        onClick = { vm.copyFrom(source) },
                    )
                }
            }
            Text(
                "Copies everything logged that day onto this one. If there was nothing, it says so and " +
                    "changes nothing.",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp,
            )
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
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "WEIGH IN — ${unit.label.uppercase()}",
                            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.9.sp,
                            color = c.muted, modifier = Modifier.weight(1f),
                        )
                        // ⚠️ Display only. Kilograms are what the record and every core hold, and the
                        // conversion happens at this boundary — a pound that reached BodyTrend would
                        // put the trend, the rate cap and the calorie floor all out by a factor of 2.2
                        // with nothing on screen looking wrong.
                        BodyTrend.MassUnit.entries.forEach { u ->
                            LcarsChip(u.label.uppercase(), unit == u, { vm.setMassUnit(u) })
                        }
                    }
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

        // ⚠️ Off the TREND, and off the person the cores were given — not off the last thing the scale
        // said. A BMI that moves half a point because of yesterday's salt is the same lie the trend
        // exists to remove, and it is worse here because a band boundary makes it look categorical.
        val p = state.person
        if (p != null) {
            item {
                val value = Body.bmi(p.kg, p.heightCm)
                val band = Body.bmiBand(value)
                LcarsDataRow(
                    label = "BMI",
                    value = if (band == null) fmt(value) else "${fmt(value)} · ${band.label}",
                )
            }
            item {
                Text(
                    "A ratio of weight to height, and nothing more — it knows nothing about how much " +
                        "of you is muscle. Useful as one line among several, misleading on its own.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp,
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
            ReadingRow(
                label = relativeDay(w.atMs),
                value = fmt(w.kg * unit.perKg) + " " + unit.label,
                onRemove = { vm.removeWeighin(w.atMs) },
            )
        }
        item {
            // ⚠️ The reason every row can be deleted, and it is not tidiness. A mistyped 850 is what
            // the trend filter's outlier suppression was tuned against — it survives one, and it is
            // deliberately graduated rather than absolute, so a wrong reading still drags the estimate
            // a little and keeps dragging it for weeks. The person who typed it is the only one who
            // knows it was a typo, so they have to be able to say so.
            Text(
                if (weighins.size > 40) {
                    "Showing the most recent 40 of ${weighins.size}. Nothing is discarded unless you " +
                        "remove it — worth doing for a mistyped reading, which pulls the trend for weeks."
                } else {
                    "Remove a mistyped reading rather than living with it — one bad number pulls the " +
                        "trend for weeks."
                },
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp,
            )
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

        item { LcarsHeaderBar("WHERE YOU ARE HEADING") }
        item { GoalField(vm, state) }

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
        item { ProteinPreference(vm, state) }

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

        item { LcarsHeaderBar("THE PLAN") }

        // ⚠️ A refusal is rendered, not swallowed. The guardrails in MacroTargets were deliberately
        // built to REFUSE rather than silently clamp — a goal in the underweight range, a body the
        // cores cannot believe, nothing measured yet — and a surface that only draws Plan.Set turns
        // every one of those back into the silent clamp they were written to avoid. Amber rather than
        // red: it is the app declining to answer, not something being wrong with the person.
        val refused = state.plan as? MacroTargets.Plan.Refused
        if (refused != null) {
            item {
                LcarsFrame(Modifier.fillMaxWidth(), accent = c.amber) {
                    Text(
                        refused.sentence,
                        fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.amber, lineHeight = 17.sp,
                    )
                }
            }
        }
        if (state.plan == null) {
            item {
                NotYet(
                    "No plan yet — it needs your height, year of birth and at least one weigh-in, and " +
                        "then something to burn against.",
                )
            }
        }

        val set = state.plan as? MacroTargets.Plan.Set
        if (set != null) {
            item {
                LcarsFrame(Modifier.fillMaxWidth()) {
                    Text(
                        MacroTargets.sentence(set),
                        fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink, lineHeight = 17.sp,
                    )
                }
            }
            // ⚠️ Every adjustment, said out loud. These are the guardrails firing — the calorie floor,
            // the rate cap, the protein bounds, carbs hitting zero — and each one means the returned
            // plan is NOT what was asked for. Without them the pace chips read as a lie: somebody picks
            // one kilogram a week, is quietly floored at the resting rate, and the only visible trace
            // is a rate they never chose in a sentence that does not explain itself.
            // ⚠️ No key. A duplicate key crashes a LazyColumn, and nothing in MacroTargets promises one
            // adjustment per kind — a short, wholly static list has nothing to gain from one anyway.
            if (set.capped) {
                items(set.adjustments) { a ->
                    LcarsFrame(
                        Modifier.fillMaxWidth(),
                        accent = c.amber,
                        padding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        Text(
                            a.sentence,
                            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.amber, lineHeight = 16.sp,
                        )
                    }
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

/**
 * Where you are heading, and how long it takes at the pace you picked.
 *
 * ⚠️ Committed on focus loss rather than on every keystroke. The plan is recomputed on every settings
 * change, and typing "8" on the way to "80" would put a goal of eight kilograms through
 * [MacroTargets.plan] — which correctly refuses it as underweight, so the screen would flash a refusal
 * at somebody in the middle of typing a perfectly ordinary number.
 */
@Composable
private fun GoalField(vm: HealthViewModel, state: HealthViewModel.State) {
    val c = Pulse.colors
    val unit = state.unit
    val stored = state.profile.goalKg
    var text by remember(stored) {
        mutableStateOf(if (stored > 0.0) fmt(stored * unit.perKg) else "")
    }
    var focused by remember { mutableStateOf(false) }

    fun commit() {
        val typed = text.toDoubleOrNull()
        when {
            text.isBlank() -> vm.setGoalKg(0.0)
            typed != null && typed > 0.0 -> vm.setGoalKg(typed / unit.perKg)
            else -> text = if (stored > 0.0) fmt(stored * unit.perKg) else ""
        }
    }

    LcarsFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                "GOAL WEIGHT — ${unit.label.uppercase()}",
                fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.9.sp, color = c.muted,
            )
            LcarsField(
                value = text,
                onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '.' }.take(6) },
                placeholder = "optional",
                // ⚠️ LcarsField's `modifier` IS the text field's own, so a focus observer here really
                // sees the field rather than a wrapper around it.
                modifier = Modifier.onFocusChanged { f ->
                    if (focused && !f.isFocused) commit()
                    focused = f.isFocused
                },
            )
            Text(
                "Optional. It changes nothing about what you eat — the pace does that — it only lets " +
                    "the plan say how long this takes. Leave it blank and nothing is lost.",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp,
            )
        }
    }
}

/**
 * How much protein, if the diet mode's own figure is not what you want.
 *
 * ⚠️ Chips rather than a field, and the range is the core's own. [MacroTargets] clamps anything outside
 * 1.2–3.0 g/kg and reports the clamp as an adjustment; offering a free number would mean routinely
 * showing somebody a correction to a value the screen invited them to type.
 */
@Composable
private fun ProteinPreference(vm: HealthViewModel, state: HealthViewModel.State) {
    val c = Pulse.colors
    val chosen = state.profile.proteinGPerKg
    val fromMode = runCatching { MacroTargets.DietMode.valueOf(state.profile.dietMode) }
        .getOrDefault(MacroTargets.DietMode.BALANCED).proteinGPerKg

    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            "PROTEIN — GRAMS PER KILOGRAM",
            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.9.sp, color = c.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LcarsChip("Mode's own", chosen <= 0.0, { vm.setProteinGPerKg(0.0) })
            listOf(1.6, 2.0, 2.4).forEach { g ->
                LcarsChip(fmt(g), abs(chosen - g) < 1e-6, { vm.setProteinGPerKg(g) })
            }
        }
        Text(
            if (chosen > 0.0) {
                "Yours, whatever the split says. The mode would have asked for ${fmt(fromMode)}."
            } else {
                "Following the split you picked — ${fmt(fromMode)} g per kilogram of a healthy weight " +
                    "for your height, not of what you weigh now."
            },
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp,
        )
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

/** A reading, and a way to take it back. Deliberately flatter than [EntryRow] — a list of forty. */
@Composable
private fun ReadingRow(label: String, value: String, onRemove: () -> Unit) {
    val c = Pulse.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2, modifier = Modifier.weight(1f),
        )
        Text(
            value,
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.ink,
        )
        Text(
            "✕",
            fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.negative,
            modifier = Modifier.clickable { onRemove() }.padding(start = 13.dp, top = 3.dp, bottom = 3.dp),
        )
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
