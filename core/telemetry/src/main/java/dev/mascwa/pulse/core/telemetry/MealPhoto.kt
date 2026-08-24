package dev.mascwa.pulse.core.telemetry

import kotlin.math.roundToInt

/**
 * Reading a photograph of a meal into something the log can hold.
 *
 * ## ⚠️ The split that makes this honest, and it is the whole design
 *
 * A model looking at a plate is guessing two different things, and they deserve very different
 * treatment:
 *
 *  - **what the food is** — a genuine strength. "Two fried eggs, a slice of buttered toast, half an
 *    avocado" is the kind of description a vision model is good at, and it is the tedious half for a
 *    person to type;
 *  - **how much of it there is, and what is in it** — a guess dressed as a measurement. A model that
 *    answers "320 kcal" has not weighed anything and has not read a label. That figure would enter
 *    the log beside numbers taken from laboratory analyses and be indistinguishable from them, and
 *    the calorie target this app computes would then be built partly on invention.
 *
 * So the model is asked for **names and rough weights only**, and every nutrient figure comes from
 * the bundled food records by looking the name up. Where no record matches, the item is offered
 * with no numbers rather than with made-up ones.
 *
 * ⚠️ And nothing here logs anything. [parse] produces PROPOSALS. A photograph is the least certain
 * input this app has — the portion especially — so the surface must show them to be corrected and
 * confirmed, never write them straight into a day's total.
 *
 * ⚠️ This is also the one part of the food half that **cannot work offline**: it needs a
 * vision-capable cloud model. The surface has to say so rather than fail quietly, which is why
 * [Outcome.NoVision] exists as its own state.
 */
object MealPhoto {

    /**
     * What the model is asked for.
     *
     * ⚠️ **One item per line, in a fixed shape, and it says why.** A free-prose answer would have to
     * be parsed with heuristics that break the first time the model writes a sentence differently.
     * The format is also deliberately austere about confidence: the model is told to say when it
     * cannot tell, because a guess offered as a certainty is exactly what the review step cannot
     * detect.
     */
    const val PROMPT: String = """Look at this photograph of food and list what is in it.

Answer with ONE LINE PER FOOD and nothing else — no preamble, no total, no commentary.

Each line must be exactly:

  name | grams | note

  name   what the food is, in a few plain words ("scrambled eggs", "wholemeal toast")
  grams  your best estimate of the weight of that item on the plate, a whole number
  note   "sure" if you can see it clearly, "guess" if you are inferring it

If you cannot tell how much there is, still give your best estimate and mark it "guess".
If the picture is not food at all, answer with the single word: NONE"""

    /** How sure the model said it was. Carried through so the surface can show it. */
    enum class Confidence { SEEN, GUESSED }

    /**
     * One food the model claims to see, before anything has been looked up or logged.
     *
     * [grams] is the model's estimate and is expected to be edited. It is a Double so the surface
     * can hand back whatever the person types without a second conversion.
     */
    data class Item(
        val name: String,
        val grams: Double,
        val confidence: Confidence = Confidence.GUESSED,
    )

    /** What came back, including the ways it can come back with nothing. */
    sealed interface Outcome {
        data class Read(val items: List<Item>) : Outcome

        /** The model looked and says there is no food here. */
        data object NotFood : Outcome

        /** Something came back that no line of could be read as an item. */
        data class Unreadable(val raw: String) : Outcome

        /** No vision-capable model is configured. The one state that is not a failure. */
        data object NoVision : Outcome

        /** The request did not complete. */
        data class Unreachable(val reason: String) : Outcome
    }

    /** Below this a "portion" is a rounding error; above it, a plate nobody ate. */
    const val MIN_GRAMS = 1.0
    const val MAX_GRAMS = 3000.0

    /** More lines than this and the model is describing a buffet rather than reading a plate. */
    const val MAX_ITEMS = 12

    /**
     * Read the model's answer.
     *
     * ⚠️ **A line that cannot be read is skipped, not guessed at.** Models add a closing sentence
     * more often than not, and treating "Let me know if you would like the totals!" as a food is
     * how a plate ends up with an item nobody can explain. A line only counts when it has a name
     * and a number in the shape the prompt asked for.
     *
     * ⚠️ An absurd weight is dropped rather than clamped. Clamping 50,000 g to 3,000 g invents a
     * portion out of a value that was plainly wrong; dropping it lets the person add what they ate.
     */
    fun parse(raw: String): Outcome {
        val text = raw.trim()
        if (text.isEmpty()) return Outcome.Unreadable(raw)
        if (text.equals("NONE", ignoreCase = true) ||
            text.lineSequence().firstOrNull()?.trim().equals("NONE", ignoreCase = true)
        ) {
            return Outcome.NotFood
        }
        val items = ArrayList<Item>()
        for (line in text.lineSequence()) {
            if (items.size >= MAX_ITEMS) break
            val parts = line.split('|')
            if (parts.size < 2) continue
            val name = parts[0].trim()
                // A model that numbers its list gives "1. scrambled eggs"; the number is not a name.
                .removePrefix("-").removePrefix("*").trim()
                .replace(NUMBERED, "")
                .trim()
            if (name.isEmpty() || name.length > MAX_NAME) continue
            // ⚠️ The SIGN is kept, and that is not fussiness. An earlier version stripped everything
            // but digits and a point, which turned "-20" into a perfectly valid twenty-gram
            // portion — a negative weight silently becoming a positive one, past a range check that
            // could no longer see anything wrong. Found by the test that expected it dropped.
            val grams = NUMBER.find(parts[1])?.value?.toDoubleOrNull() ?: continue
            if (!grams.isFinite() || grams < MIN_GRAMS || grams > MAX_GRAMS) continue
            val note = parts.getOrNull(2)?.trim()?.lowercase().orEmpty()
            items += Item(
                name = name,
                grams = grams.roundToInt().toDouble(),
                confidence = if (note.startsWith("sure")) Confidence.SEEN else Confidence.GUESSED,
            )
        }
        return if (items.isEmpty()) Outcome.Unreadable(raw) else Outcome.Read(items)
    }

    private const val MAX_NAME = 60
    private val NUMBERED = Regex("""^\d+[.)]\s*""")

    /** The first number on the line, sign included so a negative weight can be rejected. */
    private val NUMBER = Regex("""-?\d+(?:\.\d+)?""")

    /**
     * A one-line summary of what a photograph turned into, for the surface to show above the list.
     *
     * ⚠️ Says how many were guessed rather than seen, because that is the number that decides how
     * carefully somebody should read the list before confirming it.
     */
    fun summary(items: List<Item>): String {
        if (items.isEmpty()) return "Nothing recognised."
        val guessed = items.count { it.confidence == Confidence.GUESSED }
        val n = items.size
        val plate = if (n == 1) "1 item" else "$n items"
        return when (guessed) {
            0 -> "$plate — check the weights before logging."
            n -> "$plate, all estimated — check every weight before logging."
            else -> "$plate, $guessed estimated — check the weights before logging."
        }
    }
}
