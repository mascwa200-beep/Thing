package dev.mascwa.pulse.feature.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.Body
import dev.mascwa.pulse.core.telemetry.BodyTrend
import dev.mascwa.pulse.core.telemetry.Expenditure
import dev.mascwa.pulse.core.telemetry.MacroTargets
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsChip
import dev.mascwa.pulse.feature.common.LcarsField
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LcarsTabRow
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import java.time.LocalDate

/**
 * HEALTH — what you eat, what your body does about it, and what to do next.
 *
 * Six sub-tabs on the established rail, in the order a day is actually used: see where you are, log
 * what you ate, step on the scale, adjust the plan, keep the dishes you cook, keep the habits.
 *
 * ⚠️ The whole tab rests on three pure cores — [BodyTrend], [Expenditure] and [MacroTargets] — and
 * draws nothing it has not been given. Where a number cannot be computed yet the surface says which
 * fact is missing rather than showing a plausible zero, because a calorie target is the one thing this
 * app produces that somebody acts on with their body.
 */
enum class HealthTab(val label: String) {
    MACROS("MACROS"),
    INTAKE("INTAKE"),
    BODY("BODY"),
    COACH("COACH"),
    RECIPES("RECIPES"),
    HABITS("HABITS"),
}

@Composable
fun HealthScreen(vm: HealthViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val idx by vm.tabIndex.collectAsStateWithLifecycle()
    val tab = HealthTab.entries[idx.coerceIn(0, HealthTab.entries.lastIndex)]

    // ⚠️ On every entry, not once. The view model outlives the composition — this app's panel
    // transitions take a tab's composable out of composition when you leave it — so `LaunchedEffect(Unit)`
    // genuinely re-runs on return, which is what carries the log across midnight and picks up a
    // weigh-in recorded from somewhere else since.
    LaunchedEffect(Unit) { vm.refresh() }

    PulseScaffold(title = "Health") { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            if (!state.profile.configured) {
                HealthSetup(vm, state)
                return@Column
            }
            LcarsTabRow(
                tabs = HealthTab.entries.map { it.label },
                selected = tab.ordinal,
                onSelect = { vm.tabIndex.value = it },
            )
            when (tab) {
                HealthTab.MACROS -> MacrosBody(vm, state)
                HealthTab.INTAKE -> IntakeBody(vm, state)
                HealthTab.BODY -> BodyBody(vm, state)
                HealthTab.COACH -> CoachBody(vm, state)
                HealthTab.RECIPES -> RecipesBody(vm)
                HealthTab.HABITS -> HabitsBody(vm)
            }
        }
    }
}

/**
 * The one screen that has to come before any of the others.
 *
 * ⚠️ Nothing here is optional and nothing is guessed. Height, year of birth and a first weigh-in are
 * what [Body.bmr] needs, and without a resting rate there is no floor under the calorie target — which
 * is the single guardrail this feature most needs. So the tab refuses to show a plan at all until it
 * has them, rather than filling in a population average and letting somebody eat to it.
 */
@Composable
private fun HealthSetup(vm: HealthViewModel, state: HealthViewModel.State) {
    val c = Pulse.colors
    val p = state.profile
    var height by remember(p.heightCm) { mutableStateOf(if (p.heightCm > 0) p.heightCm.toInt().toString() else "") }
    var year by remember(p.birthYear) { mutableStateOf(if (p.birthYear > 0) p.birthYear.toString() else "") }
    var weight by remember { mutableStateOf("") }

    val heightOk = height.toDoubleOrNull()?.let { it in Body.MIN_HEIGHT_CM..Body.MAX_HEIGHT_CM } == true
    val thisYear = LocalDate.now().year
    val yearOk = year.toIntOrNull()?.let { thisYear - it in Body.MIN_AGE_YEARS..Body.MAX_AGE_YEARS } == true
    val haveWeighin = state.latest != null
    val weightOk = haveWeighin || weight.toDoubleOrNull()?.let { it in Body.MIN_KG..Body.MAX_KG } == true

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(13.dp)) {
        item {
            LcarsFrame(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(
                        "SET UP HEALTH",
                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.accent,
                    )
                    Text(
                        "Three facts, and this stops being a calculator and starts measuring you. " +
                            "Everything stays on this device.",
                        fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink2, lineHeight = 17.sp,
                    )
                }
            }
        }
        item {
            SetupField(
                label = "HEIGHT — CENTIMETRES",
                value = height,
                onChange = { height = it.filter { ch -> ch.isDigit() }.take(3) },
                ok = heightOk,
                why = "Needed for the resting rate every target is floored at.",
            )
        }
        item {
            SetupField(
                label = "YEAR OF BIRTH",
                value = year,
                onChange = { year = it.filter { ch -> ch.isDigit() }.take(4) },
                ok = yearOk,
                why = "A year, not an age — an age would be wrong within twelve months and stay wrong.",
            )
        }
        item {
            Column(Modifier.padding(top = 11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    "SEX",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.9.sp, color = c.muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Body.Sex.entries.forEach { s ->
                        LcarsChip(
                            text = when (s) {
                                Body.Sex.MALE -> "Male"
                                Body.Sex.FEMALE -> "Female"
                                Body.Sex.UNSPECIFIED -> "Rather not"
                            },
                            selected = p.sex == s.name,
                            onClick = { vm.setSex(s) },
                        )
                    }
                }
                Text(
                    "Only used for the resting-rate formula. Leaving it takes the higher of the two, " +
                        "which makes the floor under your calories higher rather than lower.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp,
                )
            }
        }
        if (!haveWeighin) {
            item {
                SetupField(
                    label = "WEIGH IN NOW — KILOGRAMS",
                    value = weight,
                    onChange = { weight = it.filter { ch -> ch.isDigit() || ch == '.' }.take(6) },
                    ok = weightOk,
                    why = "The trend starts here. One reading is enough to begin.",
                )
            }
        }
        item {
            Column(Modifier.padding(top = 15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                LcarsButton(
                    text = "START",
                    enabled = heightOk && yearOk && weightOk,
                    onClick = {
                        height.toDoubleOrNull()?.let(vm::setHeightCm)
                        year.toIntOrNull()?.let(vm::setBirthYear)
                        weight.toDoubleOrNull()?.takeIf { !haveWeighin }?.let(vm::recordWeighin)
                        vm.setConfigured(true)
                    },
                )
                Text(
                    MacroTargets.DISCLAIMER,
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber, lineHeight = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun SetupField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    ok: Boolean,
    why: String,
) {
    val c = Pulse.colors
    Column(Modifier.padding(top = 11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.9.sp, color = c.muted)
        LcarsField(value = value, onValueChange = onChange, placeholder = "—")
        Text(
            why,
            fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 14.sp,
            color = if (value.isNotBlank() && !ok) c.negative else c.muted,
        )
    }
}

/**
 * A shared readout for the state where a number genuinely cannot be produced yet.
 *
 * ⚠️ It names the missing fact rather than showing a dash. "No expenditure yet" tells somebody nothing;
 * "eleven of the last twenty-eight days are logged, and it needs most of them" tells them what to do.
 */
@Composable
internal fun NotYet(text: String, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    LcarsFrame(modifier.fillMaxWidth(), accent = c.muted) {
        Text(text, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink2, lineHeight = 17.sp)
    }
}

/** A transient line for something that just happened, cleared once it has been on screen a moment. */
@Composable
internal fun Notice(vm: HealthViewModel) {
    val notice by vm.notice.collectAsStateWithLifecycle()
    val c = Pulse.colors
    val text = notice ?: return
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(2_600)
        vm.clearNotice()
    }
    LcarsFrame(Modifier.fillMaxWidth(), accent = c.positive) {
        Text(text, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.positive)
    }
}

/** Shared by the bodies: the expenditure line, said the same way everywhere it appears. */
internal fun expenditureLine(state: HealthViewModel.State): String {
    val e = state.expenditure ?: return "Not enough to go on yet."
    return Expenditure.sentence(e)
}
