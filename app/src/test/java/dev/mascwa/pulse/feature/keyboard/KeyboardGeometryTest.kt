package dev.mascwa.pulse.feature.keyboard

import dev.mascwa.pulse.feature.keyboard.KeyboardGeometry.Metrics
import dev.mascwa.pulse.feature.keyboard.KeyboardGeometry.Placed
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Key
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardGeometryTest {

    // A 400 px keyboard: 392 px of row inside 4 px of side padding, so one key width is 39.2 px.
    private val m = Metrics(keyHeight = 50f, rowGap = 8f, keyGap = 6f, padTop = 6f, padBottom = 6f, padSide = 4f)
    private val letters = KeyboardGeometry.place(KeyboardModel.rows(Layer.LETTERS, ",", false), 400f, m)

    private fun at(x: Float, y: Float): Key? = KeyboardGeometry.keyAt(letters, x, y)?.key
    private fun text(s: String) = Key.Text(s)
    private fun placed(s: String): Placed = letters.first { it.key == text(s) }
    private val eps = 1e-3f

    @Test
    fun `the keyboard is its rows, their gaps and its padding`() {
        // 6 + 4 rows of 50 + 3 gaps of 8 + 6
        assertEquals(236f, KeyboardGeometry.height(4, m), eps)
    }

    @Test
    fun `a key is drawn inside its cell, half a gap in from each side`() {
        val q = placed("q")
        assertEquals(4f, q.cell.left, eps)
        assertEquals(43.2f, q.cell.right, eps)
        assertEquals(7f, q.box.left, eps)
        assertEquals(40.2f, q.box.right, eps)
        assertEquals(6f, q.box.top, eps)
        assertEquals(56f, q.box.bottom, eps)
    }

    @Test
    fun `every full row's cells run edge to edge with no seam`() {
        for (row in listOf(0, 2, 3)) {
            val cells = letters.filter { it.row == row }.map { it.cell }
            assertEquals("row $row starts at the padding", 4f, cells.first().left, eps)
            assertEquals("row $row ends at the padding", 396f, cells.last().right, eps)
            cells.zipWithNext().forEach { (a, b) -> assertEquals("row $row has a seam", a.right, b.left, eps) }
        }
    }

    @Test
    fun `the half-key indent of the middle row is not a key`() {
        assertEquals(9, letters.count { it.row == 1 })
        assertTrue(letters.none { it.key == Key.Gap })
    }

    @Test
    fun `a touch in the gap between two keys types the nearer one`() {
        // q's cell ends at 43.2 — between q's box (ending 40.2) and w's (starting 46.2).
        assertEquals(text("q"), at(43.0f, 30f))
        assertEquals(text("w"), at(43.3f, 30f))
    }

    @Test
    fun `a touch between two rows goes to the nearer row`() {
        // Row one ends at 56 and row two starts at 64: the boundary is halfway, at 60.
        assertEquals(0, KeyboardGeometry.keyAt(letters, 100f, 59f)?.row)
        assertEquals(1, KeyboardGeometry.keyAt(letters, 100f, 61f)?.row)
    }

    @Test
    fun `a touch above or below the keys is the nearest row`() {
        assertEquals(0, KeyboardGeometry.keyAt(letters, 100f, -20f)?.row)
        assertEquals(Key.Space, at(200f, 300f))
    }

    @Test
    fun `a touch in the middle row's indent or past a row's end is the nearest key`() {
        assertEquals(text("a"), at(10f, 90f))
        assertEquals(text("l"), at(390f, 90f))
        assertEquals(text("p"), at(399f, 30f))
    }

    /**
     * `keyAt` is a question about geometry, not about the order the keys happen to be listed in. Every
     * other fixture lists the rows top to bottom, where a nearest-row rule that got the distance above a
     * row wrong would still land on the right answer by returning the first tie — so this one reverses
     * the list, which is the only way that half of the rule decides anything.
     */
    @Test
    fun `the answer does not depend on the order the keys are listed`() {
        val reversed = letters.reversed()
        assertEquals(0, KeyboardGeometry.keyAt(reversed, 100f, 30f)?.row)
        assertEquals(1, KeyboardGeometry.keyAt(reversed, 100f, 90f)?.row)
        assertEquals(3, KeyboardGeometry.keyAt(reversed, 200f, 300f)?.row)
    }

    @Test
    fun `no keys, no key`() {
        assertNull(KeyboardGeometry.keyAt(emptyList(), 10f, 10f))
    }
}
