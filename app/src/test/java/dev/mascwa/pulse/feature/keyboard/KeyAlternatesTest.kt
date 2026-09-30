package dev.mascwa.pulse.feature.keyboard

import dev.mascwa.pulse.feature.keyboard.AlternatePicker.Row
import dev.mascwa.pulse.feature.keyboard.KeyboardGeometry.Metrics
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Key
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyAlternatesTest {

    // The same 400 px keyboard the geometry tests use: one key cell is 39.2 px, 4 px of padding a side.
    private val m = Metrics(keyHeight = 50f, rowGap = 8f, keyGap = 6f, padTop = 6f, padBottom = 6f, padSide = 4f)
    private val keys = KeyboardGeometry.place(KeyboardModel.rows(Layer.LETTERS, ",", false), 400f, m)
    private fun cx(s: String): Float = keys.first { it.key == Key.Text(s) }.box.cx
    private val cell = 39.2f
    private val eps = 1e-3f

    @Test
    fun `a top-row letter offers its digit first, then its accents`() {
        assertEquals(listOf("1"), KeyAlternates.of("q"))
        assertEquals(listOf("3", "è", "é", "ê", "ë", "ē"), KeyAlternates.of("e"))
        assertEquals("0", KeyAlternates.hint("p"))
    }

    @Test
    fun `a key off the top row offers only its accents, and most keys offer nothing`() {
        assertEquals("à", KeyAlternates.of("a").first())
        assertNull(KeyAlternates.hint("a"))
        assertTrue(KeyAlternates.of("b").isEmpty())
        assertTrue(KeyAlternates.of("1").isEmpty())
    }

    @Test
    fun `the full stop holds the punctuation that is not on the letters`() {
        val p = KeyAlternates.of(".")
        assertEquals(",", p.first())
        assertEquals(10, p.size)
        assertTrue("?" in p && "!" in p && "'" in p)
    }

    @Test
    fun `shift capitalises a choice as it does the letter, but never changes its length`() {
        assertEquals("É", KeyAlternates.cased("é", upper = true))
        assertEquals("é", KeyAlternates.cased("é", upper = false))
        assertEquals("3", KeyAlternates.cased("3", upper = true))
        // ß capitalises to "SS": two letters under a finger that is on one.
        assertEquals("ß", KeyAlternates.cased("ß", upper = true))
    }

    @Test
    fun `the row opens with the first choice over the key and runs to its right`() {
        val row = AlternatePicker.row(cx("e"), 6, cell, 400f, 4f)
        assertFalse(row.reversed)
        assertEquals(cx("e") - cell / 2f, row.left, eps)
        // A finger that has not moved is on the first choice.
        assertEquals(0, AlternatePicker.indexAt(cx("e"), row))
        assertEquals(2, AlternatePicker.indexAt(cx("e") + 2 * cell, row))
    }

    @Test
    fun `near the right edge the row runs left, with the first choice still over the key`() {
        val row = AlternatePicker.row(cx("o"), 9, cell, 400f, 4f)
        assertTrue(row.reversed)
        assertEquals(cx("o") + cell / 2f - 9 * cell, row.left, eps)
        assertEquals(0, AlternatePicker.indexAt(cx("o"), row))
        assertEquals(1, AlternatePicker.indexAt(cx("o") - cell, row))
        assertEquals(8, AlternatePicker.slotOf(0, row))
    }

    @Test
    fun `a row too wide for the keyboard shares its width and stays on screen`() {
        val row = AlternatePicker.row(200f, 12, cell, 400f, 4f)
        assertEquals(392f / 12, row.cellWidth, eps)
        assertEquals(4f, row.left, eps)
    }

    @Test
    fun `sliding past either end keeps the end choice`() {
        val row = Row(left = 100f, cellWidth = 40f, count = 5)
        assertEquals(0, AlternatePicker.indexAt(0f, row))
        assertEquals(4, AlternatePicker.indexAt(999f, row))
    }

    @Test
    fun `dragging well below the key backs out of the row`() {
        val row = Row(left = 100f, cellWidth = 40f, count = 5)
        assertEquals(1, AlternatePicker.pick(150f, 80f, row, cancelBelow = 120f))
        assertNull(AlternatePicker.pick(150f, 121f, row, cancelBelow = 120f))
    }
}
