package dev.mascwa.pulse.feature.keyboard

import kotlin.math.floor

/**
 * What a held key offers, the way Gboard does it: the top row's letters carry their digit (drawn small
 * in the key's corner, so it can be found without holding anything), the vowels and a few consonants
 * carry their accented forms, and the full stop carries the punctuation that is not on the letters.
 *
 * The first choice is the one already highlighted when the row opens, so holding a key and lifting
 * without moving types it: on the top row that is the digit, which is what makes "hold q for 1" work.
 */
object KeyAlternates {

    private val DIGITS = mapOf(
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
        "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
    )

    private val ACCENTS = mapOf(
        "a" to listOf("à", "á", "â", "ä", "æ", "ã", "å", "ā"),
        "e" to listOf("è", "é", "ê", "ë", "ē"),
        "i" to listOf("ì", "í", "î", "ï", "ī"),
        "o" to listOf("ò", "ó", "ô", "ö", "õ", "ø", "ō", "œ"),
        "u" to listOf("ù", "ú", "û", "ü", "ū"),
        "y" to listOf("ý", "ÿ"),
        "c" to listOf("ç", "ć", "č"),
        "n" to listOf("ñ", "ń"),
        "s" to listOf("ß", "ś", "š"),
        "z" to listOf("ž", "ź", "ż"),
        "l" to listOf("ł"),
    )

    /** Ten, so the row fits one line on the narrowest phone this runs on at a readable width. */
    private val PUNCTUATION = listOf(",", "?", "!", "'", "\"", ":", ";", "-", "(", ")")

    /** The choices a held [value] offers, default first, or empty when holding it offers nothing. */
    fun of(value: String): List<String> = when (value) {
        "." -> PUNCTUATION
        else -> listOfNotNull(DIGITS[value]) + ACCENTS[value].orEmpty()
    }

    /** The small character drawn in a key's corner, telling what holding it types: a top-row digit. */
    fun hint(value: String): String? = DIGITS[value]

    /**
     * [choice] as the keyboard would type it with shift in the given state — the same rule the letter
     * itself follows. A choice with no one-character capital (ß capitalises to two letters) stays as it
     * is, so the row never changes width under a finger.
     */
    fun cased(choice: String, upper: Boolean): String = if (upper) KeyboardModel.capital(choice) else choice
}

/**
 * Where the row of choices sits, and which one a finger is on. Pure, so the arithmetic a thumb relies on
 * is tested rather than eyeballed.
 */
object AlternatePicker {

    /**
     * The row: its left edge, the width of one choice, how many there are, and whether they run
     * right-to-left from the key ([reversed]) because there was no room to the right.
     */
    data class Row(val left: Float, val cellWidth: Float, val count: Int, val reversed: Boolean = false)

    /**
     * Lay out [count] choices for a key centred at [keyCx] on a keyboard [width] wide with [pad] at each
     * side. Choices are [preferredCell] wide unless there are too many to fit, when they share the width.
     *
     * ⚠️ **The first choice sits over the key whenever it can**, because it is the one highlighted when
     * the row opens and the finger is still on the key. So the row runs to the key's RIGHT, or — near the
     * right edge, where that would leave the screen — to its LEFT, first choice still over the key, as
     * Gboard's does. Sliding the row along until it fits instead would put some other choice under a
     * finger that has not moved, and lifting would type that.
     *
     * Only when the row fits neither way is it simply clamped on screen.
     */
    fun row(keyCx: Float, count: Int, preferredCell: Float, width: Float, pad: Float): Row {
        val available = (width - 2 * pad).coerceAtLeast(0f)
        val cell = if (count <= 0) preferredCell else minOf(preferredCell, available / count)
        val span = cell * count
        val rightward = keyCx - cell / 2f
        if (rightward >= pad && rightward + span <= width - pad) return Row(rightward, cell, count)
        val leftward = keyCx + cell / 2f - span
        if (leftward >= pad && leftward + span <= width - pad) return Row(leftward, cell, count, reversed = true)
        return Row(rightward.coerceIn(pad, (width - pad - span).coerceAtLeast(pad)), cell, count)
    }

    /** Where choice [index] is drawn: which slot of the row, counting from the left. */
    fun slotOf(index: Int, row: Row): Int = if (row.reversed) row.count - 1 - index else index

    /** The choice under a finger at [x]: past either end of the row is that end's choice. */
    fun indexAt(x: Float, row: Row): Int {
        val slot = floor((x - row.left) / row.cellWidth).toInt().coerceIn(0, (row.count - 1).coerceAtLeast(0))
        return slotOf(slot, row)
    }

    /**
     * The choice a finger at ([x], [y]) would type, or null when it has been dragged down below
     * [cancelBelow] — Gboard's way of backing out of a row opened by mistake.
     */
    fun pick(x: Float, y: Float, row: Row, cancelBelow: Float): Int? =
        if (y > cancelBelow || row.count <= 0) null else indexAt(x, row)
}
