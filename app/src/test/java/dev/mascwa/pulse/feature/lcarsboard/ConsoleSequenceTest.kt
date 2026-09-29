package dev.mascwa.pulse.feature.lcarsboard

import dev.mascwa.pulse.feature.lcarsboard.ConsoleSequence.DURATION_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleSequenceTest {

    private val n = 9

    @Test
    fun `dark is nothing and up is everything`() {
        for (i in 0 until n) {
            assertEquals(0f, ConsoleSequence.element(0f, i, n), 0f)
            assertEquals(1f, ConsoleSequence.element(1f, i, n), 1e-6f)
        }
    }

    @Test
    fun `every element only ever moves one way as progress moves`() {
        for (i in 0 until n) {
            var last = -1f
            for (s in 0..400) {
                val e = ConsoleSequence.element(s / 400f, i, n)
                assertTrue("element $i went backwards at step $s", e >= last)
                last = e
            }
        }
    }

    @Test
    fun `the corner leads and the last panel follows, at every moment`() {
        for (s in 0..400) {
            val p = s / 400f
            for (i in 0 until n - 1) {
                assertTrue(
                    "element $i behind element ${i + 1} at p=$p",
                    ConsoleSequence.element(p, i, n) >= ConsoleSequence.element(p, i + 1, n),
                )
            }
        }
        // The stagger is real: halfway through, the first element is further along than the last.
        assertTrue(ConsoleSequence.element(0.5f, 0, n) > ConsoleSequence.element(0.5f, n - 1, n))
    }

    @Test
    fun `the power-down is the power-up played backwards, frame for frame`() {
        var t = 0L
        while (t <= DURATION_MS) {
            val down = ConsoleSequence.progressAt(t, rising = false, fromProgress = 1f)
            val up = ConsoleSequence.progressAt(DURATION_MS - t, rising = true, fromProgress = 0f)
            assertEquals("at ${t}ms", up, down, 1e-6f)
            t += 5
        }
    }

    @Test
    fun `a power-down interrupting a power-up continues from where the console is`() {
        // Half-assembled when the power button goes: it starts there, it does not flash up first.
        assertEquals(0.4f, ConsoleSequence.progressAt(0, rising = false, fromProgress = 0.4f), 0f)
        assertEquals(0f, ConsoleSequence.progressAt((DURATION_MS * 0.4f).toLong() + 1, rising = false, fromProgress = 0.4f), 0f)
        // And never overshoots either end.
        assertEquals(1f, ConsoleSequence.progressAt(DURATION_MS * 3, rising = true, fromProgress = 0.9f), 0f)
    }

    @Test
    fun `removing animations means no sweep at all`() {
        assertEquals(0L, ConsoleSequence.durationFor(0f))
        assertEquals(0L, ConsoleSequence.durationFor(Float.NaN))
        // ⚠️ The guard's only real work: 0 × duration and NaN.toLong() are 0 by arithmetic anyway,
        // but a scale read back as negative would otherwise give a negative duration.
        assertEquals(0L, ConsoleSequence.durationFor(-1f))
        assertEquals(DURATION_MS, ConsoleSequence.durationFor(1f))
        assertEquals(1f, ConsoleSequence.progressAt(0, rising = true, fromProgress = 0f, durationMs = 0), 0f)
        assertEquals(0f, ConsoleSequence.progressAt(0, rising = false, fromProgress = 1f, durationMs = 0), 0f)
    }
}
