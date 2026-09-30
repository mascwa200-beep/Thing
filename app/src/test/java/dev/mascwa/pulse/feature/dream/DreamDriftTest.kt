package dev.mascwa.pulse.feature.dream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DreamDriftTest {

    @Test
    fun `it moves every minute`() {
        for (m in 0L until DreamDrift.PATH.size * 2L) {
            assertNotEquals("minute $m", DreamDrift.offsetAt(m), DreamDrift.offsetAt(m + 1))
        }
    }

    @Test
    fun `each step is one small move`() {
        for (m in 0L until DreamDrift.PATH.size * 2L) {
            val (x0, y0) = DreamDrift.offsetAt(m)
            val (x1, y1) = DreamDrift.offsetAt(m + 1)
            assertEquals("minute $m", DreamDrift.STEP_DP, abs(x1 - x0) + abs(y1 - y0))
        }
    }

    @Test
    fun `it never wanders far`() {
        for ((x, y) in DreamDrift.PATH) {
            assertTrue("$x,$y", abs(x) <= DreamDrift.MAX_DP && abs(y) <= DreamDrift.MAX_DP)
        }
    }

    @Test
    fun `a lap averages to the centre`() {
        assertEquals(0, DreamDrift.PATH.sumOf { it.first })
        assertEquals(0, DreamDrift.PATH.sumOf { it.second })
    }

    @Test
    fun `a lap visits every point once`() {
        assertEquals(DreamDrift.PATH.size, DreamDrift.PATH.toSet().size)
        // The square's perimeter at this step: 4 sides of (2 * MAX / STEP) points each.
        assertEquals(4 * (2 * DreamDrift.MAX_DP / DreamDrift.STEP_DP), DreamDrift.PATH.size)
    }

    @Test
    fun `a clock that ran backwards does not throw`() {
        assertEquals(DreamDrift.offsetAt(0), DreamDrift.offsetAt(-5))
    }
}
