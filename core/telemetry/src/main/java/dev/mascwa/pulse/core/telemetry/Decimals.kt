package dev.mascwa.pulse.core.telemetry

/**
 * Reading a number somebody typed, whichever key their keyboard puts the decimal point on.
 *
 * ## Why this exists
 *
 * `String.toDoubleOrNull()` is `java.lang.Double.parseDouble`, which is locale-independent and
 * accepts a full stop and nothing else. Measured, not recalled:
 *
 *     "2.5"  -> 2.5
 *     "2,5"  -> null
 *
 * Most of Europe and most of South America write the second one, and Android's numeric keyboard
 * offers whichever separator the device locale uses — so on those phones a fractional weight, a
 * portion, a macro figure or a training load could not be entered **anywhere in either
 * application**. Every one of roughly thirty input sites simply returned null: the SAVE button
 * stayed disabled, or the field committed as zero, with nothing on screen to say why.
 *
 * ⚠️ **And one site was worse than refusing.** `TrainingCard`'s number field pre-filtered its input
 * to `isDigit() || it == '.'`, which DELETES a comma and closes the gap — so `2,5` became the string
 * `25` and a two-and-a-half kilogram plate was recorded as twenty-five. A refusal is visible; a
 * value ten times too large looks like it worked, and it then feeds the progression advice.
 *
 * ## The rule, and why it is symmetric
 *
 * ⚠️ **A lone separator is a DECIMAL separator, whichever character it is.** So `1,234` and `1.234`
 * both read as one-point-two-three-four. That is deliberate and it is the conservative choice:
 * `toDoubleOrNull` already read `1.234` that way, so nothing a dot-locale reader types changes
 * meaning, and a numeric keyboard offers no grouping key at all — a separator in a field like this
 * came from the decimal key.
 *
 * When BOTH characters appear, the LAST one is the decimal separator and the other is grouping,
 * which is what `1.234,56` and `1,234.56` mean in the two conventions that write them.
 *
 * Anything else — two commas, a stray letter, an empty string — is null, exactly as before.
 */
object Decimals {

    /**
     * A typed number, or null if it is not one.
     *
     * ⚠️ A drop-in replacement for `toDoubleOrNull()` at an input site, and **identical to it for
     * every string that one already accepted**. Nothing here widens what counts as a number beyond
     * the separator: `parseDouble` still does the parsing, on a string with at most one full stop
     * in it and no grouping.
     */
    fun parse(text: String?): Double? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null

        val dot = t.lastIndexOf('.')
        val comma = t.lastIndexOf(',')

        val normalised = when {
            // Neither: whatever it is, parseDouble has the final say.
            dot < 0 && comma < 0 -> t

            // Both: the rightmost is the decimal point, the other is grouping and comes out.
            //
            // ⚠️ `decimal` is the last separator of EITHER kind by construction — both indices are
            // `lastIndexOf` and this takes the larger — so every occurrence of the grouping
            // character is already behind it and stripping only the head is not a restriction.
            // What that leaves behind is the useful part: a SECOND decimal-kind character in the
            // head survives, so "1.2,3.4" normalises to "1.23.4" and the final parse refuses it.
            dot >= 0 && comma >= 0 -> {
                val decimal = maxOf(dot, comma)
                val grouping = if (decimal == dot) ',' else '.'
                t.substring(0, decimal).replace(grouping.toString(), "") + "." + t.substring(decimal + 1)
            }

            // One kind only: it is the decimal point.
            //
            // ⚠️ **There is deliberately no "more than one of them" guard here, and an earlier
            // version of this had one that could never fire.** Two commas normalise to two dots and
            // the final parse refuses them, so the guard was dead code dressed as a safety rule —
            // negative-testing it found nothing, which is what a redundant rule looks like. The
            // behaviour is pinned by a test instead, because it must not change even though no line
            // here implements it.
            else -> if (dot >= 0) t else t.replace(',', '.')
        }
        return normalised.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /**
     * What a number field may keep of what was typed into it, bounded to [max] characters.
     *
     * ⚠️ **The half of this defect that a hand-grep missed, and a gate found: FOURTEEN fields across
     * both applications filtered their own displayed text to `isDigit() || ch == '.'`.** That does
     * not reject a comma, it DELETES it and closes the gap — so on a keyboard whose decimal key is a
     * comma, `2,5` became the string `25` **in the field itself**, and every reader downstream saw a
     * number ten times too large that looked exactly like what had been typed. A refusal is visible;
     * this was not.
     *
     * The sanitiser and the parser have to agree about what a number may contain, which is the
     * reason they live in one file: the filter that keeps a character and the parse that reads it
     * cannot be two people's opinions.
     *
     * ⚠️ A minus sign is deliberately still dropped, as every one of those fields already dropped
     * it. They ask for a weight, a portion or a quantity of food, and there is no negative one.
     */
    fun keep(text: String, max: Int): String =
        text.filter { it.isDigit() || it == '.' || it == ',' }.take(max)

    /**
     * The same, for a whole number.
     *
     * ⚠️ Deliberately NOT `parse(...)?.toInt()`: that would silently accept `2.7` as 2 in a field
     * asking for repetitions. A separator here is a typing mistake, not a decimal point, and the
     * honest answer is null.
     */
    fun parseInt(text: String?): Int? = text?.trim()?.toIntOrNull()
}
