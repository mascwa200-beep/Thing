package dev.mascwa.pulse.feature.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyGesturesTest {

    // A 40 px key and a 12 px slop: one cursor step is 14 px, one word step 20 px.
    private val key = 40f
    private val slop = 12f

    @Test
    fun `a wobble is not a slide, and the first step comes the moment it is one`() {
        assertEquals(0, KeyGestures.cursorSteps(11.9f, slop, key))
        assertEquals(1, KeyGestures.cursorSteps(12f, slop, key))
        assertEquals(1, KeyGestures.cursorSteps(25.9f, slop, key))
        assertEquals(2, KeyGestures.cursorSteps(26f, slop, key))
    }

    /**
     * With a step wider than the slop, rounding down already turns a small wobble into no movement, so
     * the fixtures above would pass with no slop at all. On keys narrow enough that one step is shorter
     * than the slop — a 20 px key steps every 7 px — it is the slop alone that keeps a wobble still.
     */
    @Test
    fun `a wobble is never a slide, however narrow the keys`() {
        assertEquals(0, KeyGestures.cursorSteps(1f, slop, 20f))
        assertEquals(0, KeyGestures.cursorSteps(-5f, slop, 20f))
    }

    @Test
    fun `sliding left moves the cursor left`() {
        assertEquals(-2, KeyGestures.cursorSteps(-26f, slop, key))
        assertEquals(0, KeyGestures.cursorSteps(-11f, slop, key))
    }

    @Test
    fun `a key with no width moves nothing, rather than dividing by it`() {
        assertEquals(0, KeyGestures.cursorSteps(500f, slop, 0f))
        assertEquals(0, KeyGestures.deleteWords(-500f, slop, 0f))
    }

    @Test
    fun `swiping left from delete marks one more word every half key`() {
        assertEquals(0, KeyGestures.deleteWords(-11f, slop, key))
        assertEquals(1, KeyGestures.deleteWords(-12f, slop, key))
        assertEquals(1, KeyGestures.deleteWords(-31.9f, slop, key))
        assertEquals(2, KeyGestures.deleteWords(-32f, slop, key))
    }

    @Test
    fun `swiping right from delete is not a gesture`() {
        assertEquals(0, KeyGestures.deleteWords(50f, slop, key))
    }

    @Test
    fun `a word is erased with the spaces after it`() {
        assertEquals(6, WordErase.chars("hello world ", 1))
        assertEquals(12, WordErase.chars("hello world ", 2))
        assertEquals(3, WordErase.chars("a  b  ", 1))
    }

    @Test
    fun `punctuation goes with the word it touches`() {
        assertEquals(5, WordErase.chars("hello, world", 1))
        assertEquals(12, WordErase.chars("hello, world", 2))
    }

    @Test
    fun `erasing never takes more than there is`() {
        assertEquals(2, WordErase.chars("hi", 5))
        assertEquals(0, WordErase.chars("", 1))
        assertEquals(0, WordErase.chars("hello", 0))
    }

    @Test
    fun `two spaces after a word make a full stop`() {
        val t = DoubleSpace.typedSpace()
        assertTrue(DoubleSpace.applies(t, "word ", allowedHere = true))
        assertTrue(DoubleSpace.applies(t, "1 ", allowedHere = true))
    }

    @Test
    fun `not after two spaces already, nor after a full stop, nor where the field says no`() {
        val t = DoubleSpace.typedSpace()
        assertFalse(DoubleSpace.applies(t, "word  ", allowedHere = true))
        assertFalse(DoubleSpace.applies(t, "word. ", allowedHere = true))
        assertFalse(DoubleSpace.applies(t, "word ", allowedHere = false))
        assertFalse(DoubleSpace.applies(t, " ", allowedHere = true))
        assertFalse(DoubleSpace.applies(t, null, allowedHere = true))
    }

    @Test
    fun `only when the last key was the space bar`() {
        assertFalse(DoubleSpace.applies(DoubleSpace.typedOther(), "word ", allowedHere = true))
    }

    @Test
    fun `the space bar's space is remembered where the cursor was left, and forgotten if it moves`() {
        val landed = DoubleSpace.selectionMoved(DoubleSpace.typedSpace(), 5, 5)
        assertTrue(landed.lastWasSpace)
        assertEquals(5, landed.at)
        // The editor reporting the same place again changes nothing.
        assertTrue(DoubleSpace.selectionMoved(landed, 5, 5).lastWasSpace)
        // A tap elsewhere in the text forgets it: the next space there is only a space.
        assertFalse(DoubleSpace.selectionMoved(landed, 3, 3).lastWasSpace)
        // So does selecting text.
        assertFalse(DoubleSpace.selectionMoved(DoubleSpace.typedSpace(), 2, 5).lastWasSpace)
    }

    @Test
    fun `a full stop from two spaces is for sentences, not passwords, addresses or numbers`() {
        assertTrue(DoubleSpace.allowed(0x01))
        assertTrue(DoubleSpace.allowed(0x01 or 0x4000)) // capitalise sentences
        assertFalse(DoubleSpace.allowed(0x01 or 0x80)) // password
        assertFalse(DoubleSpace.allowed(0x01 or 0x90)) // visible password
        assertFalse(DoubleSpace.allowed(0x01 or 0xE0)) // web password
        assertFalse(DoubleSpace.allowed(0x01 or 0x10)) // web address
        assertFalse(DoubleSpace.allowed(0x01 or 0x20)) // email
        assertFalse(DoubleSpace.allowed(0x01 or 0xD0)) // web email
        assertFalse(DoubleSpace.allowed(0x02 or 0x10)) // a number password
        assertFalse(DoubleSpace.allowed(0x03)) // a phone number
    }
}
