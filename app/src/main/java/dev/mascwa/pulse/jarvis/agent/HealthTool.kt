package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.core.telemetry.BodyTrend
import dev.mascwa.pulse.core.telemetry.Expenditure
import dev.mascwa.pulse.core.telemetry.MacroTargets
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.data.health.BodyStore
import dev.mascwa.pulse.data.health.FoodLogStore
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.feature.health.composeHealthReading
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What has been eaten, what the body is doing about it, and what is left for today.
 *
 * ⚠️ **It calls [composeHealthReading] — the same composition the HEALTH screen draws — rather than
 * doing its own arithmetic.** Nothing derived is stored anywhere in this feature, so the only way the
 * Computer and the screen could disagree about a calorie target is if there were two copies of the
 * maths. Getting that wrong here would be worse than usual: somebody asks how much is left, acts on
 * the answer, and the screen never contradicts it because they never open it.
 *
 * Read-only, and logging by voice is deliberately absent. A food is a name, a portion and a unit, and
 * a model that guesses any of the three writes a wrong number into the record that drives the whole
 * expenditure measurement. Search-and-confirm on screen is two taps and cannot be wrong that way.
 */
class HealthTool(
    private val foodLog: FoodLogStore,
    private val body: BodyStore,
    private val settings: SettingsRepository,
) : JarvisTool {
    override val name = "health"
    override val usage =
        "health [today|body|targets] — what has been eaten today, the weight trend and measured " +
            "expenditure, or the current calorie and macro targets. Defaults to today."

    override suspend fun run(arg: String): String = runCatching {
        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val entries = foodLog.entriesFor(todayStart)
        val state = composeHealthReading(
            p = settings.current().health,
            w = body.all(),
            todayEntries = entries,
            foodLog = foodLog,
            todayStartMs = todayStart,
        )
        when (arg.trim().lowercase()) {
            "body" -> bodyReport(state)
            "targets" -> targetsReport(state)
            else -> todayReport(state, entries)
        }
    }.getOrElse { "Could not read the health record: ${it.message}" }

    // -------------------------------------------------------------------------------- today

    private fun todayReport(state: dev.mascwa.pulse.feature.health.HealthViewModel.State, entries: List<NutritionDay.Entry>): String {
        if (entries.isEmpty()) return "Nothing logged today yet."
        return buildString {
            append("Today: ").append(NutritionDay.summarise(state.eatenToday))
            // ⚠️ Only when there IS a target. A "remaining" figure with nothing to remain against is
            // a number the reader takes as meaningful, computed from a guess.
            val targets = state.targets
            val left = state.remaining
            if (targets != null && left != null) {
                append("\nTarget ").append(targets.kcal).append(" kcal — ")
                append(if (left.overKcal) "${-left.kcal} over" else "${left.kcal} left")
                append("\nProtein ").append(state.eatenToday.proteinG.roundToInt())
                    .append(" of ").append(targets.proteinG).append(" g")
            }
            append("\nBy meal: ")
            append(
                NutritionDay.byMeal(entries).entries.joinToString("; ") { (meal, n) ->
                    "${meal.label} ${n.kcal.roundToInt()}"
                },
            )
        }
    }

    // --------------------------------------------------------------------------------- body

    private fun bodyReport(state: dev.mascwa.pulse.feature.health.HealthViewModel.State): String {
        val trend = state.trend
        if (trend is BodyTrend.Trend.TooLittle) return trend.sentence
        val latest = state.latest ?: return "No weigh-ins recorded yet."
        val unit = state.unit

        return buildString {
            append("Weight trend ").append(mass(latest.trendKg, unit))
            append(" (the scale last said ").append(mass(latest.observedKg, unit)).append(")")
            // ⚠️ A rate is quoted only when its own interval excludes zero. `rateIsClear` is the
            // core's judgement on that, and ignoring it means telling somebody they are losing
            // weight when the data cannot tell losing from gaining — on a feature where the
            // response is to eat less.
            if (latest.rateIsClear) {
                val perWeek = latest.ratePerWeekKg
                append("\n").append(if (perWeek < 0) "Losing " else "Gaining ")
                append(mass(abs(perWeek), unit)).append(" a week")
            } else {
                append("\nNo clear direction yet — the change so far is within the noise.")
            }
            append("\n").append(expenditureLine(state))
        }
    }

    /**
     * ⚠️ Every branch says something, and the refusals say WHY.
     *
     * An expenditure estimate offered too early is the one that gets acted on hardest, so the cores
     * refuse rather than guess — and a refusal rendered as silence would look like a broken feature
     * instead of an honest one.
     */
    private fun expenditureLine(state: dev.mascwa.pulse.feature.health.HealthViewModel.State): String =
        when (val e = state.expenditure) {
            null -> when (val m = state.measured) {
                is Expenditure.Estimate.NotYet -> "Expenditure not measurable yet — ${m.why}"
                is Expenditure.Estimate.Doubtful -> "Expenditure looks wrong — ${m.why}"
                else -> "Expenditure needs your height, age and a weigh-in before it can say anything."
            }
            else -> buildString {
                append("Expenditure about ").append(e.kcal.roundToInt())
                append(" kcal a day, give or take ").append(e.sdKcal.roundToInt())
                // How much of it is actually measured rather than a formula's guess is the single
                // most useful qualifier on this number, and it climbs as the log fills in.
                append(" (").append((state.measuredShare * 100).roundToInt())
                append("% measured from your own logging, ").append(e.loggedDays).append(" days)")
            }
        }

    // ------------------------------------------------------------------------------ targets

    private fun targetsReport(state: dev.mascwa.pulse.feature.health.HealthViewModel.State): String {
        val plan = state.plan
        if (plan is MacroTargets.Plan.Refused) return "No targets yet — ${plan.sentence}"
        val t = state.targets ?: return "No targets yet — ${expenditureLine(state)}"
        return buildString {
            append("Target ").append(t.kcal).append(" kcal a day")
            append("\nProtein ").append(t.proteinG).append(" g · fat ").append(t.fatG)
                .append(" g · carbs ").append(t.carbG).append(" g")
            // ⚠️ Adjustments are surfaced, never swallowed. Every guardrail in MacroTargets is
            // written to be visible, because a silently clamped calorie target is one nobody can
            // question — and this is the part of the app with direct physical consequence.
            val adjustments = (plan as? MacroTargets.Plan.Set)?.adjustments.orEmpty()
            if (adjustments.isNotEmpty()) {
                append("\nAdjusted: ").append(adjustments.joinToString("; ") { it.sentence })
            }
        }
    }

    /** Kilograms rendered in whichever unit the reader set. */
    private fun mass(kg: Double, unit: BodyTrend.MassUnit): String =
        String.format(java.util.Locale.US, "%.1f %s", kg * unit.perKg, unit.label)
}
