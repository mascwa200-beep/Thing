package dev.mascwa.pulse.core.telemetry

/**
 * What somebody typed, read as a list of things they ate.
 *
 * `two eggs, a slice of toast and 200g of chicken` is how a person describes a meal. Every logging
 * path in this app instead asks for one food, then a number, then a unit, then the next food — which
 * is right for a packet and laborious for a plate.
 *
 * ⚠️ **THE INVARIANT, and it is the same one the photograph path holds: the words name foods, and
 * every NUMBER comes from a real record.** This parser reads a quantity and a name. It does not know
 * what an egg contains and must never appear to: the name goes to [FoodSearch], the amount goes to
 * [FoodPortion], and a name that matches nothing is reported unmatched rather than quietly dropped
 * or filled in. A parser that guessed at nutrition would put invented figures in a food log beside
 * laboratory analyses, looking exactly like them.
 *
 * ⚠️ **Deliberately deterministic, and not a model.** A language model could read this too, and the
 * plan this came from assumed one would. It is not needed: the shapes people actually type are few
 * and regular, and doing it in arithmetic means it works with no key, no network and no cloud
 * setting — which the standalone application requires, since it has no vision path at all. The two
 * compose rather than compete: a model asked to transcribe a spoken meal produces exactly this kind
 * of text, and it can be fed straight in.
 */
object FoodPhrase {

    /** The most items one description will yield, so a pasted essay cannot become five hundred rows. */
    const val MAX_ITEMS: Int = 24

    /**
     * One thing somebody said they ate.
     *
     * [amount] and [unit] are null when they were not said, and stay null — see [portion] for what
     * that turns into and why.
     */
    data class Item(
        val raw: String,
        val name: String,
        val amount: Double? = null,
        val unit: FoodPortion.Unit? = null,
    ) {
        /**
         * The portion to look up, or null when there is no name to look anything up for.
         *
         * ⚠️ **A number with no unit is a COUNT, not a mass.** "2 eggs" is two eggs; reading it as
         * two grams is the single worst thing this could do, and it is what a parser that defaulted
         * to grams would do on the commonest phrasing there is. A count becomes
         * [FoodPortion.Unit.SERVING], which is honest twice over: a serving of egg is an egg, and a
         * food that declares no serving weight makes [FoodPortion.gramsFor] return null, so the
         * surface has to say it cannot work the amount out instead of inventing one.
         *
         * ⚠️ Nothing said at all is ONE serving, not zero and not a hundred grams. Somebody who
         * writes "toast" ate some toast.
         */
        val portion: FoodPortion.Portion?
            get() = if (name.isBlank()) {
                null
            } else {
                FoodPortion.Portion(amount ?: 1.0, unit ?: FoodPortion.Unit.SERVING)
            }

        /** Was a quantity actually stated, or is the portion a stand-in? */
        val stated: Boolean get() = amount != null || unit != null
    }

    // ---------------------------------------------------------------------------------- splitting

    /**
     * The description as separate things.
     *
     * ⚠️ ", and" is one separator, not two, or every "eggs, and toast" yields an empty item between
     * them. Commas, newlines, semicolons, "and", "&" and "+" all separate; " with " deliberately
     * does not, because "toast with butter" is one thing somebody would look up as written and
     * splitting it invents a second food they did not mention.
     *
     * ⚠️ **A comma BETWEEN DIGITS does not separate anything.** Most of the world writes `12,5 g`,
     * and splitting there turns one item into a food called "12" and five grams of butter. Found by
     * running this over real phrasings rather than by reading it; it is the same trap the label
     * parser documents, arriving from the other direction.
     *
     * ⚠️ "and" separating is the right default — "eggs and toast" is two things far more often than
     * one — and it has a known cost: "sweet and sour chicken" becomes two items. The surface shows
     * what was read before anything is logged, which is where that gets corrected.
     */
    fun split(text: String): List<String> =
        text.split(SEPARATOR)
            .map { it.trim().trim(',', ';', '.', '-', '·').trim() }
            .filter { it.isNotBlank() }
            .take(MAX_ITEMS)

    private val SEPARATOR =
        Regex("""\s*(?:,\s*and\s+|,(?!\d)|;|\n|\r|\+|&|\band\b)\s*""", RegexOption.IGNORE_CASE)

    // ----------------------------------------------------------------------------------- parsing

    /** Every thing named in [text], in the order it was written. */
    fun parse(text: String): List<Item> = split(text).map { item(it) }

    /**
     * One phrase read as a quantity and a name.
     *
     * ⚠️ Only a LEADING quantity is read. "2 eggs" is two eggs; "eggs 2" is not a thing anybody
     * writes, and a parser that hunted for a number anywhere in the phrase would read the 2 in
     * "semi-skimmed milk 2%" as two of something.
     */
    fun item(phrase: String): Item {
        val raw = phrase.trim()
        if (raw.isBlank()) return Item(raw = phrase, name = "")

        var rest = raw
        var amount: Double? = null
        var unit: FoodPortion.Unit? = null

        // ⚠️ Vagueness is tested FIRST, before the word numbers, because "a few" begins with "a" —
        // and "a" is one. Testing the words first eats the article and leaves a food called
        // "few biscuits", which is both wrong and impossible to look up.
        val vague = VAGUE.find(rest)
        val couple = COUPLE.find(rest)
        val digits = LEADING_NUMBER.find(rest)
        when {
            // "a couple" is exactly two and is not vague, whatever it sits next to.
            couple != null -> {
                amount = 2.0
                rest = rest.removeRange(couple.range).trim()
            }
            // ⚠️ "a few", "some", "several" are NOT turned into a number. Three is a guess, and a
            // guess rendered as a quantity is indistinguishable on screen from something weighed.
            // Left unstated, the record's own serving is used, which is at least a real figure.
            vague != null -> rest = rest.removeRange(vague.range).trim()
            digits != null -> {
                amount = digits.groupValues[1].replace(',', '.').toDoubleOrNull()
                rest = rest.removeRange(digits.range).trim()
            }
            else -> {
                val word = rest.substringBefore(' ').lowercase().trim('.')
                val n = WORDS[word]
                if (n != null) {
                    amount = n
                    rest = rest.substringAfter(' ', "").trim()
                    // ⚠️ "half a cup", "two a day" — an article between a word number and its unit
                    // is ordinary English and would otherwise be read as the unit, blocking it.
                    rest = ARTICLE.replace(rest, "")
                }
            }
        }

        // ⚠️ A multiplication sign between the quantity and the name: "3 x biscuits". Matched as a
        // WHOLE token, because removing a bare "x" prefix would turn two xylitol into two ylitol.
        rest = MULTIPLIER.replace(rest, "").trim()
        val u = LEADING_UNIT.find(rest)
        if (u != null && u.range.first == 0) {
            val token = u.groupValues[1].lowercase().removeSuffix("s")
            val known = UNITS[token]
            if (known != null) {
                unit = known.unit
                if (amount != null) amount *= known.factor
                rest = rest.removeRange(u.range).trim()
            }
        }

        // "of" only ever joins a quantity to a name, so it is dropped rather than searched for.
        // ⚠️ And the article AFTER it — "quarter of a pizza" otherwise names a food "a pizza",
        // which looks up nothing. Found by running this over real phrasings.
        rest = ARTICLE.replace(rest.removePrefix("of ").trim(), "").trim()
        rest = rest.trimStart('-', '·', ':').trim()
        return Item(raw = raw, name = rest, amount = amount, unit = unit)
    }

    private val LEADING_NUMBER = Regex("""^(\d+(?:[.,]\d+)?)\s*""")
    private val LEADING_UNIT = Regex("""^([A-Za-z]+)\b\.?""")
    private val VAGUE = Regex("""^(?:a\s+few|a\s+bit\s+of|some|several)\s+""", RegexOption.IGNORE_CASE)
    private val COUPLE = Regex("""^a\s+couple\s+(?:of\s+)?""", RegexOption.IGNORE_CASE)
    private val ARTICLE = Regex("""^an?\s+""", RegexOption.IGNORE_CASE)
    private val MULTIPLIER = Regex("""^[x×]\s+""", RegexOption.IGNORE_CASE)

    /**
     * Quantities written as words.
     *
     * ⚠️ "a" and "an" are here and "the" is not. "an apple" is one apple; "the apple" is one apple
     * too, but "the" also opens phrases that are not quantities at all, and stripping it would turn
     * "the usual" into a food called "usual".
     */
    private val WORDS: Map<String, Double> = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0,
        "five" to 5.0, "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0,
        "eleven" to 11.0, "twelve" to 12.0, "half" to 0.5, "quarter" to 0.25,
    )

    private data class Measure(val unit: FoodPortion.Unit, val factor: Double)

    /**
     * The words people write for an amount.
     *
     * ⚠️ Ounces and pounds convert EXACTLY, because they are units of mass and the conversion is a
     * defined constant rather than an estimate.
     *
     * ⚠️ Cups and spoons convert to MILLILITRES, and that is a deliberate choice with a known cost.
     * A cup is a defined volume, so cup-to-millilitre is exact; it is the millilitre-to-gram step
     * that approximates, and [FoodPortion.gramsFor] already documents that approximation, where it
     * holds and where it does not. Doing it any other way would mean either a density table this
     * app has no source for, or throwing away a quantity somebody took the trouble to state.
     *
     * ⚠️ A slice, a piece and a portion are all SERVING rather than a mass. That is the same
     * honesty as a bare count: if the record cannot say what one weighs, nothing here can either.
     */
    private val UNITS: Map<String, Measure> = mapOf(
        "g" to Measure(FoodPortion.Unit.GRAM, 1.0),
        "gram" to Measure(FoodPortion.Unit.GRAM, 1.0),
        "gramme" to Measure(FoodPortion.Unit.GRAM, 1.0),
        "gm" to Measure(FoodPortion.Unit.GRAM, 1.0),
        "kg" to Measure(FoodPortion.Unit.GRAM, 1000.0),
        "kilo" to Measure(FoodPortion.Unit.GRAM, 1000.0),
        "kilogram" to Measure(FoodPortion.Unit.GRAM, 1000.0),
        "oz" to Measure(FoodPortion.Unit.GRAM, 28.349523125),
        "ounce" to Measure(FoodPortion.Unit.GRAM, 28.349523125),
        "lb" to Measure(FoodPortion.Unit.GRAM, 453.59237),
        "pound" to Measure(FoodPortion.Unit.GRAM, 453.59237),
        "ml" to Measure(FoodPortion.Unit.MILLILITRE, 1.0),
        "millilitre" to Measure(FoodPortion.Unit.MILLILITRE, 1.0),
        "milliliter" to Measure(FoodPortion.Unit.MILLILITRE, 1.0),
        "l" to Measure(FoodPortion.Unit.MILLILITRE, 1000.0),
        "litre" to Measure(FoodPortion.Unit.MILLILITRE, 1000.0),
        "liter" to Measure(FoodPortion.Unit.MILLILITRE, 1000.0),
        "cup" to Measure(FoodPortion.Unit.MILLILITRE, 240.0),
        "tbsp" to Measure(FoodPortion.Unit.MILLILITRE, 15.0),
        "tablespoon" to Measure(FoodPortion.Unit.MILLILITRE, 15.0),
        "tsp" to Measure(FoodPortion.Unit.MILLILITRE, 5.0),
        "teaspoon" to Measure(FoodPortion.Unit.MILLILITRE, 5.0),
        "slice" to Measure(FoodPortion.Unit.SERVING, 1.0),
        "piece" to Measure(FoodPortion.Unit.SERVING, 1.0),
        "serving" to Measure(FoodPortion.Unit.SERVING, 1.0),
        "portion" to Measure(FoodPortion.Unit.SERVING, 1.0),
        "pack" to Measure(FoodPortion.Unit.PACKAGE, 1.0),
        "packet" to Measure(FoodPortion.Unit.PACKAGE, 1.0),
        "package" to Measure(FoodPortion.Unit.PACKAGE, 1.0),
    )

    // ------------------------------------------------------------------------------------- words

    /**
     * What was read back, so somebody can see it was understood before anything is logged.
     *
     * ⚠️ Says when a quantity was NOT stated rather than printing the stand-in as though it had
     * been. "toast" reads as "toast — a serving", which is a different claim from "1 serving of
     * toast" and the honest one.
     */
    fun describe(item: Item): String {
        if (item.name.isBlank()) return "nothing"
        if (!item.stated) return "${item.name} — a serving"
        val n = item.amount ?: 1.0
        val u = item.unit ?: FoodPortion.Unit.SERVING
        val amount = if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()
        return when (u) {
            FoodPortion.Unit.GRAM -> "$amount g of ${item.name}"
            FoodPortion.Unit.MILLILITRE -> "$amount ml of ${item.name}"
            FoodPortion.Unit.SERVING -> if (n == 1.0) "1 serving of ${item.name}" else "$amount × ${item.name}"
            FoodPortion.Unit.PACKAGE -> if (n == 1.0) "a pack of ${item.name}" else "$amount packs of ${item.name}"
        }
    }
}
