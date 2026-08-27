package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A meal assembled before it is written down.
 *
 * Every logging path in this app commits the instant it is tapped: search a food, set a portion, and
 * it is in the record. That is right for one biscuit and wrong for the way people actually eat. A
 * plate of five things is five separate round trips, the running total is only visible after the
 * fact, and correcting the third means finding it again in the day's list and removing it.
 *
 * Staging fixes all three at once, but the reason it earns a core rather than a list in a view model
 * is the third thing it makes possible: **seeing what the meal would do to the day before deciding
 * to eat it.** That question needs the day, the plate and the targets together, and answering it
 * consistently on two platforms means answering it once.
 *
 * ⚠️ **Nothing here ever blocks a commit, and no line is ever a verdict on the person.** The
 * sentences state arithmetic. This app measures expenditure FROM what you log, so a surface that
 * renders an honest over-budget meal as a failure is a surface that teaches somebody to stop logging
 * — and a day nobody logs is a hole in the twenty-eight-day window that degrades the estimate for
 * four weeks. See [MacroTargets.Bound], which exists because both surfaces had that defect.
 *
 * Pure, clock-free and zone-free. The entries arrive already stamped with the day they count toward,
 * because deciding that is a calendar question and this has no calendar in it.
 *
 * ⚠️ **Named MealDraft rather than Plate, and the reason is not style.** Two nested types called
 * `Plate` already exist in this feature — `MealPhotos.Result.Plate` and `HealthViewModel.MealShot.Plate`,
 * both of them the model's proposals from a photograph awaiting correction. A third `Plate` in scope
 * would read as one of those in every file that touches both. It also silently blinded
 * `tools/kotlin_import_check.py`: that gate treats a name declared anywhere in the same package as
 * needing no import, nested declarations included, so with `MealShot.Plate` present it reported a
 * genuinely missing `import ...telemetry.Plate` as clean. The surfaces still say "the plate", which
 * is the right word for a person; the type says what it is.
 */
object MealDraft {

    /** How a number stands against its target once the plate is committed. */
    enum class Verdict {
        /** Short of the target. On a floor that is worth saying; on a budget it is headroom. */
        UNDER,

        /** Within [ON_TOLERANCE] of it, either way. */
        ON,

        /** Past it. On a floor that is usually the intention; on a budget it is worth noticing. */
        OVER,
    }

    /**
     * How close counts as landing on a target.
     *
     * ⚠️ **A fraction, not a fixed figure, because the four numbers are three orders of magnitude
     * apart.** Two grams either way on a 40 g fat target is a rounding error; two calories either way
     * on a 2,400 kcal budget is not worth a word, and a fixed 2 would call the first ON and the
     * second OVER. Three per cent is about a bite of most things.
     */
    const val ON_TOLERANCE: Double = 0.03

    /** One target, and what committing the plate would do to it. */
    data class Line(
        val macro: MacroTargets.Macro,
        val target: Int,
        /** Where the day stands with the plate not yet committed. */
        val before: Double,
        /** Where it would stand after. */
        val after: Double,
        val verdict: Verdict,
        /** The arithmetic in words. Never a judgement — see the note on [MealDraft]. */
        val sentence: String,
    ) {
        /** What the plate itself contributes. */
        val delta: Double get() = after - before
    }

    /** What is on the plate and what committing it would do to the day. */
    data class Effect(
        val items: Int,
        val plate: NutritionDay.Nutrients,
        val before: NutritionDay.Nutrients,
        val after: NutritionDay.Nutrients,
        /**
         * One line per target, or empty when there is no plan to compare against.
         *
         * ⚠️ **Empty rather than a stand-in.** [MacroTargets.Plan.Refused] means the app has said in
         * as many words that it cannot work targets out yet, and substituting a round 2,000 here
         * would put a comparison on screen that looks measured and is invented. The plate still
         * shows its own totals; it just does not pretend to know what they mean.
         */
        val lines: List<Line>,
    ) {
        val isEmpty: Boolean get() = items == 0
    }

    /**
     * What [staged] adds to a day that already holds [logged].
     *
     * [targets] is null when no plan has been made, and the comparison is then simply absent.
     */
    fun effect(
        staged: List<NutritionDay.Entry>,
        logged: List<NutritionDay.Entry>,
        targets: MacroTargets.Targets?,
    ): Effect {
        val plate = NutritionDay.total(staged)
        val before = NutritionDay.total(logged)
        val after = before + plate
        val lines = if (targets == null) {
            emptyList()
        } else {
            MacroTargets.Macro.entries.mapNotNull { macro ->
                val target = targetOf(macro, targets)
                // ⚠️ A target of zero is not a target of zero — it is a target the planner has not
                // produced. A line comparing against it would report every plate as infinitely over.
                if (target <= 0) null else line(macro, target, valueOf(macro, before), valueOf(macro, after))
            }
        }
        return Effect(items = staged.size, plate = plate, before = before, after = after, lines = lines)
    }

    /**
     * A one-line summary of the whole plate, for a surface with room for one line.
     *
     * ⚠️ Leads with CALORIES when there is a plan, because that is the number a meal is decided
     * against, and falls back to the plate's own total when there is not. A plate with nothing on it
     * says so rather than reporting a row of zeros.
     */
    fun summary(e: Effect): String {
        if (e.isEmpty) return "Nothing on the plate yet."
        val what = if (e.items == 1) "1 item" else "${e.items} items"
        val kcal = e.plate.kcal.roundToInt()
        val calories = e.lines.firstOrNull { it.macro == MacroTargets.Macro.CALORIES }
            ?: return "$what · $kcal kcal."
        return "$what · $kcal kcal — ${calories.sentence.replaceFirstChar { it.lowercase() }}"
    }

    /**
     * The days the plate is stamped for, oldest first.
     *
     * ⚠️ **The reason this exists is a real way to lose a meal quietly.** An entry is stamped with
     * the day it counts toward at the moment it is staged, and the surfaces let somebody page back
     * and forth through days while a plate is standing. Committing must write each item to the day
     * it was staged for — that is what the person meant — but then a plate assembled on Monday and
     * committed while Tuesday is on screen lands somewhere they are not looking, and there is
     * nothing on screen to say so. The surface asks this and says so when it is not just today.
     */
    fun daysCovered(staged: List<NutritionDay.Entry>): List<Long> =
        staged.map { it.dayStartMs }.distinct().sorted()

    // ------------------------------------------------------------------------------------ internals

    private fun line(macro: MacroTargets.Macro, target: Int, before: Double, after: Double): Line {
        val gap = after - target
        val verdict = when {
            abs(gap) <= target * ON_TOLERANCE -> Verdict.ON
            gap < 0 -> Verdict.UNDER
            else -> Verdict.OVER
        }
        return Line(macro, target, before, after, verdict, sentenceFor(macro, target, after, verdict))
    }

    private fun sentenceFor(
        macro: MacroTargets.Macro,
        target: Int,
        after: Double,
        verdict: Verdict,
    ): String {
        val name = label(macro)
        val unit = unit(macro)
        val gap = abs(after - target).roundToInt()
        return when (verdict) {
            Verdict.ON -> "That lands on your $name."
            // ⚠️ The two bounds read differently, and getting this backwards is the defect
            // `MacroTargets.Bound` was written to stop. Short of a FLOOR is the thing worth saying;
            // short of a BUDGET is headroom and is good news.
            Verdict.UNDER -> when (macro.bound) {
                MacroTargets.Bound.FLOOR -> "That leaves you $gap $unit short of your $name."
                MacroTargets.Bound.BUDGET -> "That leaves $gap $unit of your $name."
            }
            // ⚠️ And past a FLOOR is usually the intention, so it is stated and not warned about.
            Verdict.OVER -> when (macro.bound) {
                MacroTargets.Bound.FLOOR -> "That puts you $gap $unit past your $name."
                MacroTargets.Bound.BUDGET -> "That puts you $gap $unit over your $name."
            }
        }
    }

    private fun label(macro: MacroTargets.Macro): String = when (macro) {
        MacroTargets.Macro.CALORIES -> "calorie budget"
        MacroTargets.Macro.PROTEIN -> "protein floor"
        MacroTargets.Macro.FAT -> "fat floor"
        MacroTargets.Macro.CARBS -> "carbohydrate budget"
    }

    private fun unit(macro: MacroTargets.Macro): String =
        if (macro == MacroTargets.Macro.CALORIES) "kcal" else "g"

    private fun targetOf(macro: MacroTargets.Macro, t: MacroTargets.Targets): Int = when (macro) {
        MacroTargets.Macro.CALORIES -> t.kcal
        MacroTargets.Macro.PROTEIN -> t.proteinG
        MacroTargets.Macro.FAT -> t.fatG
        MacroTargets.Macro.CARBS -> t.carbG
    }

    private fun valueOf(macro: MacroTargets.Macro, n: NutritionDay.Nutrients): Double = when (macro) {
        MacroTargets.Macro.CALORIES -> n.kcal
        MacroTargets.Macro.PROTEIN -> n.proteinG
        MacroTargets.Macro.FAT -> n.fatG
        MacroTargets.Macro.CARBS -> n.carbG
    }
}
