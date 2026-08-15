package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LcarsCodesTest {

    @Test fun theSameScreenAlwaysGetsTheSameCode() {
        // The whole point. Set dressing that changed between visits would read as a fault rather
        // than as a panel marking.
        assertEquals(LcarsCodes.of("weather"), LcarsCodes.of("weather"))
        assertEquals(LcarsCodes.column("markets", 5), LcarsCodes.column("markets", 5))
    }

    @Test fun differentScreensAndDifferentBlocksDiffer() {
        assertNotEquals(LcarsCodes.of("weather"), LcarsCodes.of("markets"))
        val column = LcarsCodes.column("weather", 6)
        // Not a strict requirement that all six differ, but a column that collapsed to one repeated
        // number would look broken, so assert it has real variety.
        assertTrue("column was too uniform: $column", column.toSet().size >= 4)
    }

    @Test fun everyCodeIsShortDigitsAndNothingElse() {
        // No sign, no hex, no letters. The old cyberTag() produced "TRN.0x3F2A"; this must not.
        val seeds = listOf("", "a", "weather", "jarvis_setup", "survival?guide=knots", "éèê", "x".repeat(500))
        for (seed in seeds) {
            for (i in 0 until 12) {
                val code = LcarsCodes.of(seed, i)
                assertTrue("'$code' from '$seed'#$i is not digits", code.all { it.isDigit() })
                assertTrue(
                    "'$code' length ${code.length} out of range",
                    code.length in LcarsCodes.MIN_DIGITS..LcarsCodes.MAX_DIGITS,
                )
            }
        }
    }

    @Test fun aHashCollapsingToTheSignBitCannotProduceAMinusSign() {
        // The reason the hash masks its sign bit rather than calling abs(): abs(Int.MIN_VALUE) is
        // still Int.MIN_VALUE, so a negative would survive into the modulo and print "-42".
        // Brute-forced over enough seeds to exercise the full hash range.
        for (i in 0 until 4000) {
            val code = LcarsCodes.of("seed$i", i)
            assertTrue("negative code leaked: $code", !code.contains('-'))
        }
    }

    @Test fun theDigitCountAndTheDigitsAreSaltedSeparately() {
        // Both are drawn from the same seed; if they shared a hash, the length and the leading digit
        // would move together and every long code on a screen would open with the same numeral.
        val leadingOfFourDigit = (0 until 400)
            .map { LcarsCodes.of("route$it", it) }
            .filter { it.length == LcarsCodes.MAX_DIGITS }
            .map { it.first() }
            .toSet()
        assertTrue("4-digit codes all began alike: $leadingOfFourDigit", leadingOfFourDigit.size >= 5)
    }

    @Test fun aColumnOfZeroOrNegativeLengthIsEmptyRatherThanAnError() {
        assertEquals(emptyList<String>(), LcarsCodes.column("weather", 0))
        assertEquals(emptyList<String>(), LcarsCodes.column("weather", -3))
    }
}
