package dev.mascwa.pulse.feature.keyboard

import dev.mascwa.pulse.feature.keyboard.KeyboardGeometry.Metrics
import dev.mascwa.pulse.feature.keyboard.KeyboardGeometry.Placed
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Key
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyTouchTest {

    private val m = Metrics(keyHeight = 50f, rowGap = 8f, keyGap = 6f, padTop = 6f, padBottom = 6f, padSide = 4f)
    private val keys = KeyboardGeometry.place(KeyboardModel.rows(Layer.LETTERS, ",", false), 400f, m)

    private fun k(s: String): Placed = keys.first { it.key == Key.Text(s) }
    private val shift get() = keys.first { it.key == Key.Shift }
    private val delete get() = keys.first { it.key == Key.Delete }
    private val enter get() = keys.first { it.key == Key.Enter }

    @Test
    fun `a tap on a letter types it on release`() {
        assertEquals(k("e"), KeyTouch.release(KeyTouch.down(k("e"))))
    }

    @Test
    fun `sliding from one letter to another types the one the finger is on`() {
        val f = KeyTouch.moveTo(KeyTouch.down(k("e")), k("r"))
        assertEquals(k("r"), KeyTouch.release(f))
    }

    @Test
    fun `sliding off the letters altogether types nothing`() {
        assertNull(KeyTouch.release(KeyTouch.moveTo(KeyTouch.down(k("z")), shift)))
    }

    @Test
    fun `a key that does something acts only when released on itself`() {
        assertEquals(enter, KeyTouch.release(KeyTouch.down(enter)))
        assertNull(KeyTouch.release(KeyTouch.moveTo(KeyTouch.down(enter), k("m"))))
        // And coming back onto it before lifting still counts.
        val back = KeyTouch.moveTo(KeyTouch.moveTo(KeyTouch.down(shift), k("z")), shift)
        assertEquals(shift, KeyTouch.release(back))
    }

    @Test
    fun `delete acts when touched, so its release does nothing more`() {
        assertTrue(KeyTouch.actsOnDown(Key.Delete))
        assertFalse(KeyTouch.actsOnDown(Key.Text("a")))
        assertFalse(KeyTouch.actsOnDown(Key.Space))
        assertNull(KeyTouch.release(KeyTouch.down(delete)))
    }

    @Test
    fun `a second finger coming down types the first finger's letter at once, and only once`() {
        val (after, typed) = KeyTouch.rollOver(KeyTouch.moveTo(KeyTouch.down(k("t")), k("y")))
        assertEquals(k("y"), typed)
        assertTrue(after.done)
        assertNull(KeyTouch.release(after))
        // A second rollover types nothing more.
        assertNull(KeyTouch.rollOver(after).second)
    }

    @Test
    fun `a rollover leaves a key that does something to finish as it would`() {
        val f = KeyTouch.down(shift)
        val (after, typed) = KeyTouch.rollOver(f)
        assertNull(typed)
        assertEquals(shift, KeyTouch.release(after))
    }

    @Test
    fun `the pop-up shows the letter about to be typed, and nothing else`() {
        assertEquals(k("g"), KeyTouch.preview(KeyTouch.moveTo(KeyTouch.down(k("f")), k("g"))))
        assertNull(KeyTouch.preview(KeyTouch.down(shift)))
        // A finger that came down on shift and slid onto a letter will not type it.
        assertNull(KeyTouch.preview(KeyTouch.moveTo(KeyTouch.down(shift), k("a"))))
        assertNull(KeyTouch.preview(KeyTouch.rollOver(KeyTouch.down(k("h"))).first))
    }

    @Test
    fun `moving within the same key changes nothing`() {
        val f = KeyTouch.down(k("u"))
        assertSame(f, KeyTouch.moveTo(f, k("u")))
    }
}
