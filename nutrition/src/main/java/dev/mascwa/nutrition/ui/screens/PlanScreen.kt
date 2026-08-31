package dev.mascwa.nutrition.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.nutrition.data.NutritionContainer
import dev.mascwa.nutrition.ui.ChipRow
import dev.mascwa.nutrition.ui.EnergyChart
import dev.mascwa.nutrition.ui.SectionCard
import dev.mascwa.nutrition.ui.StatRow
import dev.mascwa.nutrition.ui.round
import dev.mascwa.pulse.core.telemetry.BmrEquations
import dev.mascwa.pulse.core.telemetry.Body
import dev.mascwa.pulse.core.telemetry.BodyTrend
import dev.mascwa.pulse.core.telemetry.CheckIn
import dev.mascwa.pulse.core.telemetry.Decimals
import dev.mascwa.pulse.core.telemetry.EnergyBalance
import dev.mascwa.pulse.core.telemetry.Expenditure
import dev.mascwa.pulse.core.telemetry.MacroTargets
import dev.mascwa.pulse.core.telemetry.Maintenance
import dev.mascwa.pulse.core.telemetry.WeeklyPlan
import dev.mascwa.pulse.feature.health.HealthViewModel
import kotlin.math.abs

/**
 * Who you are and where you are going — the handful of facts every target is derived from.
 *
 * ⚠️ **Nothing on this page is a target.** Targets are computed by `MacroTargets` from these inputs
 * plus what the log has measured, and shown on Today. Letting somebody type a calorie goal directly
 * would make the measurement pointless, which is the whole idea this app is built around.
 */

/**
 * The check-in, so a target that has stopped moving reads as a decision rather than a fault.
 *
 * ⚠️ **The numbers on Today no longer change on their own.** They used to move with every weigh-in
 * and every logged meal, because the planner ran on every state build — see `CheckIn` for why that is
 * the opposite of a plan. Having fixed it, the app has to say so: a figure that quietly stopped
 * responding would look broken to anybody who had noticed it responding before.
 *
 * ⚠️ The sentences are the STORED report, in the words the check-in used at the time. Recomputing
 * them here against today's figures would answer a different question and would change with every
 * meal logged — the drift this feature removes, put back in the caption.
 */
@Composable
private fun CheckInCard(vm: HealthViewModel, state: HealthViewModel.State) {
    val v = state.checkIn ?: return
    SectionCard("Your check-in") {
        Text(
            when (v) {
                is CheckIn.Verdict.Hold -> when (v.daysUntilDue) {
                    0 -> "New targets are due today."
                    1 -> "These targets stand until tomorrow."
                    else -> "These targets stand for another ${v.daysUntilDue} days."
                }
                // ⚠️ Present tense: by the time this draws, the view model's collector has already
                // published, and a line promising something that has happened reads as a stuck screen.
                is CheckIn.Verdict.Publish -> v.why.sentence
            },
            style = MaterialTheme.typography.titleMedium,
        )
        state.checkInReport.forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "Your expenditure is measured every day; the targets are handed down once a week, so you " +
                "have a number you can shop and cook against.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.livePlan is MacroTargets.Plan.Set) {
            Button(onClick = { vm.recalculateNow() }) { Text("Work them out now") }
        }
    }
}

@Composable
fun PlanScreen(vm: HealthViewModel, container: NutritionContainer) {
    val state by vm.state.collectAsStateWithLifecycle()
    val p = state.profile

    CheckInCard(vm, state)

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

    ProgramCard(vm, state)

    SectionCard(
        "How active you are",
        subtitle = "Only used until there is enough of a log to measure it instead.",
    ) {
        ChipRow(
            options = Expenditure.Activity.entries.map { it to it.name.lowercase().replaceFirstChar(Char::uppercase) },
            selected = runCatching { Expenditure.Activity.valueOf(p.activity) }
                .getOrDefault(Expenditure.Activity.LIGHT),
        ) { vm.setActivity(it) }

        // ⚠️ **Offered, not applied.** The value it would replace is one the person typed, and
        // rewriting somebody's own answer from a proxy teaches them the setting does not mean
        // anything. It only ever appears when the measured walking supports MORE than what is set —
        // see `Expenditure.suggestedActivity` for why it never points the other way.
        state.stepSuggestion?.let { suggested ->
            Button(onClick = { vm.setActivity(suggested) }) {
                Text("Your steps suggest ${suggested.name.lowercase()} — use it")
            }
        }

        // The shift is worth saying even when there is nothing to change: it is why the measured
        // figure is lagging, and a number that lags without explanation reads as a broken one.
        if (state.stepShift.changed) {
            Text(
                state.stepShift.sentence,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    SectionCard("What the app has worked out") {
        val e = state.expenditure
        StatRow("Burning", e?.let { "${round(it.kcal)} kcal a day" } ?: "Not enough yet")
        // ⚠️ Both halves stated, because they answer different questions: how much of the estimate
        // is measured rather than predicted, and how many days of log it rests on. A confident
        // figure from four days is not the same as the same figure from four weeks.
        StatRow("Measured", "${round(state.measuredShare * 100)}%")
        StatRow("Days logged", "${state.loggedDaysInWindow} of ${p.expenditureWindowDays}")

        // ⚠️ **Where the unmeasured half comes from**, and shown whether or not anything has been
        // measured yet — on the first day the whole figure IS the formula, so that is when saying so
        // matters most. `BmrEquations` kept the equation label and the dieting discount precisely
        // "so a surface can say, not imply", and both were computed and thrown away a line later
        // until this read them. The discount takes up to 8% off the resting rate, and therefore off
        // the calories this screen tells somebody to eat.
        //
        // ⚠️ Hoisted to locals before the null tests: `State` is declared in `:core:health`, and
        // Kotlin will not smart-cast a public property across a module boundary.
        val resting = state.resting
        val formula = state.formula
        if (resting != null) {
            if (formula != null) {
                StatRow("Formula alone", "${round(formula.kcal)} kcal a day")
                StatRow("Resting", "${round(resting.kcal)} kcal a day")
            }
            Text(
                BmrEquations.describe(resting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ⚠️ Beside the figure it qualifies rather than in the steps card above, for the reason that
        // card gives about the step shift: a measured number that lags with no explanation reads as a
        // broken one.
        if (state.intakeShift.changed) {
            Text(
                state.intakeShift.sentence,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ⚠️ Both of these are sentences the core produced, printed verbatim. They exist precisely
        // because the honest answer is often a refusal — "these two readings share their data", "at
        // this pace the change is slower than the scale's own noise" — and a screen that rephrased
        // them would eventually rephrase a refusal into a claim.
        Text(
            when (val r = state.recovery) {
                is Maintenance.Recovery.Measured -> r.sentence
                is Maintenance.Recovery.TooSoon -> r.sentence
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            when (val c = state.confirmation) {
                is Maintenance.Confirmation.InDays -> c.sentence
                is Maintenance.Confirmation.Never -> c.sentence
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ⚠️ Offered only when it would raise the target. Ending a deficit is the case this is for,
        // and showing "step DOWN to what was measured" as a button labelled about deficits would be
        // the wrong action wearing the right words — `Maintenance.stepUp` says the other case in
        // prose instead.
        val measuredNow = state.expenditure?.kcal
        val targetNow = state.targets?.kcal
        if (measuredNow != null && targetNow != null && p.ratePerWeekKg < 0.0) {
            val step = Maintenance.stepUp(targetNow, measuredNow)
            if (step.deltaKcal > 0) {
                Button(onClick = { vm.setRatePerWeekKg(0.0) }) { Text("Stop losing — eat at what was measured") }
                Text(
                    step.sentence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    BalanceCard(vm)

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

    UpdateCard(container.updates)

    FoodPackCard(container)

    AboutCard()

    DiagnosticsCard(container)

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
/**
 * Who is in charge of the calories, and what the week looks like as a result.
 *
 * ⚠️ **The three modes differ in who owns the TOTAL, and the card says so rather than listing three
 * words and leaving it to be guessed.** Coached means the app decides; collaborative means the app
 * keeps the weekly total and you decide its shape; manual means you own the number. A picker whose
 * options mean nothing until you try them is a picker nobody moves off the default.
 *
 * ⚠️ The day toggles appear only under collaborative, because they do nothing under the other two —
 * and a control that is present and inert is worse than one that is absent, since it teaches the
 * reader that this screen's controls sometimes do not work.
 */
/**
 * What you ate against what you burned, over an interval you choose.
 *
 * ⚠️ The burn line is the reading that was in force each day, worked out from the record up to that
 * day and nothing later — see `EnergyBalance` for why a single figure measured over the whole
 * interval would agree with the scale by construction and prove nothing. The caption says so on
 * screen, because a chart of an estimate that looks like a chart of a measurement is a chart that
 * claims more than it has.
 *
 * ⚠️ Asked for when the screen appears, never derived on every state build. It reads four months of
 * the food log, and hanging that off the state flow would repeat it on every logged meal.
 */
@Composable
private fun BalanceCard(vm: HealthViewModel) {
    val span by vm.balanceSpan.collectAsStateWithLifecycle()
    val reading by vm.balance.collectAsStateWithLifecycle()
    val loading by vm.balanceLoading.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { if (vm.balance.value == null) vm.loadBalance() }

    SectionCard("Energy balance") {
        ChipRow(
            options = EnergyBalance.Span.entries.map { it to it.label.lowercase().replaceFirstChar(Char::uppercase) },
            selected = span,
        ) { vm.setBalanceSpan(it) }

        val r = reading
        when {
            r == null && loading -> Text(
                "Working it out…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            r == null -> Text(
                "Nothing to show for this interval.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            r is EnergyBalance.Reading.NotYet -> Text(
                r.why,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            r is EnergyBalance.Reading.Ready -> {
                EnergyChart(
                    eaten = remember(r) { EnergyBalance.intakeRuns(r.days) },
                    burned = remember(r) { EnergyBalance.burnSeries(r.days) },
                    modifier = Modifier.fillMaxWidth(),
                )
                StatRow("Eaten", "${round(r.intakeKcal)} kcal")
                StatRow("Burned", "${round(r.expenditureKcal)} kcal")
                StatRow(
                    if (r.balanceKcal < 0) "Under" else "Over",
                    "${round(abs(r.balanceKcal))} kcal across ${r.pairedDays} days",
                    emphasis = true,
                )
                Text(EnergyBalance.summary(r, state.unit), style = MaterialTheme.typography.bodyMedium)
                EnergyBalance.reconciliation(r, state.unit)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "The burn line is what the measurement said at the time, re-taken every week from " +
                        "the record up to that day — not today's figure drawn backwards.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProgramCard(vm: HealthViewModel, state: HealthViewModel.State) {
    val week = state.week
    SectionCard("Your week", subtitle = state.programMode.ownsTheTotal) {
        ChipRow(
            options = WeeklyPlan.Mode.entries.map { it to it.label },
            selected = state.programMode,
        ) { vm.setProgramMode(it) }

        if (state.programMode == WeeklyPlan.Mode.COLLABORATIVE) {
            Text(
                "Tap the days you train. The same weekly total moves onto them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // ⚠️ The names live here and not in the core: which index is Monday is a calendar
                // question, and `WeeklyPlan` deliberately has no calendar in it.
                DAY_NAMES.forEachIndexed { index, name ->
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = index in state.profile.heavyDays,
                        onClick = { vm.toggleHeavyDay(index) },
                        label = { Text(name) },
                    )
                }
            }
            // ⚠️ Offered, never applied on its own — the training log may disagree with a choice
            // somebody made deliberately, and moving calories between days unasked is not a
            // suggestion, it is a decision. Silent when the two already agree.
            HeavyDaySuggestion(vm)
        }

        if (week == null) {
            Text(
                "There is no plan to spread yet — fill in the section above and log a few days.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(WeeklyPlan.sentence(week), style = MaterialTheme.typography.bodyMedium)
            week.days.forEach { day ->
                StatRow(
                    DAY_NAMES[day.index],
                    "${day.kcal} kcal · ${day.targets.proteinG}p ${day.targets.fatG}f ${day.targets.carbG}c",
                    emphasis = day.kind == WeeklyPlan.DayKind.HEAVY,
                )
            }
            // ⚠️ A limit that bit is always stated. The floor and the heavy-day cap are the two things
            // standing between somebody and a plan that looks arithmetically fine and is not eatable,
            // and a guard rail that works silently teaches nobody why their swing is smaller than asked.
            week.limits.forEach {
                Text(
                    it.sentence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** ⚠️ Index 0 is Monday here, and it is this file that decides that — see [ProgramCard]. */
private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

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
            .onFocusChanged { f -> if (!f.isFocused) Decimals.parse(text)?.let(onCommit) },
    )
}

// ChipRow moved to `ui/Common.kt` — a third caller was about to be written, and a copied one drifts.
