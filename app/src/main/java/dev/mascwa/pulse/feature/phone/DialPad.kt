package dev.mascwa.pulse.feature.phone

import dev.mascwa.pulse.feature.launcher.HomeDirectory

/**
 * The phone's keypad: what each key types, how a number is edited, and how typed digits find a
 * contact — T9, the way every phone's dial pad does.
 *
 * Pure — no Android type — so every rule runs under JUnit. The screen draws [KEYS] and hands every
 * press to [append]; nothing about what a number may contain is decided anywhere else.
 */
object DialPad {

    /** One key: the digit it types, the letters printed under it, and what holding it types instead. */
    data class Key(val digit: Char, val letters: String, val held: Char? = null)

    /**
     * The twelve keys in reading order. Holding 0 types `+`, for international numbers; holding 1 calls
     * voicemail, which is an action rather than a character, so it is [VOICEMAIL_KEY] and not [Key.held].
     */
    val KEYS: List<Key> = listOf(
        Key('1', ""), Key('2', "ABC"), Key('3', "DEF"),
        Key('4', "GHI"), Key('5', "JKL"), Key('6', "MNO"),
        Key('7', "PQRS"), Key('8', "TUV"), Key('9', "WXYZ"),
        Key('*', ""), Key('0', "", held = '+'), Key('#', ""),
    )

    /** Holding this key calls voicemail. */
    const val VOICEMAIL_KEY = '1'

    /** Longer than any real number with an extension; a bound on a stuck key or a pasted essay. */
    const val MAX_LENGTH = 40

    /**
     * The fewest digits that search INSIDE numbers. One digit matches nearly every number in a phone
     * book, so number matches wait until a run is specific enough to mean something; names are matched
     * from the first digit, because a first letter is already a real filter.
     */
    const val NUMBER_MATCH_MIN = 3

    /**
     * What a dial string may hold: digits, `*` and `#` for codes, `+` for international numbers, and the
     * two pause characters (`,` waits, `;` waits for a tap) that dial an extension after the call connects.
     */
    private const val DIALABLE = "0123456789*#+,;"

    private val DIGIT_OF: Map<Char, Char> = buildMap {
        for (k in KEYS) for (c in k.letters) put(c, k.digit)
    }

    /** The digit a letter sits on, or null for anything that is not a letter A–Z. */
    fun digitFor(c: Char): Char? = DIGIT_OF[c.uppercaseChar()]

    /**
     * [current] with [c] typed on the end — unchanged when [c] is not dialable, when the number is full,
     * or when it is a `+` anywhere but the start, where it means nothing to the network.
     */
    fun append(current: String, c: Char): String = when {
        current.length >= MAX_LENGTH -> current
        c !in DIALABLE -> current
        c == '+' && current.isNotEmpty() -> current
        else -> current + c
    }

    fun backspace(current: String): String = current.dropLast(1)

    /**
     * A pasted number made dialable: letters become the digits they sit on ("1-800-FLOWERS"), spaces,
     * dashes, dots and brackets go, and anything else that cannot be dialled is dropped. Built by
     * [append], so a paste obeys exactly the rules a keypress does.
     */
    fun sanitise(pasted: String): String =
        pasted.fold("") { acc, raw -> append(acc, digitFor(raw) ?: raw) }

    /**
     * A `tel:` link's number, ready for the keypad. Its scheme-specific part is RFC 3966: a number,
     * then `;name=value` parameters. An extension (`;ext=42`) is kept as a pause and its digits — what
     * a phone dials after the call connects — and every other parameter (`phone-context`, `isub`) is
     * dropped, because it is not something to dial. A bare `;` is Android's wait character and stays.
     */
    fun fromTelUri(ssp: String): String {
        val parts = ssp.split(';')
        val number = StringBuilder(parts.first())
        for (part in parts.drop(1)) {
            val eq = part.indexOf('=')
            when {
                eq < 0 -> number.append(';').append(part)
                part.substring(0, eq).trim().equals("ext", ignoreCase = true) ->
                    number.append(',').append(part.substring(eq + 1))
            }
        }
        return sanitise(number.toString())
    }

    /**
     * The digits a name's letters sit on, one run per word: "Ann Lee" is ["266", "533"]. Folded first
     * ([HomeDirectory.fold]), so an accented letter is on the key its plain letter is: "Zoë" is 963.
     */
    fun t9Words(name: String): List<String> =
        HomeDirectory.fold(name).split(' ', '-', '.', ',', '\'', '(', ')', '/')
            .map { word -> word.mapNotNull { digitFor(it) }.joinToString("") }
            .filter { it.isNotEmpty() }

    /** How well typed [digits] match a contact; higher first, 0 for no match. */
    fun score(digits: String, name: String?, number: String?): Int {
        // No check that [digits] is all digits: a name and a number are compared as digits only, so a
        // `*` or `#` typed into the pad can never match either, and a check here would be dead code.
        if (digits.isEmpty()) return 0
        val words = name?.let(::t9Words).orEmpty()
        if (words.any { it.startsWith(digits) }) return NAME_WORD
        if (words.size > 1 && words.joinToString("").startsWith(digits)) return NAME_RUN
        if (digits.length >= NUMBER_MATCH_MIN && PhoneMatch.digits(number).contains(digits)) return NUMBER
        return 0
    }

    /** A word of the name starts with the digits: "7" finds Paul, "72" finds Paul and not Rob. */
    const val NAME_WORD = 3

    /** The name run together starts with them, so "562" finds "Jo Ann" by typing through the space. */
    const val NAME_RUN = 2

    /** The digits appear inside the number. */
    const val NUMBER = 1
}
