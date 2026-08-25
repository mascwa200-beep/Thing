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
        // ⚠️ A POSITIVE check, not the exclusion above. Kotlin does not narrow a sealed type by
        // ruling one branch out — after the return, `trend` is still a `Trend` and reading
        // `hasRate` off it does not compile. The BODY screen has always tested `!is Estimated` for
        // the same reason.
        val estimated = trend as? BodyTrend.Trend.Estimated ?: return "No weigh-ins recorded yet."
        val latest = estimated.latest
        val unit = state.unit

        // ⚠️ Both lines come from the core rather than being written here, and that is not tidiness.
        // A rate is quoted only when its own interval excludes zero — `rateIsClear` is the core's
        // judgement on that, and ignoring it means telling somebody they are losing weight when the
        // data cannot tell losing from gaining, on a feature whose response is to eat less. This
        // file used to restate that rule in its own words, which made two places decide how a weight
        // change is described; they had already drifted, since the copy here dropped the give-or-take
        // the core deliberately quotes. The screen has always called `rateSentence`, so the assistant
        // and the BODY page now say the same thing about the same reading.
        return buildString {
            append(BodyTrend.trendSentence(latest, unit))
            append("\n").append(BodyTrend.rateSentence(latest, unit, estimated.hasRate))
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
            // ⚠️ The share of the day each one is, which is the question three gram figures raise and
            // which nothing in this app answered — `Targets` has computed the energy split since it
            // was written and had no reader anywhere.
            //
            // Taken as a share of the TARGET rather than of the three added together: the target is
            // the number being divided up, and nothing guarantees three rounded gram figures add
            // back to it. Running the shipped core over all five diet modes at three body masses
            // and three rates — 41 real target sets — they in fact agreed to the calorie every
            // time, so this denominator costs nothing today and stays correct if that ever slips.
            //
            // The three shares therefore need not total exactly 100. Measured across the same 41,
            // they land between 99 and 101, which is ordinary rounding and honest rather than untidy.
            if (t.kcal > 0) {
                fun share(kcal: Int) = (kcal * 100.0 / t.kcal).roundToInt()
                append(" — ").append(share(t.proteinKcal)).append("% protein · ")
                append(share(t.fatKcal)).append("% fat · ")
                append(share(t.carbKcal)).append("% carbs")
            }
            // ⚠️ Adjustments are surfaced, never swallowed. Every guardrail in MacroTargets is
            // written to be visible, because a silently clamped calorie target is one nobody can
            // question — and this is the part of the app with direct physical consequence.
            val adjustments = (plan as? MacroTargets.Plan.Set)?.adjustments.orEmpty()
            if (adjustments.isNotEmpty()) {
                append("\nAdjusted: ").append(adjustments.joinToString("; ") { it.sentence })
            }
        }
    }
}
