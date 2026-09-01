package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reading the panel on the back of a packet.
 *
 * Typing five numbers off a label is the commonest logging action there is, and the one the app
 * cannot avoid: between the bundled analyses and the crowd-sourced product database most things are
 * findable, and most is not all. So the fallback path is manual, and it is a lot of typing.
 *
 * ⚠️ **The value and the difficulty are both HERE, not in the optical recognition.** Turning a
 * photograph into text is a solved problem somebody else's library does; turning that text into a
 * density is where a wrong answer comes from, and it is full of ways to be confidently wrong:
 *
 *  - a European panel states energy twice — `1046 kJ / 250 kcal` — and taking the first number gives
 *    a figure four times too large;
 *  - most of the world writes `12,5 g` where the United States writes `12.5 g`, so a naive parser
 *    reads a decimal as thousands and vice versa;
 *  - a panel usually carries TWO columns, per 100 g and per serving, and they differ by whatever the
 *    serving happens to be. Picking the wrong one silently scales everything;
 *  - the recogniser mistakes `0` for `O` and `1` for `l`, and drops spaces.
 *
 * All of that is pure text, which means it is testable, which is why it lives here. The camera is
 * wired behind it.
 *
 * ⚠️ **Everything this returns is a claim about what the label SAYS, never about what was eaten.**
 * The caller converts, exactly as it does for a searched food — see [FoodPortion]. And when a figure
 * cannot be read honestly it is left absent rather than guessed at: the whole sparse-nutrient layer
 * exists so that "nobody measured this" and "this is zero" stay different, and a parser that filled
 * gaps with noughts would undo it in one pass.
 */
object NutritionLabel {

    /** Which column of the panel a figure came from. */
    enum class Basis {
        /** Per one hundred grams or millilitres — what this app stores and what it prefers. */
        PER_100,

        /** Per serving, with the serving's own weight in [Reading.servingGrams] when it is stated. */
        PER_SERVING,
    }

    /**
     * What a panel yielded.
     *
     * @param basis which column the figures came from.
     * @param servingGrams the serving's weight where the label stated one. Null is common and is not
     *   a failure — plenty of panels give a serving in pieces, or in a household measure, or not at
     *   all. ⚠️ It matters most exactly when [basis] is [Basis.PER_SERVING], because without it the
     *   figures cannot be turned into a density at all, and the caller has to say so.
     * @param confident whether the reading is worth showing without a second look — see [Note].
     */
    data class Reading(
        val basis: Basis,
        val nutrients: NutritionDay.Nutrients,
        val micros: Micronutrients.Amounts = Micronutrients.Amounts(),
        val extras: NutrientSet.Amounts = NutrientSet.Amounts(),
        val servingGrams: Double? = null,
        val notes: List<Note> = emptyList(),
    ) {
        /**
         * Nothing was recognised at all.
         *
         * ⚠️ Keyed on ENERGY rather than on the whole record being empty. Every panel in the world
         * states energy; a "reading" with grams of fat and no calories is a misread panel, not a
         * food, and offering it would put a plausible-looking half-answer in front of somebody.
         */
        val isEmpty: Boolean get() = nutrients.kcal <= 0.0

        val confident: Boolean get() = !isEmpty && notes.none { it.blocking }
    }

    /** Something the reader wants said out loud rather than silently worked around. */
    enum class Note(val sentence: String, val blocking: Boolean) {
        /**
         * ⚠️ Blocking, and it is the most important thing in this file. Per-serving figures with no
         * serving weight cannot become a density by any honest route, and a density is what the app
         * stores. Offering the numbers anyway would record a portion as if it were a hundred grams.
         */
        SERVING_WITHOUT_WEIGHT(
            "This panel is per serving and does not say what a serving weighs. Enter the weight and " +
                "the figures can be used.",
            true,
        ),

        /**
         * The macros do not come to the stated energy.
         *
         * Not blocking: the label may genuinely round, and fibre and polyols carry energy the four
         * headline macros do not account for. Worth a second look rather than a refusal — the
         * threshold is [NutritionDay.energyLooksWrong]'s, so this and the manual-entry warning
         * cannot come to different opinions about the same numbers.
         */
        ENERGY_DISAGREES(
            "Those macros do not come to the stated calories. Worth checking before it is saved.",
            false,
        ),

        /** Energy was stated only in kilojoules, so the calorie figure here is converted. */
        CONVERTED_FROM_KJ(
            "Calories worked out from the kilojoule figure.",
            false,
        ),

        /** A per-100 column was there as well and was preferred. Purely informational. */
        USED_PER_100_COLUMN(
            "Read from the per-100 column.",
            false,
        ),
    }

    /** Kilojoules in one kilocalorie, by definition of the thermochemical calorie. */
    const val KJ_PER_KCAL: Double = 4.184

    /**
     * Read a panel out of recognised text.
     *
     * [text] is whatever the recogniser produced: line breaks preserved where it found them, and no
     * guarantee about spacing, case, or how many of the characters are right.
     *
     * ⚠️ Returns null only when nothing at all was found. A partial read comes back WITH its notes,
     * because a panel that yielded calories and protein and nothing else is still four fields the
     * person does not have to type.
     */
    fun read(text: String): Reading? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        val notes = mutableListOf<Note>()
        val perHundred = mentionsPerHundred(text)
        val serving = servingGrams(lines)

        // ⚠️ The per-100 column wins whenever the panel offers one, because that is the unit
        // everything downstream speaks and taking it needs no serving weight. Only when the panel
        // is per-serving-only does the weight become load-bearing.
        val basis = if (perHundred) Basis.PER_100 else Basis.PER_SERVING
        if (perHundred) notes += Note.USED_PER_100_COLUMN

        val kcal = energy(lines)?.also { if (it.fromKj) notes += Note.CONVERTED_FROM_KJ }
        val macros = NutritionDay.Nutrients(
            kcal = kcal?.kcal ?: 0.0,
            proteinG = grams(lines, PROTEIN) ?: 0.0,
            fatG = grams(lines, FAT) ?: 0.0,
            carbG = grams(lines, CARB) ?: 0.0,
            fibreG = grams(lines, FIBRE) ?: 0.0,
            sugarG = grams(lines, SUGAR) ?: 0.0,
            satFatG = grams(lines, SAT_FAT) ?: 0.0,
            sodiumMg = sodiumMg(lines) ?: 0.0,
        )

        if (macros.kcal <= 0.0 && macros.proteinG <= 0.0 && macros.fatG <= 0.0 && macros.carbG <= 0.0) {
            return null
        }
        if (basis == Basis.PER_SERVING && serving == null) notes += Note.SERVING_WITHOUT_WEIGHT
        // ⚠️ Only worth saying once the macros are all present. The check compares a sum against a
        // stated total, and a sum missing one of its terms disagrees by construction — warning about
        // that would fire on every partially-read panel and teach the reader to ignore it.
        if (macros.kcal > 0.0 && macros.proteinG > 0.0 && macros.fatG > 0.0 && macros.carbG > 0.0 &&
            NutritionDay.energyLooksWrong(macros)
        ) {
            notes += Note.ENERGY_DISAGREES
        }

        return Reading(
            basis = basis,
            nutrients = macros,
            servingGrams = serving,
            notes = notes.distinct(),
        )
    }

    /**
     * The figures as a density, or null when the panel cannot honestly give one.
     *
     * ⚠️ **The one place per-serving figures become per-100-gram ones**, and it refuses rather than
     * assuming a serving weight. [FoodPortion.per100gFrom] makes the same refusal for typed figures
     * and for the same reason; this delegates to it so the two cannot disagree.
     */
    fun per100g(r: Reading, servingGramsOverride: Double? = null): NutritionDay.Nutrients? {
        if (r.isEmpty) return null
        if (r.basis == Basis.PER_100) return r.nutrients
        val w = servingGramsOverride ?: r.servingGrams ?: return null
        return FoodPortion.per100gFrom(r.nutrients, w)
    }

    // ---------------------------------------------------------------------------------- internals

    private val PROTEIN = listOf("protein")
    private val FAT = listOf("total fat", "fat")
    private val SAT_FAT = listOf("saturated fat", "saturates", "of which saturates", "saturated")
    private val CARB = listOf("total carbohydrate", "carbohydrate", "carbs", "carbohydrates")
    private val SUGAR = listOf("total sugars", "of which sugars", "sugars", "sugar")
    private val FIBRE = listOf("dietary fibre", "dietary fiber", "fibre", "fiber")

    /**
     * Every phrase the reader knows, so a line can be matched against the LONGEST one it carries.
     *
     * ⚠️ **This is what stops saturates being read as the total fat**, and the case is real rather
     * than theoretical: "fat" is a substring of "saturated fat", and on a panel that lists saturates
     * first — or that omits a total-fat line entirely — matching the short phrase against the first
     * line containing it records the wrong figure. Not merely wrong: wrong in the direction that
     * makes a food look better than it is, and plausible enough that nobody would question it.
     *
     * The same shape catches "sugars" inside "of which sugars" and "fibre" inside "dietary fibre".
     * Sorting one family's own keys by length handles those; only a table of ALL of them handles a
     * collision that crosses families, which the fat one does.
     */
    private val ALL_KEYS: List<String> =
        (PROTEIN + FAT + SAT_FAT + CARB + SUGAR + FIBRE + listOf("sodium", "salt"))
            .distinct()
            .sortedByDescending { it.length }

    /** Does [line] name [key] as a word rather than inside a longer one? */
    private fun namesKey(line: String, key: String): Boolean {
        val l = line.lowercase()
        val i = l.indexOf(key)
        // The key has to start a word, so "fat" does not match "lowfat" and "salt" not "unsalted".
        return i >= 0 && (i == 0 || !l[i - 1].isLetter())
    }

    /**
     * The line stating [keys], or null.
     *
     * A line is only accepted for a key when no LONGER known phrase also appears on it — see
     * [ALL_KEYS].
     */
    private fun matchLine(lines: List<String>, keys: List<String>): String? {
        for (k in keys.sortedByDescending { it.length }) {
            val hit = lines.firstOrNull { line ->
                namesKey(line, k) &&
                    ALL_KEYS.none { other -> other.length > k.length && namesKey(line, other) }
            }
            if (hit != null) return hit
        }
        return null
    }

    /**
     * Every number on a line, in order, decimal separator normalised.
     *
     * ⚠️ **A comma is a decimal point unless it is grouping three digits.** Most of the world writes
     * `12,5 g`, and reading that as twelve thousand five hundred is the single largest error this
     * parser could make. The rule keys on what FOLLOWS the comma, which is the only thing that tells
     * them apart: exactly three digits and then a non-digit is grouping, anything else is a decimal.
     */
    internal fun numbers(line: String): List<Double> {
        val cleaned = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == ',') {
                val rest = line.drop(i + 1)
                val digits = rest.takeWhile { it.isDigit() }
                val grouping = digits.length == 3 && rest.getOrNull(3)?.isDigit() != true
                // A grouping comma is dropped so `1,046` becomes `1046`; a decimal comma becomes a point.
                if (!grouping) cleaned.append('.')
            } else {
                cleaned.append(c)
            }
            i++
        }
        return Regex("""\d+(?:\.\d+)?""").findAll(cleaned).mapNotNull { it.value.toDoubleOrNull() }.toList()
    }

    /**
     * Which number on a line to take: always the first.
     *
     * ⚠️ **It reads as though there ought to be a choice here and there is not, so this says why
     * rather than leaving a branch that looks load-bearing.** A European panel puts per-100 FIRST
     * (`Protein 6.2 g 12.4 g`) and is the only kind that carries two columns; a United States panel
     * is per-serving and states one figure. So the first number is right under both bases, and a
     * `when` selecting on which basis we are reading would have had two identical arms.
     *
     * ⚠️ The trailing `%` reference-intake column is dropped by [dropReferenceIntake] before this
     * sees the line. Note what that does and does not buy: while the quantity is read too, the
     * percentage sits after it and taking the first number already ignores it. The strip matters
     * only when the quantity is the number that went missing.
     */
    private fun pick(values: List<Double>): Double? = values.firstOrNull()

    /**
     * Strip the reference-intake percentage, which is a number that is not a quantity.
     *
     * ⚠️ Only a `%` immediately after a number, so a line mentioning "% RI" as a heading is
     * untouched and a genuine figure is never eaten.
     *
     * ⚠️ **When this actually changes an answer**: only when the percentage is the FIRST number
     * left on the line, because [pick] takes the first and every real panel writes the quantity
     * before the percentage. That is not a reason to drop it — it is what a panel looks like when
     * a photograph read the plain percentage column and missed the bold gram figure beside it, and
     * `Fat 18%` recorded as eighteen grams is exactly the plausible wrong number this parser
     * exists to avoid. Stated here because a comment that claims more than it does is worse than
     * none, and the fixture that first covered this did not reach this branch at all.
     */
    private fun dropReferenceIntake(line: String): String =
        line.replace(Regex("""\d+(?:[.,]\d+)?\s*%"""), " ")

    private fun grams(lines: List<String>, keys: List<String>): Double? {
        val line = matchLine(lines, keys) ?: return null
        val v = pick(numbers(dropReferenceIntake(line))) ?: return null
        // ⚠️ Milligrams where the label says so. A panel stating `Sodium 380 mg` beside grams of
        // everything else is ordinary, and recording 380 GRAMS of anything is not a plausible food.
        return if (Regex("""\d\s*mg\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)) v / 1000.0 else v
    }

    private fun sodiumMg(lines: List<String>): Double? {
        val line = matchLine(lines, listOf("sodium", "salt")) ?: return null
        val v = pick(numbers(dropReferenceIntake(line))) ?: return null
        val l = line.lowercase()
        // ⚠️ SALT is not sodium, and European panels state salt. The ratio is the formula mass of
        // sodium chloride over sodium — 58.44 / 22.99 — so a gram of salt is 393 mg of sodium.
        // Treating one as the other overstates sodium by two and a half times.
        val isSalt = l.contains("salt") && !l.contains("sodium")
        val mg = if (Regex("""\d\s*mg\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)) v else v * 1000.0
        return if (isSalt) mg / SALT_TO_SODIUM else mg
    }

    /** Grams of salt per gram of sodium: 58.44 / 22.99. */
    const val SALT_TO_SODIUM: Double = 2.5421

    private data class Energy(val kcal: Double, val fromKj: Boolean)

    /**
     * The energy figure, in calories.
     *
     * ⚠️ **A European panel states energy TWICE**, kilojoules first: `Energy 1046 kJ / 250 kcal`.
     * Taking the first number gives a figure four times too large, which is the error most likely to
     * go unnoticed because it is still a plausible-looking number. The calorie figure is preferred
     * wherever it is labelled; kilojoules are converted only when they are all there is.
     */
    private fun energy(lines: List<String>): Energy? {
        val line = matchLine(lines, listOf("energy", "calories", "kcal", "kj")) ?: return null
        val stripped = dropReferenceIntake(line)

        // A figure explicitly labelled kcal wins outright, wherever it sits on the line.
        Regex("""(\d[\d.,]*)\s*(?:kcal|cal\b|calories)""", RegexOption.IGNORE_CASE)
            .find(stripped)
            ?.groupValues?.get(1)
            ?.let { numbers(it).firstOrNull() }
            ?.let { return Energy(it, fromKj = false) }

        Regex("""(\d[\d.,]*)\s*kj""", RegexOption.IGNORE_CASE)
            .find(stripped)
            ?.groupValues?.get(1)
            ?.let { numbers(it).firstOrNull() }
            ?.let { return Energy(it / KJ_PER_KCAL, fromKj = true) }

        // Neither unit is on the line — a United States panel says "Calories 250" and nothing else.
        return pick(numbers(stripped))?.let { Energy(it, fromKj = false) }
    }

    /** Does the panel carry a per-100 column at all? */
    internal fun mentionsPerHundred(text: String): Boolean =
        Regex("""per\s*100|/\s*100\s*(?:g|ml)|\b100\s*(?:g|ml)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)

    /**
     * The serving weight, where the panel states one.
     *
     * ⚠️ Only a weight, never a count. `Serving size 2 biscuits` gives this nothing to work with, and
     * returning 2 would record a two-gram serving — which is why the refusal above exists.
     */
    internal fun servingGrams(lines: List<String>): Double? {
        for (line in lines) {
            val l = line.lowercase()
            if (!l.contains("serving") && !l.contains("portion") && !l.contains("per pack")) continue
            val m = Regex("""(\d[\d.,]*)\s*(g|ml)\b""", RegexOption.IGNORE_CASE).find(line) ?: continue
            val v = numbers(m.groupValues[1]).firstOrNull() ?: continue
            if (v > 0.0 && v.isFinite()) return v
        }
        return null
    }

    /**
     * A one-line description of what was read, for a surface with room for one line.
     *
     * ⚠️ Leads with the blocking note when there is one, because that is the thing the person has to
     * act on and a summary that buried it under a calorie count would be read as success.
     */
    fun summary(r: Reading): String {
        r.notes.firstOrNull { it.blocking }?.let { return it.sentence }
        val per = if (r.basis == Basis.PER_100) "per 100 g" else "per serving"
        val bits = buildList {
            add("${r.nutrients.kcal.roundToInt()} kcal")
            if (r.nutrients.proteinG > 0) add("P ${fmt(r.nutrients.proteinG)}")
            if (r.nutrients.fatG > 0) add("F ${fmt(r.nutrients.fatG)}")
            if (r.nutrients.carbG > 0) add("C ${fmt(r.nutrients.carbG)}")
        }
        return "Read $per — ${bits.joinToString(" · ")}."
    }

    private fun fmt(v: Double): String {
        val r = (v * 10).roundToInt()
        return if (r % 10 == 0) "${r / 10}" else "${r / 10}.${abs(r % 10)}"
    }
}
