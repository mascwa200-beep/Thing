package dev.mascwa.pulse.feature.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DialPadTest {

    @Test
    fun `the keys are the standard twelve, with their letters`() {
        assertEquals("123456789*0#", DialPad.KEYS.map { it.digit }.joinToString(""))
        assertEquals("PQRS", DialPad.KEYS.first { it.digit == '7' }.letters)
        assertEquals('+', DialPad.KEYS.first { it.digit == '0' }.held)
        // Every letter A–Z sits on exactly one key.
        assertEquals(('A'..'Z').toList(), DialPad.KEYS.flatMap { it.letters.toList() }.sorted())
    }

    @Test
    fun `a letter types the digit it sits on, in either case`() {
        assertEquals('7', DialPad.digitFor('s'))
        assertEquals('9', DialPad.digitFor('Z'))
        assertNull(DialPad.digitFor('é'))
        assertNull(DialPad.digitFor('3'))
    }

    @Test
    fun `only dialable characters are typed`() {
        var n = ""
        for (c in "6x1-6 5*#,;") n = DialPad.append(n, c)
        assertEquals("616 5".filter { it != ' ' } + "*#,;", n)
    }

    @Test
    fun `a plus is only typed at the start`() {
        assertEquals("+", DialPad.append("", '+'))
        assertEquals("44", DialPad.append("44", '+'))
    }

    @Test
    fun `a number stops growing at its limit`() {
        val full = "1".repeat(DialPad.MAX_LENGTH)
        assertEquals(full, DialPad.append(full, '2'))
        assertEquals("1".repeat(DialPad.MAX_LENGTH - 1), DialPad.backspace(full))
        assertEquals("", DialPad.backspace(""))
    }

    @Test
    fun `a pasted number is made dialable, letters and all`() {
        assertEquals("18003569377", DialPad.sanitise("1-800-FLOWERS"))
        assertEquals("+16165550100", DialPad.sanitise("+1 (616) 555-0100"))
        assertEquals("+16165550100", DialPad.sanitise("  +1 616.555.0100"))
        // A plus in the middle of a paste means nothing and is dropped, as a keypress would be.
        assertEquals("1234", DialPad.sanitise("12+34"))
    }

    @Test
    fun `a name becomes one digit run per word`() {
        assertEquals(listOf("266", "533"), DialPad.t9Words("Ann Lee"))
        assertEquals(listOf("56", "266"), DialPad.t9Words("Jo-Ann"))
        assertEquals(listOf("6", "6325"), DialPad.t9Words("O'Neal"))
        assertTrue(DialPad.t9Words("123 !!").isEmpty())
    }

    @Test
    fun `a word of the name starting with the digits is the best match`() {
        assertEquals(DialPad.NAME_WORD, DialPad.score("7", "Paul Smith", null))
        assertEquals(DialPad.NAME_WORD, DialPad.score("72", "Paul Smith", null))
        assertEquals(DialPad.NAME_WORD, DialPad.score("76", "Paul Smith", null)) // "Sm" is 76
        assertEquals(0, DialPad.score("72", "Rob Jones", null))
    }

    @Test
    fun `the name run together is matched through the space`() {
        // "Jo Ann": "56" then "266"; "562" starts neither word, but does start "56266".
        assertEquals(DialPad.NAME_RUN, DialPad.score("562", "Jo Ann", null))
    }

    @Test
    fun `digits inside the number match only once they are specific enough`() {
        assertEquals(DialPad.NUMBER, DialPad.score("555", null, "(616) 555-0100"))
        assertEquals(0, DialPad.score("55", null, "(616) 555-0100"))
    }

    @Test
    fun `nothing typed, or anything but digits typed, matches nothing`() {
        assertEquals(0, DialPad.score("", "Paul", "5550100"))
        assertEquals(0, DialPad.score("7*", "Paul", "5550100"))
    }

    @Test
    fun `a name match outranks a number match`() {
        assertTrue(DialPad.score("555", "Kim", "5550100") < DialPad.score("546", "Kim", "123"))
    }
}
