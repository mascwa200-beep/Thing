package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecimalsTest {

    // ⚠️ Every expectation below was checked against the SHIPPED `toDoubleOrNull` first, by running
    // it over this exact list. The dot column is what the app does today and must not move; the
    // comma column is what it could not do at all.

    @Test
    fun `a full stop still means what it always meant`() {
        assertEquals(2.5, Decimals.parse("2.5")!!, 1e-9)
        assertEquals(12.0, Decimals.parse("12")!!, 1e-9)
        assertEquals(0.5, Decimals.parse("0.5")!!, 1e-9)
        assertEquals(82.4, Decimals.parse("82.4")!!, 1e-9)
    }

    @Test
    fun `a comma is a decimal point, which is the whole point`() {
        assertEquals(2.5, Decimals.parse("2,5")!!, 1e-9)
        assertEquals(82.4, Decimals.parse("82,4")!!, 1e-9)
        assertEquals(0.5, Decimals.parse("0,5")!!, 1e-9)
    }

    @Test
    fun `a lone separator reads the same either way`() {
        // The symmetry rule stated on `parse`: 1,234 and 1.234 are the same number. `toDoubleOrNull`
        // already read the second as 1.234, so nothing a dot-locale reader types changes meaning.
        assertEquals(Decimals.parse("1.234")!!, Decimals.parse("1,234")!!, 1e-9)
        assertEquals(1.234, Decimals.parse("1,234")!!, 1e-9)
    }

    @Test
    fun `with both separators the last one is the decimal point`() {
        // The two conventions that actually write grouping, and they must agree on the value.
        assertEquals(1234.56, Decimals.parse("1.234,56")!!, 1e-9)
        assertEquals(1234.56, Decimals.parse("1,234.56")!!, 1e-9)
        assertEquals(1234567.89, Decimals.parse("1.234.567,89")!!, 1e-9)
        assertEquals(1234567.89, Decimals.parse("1,234,567.89")!!, 1e-9)
    }

    // ⚠️ **The next two are BEHAVIOUR PINS, not guard tests, and saying so is the point.** Both came
    // back "asleep" under negative-testing — perturbing the lines that appeared to enforce them
    // changed nothing — because in each case the final `parseDouble` is what refuses the string.
    // The rules they describe still have to hold, so they are asserted here rather than deleted; a
    // future rewrite that normalises more eagerly would break them, which is exactly when somebody
    // needs to be told.

    @Test
    fun `two of the same separator is not a number`() {
        // Normalises to "2.5.5", which the final parse refuses. The count guard that used to sit in
        // the source could never fire and has been removed.
        assertNull(Decimals.parse("2,5,5"))
        assertNull(Decimals.parse("2.5.5"))
        assertNull(Decimals.parse("1,2,3"))
    }

    @Test
    fun `a decimal-kind character left in the head is refused, not silently regrouped`() {
        // "1.2,3.4": the comma is grouping and comes out of the head, but the head's own dot stays,
        // so this normalises to "1.23.4" and is refused. Reading it as 1.234 would be inventing a
        // number out of a typing mistake.
        assertNull(Decimals.parse("1.2,3.4"))
        assertNull(Decimals.parse("1,2.3,4"))
    }

    @Test
    fun `nothing, whitespace and rubbish are all null`() {
        assertNull(Decimals.parse(null))
        assertNull(Decimals.parse(""))
        assertNull(Decimals.parse("   "))
        assertNull(Decimals.parse("abc"))
        assertNull(Decimals.parse("12kg"))
    }

    @Test
    fun `whitespace around a real number is trimmed, as toDoubleOrNull also allows`() {
        assertEquals(2.5, Decimals.parse(" 2.5 ")!!, 1e-9)
        assertEquals(2.5, Decimals.parse(" 2,5 ")!!, 1e-9)
    }

    @Test
    fun `a negative reading is parsed rather than refused`() {
        // Nothing in these fields wants one, and the CALLER is what rejects it — `takeIf { it > 0 }`
        // at the input sites. A parser that quietly turned -1 into null would hide a typo instead
        // of letting the field say the number is out of range.
        assertEquals(-1.5, Decimals.parse("-1,5")!!, 1e-9)
        assertEquals(-1.5, Decimals.parse("-1.5")!!, 1e-9)
    }

    @Test
    fun `an infinity or a not-a-number never gets out`() {
        // parseDouble accepts these words, and "Infinity" grams would propagate through every
        // downstream sum. The physical bounds elsewhere assume a finite figure to compare.
        assertNull(Decimals.parse("Infinity"))
        assertNull(Decimals.parse("-Infinity"))
        assertNull(Decimals.parse("NaN"))
    }

    @Test
    fun `a trailing separator is the halfway state of typing and reads as the whole number`() {
        // Somebody has pressed the decimal key and not yet typed the fraction. `toDoubleOrNull`
        // already accepted "2." as 2.0, so both forms agree rather than one field going blank
        // mid-keystroke while the other does not.
        assertEquals(2.0, Decimals.parse("2.")!!, 1e-9)
        assertEquals(2.0, Decimals.parse("2,")!!, 1e-9)
    }

    @Test
    fun `a leading separator reads as a fraction, both ways`() {
        // ⚠️ `toDoubleOrNull` accepts ".5" and returns 0.5 — measured. The comma form must agree.
        assertEquals(0.5, Decimals.parse(".5")!!, 1e-9)
        assertEquals(0.5, Decimals.parse(",5")!!, 1e-9)
    }

    @Test
    fun `repetitions are whole numbers and a decimal point in one is a typing mistake`() {
        assertEquals(8, Decimals.parseInt("8"))
        assertEquals(8, Decimals.parseInt(" 8 "))
        assertNull(Decimals.parseInt("8.5"))
        assertNull(Decimals.parseInt("8,5"))
        assertNull(Decimals.parseInt(""))
        assertNull(Decimals.parseInt(null))
    }

    @Test
    fun `the field keeps both separators, so nothing is silently closed up`() {
        assertEquals("2,5", Decimals.keep("2,5", 6))
        assertEquals("2.5", Decimals.keep("2.5", 6))
        assertEquals("82,4", Decimals.keep("82,4kg", 6))
        // A minus sign still goes, exactly as all fourteen fields already dropped it: these ask for
        // a weight or a quantity of food and there is no negative one.
        assertEquals("15", Decimals.keep("-15", 6))
    }

    @Test
    fun `the field bound is applied after filtering, as it always was`() {
        assertEquals("123456", Decimals.keep("123456789", 6))
        // Letters do not consume the budget — "1a2a3" is three digits, not five characters.
        assertEquals("123", Decimals.keep("1a2a3", 6))
    }

    @Test
    fun `what the field keeps is what the parser can read`() {
        // ⚠️ The two must agree, which is the reason they are in one file. A character the filter
        // lets through and the parser refuses would be a field somebody can type into and never
        // save from.
        for (typed in listOf("2,5", "2.5", "1,234", "12", "0,5", "1.234,56")) {
            val kept = Decimals.keep(typed, 12)
            assertEquals("kept '$kept' from '$typed'", typed, kept)
            assertTrue("parser refused what the field kept: '$kept'", Decimals.parse(kept) != null)
        }
    }

    @Test
    fun `the exact failures this was written for`() {
        // The training load that became twenty-five, and the weigh-in that could not be entered.
        assertEquals(2.5, Decimals.parse("2,5")!!, 1e-9)
        assertEquals(82.4, Decimals.parse("82,4")!!, 1e-9)
        // What the old pre-filter did to the first of those, kept here so the regression is named.
        assertEquals(25.0, "2,5".filter { it.isDigit() || it == '.' }.toDouble(), 1e-9)
    }
}
