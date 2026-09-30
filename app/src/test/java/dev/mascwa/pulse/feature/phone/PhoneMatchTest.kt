package dev.mascwa.pulse.feature.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMatchTest {

    @Test
    fun `one phone written three ways is one caller`() {
        assertTrue(PhoneMatch.same("(616) 555-0100", "+16165550100"))
        assertTrue(PhoneMatch.same("616.555.0100", "+1 616 555 0100"))
        // A trunk prefix and a country code both fall outside the last nine digits.
        assertTrue(PhoneMatch.same("+44 7700 900123", "07700 900123"))
    }

    @Test
    fun `different numbers are different callers`() {
        assertFalse(PhoneMatch.same("6165550100", "6165550101"))
    }

    @Test
    fun `a short code is compared whole`() {
        assertEquals("611", PhoneMatch.key("611"))
        assertFalse(PhoneMatch.same("611", "911"))
        // Not a suffix of something longer, either: 12345 and 912345 are different numbers.
        assertFalse(PhoneMatch.same("12345", "912345"))
    }

    @Test
    fun `a number with no digits has no key and matches nothing, not even itself`() {
        assertNull(PhoneMatch.key(null))
        assertNull(PhoneMatch.key(""))
        assertNull(PhoneMatch.key("Private"))
        assertFalse(PhoneMatch.same("", ""))
        assertFalse(PhoneMatch.same(null, null))
    }

    @Test
    fun `the key is the last nine digits`() {
        assertEquals("165550100", PhoneMatch.key("+1 (616) 555-0100"))
    }
}
