package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reading a recipe off a web page into something the builder can start from.
 *
 * ## The rule this whole file is shaped around
 *
 * ⚠️ **A recipe page supplies NAMES and QUANTITIES. It does not supply nutrition, and nothing here
 * invents any.** Every figure in this app comes from a real food record; what an import can honestly
 * do is save somebody typing "200 g plain flour" and then finding flour in the database themselves.
 * So the output of this file is a list of parsed lines, and matching them to records happens
 * afterwards, through the same search every hand-built recipe uses.
 *
 * ⚠️ **And a volume is not a mass.** A cup of flour is about 120 g and a cup of water is about 240 g
 * — the same measure, twice the weight — so [grams] converts only genuine mass units and returns
 * null for everything else. The builder then asks. That refusal is the difference between an import
 * that saves typing and one that quietly halves somebody's calorie total for a week.
 */
object RecipeImport {

    /** A measure as a recipe writes it. */
    enum class Measure(val label: String, val gramsEach: Double?) {
        GRAM("g", 1.0),
        KILOGRAM("kg", 1000.0),
        OUNCE("oz", 28.349523125),
        POUND("lb", 453.59237),

        /**
         * ⚠️ Volume units carry a null conversion ON PURPOSE, not because nobody has filled them in.
         * Grams per millilitre is a property of the ingredient, and this file does not know which
         * ingredient it is looking at. See the note on [grams].
         */
        MILLILITRE("ml", null),
        LITRE("l", null),
        CUP("cup", null),
        TABLESPOON("tbsp", null),
        TEASPOON("tsp", null),
        FLUID_OUNCE("fl oz", null),

        /** No unit at all — "2 onions", "1 egg". A count, and equally not a mass. */
        PIECE("", null),
        ;

        val isMass: Boolean get() = gramsEach != null
    }

    /**
     * ⚠️ Longest spelling first within each measure, and the whole table scanned in order.
     *
     * "tablespoon" starts with "tabl" and "tbsp" does not contain "tsp", but "fl oz" contains "oz" —
     * so a shorter alias matched first would read "2 fl oz" as two ounces of MASS, which is exactly
     * the conversion this file exists to refuse.
     */
    private val ALIASES: List<Pair<String, Measure>> = listOf(
        "fluid ounces" to Measure.FLUID_OUNCE,
        "fluid ounce" to Measure.FLUID_OUNCE,
        "fl. oz." to Measure.FLUID_OUNCE,
        "fl oz" to Measure.FLUID_OUNCE,
        "tablespoons" to Measure.TABLESPOON,
        "tablespoon" to Measure.TABLESPOON,
        "tbsps" to Measure.TABLESPOON,
        "tbsp" to Measure.TABLESPOON,
        "tbs" to Measure.TABLESPOON,
        "teaspoons" to Measure.TEASPOON,
        "teaspoon" to Measure.TEASPOON,
        "tsps" to Measure.TEASPOON,
        "tsp" to Measure.TEASPOON,
        "kilograms" to Measure.KILOGRAM,
        "kilogram" to Measure.KILOGRAM,
        "kgs" to Measure.KILOGRAM,
        "kg" to Measure.KILOGRAM,
        "grams" to Measure.GRAM,
        "gram" to Measure.GRAM,
        "gr" to Measure.GRAM,
        "g" to Measure.GRAM,
        "millilitres" to Measure.MILLILITRE,
        "milliliters" to Measure.MILLILITRE,
        "millilitre" to Measure.MILLILITRE,
        "milliliter" to Measure.MILLILITRE,
        "mls" to Measure.MILLILITRE,
        "ml" to Measure.MILLILITRE,
        "litres" to Measure.LITRE,
        "liters" to Measure.LITRE,
        "litre" to Measure.LITRE,
        "liter" to Measure.LITRE,
        "ounces" to Measure.OUNCE,
        "ounce" to Measure.OUNCE,
        "oz" to Measure.OUNCE,
        "pounds" to Measure.POUND,
        "pound" to Measure.POUND,
        "lbs" to Measure.POUND,
        "lb" to Measure.POUND,
        "cups" to Measure.CUP,
        "cup" to Measure.CUP,
    )

    /** One line of an ingredient list, as far as it can honestly be read. */
    data class Ingredient(
        /** What the page said, kept verbatim so the builder can show it beside the guess. */
        val raw: String,
        val quantity: Double?,
        val measure: Measure,
        /** The food, with the quantity, the measure and any parenthetical note taken off. */
        val name: String,
        /** "finely chopped", "at room temperature" — kept, never folded into [name]. */
        val note: String?,
    ) {
        /**
         * The weight in grams, or **null** when the page did not give one.
         *
         * ⚠️ Null is the ordinary case on a lot of recipes and is not a failure. Converting a cup or
         * a spoon needs the ingredient's density, which this file has no way to know — and guessing
         * a middling figure would be worse than asking, because the error is not small: flour and
         * water differ by a factor of two in the same cup.
         */
        val grams: Double? get() = quantity?.let { q -> measure.gramsEach?.let { q * it } }
    }

    // ------------------------------------------------------------------------------ one line

    /** Fractions a recipe writes as a single character, and the plain ones. */
    private val FRACTIONS = mapOf(
        '¼' to 0.25, '½' to 0.5, '¾' to 0.75,
        '⅓' to 1.0 / 3.0, '⅔' to 2.0 / 3.0,
        '⅛' to 0.125, '⅜' to 0.375, '⅝' to 0.625, '⅞' to 0.875,
        '⅕' to 0.2, '⅖' to 0.4, '⅗' to 0.6, '⅘' to 0.8,
        '⅙' to 1.0 / 6.0, '⅚' to 5.0 / 6.0,
    )

    /**
     * Read one ingredient line.
     *
     * ⚠️ **Never returns null for a non-empty line.** A line it cannot parse comes back with a null
     * quantity and the whole text as the name, because the builder shows it either way and a dropped
     * line is an ingredient somebody does not know is missing.
     */
    fun parseLine(line: String): Ingredient? {
        val trimmed = line.trim().trimStart('-', '•', '*', '·').trim()
        if (trimmed.isEmpty()) return null

        // The parenthetical comes off first, or "(about 2 cups)" would be read as the quantity.
        val noteMatch = Regex("""\(([^)]*)\)""").find(trimmed)
        val note = noteMatch?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        var rest = (if (noteMatch != null) trimmed.removeRange(noteMatch.range) else trimmed).trim()

        val (quantity, afterQuantity) = readQuantity(rest)
        rest = afterQuantity

        val (measure, afterMeasure) = readMeasure(rest)
        rest = afterMeasure

        // A trailing ", finely chopped" is a note, not part of the name.
        var name = rest.trim().trim(',', '.', ';').trim()
        var extra: String? = null
        val comma = name.indexOf(',')
        if (comma > 0) {
            extra = name.substring(comma + 1).trim().takeIf { it.isNotEmpty() }
            name = name.substring(0, comma).trim()
        }

        return Ingredient(
            raw = trimmed,
            quantity = quantity,
            measure = measure,
            name = name,
            note = listOfNotNull(note, extra).joinToString("; ").takeIf { it.isNotEmpty() },
        )
    }

    /**
     * ⚠️ Reads "1 1/2", "1½", "½", "1.5" and "1-2" — and for a range takes the LOWER bound.
     *
     * A range is a genuine ambiguity and the low end is the safe direction: under-counting an
     * ingredient shows up as a total that looks small, where over-counting silently inflates the
     * day's calories against a target somebody is eating to.
     */
    private fun readQuantity(text: String): Pair<Double?, String> {
        var i = 0

        fun skipSpaces() { while (i < text.length && text[i] == ' ') i++ }

        /** A run of digits, with '.' or ',' as a decimal point. Null when there is not one here. */
        fun number(): Double? {
            val start = i
            while (i < text.length && (text[i].isDigit() || text[i] == '.' || text[i] == ',')) i++
            if (i == start) return null
            val v = text.substring(start, i).replace(",", ".").toDoubleOrNull()
            if (v == null) i = start
            return v
        }

        skipSpaces()
        val first = number()

        // ⚠️ **The slash is checked IMMEDIATELY after the first number, before anything else.** A
        // whole-number scan that runs first eats the numerator, so "1/2 tsp" reads as one teaspoon
        // rather than half of one — twice the amount, silently, on the commonest way a recipe
        // writes a fraction.
        if (first != null && i < text.length && text[i] == '/') {
            val save = i
            i++
            val denominator = number()
            if (denominator != null && denominator != 0.0) {
                return first / denominator to text.substring(i).trim()
            }
            i = save
        }

        var total = first ?: 0.0
        var found = first != null

        // A range: take the lower bound and drop the rest of it.
        if (found && i < text.length && (text[i] == '-' || text[i] == '–')) {
            val save = i
            i++
            skipSpaces()
            if (number() == null) i = save
        }

        // "1 1/2" or "1½" — a fraction AFTER a whole number, or on its own.
        skipSpaces()
        if (i < text.length && FRACTIONS.containsKey(text[i])) {
            total += FRACTIONS.getValue(text[i])
            found = true
            i++
        } else {
            val save = i
            val numerator = number()
            if (numerator != null && i < text.length && text[i] == '/') {
                i++
                val denominator = number()
                if (denominator != null && denominator != 0.0) {
                    total += numerator / denominator
                    found = true
                } else {
                    i = save
                }
            } else {
                i = save
            }
        }

        return if (found) total to text.substring(i).trim() else null to text
    }

    /** ⚠️ The unit must be a whole WORD. "gram" is in "programme", and "l" is in almost everything. */
    private fun readMeasure(text: String): Pair<Measure, String> {
        val lower = text.lowercase()
        for ((alias, measure) in ALIASES) {
            if (!lower.startsWith(alias)) continue
            val after = alias.length
            val boundary = after >= lower.length || !lower[after].isLetter()
            if (!boundary) continue
            var rest = text.substring(after).trim()
            // "of" after a measure is filler: "2 cups of flour".
            if (rest.lowercase().startsWith("of ")) rest = rest.substring(3).trim()
            return measure to rest
        }
        return Measure.PIECE to text
    }

    // ---------------------------------------------------------------------------- a whole page

    /** What a page yielded, and how confident the shape of it was. */
    data class Import(
        val title: String,
        val ingredients: List<Ingredient>,
        /** Null when the page did not say, which is common. */
        val servings: Int?,
        val sourceUrl: String,
        /** How many ingredient lines carried a weight this app can use without asking. */
        val weighed: Int,
    ) {
        val needsWeights: Int get() = ingredients.size - weighed
    }

    /**
     * ⚠️ Below this many lines a bullet list is not an ingredient list — it is a set of tags, a
     * share bar, or a related-recipes strip. Measured against the shape of real pages rather than
     * chosen: a recipe with two ingredients exists, but a two-item list on a recipe page is far more
     * often navigation, and offering navigation as ingredients is worse than missing a short recipe.
     */
    const val MIN_INGREDIENT_LINES = 3

    /** Words that mark the heading above an ingredient list. */
    private val INGREDIENT_HEADINGS = listOf("ingredient", "you will need", "you'll need", "shopping")

    /**
     * Find the ingredient list on a page and read it.
     *
     * ⚠️ **A list under an "Ingredients" heading wins outright, however it scores otherwise.** A page
     * that labels its own list is telling us something no heuristic can beat, and the fallback —
     * whichever list has the most lines that begin with a quantity — is for pages that do not.
     */
    fun fromBlocks(
        blocks: List<Readability.Block>,
        title: String,
        sourceUrl: String,
    ): Import? {
        val lists = mutableListOf<Pair<List<String>, Boolean>>()
        var labelled: List<String>? = null
        var underHeading = false

        for (b in blocks) {
            when (b) {
                is Readability.Block.Heading -> {
                    val h = b.text.lowercase()
                    underHeading = INGREDIENT_HEADINGS.any { h.contains(it) }
                }
                is Readability.Block.Bullets -> {
                    if (b.items.size >= MIN_INGREDIENT_LINES) {
                        if (underHeading && labelled == null) labelled = b.items
                        lists += b.items to underHeading
                    }
                    // ⚠️ A heading governs the list DIRECTLY under it and nothing after that. A page
                    // with "Ingredients" then a list then a "Method" list would otherwise hand back
                    // the method as ingredients when the method heading is not one we recognise.
                    underHeading = false
                }
                else -> Unit
            }
        }

        val chosen = labelled ?: lists.maxByOrNull { (items, _) -> quantityLines(items) }?.first
        if (chosen == null) return null
        if (labelled == null && quantityLines(chosen) < MIN_INGREDIENT_LINES) return null

        val parsed = chosen.mapNotNull { parseLine(it) }
        if (parsed.isEmpty()) return null

        return Import(
            title = title.trim().ifEmpty { "Imported recipe" },
            ingredients = parsed,
            servings = servingsIn(blocks),
            sourceUrl = sourceUrl,
            weighed = parsed.count { it.grams != null },
        )
    }

    private fun quantityLines(items: List<String>): Int =
        items.count { parseLine(it)?.quantity != null }

    /**
     * ⚠️ Reads only an explicit statement — "Serves 4", "Makes 12", "4 servings".
     *
     * A recipe that does not say is left null rather than guessed at, because the servings figure
     * divides every number the builder produces and a wrong one is wrong in every direction at once.
     */
    private fun servingsIn(blocks: List<Readability.Block>): Int? {
        val patterns = listOf(
            Regex("""serves\s+(\d{1,3})""", RegexOption.IGNORE_CASE),
            Regex("""makes\s+(\d{1,3})""", RegexOption.IGNORE_CASE),
            Regex("""(\d{1,3})\s+servings""", RegexOption.IGNORE_CASE),
            Regex("""(\d{1,3})\s+portions""", RegexOption.IGNORE_CASE),
        )
        for (b in blocks) {
            val text = when (b) {
                is Readability.Block.Paragraph -> b.text
                is Readability.Block.Heading -> b.text
                is Readability.Block.Bullets -> b.items.joinToString(" ")
                else -> continue
            }
            for (p in patterns) {
                val n = p.find(text)?.groupValues?.get(1)?.toIntOrNull()
                if (n != null && n in 1..100) return n
            }
        }
        return null
    }

    // ------------------------------------------------------------------------------- sentences

    /** What the import found, in one line, so the builder can say it before anything is saved. */
    fun sentence(import: Import): String {
        val n = import.ingredients.size
        val need = import.needsWeights
        val servings = import.servings?.let { ", serves $it" } ?: ""
        return when (need) {
            0 -> "$n ingredient${plural(n)}, all weighed$servings."
            n -> "$n ingredient${plural(n)}$servings — none of them weighed, so each needs a weight " +
                "before it can be counted."
            else -> "$n ingredient${plural(n)}$servings — $need need${if (need == 1) "s" else ""} a " +
                "weight, because a cup or a spoon is a volume and this app cannot turn one into " +
                "grams without knowing the ingredient."
        }
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    /** The measure and quantity as the page had them, for a row that shows both. */
    fun describe(ingredient: Ingredient): String {
        val q = ingredient.quantity ?: return ingredient.name
        val number = if (abs(q - q.roundToInt()) < 0.01) "${q.roundToInt()}" else trimQuantity(q)
        return if (ingredient.measure == Measure.PIECE) "$number ${ingredient.name}"
        else "$number ${ingredient.measure.label} ${ingredient.name}"
    }

    private fun trimQuantity(v: Double): String {
        val hundredths = Math.round(v * 100.0)
        return when {
            hundredths % 100L == 0L -> "${hundredths / 100L}"
            hundredths % 10L == 0L -> String.format(java.util.Locale.US, "%.1f", v)
            else -> String.format(java.util.Locale.US, "%.2f", v)
        }
    }
}
