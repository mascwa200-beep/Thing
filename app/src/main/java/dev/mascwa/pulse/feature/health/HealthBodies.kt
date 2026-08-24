package dev.mascwa.pulse.feature.health

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.mascwa.pulse.core.telemetry.Body
import dev.mascwa.pulse.core.telemetry.BodyTrend
import dev.mascwa.pulse.core.telemetry.Expenditure
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.core.telemetry.FoodPortion
import dev.mascwa.pulse.core.telemetry.Habits
import dev.mascwa.pulse.core.telemetry.MacroTargets
import dev.mascwa.pulse.core.telemetry.Micronutrients
import dev.mascwa.pulse.core.telemetry.MealPhoto
import dev.mascwa.pulse.core.telemetry.NutrientGuides
import dev.mascwa.pulse.core.telemetry.IntakeWeek
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.data.food.Food
import dev.mascwa.pulse.core.util.createCameraImageUri
import dev.mascwa.pulse.data.health.MealPhotoReader
import dev.mascwa.pulse.data.health.BodyStore
import dev.mascwa.pulse.data.health.HealthConnectBridge
import dev.mascwa.pulse.feature.common.ChartSeries
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.LcarsChip
import dev.mascwa.pulse.feature.common.LcarsDataRow
import dev.mascwa.pulse.feature.common.LcarsField
import dev.mascwa.pulse.feature.common.LcarsFillRow
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.feature.common.lcarsBlockShape
import dev.mascwa.pulse.feature.common.LcarsStatBlock
import dev.mascwa.pulse.feature.common.LcarsSwitch
import dev.mascwa.pulse.feature.common.LcarsTimeChart
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import java.time.LocalDate
import java.time.ZoneId

private val Pad = PaddingValues(13.dp)

// =================================================================================== MACROS

/** Where today stands against the plan. The page somebody opens to answer "can I eat this?". */
@Composable
fun MacrosBody(vm: HealthViewModel, state: HealthViewModel.State) {
    val c = Pulse.colors
    // ⚠️ Collected HERE, not inside the LazyColumn. `content` is an ordinary
    // `LazyListScope.() -> Unit` lambda rather than a composable one, so a
    // `collectAsStateWithLifecycle()` inside it does not compile.
    val weekState by vm.week.collectAsStateWithLifecycle()
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

        // ⚠️ Fibre, sugar, saturated fat and sodium have been parsed from both food sources, summed
        // and written to the log since the store was built, and drawn by nothing. This is the first
        // caller. Which of them can be shown is NutrientGuides' judgement, not this screen's — two of
        // the three need a calorie target and one needs an adult birth year, and inventing either
        // would produce a bar that looks measured.
        val guides = NutrientGuides.forDay(
            eaten = eaten,
            targetKcal = targets.kcal,
            birthYear = state.profile.birthYear,
            thisYear = LocalDate.now(ZoneId.systemDefault()).year,
        )
        if (guides.isNotEmpty() || eaten.sugarG > 0.0) {
            item { LcarsHeaderBar("THE REST OF WHAT YOU ATE") }
        }
        items(guides) { serving -> NutrientRow(serving.guide, serving.eaten) }
        if (eaten.sugarG > 0.0) {
            item { SugarRow(eaten.sugarG) }
        }

        // ⚠️ Vitamins and minerals, and the thing that makes them honest: how many of today's foods
        // actually reported each one. Only about a quarter of product records carry calcium, so a
        // total drawn from one food in six is not the day's calcium — and a screen that shows the
        // number without the denominator says it is.
        val micros = state.microsToday
        val year = LocalDate.now(ZoneId.systemDefault()).year
        val age = if (state.profile.birthYear > 0) year - state.profile.birthYear else 0
        val sex = runCatching { Body.Sex.valueOf(state.profile.sex) }
            .getOrDefault(Body.Sex.UNSPECIFIED)
        val present = Micronutrients.Micro.entries.filter { micros[it] != null }
        if (present.isNotEmpty()) {
            item { LcarsHeaderBar("VITAMINS AND MINERALS") }
            items(present) { m -> MicroRow(m, micros, sex, age) }
        }

        // The week, which is the question somebody actually has after a fortnight: whether the plan
        // is being followed at all. It is also what makes the calorie target trustworthy — an
        // expenditure measured from a log with four days missing is measured from a fiction.
        val week = weekState
        if (week != null) {
            item { LcarsHeaderBar("THE LAST ${week.windowDays} DAYS") }
            item { WeekPanel(week, targets.kcal) }
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
 * The week: a bar per logged day against the target line, the verdict, and how complete it is.
 *
 * ⚠️ The chart has GAPS where days were not logged, and that is the truthful picture rather than a
 * rendering flaw. Drawing a missing day as a zero-height bar would say somebody ate nothing; drawing
 * the days shoulder-to-shoulder would hide that four of them are missing altogether. Each bar sits at
 * its own position in the window, so the holes are visible.
 *
 * ⚠️ Today's bar is drawn dimmer and is excluded from every figure below it. Its calories are
 * incomplete until the day ends, and counting it would mark every day under target until dinner.
 */
@Composable
private fun WeekPanel(week: IntakeWeek.Week, targetKcal: Int) {
    val c = Pulse.colors
    LcarsFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            // The tallest thing on the chart is whichever is larger — the biggest day or the target —
            // so the target line is always on the chart rather than off the top of it.
            val peak = maxOf(
                targetKcal.toDouble(),
                week.days.maxOfOrNull { it.kcal } ?: targetKcal.toDouble(),
            ).coerceAtLeast(1.0)
            val byDay = week.days.associateBy { it.dayStartMs }

            Row(
                Modifier.fillMaxWidth().height(64.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // ⚠️ Walk the WINDOW, not the logged days, so an unlogged day leaves a hole rather
                // than being quietly closed up by its neighbours — and take the window's start from
                // the core rather than deriving it, which is wrong whenever either end is unlogged.
                for (i in 0 until week.windowDays) {
                    val day = week.windowStartMs + i * IntakeWeek.DAY_MS
                    val d = byDay[day]
                    val frac = ((d?.kcal ?: 0.0) / peak).toFloat().coerceIn(0f, 1f)
                    val tint = when {
                        d == null -> c.line
                        d.partial -> c.muted
                        d.standing(targetKcal) == IntakeWeek.Standing.ON_TARGET -> c.positive
                        d.standing(targetKcal) == IntakeWeek.Standing.OVER -> c.amber
                        else -> c.sky
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(top = 0.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                // A day with nothing logged still draws a hairline, so the slot is
                                // visibly a slot rather than blank space at the end of the row.
                                .fillMaxHeight(if (d == null) 0.02f else frac.coerceAtLeast(0.02f))
                                .background(tint),
                        )
                    }
                }
            }

            Text(
                "Target ${targetKcal} kcal · green on target, amber over, blue under, grey today",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.faint, lineHeight = 13.sp,
            )

            val verdict = IntakeWeek.verdict(week)
            if (verdict != null) {
                Text(verdict, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2, lineHeight = 15.sp)
                Text(
                    "Averaging ${week.meanProteinG.roundToInt()} g protein · " +
                        "${week.meanFatG.roundToInt()} g fat · ${week.meanCarbG.roundToInt()} g carbs",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                )
            } else {
                Text(
                    "Not enough finished days yet to say how the week is going.",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted, lineHeight = 15.sp,
                )
            }

            // ⚠️ Amber, because it prices everything above it AND the calorie target itself.
            IntakeWeek.completenessNote(week)?.let {
                Text(it, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber, lineHeight = 14.sp)
            }
        }
    }
}

/**
 * One reference intake, its bar, and the sentence that says what kind of number it is.
 *
 * ⚠️ A TARGET and a LIMIT are drawn in different colours ON PURPOSE, and the colour turns at
 * different points. Reaching a fibre floor is the good outcome and is drawn in the positive colour;
 * reaching a sodium ceiling is the thing the figure exists to flag. One shared "past 100%" style
 * would congratulate somebody for going over their salt.
 */
@Composable
private fun NutrientRow(guide: NutrientGuides.Guide, amount: Double) {
    val c = Pulse.colors
    val f = guide.fractionOf(amount)
    val tint = when (guide.kind) {
        NutrientGuides.Kind.TARGET -> if (f >= 1.0) c.positive else c.sky
        NutrientGuides.Kind.LIMIT -> if (f > 1.0) c.negative else if (f >= 0.85) c.amber else c.ink2
    }
    LcarsFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    guide.label.uppercase(),
                    fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.8.sp, color = c.muted,
                )
                Text(
                    guide.readout(amount),
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = tint,
                )
            }
            MacroBar(amount, guide.amount, tint)
            Text(
                NutrientGuides.sentence(guide, amount),
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp,
            )
            // The basis, because two of these move with the calorie target and one does not — and a
            // figure that moves for a reason the screen has not given reads as a bug.
            Text(
                "${guide.basis} · ${guide.source}",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.faint, lineHeight = 13.sp,
            )
        }
    }
}

/**
 * One vitamin or mineral: how much, against the usual reference where one exists, and how much of
 * the day it was actually drawn from.
 *
 * ⚠️ **The coverage line is the point of this row, not decoration.** Measured over the bundled
 * corpus, three product records in four carry no calcium figure. A day totalled from six foods of
 * which one reported calcium is not the day's calcium, and a bar drawn without saying so is the
 * app being more confident than its data — the shape this project has corrected five times.
 *
 * ⚠️ Two of the eight have no figure to compare against, and say why instead. Cholesterol's 300 mg
 * ceiling was withdrawn in 2015; trans fat has no allowance because the guidance is elimination,
 * and a budget on screen reads as permission to spend it. Those get the sentence and no bar.
 */
@Composable
private fun MicroRow(
    m: Micronutrients.Micro,
    day: Micronutrients.Day,
    sex: Body.Sex,
    ageYears: Int,
) {
    val c = Pulse.colors
    val tally = day[m] ?: return
    val reference = Micronutrients.reference(m, sex, ageYears)
    val guide = (reference as? Micronutrients.Reference.Amount)?.guide
    val fraction = guide?.fractionOf(tally.total) ?: 0.0
    val tint = if (guide == null) c.ink2 else if (fraction >= 1.0) c.positive else c.sky
    LcarsFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    m.label.uppercase(),
                    fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.8.sp, color = c.muted,
                )
                Text(
                    Micronutrients.readout(m, tally.total, guide),
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = tint,
                )
            }
            // No bar where there is no figure to fill it against — an empty track beside a number
            // reads as "you have none of your allowance left", which is the opposite of the truth.
            if (guide != null) MacroBar(tally.total, guide.amount, tint)
            when (reference) {
                is Micronutrients.Reference.None -> Text(
                    reference.why,
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp,
                )
                is Micronutrients.Reference.Amount -> Text(
                    "${reference.guide.basis} \u00b7 ${reference.guide.source}",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.faint, lineHeight = 13.sp,
                )
            }
            day.caveat(m)?.let { caveat ->
                Text(
                    caveat,
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.amber, lineHeight = 13.sp,
                )
            }
        }
    }
}

/**
 * Sugar: the total, and no bar.
 *
 * ⚠️ Deliberately shaped unlike [NutrientRow], because it is a different kind of statement. Both food
 * sources publish TOTAL sugars; the guideline everyone quotes is about ADDED sugars, and nothing in
 * the data separates them. A bar here would tell somebody eating fruit they had breached a limit they
 * had not gone near, so the figure ships with its limitation attached instead.
 */
@Composable
private fun SugarRow(sugarG: Double) {
    val c = Pulse.colors
    LcarsFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "SUGAR",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.8.sp, color = c.muted,
                )
                Text(
                    "${sugarG.roundToInt()} g",
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.ink2,
                )
            }
            Text(
                NutrientGuides.sugarNote,
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp,
            )
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
 * The log itself: search the databases, scan a barcode, or type four numbers off a label.
 *
 * ⚠️ QUICK ADD stays below the search rather than being replaced by it, and that is the point of
 * having both. A search needs the food to exist somewhere; typing the numbers is the path that never
 * stops working — no signal, no barcode, a local bakery nobody has photographed.
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
    var grams by remember { mutableStateOf("") }
    var keep by remember { mutableStateOf(false) }

    // ⚠️ Read HERE, not inside the LazyColumn. A LazyColumn's content is a `LazyListScope.() -> Unit`
    // — an ordinary lambda, not a composable one — so `collectAsStateWithLifecycle()` inside it is a
    // compile error rather than something that merely works oddly. Every composable read the list
    // needs is hoisted into the composable that owns it.
    val day by vm.shownDay.collectAsStateWithLifecycle()
    val isToday = day == vm.todayStartMs()

    fun reset() {
        name = ""; kcal = ""; protein = ""; fat = ""; carb = ""; grams = ""; keep = false
    }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = Pad, verticalArrangement = Arrangement.spacedBy(11.dp)) {
        item { Notice(vm) }
        item { DayStepper(vm) }
        item { FindAFood(vm, meal) }
        item { PhotoOfAMeal(vm, meal) }
        item { EatenBefore(vm, meal) }
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
                        NumberCell("GRAMS", grams, { grams = it }, Modifier.weight(1.2f))
                    }
                    val energy = kcal.toDoubleOrNull()
                    val weight = grams.toDoubleOrNull()?.takeIf { it > 0.0 }
                    // ⚠️ The numbers above are what was EATEN; the density is what a saved food has to
                    // be. Only the density can be impossible — two thousand calories is an ordinary
                    // day — so the check happens after the conversion and only when a weight exists.
                    val typedEaten = NutritionDay.Nutrients(
                        kcal = energy ?: 0.0,
                        proteinG = protein.toDoubleOrNull() ?: 0.0,
                        fatG = fat.toDoubleOrNull() ?: 0.0,
                        carbG = carb.toDoubleOrNull() ?: 0.0,
                    )
                    val densityWrong = weight
                        ?.let { FoodPortion.per100gFrom(typedEaten, it) }
                        ?.let { FoodPortion.densityLooksWrong(it) }
                    KeepThisFood(
                        keep = keep && weight != null && densityWrong == null,
                        canKeep = weight != null && name.isNotBlank() && densityWrong == null,
                        onToggle = { keep = it },
                        reason = when {
                            // ⚠️ First, because impossible numbers are a bigger problem than a
                            // missing name and only one reason is shown.
                            densityWrong != null -> "Fix the figures below and this can be kept."
                            weight == null ->
                                "To keep this, say what it weighed. A saved food is a density — that " +
                                    "is the only way it can be scaled to a different portion later — " +
                                    "and there is no way to work one out from calories alone. An " +
                                    "approximate weight is fine."
                            name.isBlank() -> "Give it a name and it can be kept for next time."
                            else -> null
                        },
                    )
                    LcarsButton(
                        text = if (keep && weight != null) "LOG IT AND KEEP IT" else "LOG IT",
                        enabled = energy != null && energy > 0.0,
                        onClick = {
                            vm.quickAdd(
                                name = name,
                                kcal = energy ?: 0.0,
                                proteinG = protein.toDoubleOrNull() ?: 0.0,
                                fatG = fat.toDoubleOrNull() ?: 0.0,
                                carbG = carb.toDoubleOrNull() ?: 0.0,
                                meal = meal,
                                grams = weight ?: 0.0,
                                // ⚠️ `keep` can be true with the weight since cleared — the switch
                                // does not reset itself when the field is emptied, and a save with no
                                // weight is exactly what the core refuses. Re-checked at the call.
                                keepAsFood = keep && weight != null,
                            )
                            reset()
                        },
                    )
                    // ⚠️ Shown whether or not the food is being kept: an impossible density means the
                    // numbers or the weight are wrong, and that is worth saying about a figure going
                    // into the log either way. The sentence comes from the core so this and the
                    // parsers cannot come to hold different opinions about what is possible.
                    if (densityWrong != null) {
                        Text(
                            "Per 100 g, $densityWrong",
                            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber, lineHeight = 14.sp,
                        )
                    }
                    // ⚠️ Only shown once there is something to disagree with. A warning that appears
                    // while somebody is still typing the second field is noise, and they learn to
                    // ignore it before it ever means anything.
                    val typed = typedEaten
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

/**
 * Offer to remember what was just typed, and say plainly when it cannot be.
 *
 * ⚠️ The switch is DISABLED with a reason rather than absent. A control that quietly vanishes when
 * the weight field is empty teaches nothing; one that says why teaches the rule — a saved food is a
 * density, and a density needs a weight.
 */
@Composable
private fun KeepThisFood(keep: Boolean, canKeep: Boolean, onToggle: (Boolean) -> Unit, reason: String?) {
    val c = Pulse.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Keep this food for next time",
                modifier = Modifier.weight(1f),
                fontFamily = ChakraPetch, fontSize = 12.sp, color = if (canKeep) c.ink else c.muted,
            )
            LcarsSwitch(checked = keep, onCheckedChange = onToggle, enabled = canKeep)
        }
        if (reason != null) {
            Text(reason, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, lineHeight = 13.sp)
        }
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

        item { LcarsHeaderBar("PHOTOGRAPHS") }
        item { ProgressPhotos(vm) }

        item { LcarsHeaderBar("OTHER APPS") }
        item { HealthConnectPanel(vm) }

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
/**
 * The daily things worth keeping up, and the walking that feeds what you burn.
 *
 * ⚠️ Nothing here is a checkbox. Every streak is derived from a record the app already keeps, so it
 * cannot be kept by tapping — which matters because [Expenditure] measures what you burn FROM the
 * calorie log, so "how consistently am I logging" is a statement about how far the number on COACH
 * can be relied on.
 */
@Composable
fun HabitsBody(vm: HealthViewModel) {
    val c = Pulse.colors
    val habits by vm.habits.collectAsStateWithLifecycle()
    val steps by vm.steps.collectAsStateWithLifecycle()

    StepSensor(vm)

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = Pad, verticalArrangement = Arrangement.spacedBy(11.dp)) {
        item { StepsPanel(steps) }
        items(Habits.Habit.entries.toList(), key = { it.name }) { h ->
            StreakRow(h, habits[h])
        }
        item {
            Text(
                "These are counted from what is already recorded — the log, the scale, the targets. " +
                    "There is nothing to tick, because a streak you can tick says nothing about how " +
                    "far the measured expenditure can be trusted.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, lineHeight = 13.sp,
            )
        }
        item { ExportPanel(vm) }
    }
}

/**
 * Take the whole record away with you.
 *
 * ⚠️ **On this tab and not in Settings**, which is where every other data control in this app lives.
 * The habits are the one part of HEALTH that is about the *record* rather than about food or a body,
 * so an export sits with them rather than beside "clear usage data"; and the practical half is that
 * this view model already holds the two stores, where Settings' would need both threading through a
 * constructor and a factory for one button.
 */
@Composable
private fun ExportPanel(vm: HealthViewModel) {
    val c = Pulse.colors
    val busy by vm.exporting.collectAsStateWithLifecycle()
    val status by vm.exportStatus.collectAsStateWithLifecycle()
    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) vm.exportRecord(uri) }

    LcarsFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                "YOUR RECORD",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.accent,
            )
            Text(
                "Every entry, every day's totals, every weigh-in and every measurement, as four " +
                    "spreadsheets in one zip. It is yours; nothing here is sent anywhere.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, lineHeight = 13.sp,
            )
            LcarsButton(
                text = if (busy) "GATHERING…" else "EXPORT EVERYTHING",
                onClick = { save.launch("lcars-health.zip") },
                enabled = !busy,
            )
            if (busy) {
                // ⚠️ Said out loud because this genuinely takes a while: it opens every month of the
                // log at once, which is exactly what the log's sharding exists to avoid doing.
                Text(
                    "Reading the whole log — on a long record this takes a moment.",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.amber, lineHeight = 13.sp,
                )
            }
            if (status.isNotBlank()) {
                Text(status, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink, lineHeight = 14.sp)
            }
        }
    }
}

/**
 * Feed the pedometer while this tab is open.
 *
 * ⚠️ Only while it is open, and that costs nothing. `TYPE_STEP_COUNTER` is maintained by the
 * hardware whether or not anybody is listening, so the total is already right the moment somebody
 * looks — a collector running for the life of the process would hold the sensor registered for a
 * number nobody is reading.
 *
 * ⚠️ The permission is requested here rather than at startup. Without ACTIVITY_RECOGNITION the
 * sensor delivers no events at all on API 29+, which is why the raw field has never once held a
 * number; asking on the one screen that shows steps is both the honest place and the one where the
 * request has a visible reason.
 */
@Composable
private fun StepSensor(vm: HealthViewModel) {
    val context = LocalContext.current
    val container = (context.applicationContext as PulseApplication).container
    val scope = rememberCoroutineScope()
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
    // ⚠️ Keyed on the grant, and it owns the controller. `newTelemetryController()` is a FACTORY —
    // it hands back a fresh listener that has to be started and, more importantly, stopped, or the
    // sensor stays registered after the tab is gone. DisposableEffect is the shape that guarantees
    // the second half; a LaunchedEffect would start it and never take it down.
    if (!granted) return
    val vmRef = rememberUpdatedState(vm)
    DisposableEffect(granted) {
        val controller = container.newTelemetryController()
        controller.start()
        val job = scope.launch {
            controller.telemetry.collect { t -> t.stepCounterRaw?.let { vmRef.value.onSteps(it) } }
        }
        onDispose {
            job.cancel()
            controller.stop()
        }
    }
}

@Composable
private fun StepsPanel(steps: Habits.Steps?) {
    val c = Pulse.colors
    LcarsFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                "ON FOOT",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.accent,
            )
            // ⚠️ Null is "cannot tell", not zero. Showing 0 to somebody who has walked all morning
            // because the permission was refused is worse than saying the count is unavailable.
            Text(
                Habits.describe(steps)
                    ?: if (steps == null) "No step count — the pedometer is not reporting."
                    else "Nothing much yet today.",
                fontFamily = ChakraPetch, fontSize = 20.sp, color = c.ink,
            )
            if (steps?.partial == true) {
                Text(
                    "The phone restarted, so the steps before that are not recoverable — the counter " +
                        "begins again from zero and nothing recorded the old total.",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.amber, lineHeight = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun StreakRow(h: Habits.Habit, s: Habits.Streak?) {
    val c = Pulse.colors
    LcarsFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(h.label, fontFamily = ChakraPetch, fontSize = 14.sp, color = c.ink)
                Text(
                    if (s != null && s.current > 0) "${s.current}" else "—",
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    color = if (s != null && s.doneToday) c.accent else c.muted,
                )
            }
            s?.let { Habits.summary(it) }?.let {
                Text(it, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
            }
            Text(h.blurb, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, lineHeight = 13.sp)
            // The record stands even when the current run does not, which is the more encouraging
            // fact and the only one available on a broken streak.
            if (s != null && s.longest > 0 && s.longest > s.current) {
                Text(
                    "Longest so far: ${s.longest} days.",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                )
            }
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

/**
 * Photographs, which are the measurement the scale is worst at.
 *
 * Weight moves for reasons that have nothing to do with what anybody is changing — water, salt, the
 * time of day, last night's dinner — and the tape catches only some of the rest. Twelve weeks apart,
 * a photograph is the one record that shows the thing itself.
 *
 * ⚠️ The privacy line is on screen rather than in a KDoc nobody reads, and it says the cost as well
 * as the benefit: these never enter the camera roll, and uninstalling takes them with it. Both
 * follow from the same decision to keep them app-private, and somebody should get to know that
 * before they have a year of them.
 */
@Composable
private fun ProgressPhotos(vm: HealthViewModel) {
    val c = Pulse.colors
    val photos by vm.photos.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }

    val capture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        // ⚠️ Recorded only on success. A cancelled capture leaves a zero-byte file behind, and an
        // index row pointing at one renders as a thumbnail that can never load.
        pending?.let { if (ok) vm.photoTaken(it) }
        pending = null
    }

    LcarsFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            LcarsButton(
                text = "TAKE A PHOTO",
                onClick = {
                    val slot = vm.reservePhoto() ?: return@LcarsButton
                    pending = slot.first
                    capture.launch(slot.second)
                },
            )
            if (photos.isEmpty()) {
                Text(
                    "None yet. One now and one in twelve weeks is the comparison the scale cannot make.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(photos, key = { it.id }) { p ->
                        Column(
                            Modifier
                                .width(PHOTO_W)
                                .clickable { viewing = if (viewing == p.id) null else p.id },
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            // Coil downsamples to the drawn size, so a row of full-resolution JPEGs
                            // never reaches memory whole. The shared ImageLoader is the app's.
                            AsyncImage(
                                model = vm.photoUri(p.id),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(PHOTO_W)
                                    .height(PHOTO_H)
                                    .clip(lcarsBlockShape(6.dp, LcarsCorner.TopStart))
                                    .background(c.raise),
                            )
                            Text(
                                relativeDay(p.atMs),
                                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                val open = viewing
                if (open != null && photos.any { it.id == open }) {
                    AsyncImage(
                        model = vm.photoUri(open),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(PHOTO_OPEN_H),
                    )
                    LcarsButton(
                        text = "DELETE THIS ONE",
                        color = c.negative,
                        onClick = {
                            vm.forgetPhoto(open)
                            viewing = null
                        },
                    )
                }
            }
            Text(
                "Kept on this phone only — never in the camera roll and never sent anywhere. That " +
                    "also means uninstalling takes them with it, and the spreadsheet export does " +
                    "not include them.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, lineHeight = 13.sp,
            )
        }
    }
}

private val PHOTO_W = 92.dp
private val PHOTO_H = 122.dp
private val PHOTO_OPEN_H = 360.dp

/**
 * Let a scale or a watch fill the record in, if this device has Health Connect at all.
 *
 * ⚠️ **The whole panel is a capability check, and the "no" case is a first-class state.** Below
 * Android 14 Health Connect is a separate app; from 14 it is part of the platform — but a de-Googled
 * or hardened build can ship without it, and this app's own device gate targets GrapheneOS, where
 * its presence is **unverified**. Absence is stated plainly, alongside the fact that nothing here
 * stops working: weight is typed in and steps come from the phone's own pedometer.
 *
 * ⚠️ The availability is read on every composition rather than remembered. Health Connect can be
 * installed while the app is alive, and a status decided once would leave the panel greyed out
 * after somebody did the exact thing it told them to.
 */
@Composable
private fun HealthConnectPanel(vm: HealthViewModel) {
    val c = Pulse.colors
    val bridge = vm.healthConnect()
    val status by vm.syncStatus.collectAsStateWithLifecycle()
    val availability = bridge.availability()
    var granted by remember { mutableStateOf(false) }

    // ⚠️ ONE launcher, created unconditionally. A `rememberLauncherForActivityResult` inside a
    // conditional branch exists in some compositions and not others, which is a fragile thing to
    // write in a file no local gate can type-check. The contract needs no provider to construct —
    // it only describes an intent — so the gate lives on the button that launches it.
    val ask = rememberLauncherForActivityResult(remember { bridge.permissionContract() }) { result ->
        granted = result.containsAll(bridge.permissions)
    }
    LaunchedEffect(availability) {
        granted = availability is HealthConnectBridge.Availability.Ready && bridge.hasAll()
    }

    LcarsFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "HEALTH CONNECT",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.accent,
            )
            when (availability) {
                is HealthConnectBridge.Availability.Missing -> Text(
                    availability.reason,
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp,
                )
                HealthConnectBridge.Availability.UpdateNeeded -> Text(
                    "Health Connect is installed but too old to talk to. Updating it from the Play " +
                        "Store or your app store is all this needs.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber, lineHeight = 14.sp,
                )
                HealthConnectBridge.Availability.Ready -> {
                    Text(
                        if (granted) {
                            "Connected. Weigh-ins recorded by a scale or another app can be brought " +
                                "in, and readings typed here are published back."
                        } else {
                            "Available on this device. Weight and steps only — nothing about food, " +
                                "sleep or exercise is asked for."
                        },
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp,
                    )
                    if (!granted) {
                        LcarsButton(
                            text = "ALLOW WEIGHT & STEPS",
                            onClick = { runCatching { ask.launch(bridge.permissions) } },
                        )
                    } else {
                        LcarsButton(text = "BRING IN NEW WEIGH-INS", onClick = { vm.importFromHealthConnect() })
                    }
                }
            }
            if (status.isNotBlank()) {
                Text(status, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink, lineHeight = 14.sp)
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

// -------------------------------------------------------------------------- photograph a meal

/**
 * Photograph a plate; get proposals to correct and confirm.
 *
 * ⚠️ **The model names the foods; the numbers come from real records**, and the surface says so
 * rather than leaving it to be inferred. A model answering "320 kcal" has weighed nothing and read
 * no label, and that figure would sit in the log beside a laboratory analysis looking exactly like
 * one. Every kilocalorie shown here was looked up by name in the bundled corpus.
 *
 * ⚠️ Nothing is logged until the button is pressed. A photograph is the least certain input this app
 * takes — the portion above all — so every weight is editable and every item can be dropped.
 *
 * ⚠️ This is the one part of the food half that cannot work offline, and [MealShot.NoVision] says
 * that in words instead of the button doing nothing.
 */
@Composable
private fun PhotoOfAMeal(vm: HealthViewModel, meal: NutritionDay.Meal) {
    val c = Pulse.colors
    val context = LocalContext.current
    val shot by vm.mealShot.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<android.net.Uri?>(null) }

    val capture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        // ⚠️ Read only on success. A cancelled capture leaves a zero-byte file behind, and decoding
        // one produces a null bitmap — which would surface as "that photograph could not be read"
        // for a photograph nobody took.
        val uri = pending
        pending = null
        if (ok && uri != null) vm.readMealPhoto(context, uri)
    }

    fun shoot() {
        val uri = createCameraImageUri(context) ?: return
        pending = uri
        capture.launch(uri)
    }

    LcarsFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(
                "PHOTOGRAPH A MEAL",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.accent,
            )
            when (val st = shot) {
                is HealthViewModel.MealShot.Idle -> {
                    LcarsButton(text = "◉ PHOTOGRAPH A MEAL", onClick = { shoot() })
                    Text(
                        "It names what is on the plate and estimates the weights. Every calorie and " +
                            "gram comes from a real food record, not from the picture — so check the " +
                            "weights before logging. Needs a connection and a vision model.",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, lineHeight = 13.sp,
                    )
                }
                is HealthViewModel.MealShot.Reading -> Text(
                    "Reading the photograph…",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                )
                is HealthViewModel.MealShot.NoVision -> {
                    Text(
                        "No vision model is set up, so nothing can look at a photograph. Add a cloud " +
                            "key in the Computer's setup and this works; everything else in the food " +
                            "half stays offline either way.",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber, lineHeight = 14.sp,
                    )
                    LcarsButton(text = "CLOSE", onClick = { vm.clearMealShot() })
                }
                is HealthViewModel.MealShot.NotFood -> {
                    Text(
                        "That does not look like food.",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        LcarsButton(text = "TRY AGAIN", onClick = { shoot() })
                        LcarsButton(text = "CLOSE", onClick = { vm.clearMealShot() })
                    }
                }
                is HealthViewModel.MealShot.Failed -> {
                    Text(
                        st.reason,
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber, lineHeight = 14.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        LcarsButton(text = "TRY AGAIN", onClick = { shoot() })
                        LcarsButton(text = "CLOSE", onClick = { vm.clearMealShot() })
                    }
                }
                is HealthViewModel.MealShot.Plate -> PlateReview(st, meal, vm, onRetake = { shoot() })
            }
        }
    }
}

/**
 * The review step: what the model saw, what each item was matched to, and what it will log.
 *
 * ⚠️ Unmatched items are shown rather than hidden, and counted in the button's own sentence. Quietly
 * dropping them is how somebody comes to believe a day is fully logged when part of the plate never
 * reached it.
 */
@Composable
private fun PlateReview(
    plate: HealthViewModel.MealShot.Plate,
    meal: NutritionDay.Meal,
    vm: HealthViewModel,
    onRetake: () -> Unit,
) {
    val c = Pulse.colors
    val loggable = plate.proposals.count { it.loggable }
    val unmatched = plate.proposals.size - loggable
    Text(
        plate.summary,
        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink, lineHeight = 14.sp,
    )
    plate.proposals.forEachIndexed { i, p ->
        PlateRow(
            proposal = p,
            onGrams = { vm.editMealGrams(i, it) },
            onDrop = { vm.dropMealItem(i) },
        )
    }
    val total = plate.proposals.sumOf { it.eaten?.kcal ?: 0.0 }
    if (loggable > 0) {
        Text(
            "${total.roundToInt()} kcal across $loggable " + if (loggable == 1) "item" else "items",
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.accent,
        )
    }
    if (unmatched > 0) {
        Text(
            "$unmatched " + (if (unmatched == 1) "item has" else "items have") +
                " no match in the food database and will not be logged. Rename one to something " +
                "the database knows, or add it below with QUICK ADD.",
            fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.amber, lineHeight = 13.sp,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        LcarsButton(
            text = if (loggable > 0) "LOG $loggable TO ${meal.label.uppercase()}" else "NOTHING TO LOG",
            enabled = loggable > 0,
            onClick = { vm.logPlate(meal) },
        )
        LcarsButton(text = "RETAKE", onClick = onRetake)
        LcarsButton(text = "DISCARD", onClick = { vm.clearMealShot() })
    }
}

/**
 * One proposed item: the model's words, an editable weight, and what it was matched to.
 *
 * ⚠️ The weight is held locally while it is being typed and committed on every parseable keystroke.
 * Reading it straight out of the view model would fight the typing — a half-typed "1" is a valid
 * number and would be rounded and written back under the caret.
 */
@Composable
private fun PlateRow(
    proposal: MealPhotoReader.Proposal,
    onGrams: (Double) -> Unit,
    onDrop: () -> Unit,
) {
    val c = Pulse.colors
    // ⚠️ Keyed on the item's name so a DROP, which shifts every later item up one index, re-seeds
    // the field from the item now at this position rather than leaving the old one's weight behind.
    var text by remember(proposal.item.name) {
        mutableStateOf(proposal.item.grams.roundToInt().toString())
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                proposal.item.name,
                modifier = Modifier.weight(1f),
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (proposal.item.confidence == MealPhoto.Confidence.GUESSED) {
                Text(
                    "ESTIMATED",
                    fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 0.8.sp, color = c.amber,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            NumberCell(
                label = "GRAMS",
                value = text,
                onChange = {
                    text = it
                    it.toDoubleOrNull()?.let(onGrams)
                },
                modifier = Modifier.width(GRAMS_W),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val match = proposal.match
                val eaten = proposal.eaten
                if (match != null && eaten != null) {
                    Text(
                        "${eaten.kcal.roundToInt()} kcal · P ${eaten.proteinG.roundToInt()} " +
                            "F ${eaten.fatG.roundToInt()} C ${eaten.carbG.roundToInt()}",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.accent,
                    )
                    Text(
                        // Named so it is obvious WHERE the numbers came from, which is the whole
                        // reason this feature is trustworthy.
                        "from ${match.display}",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        "no match — rename or drop it",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.amber,
                    )
                }
            }
            LcarsButton(text = "DROP", onClick = onDrop)
        }
    }
}

private val GRAMS_W = 78.dp

// ------------------------------------------------------------------------------- finding a food

/**
 * Search the food databases and log a portion of what comes back.
 *
 * ⚠️ The whole flow is one card rather than a route of its own, and that is deliberate: it sits above
 * QUICK ADD because it is the path somebody should reach for first, and a search that navigated away
 * would lose the meal they had already chosen. QUICK ADD stays below it for the food no database has.
 */
@Composable
private fun FindAFood(vm: HealthViewModel, meal: NutritionDay.Meal) {
    val c = Pulse.colors
    val search by vm.search.collectAsStateWithLifecycle()
    val picked by vm.picked.collectAsStateWithLifecycle()
    var scanning by remember { mutableStateOf(false) }

    // ⚠️ This card DECLARES what its picks are for rather than relying on the default. The search box
    // and the picked food are shared with the recipe builder, so opening a builder and then coming
    // here without closing it left `pickFor` on RECIPE — and the next food chosen for the log would
    // also drop into the ingredient slot. `searchFor` is a no-op when the target already matches, so
    // this costs nothing on the ordinary path.
    LaunchedEffect(Unit) { vm.searchFor(HealthViewModel.PickFor.LOG) }

    // ⚠️ The scanner REPLACES this card rather than sitting inside it. A viewfinder is the whole
    // screen's worth of attention, and leaving a text field and a result list live underneath it
    // would mean the camera is holding the floor while somebody types.
    if (scanning) {
        BarcodeScanner(
            onCode = { code ->
                scanning = false
                vm.lookUpBarcode(code)
            },
            onCancel = { scanning = false },
        )
        return
    }

    LcarsFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "FIND A FOOD",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.accent,
            )
            LcarsField(
                search.query,
                vm::onSearchQuery,
                placeholder = "Chicken breast, olive oil, a brand, a takeaway…",
            )
            // Nobody types "Ferrero Nutella hazelnut spread" standing in a kitchen, and Open Food
            // Facts is organised around barcodes because that is how a shelf identifies itself.
            LcarsButton(text = "⬚ SCAN A BARCODE", onClick = { scanning = true })
            when {
                search.busy -> Text(
                    "Looking…",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                )
                // ⚠️ Only once they have actually typed enough to have searched. "No matches" under a
                // half-typed word is the screen calling somebody wrong mid-sentence.
                search.query.trim().length >= 2 && search.results.isEmpty() -> Text(
                    // ⚠️ Says WHY rather than just "no matches", because the commonest reason is a
                    // knowable one. Every word has to match: "chipotle burrito bowl" finds nothing
                    // while "burrito bowl" finds ten. And the free data names only a handful of the
                    // big American chains — measured, and recorded where the data is built rather
                    // than listed here, because a list in UI copy drifts from the corpus it
                    // describes the moment either changes.
                    "No matches. Every word has to match, so fewer words usually finds more — and " +
                        "a dish on its own (\"burrito bowl\", \"cheeseburger\") is more likely to be " +
                        "in there than the name of the place you got it. QUICK ADD below takes the " +
                        "numbers straight off a label.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp,
                )
            }
            if (search.note.isNotBlank()) {
                Text(
                    search.note,
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.amber, lineHeight = 13.sp,
                )
            }
            val chosen = picked
            if (chosen == null) {
                search.results.take(12).forEach { food ->
                    FoodResultRow(food) { vm.pick(food) }
                }
                // ⚠️ Shown only with the box empty, and here rather than on a settings page of its
                // own. This is where somebody looks for a food, so it is where they will look for
                // the one they made — and it costs nothing while they are typing, because the
                // moment there is a query the results replace it.
                if (search.query.isBlank()) MyFoods(vm)
            } else {
                PortionPicker(chosen, meal, vm)
            }
        }
    }
}

/**
 * Foods typed in by hand — pick one to log it, or forget it.
 *
 * ⚠️ Forgetting a food does NOT touch what was logged with it. An entry stores its own numbers, so
 * the record stays exactly as it was; only the shortcut goes. Deleting a food that quietly rewrote
 * six months of history would be a far worse thing than a stale list.
 */
@Composable
private fun MyFoods(vm: HealthViewModel) {
    val c = Pulse.colors
    val mine by vm.myFoods.collectAsStateWithLifecycle()
    if (mine.isEmpty()) return
    Text(
        "YOUR FOODS",
        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 11.sp,
        letterSpacing = 1.sp, color = c.muted,
    )
    mine.take(MY_FOODS_SHOWN).forEach { food ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { FoodResultRow(food) { vm.pick(food) } }
            Text(
                "FORGET",
                modifier = Modifier
                    .clickable { vm.forgetFood(food.id) }
                    .padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.8.sp, color = c.muted,
            )
        }
    }
    if (mine.size > MY_FOODS_SHOWN) {
        Text(
            "…and ${mine.size - MY_FOODS_SHOWN} more — search to find them.",
            fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
        )
    }
}

/**
 * ⚠️ A list, not a scroll. This sits inside the search card on a page that already scrolls, so an
 * unbounded personal list would push everything below it — including QUICK ADD, the thing you use
 * to add to that very list — off the bottom of the page.
 */
private const val MY_FOODS_SHOWN = 6

/** One search result: what it is, where the numbers came from, and what it costs per 100 g. */
@Composable
private fun FoodResultRow(food: Food, onPick: () -> Unit) {
    val c = Pulse.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 4.dp),
    ) {
        Text(
            food.display,
            fontFamily = ChakraPetch, fontSize = 13.sp, color = c.ink, lineHeight = 16.sp,
        )
        Text(
            // ⚠️ Always says per 100 g. Two rows showing "148" mean nothing to each other unless the
            // basis is stated, and this corpus mixes foods whose natural portion differs tenfold.
            "${food.per100g.kcal.roundToInt()} kcal / 100 g · " +
                "P ${fmt1(food.per100g.proteinG)} F ${fmt1(food.per100g.fatG)} C ${fmt1(food.per100g.carbG)}" +
                " · ${food.source.label}",
            fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, lineHeight = 13.sp,
        )
    }
}

/**
 * How much of it, and what that comes to.
 *
 * ⚠️ The units offered come from [FoodPortion.unitsFor], which asks the food what it can express.
 * A record with no declared serving weight simply does not offer "serving" — rather than offering it
 * and quietly guessing, which is how a portion becomes a number nobody can check.
 */
@Composable
private fun PortionPicker(food: Food, meal: NutritionDay.Meal, vm: HealthViewModel) {
    val c = Pulse.colors
    val units = remember(food.id) { FoodPortion.unitsFor(food.sizes) }
    var unit by remember(food.id) { mutableStateOf(units.first()) }
    var amount by remember(food.id) { mutableStateOf(if (unit == FoodPortion.Unit.GRAM) "100" else "1") }

    val value = amount.replace(',', '.').toDoubleOrNull()
    val grams = value?.let { FoodPortion.gramsFor(FoodPortion.Portion(it, unit), food.sizes) }
    val eaten = grams?.let { FoodPortion.eaten(food.per100g, it) }

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(food.display, fontFamily = ChakraPetch, fontSize = 13.sp, color = c.ink, lineHeight = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            units.forEach { u ->
                LcarsChip(u.label.uppercase(), unit == u, {
                    unit = u
                    // Switching units keeps the amount sensible: 100 grams, but one of anything else.
                    amount = if (u == FoodPortion.Unit.GRAM) "100" else "1"
                })
            }
        }
        NumberCell("HOW MUCH", amount, { amount = it }, Modifier.fillMaxWidth())
        if (eaten != null) {
            Text(
                "${eaten.kcal.roundToInt()} kcal · P ${fmt1(eaten.proteinG)} " +
                    "F ${fmt1(eaten.fatG)} C ${fmt1(eaten.carbG)}",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.accent,
            )
            Text(
                FoodPortion.describe(FoodPortion.Portion(value ?: 0.0, unit), food.sizes),
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LcarsButton(
                text = "LOG TO ${meal.label.uppercase()}",
                enabled = eaten != null,
                modifier = Modifier.weight(1f),
                onClick = { if (value != null) vm.logPortion(food, value, unit, meal) },
            )
            LcarsButton(text = "BACK", modifier = Modifier.weight(0.5f), onClick = { vm.pick(null) })
        }
    }
}

/** One decimal, and never the device locale — these sit beside numbers rendered elsewhere. */
private fun fmt1(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

/**
 * One tap to log something eaten before.
 *
 * ⚠️ Deliberately a horizontal rail rather than a list. It sits between search and quick-add on a
 * page that is already long, and a vertical list of twenty rows would push the thing somebody
 * actually came to do below the fold — which is how a convenience becomes an obstacle.
 */
@Composable
private fun EatenBefore(vm: HealthViewModel, meal: NutritionDay.Meal) {
    val c = Pulse.colors
    val recents by vm.recents.collectAsStateWithLifecycle()
    if (recents.isEmpty()) return

    LcarsFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                "EATEN BEFORE",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.accent,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(recents, key = { it.id }) { e ->
                    Column(
                        Modifier
                            .widthIn(min = 96.dp, max = 150.dp)
                            .clickable { vm.logAgain(e, meal) }
                            .background(c.raise, lcarsBlockShape(8.dp, LcarsCorner.TopStart))
                            .padding(horizontal = 9.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            e.name,
                            fontFamily = ChakraPetch, fontSize = 12.sp, color = c.ink,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp,
                        )
                        Text(
                            // The portion is part of the identity of the tap: two rows reading
                            // "Porridge" that log different amounts would be indistinguishable.
                            "${e.nutrients.kcal.roundToInt()} kcal" +
                                if (e.grams > 0.0) " · ${e.grams.roundToInt()} g" else "",
                            fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                        )
                    }
                }
            }
            Text(
                "Tap to log it again to ${meal.label.lowercase()}.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            )
        }
    }
}
