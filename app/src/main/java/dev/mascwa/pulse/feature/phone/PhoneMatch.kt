package dev.mascwa.pulse.feature.phone

/**
 * When two written-down numbers are the same phone.
 *
 * A contact saved as `(616) 555-0100`, a call logged from `+16165550100` and a number typed as
 * `616.555.0100` are one caller, and nothing in the call log says so. The rule is the one phones have
 * always used: compare the digits from the right. [KEY_DIGITS] of them is enough to span a whole
 * national number in North America and most of Europe while dropping a country code and a trunk
 * prefix, so `+44 7700 900123` and `07700 900123` also agree.
 *
 * ⚠️ **The honest cost:** two different international numbers that happen to share their last nine
 * digits are treated as one. That is Android's own trade (`PhoneNumberUtils.compare` matches on fewer)
 * and it only ever mislabels a call; nothing is dialled from a match.
 *
 * Pure, so the rule is tested rather than remembered.
 */
object PhoneMatch {

    /** How many trailing digits identify a number. */
    const val KEY_DIGITS = 9

    /** Only the digits of [number], or empty for none. */
    fun digits(number: String?): String = number?.filter { it in '0'..'9' }.orEmpty()

    /**
     * The key two numbers are compared on: the last [KEY_DIGITS] digits, or all of them for a shorter
     * number (a short code such as 611). Null when there are no digits at all — a withheld caller, a
     * blank entry — so two withheld calls are never "the same number".
     */
    fun key(number: String?): String? {
        val d = digits(number)
        return if (d.isEmpty()) null else d.takeLast(KEY_DIGITS)
    }

    /** Whether [a] and [b] are the same phone. Never true when either is unknown. */
    fun same(a: String?, b: String?): Boolean {
        val ka = key(a) ?: return false
        return ka == key(b)
    }
}
