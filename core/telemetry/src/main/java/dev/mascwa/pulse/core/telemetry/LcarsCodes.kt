package dev.mascwa.pulse.core.telemetry

/**
 * The small numbers stencilled on every LCARS block.
 *
 * On screen they mean nothing — they are set dressing, and that is exactly why they have to be
 * *stable*. A code that changed between visits to the same screen would stop reading as a panel
 * marking and start reading as a fault, so these are derived from the screen's own identity rather
 * than drawn at random. Same seed, same code, for the life of the build.
 *
 * The app has been approximating this with `cyberTag()`, which produces `TRN.0x3F2A` — a hexadecimal
 * Cyberpunk idiom borrowed from the interface language this one replaced. LCARS codes are short
 * decimal runs.
 *
 * Pure and testable, which is the whole reason this lives here rather than beside the composable
 * that draws it.
 */
object LcarsCodes {

    /** Shortest and longest run of digits. Real panels carry roughly this range. */
    const val MIN_DIGITS = 2
    const val MAX_DIGITS = 4

    /**
     * A code for [seed] — typically a route name. [index] distinguishes the several blocks that a
     * single screen's rail draws, so one screen yields a whole column of different-but-fixed codes.
     */
    fun of(seed: String, index: Int = 0): String {
        val h = hash(seed, index)
        val digits = MIN_DIGITS + (h % (MAX_DIGITS - MIN_DIGITS + 1))
        var pow = 1
        repeat(digits) { pow *= 10 }
        // A second, differently-salted pass for the value, so the digit count and the digits do not
        // move together — otherwise every 4-digit code on the screen would start with the same one.
        val value = hash(seed, index + 7919) % pow
        return value.toString().padStart(digits, '0')
    }

    /** [count] codes for one screen, in rail order. */
    fun column(seed: String, count: Int): List<String> =
        (0 until count.coerceAtLeast(0)).map { of(seed, it) }

    /**
     * FNV-1a, folded to a non-negative Int.
     *
     * `abs()` is not used: `abs(Int.MIN_VALUE)` is still `Int.MIN_VALUE`, and a negative here would
     * produce a negative modulo and a code with a minus sign in it. Masking the sign bit cannot.
     */
    private fun hash(seed: String, salt: Int): Int {
        var h = -2128831035 // 2166136261 as a signed Int
        for (ch in seed) {
            h = (h xor ch.code) * 16777619
        }
        h = (h xor salt) * 16777619
        return h and 0x7FFFFFFF
    }
}
