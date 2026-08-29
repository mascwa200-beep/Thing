package dev.mascwa.pulse.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first tests this module has ever had.
 *
 * ⚠️ **`:core:sky` shipped twenty-six files and no test source set at all**, which the handoff notes
 * recorded as a gap and left. Nine of those files are pure Kotlin — the star layer, the line and
 * deep-sky layers, the constellation field, the batcher, the frame — about thirteen hundred lines of
 * arithmetic with no gate on any of it. Every pure rule written during this arc was deliberately put
 * in `:core:telemetry` for that reason, which works and is a constraint on where logic may live.
 *
 * This file exists to end that, and it starts with [StarLayer] because that is where the trap was.
 */
class StarLayerTest {

    /**
     * ⚠️ **The defect, and it is silent in the worst way.** [StarLayer.ensure] replaced its arrays
     * rather than copying them, while [StarLayer.add] calls it with `count + 1` and keeps the count
     * — so crossing the capacity left every earlier star as a zero vector at magnitude zero. Nothing
     * throws; the bright sky simply collapses to one point.
     *
     * Grown deliberately here rather than pre-sized, because pre-sizing is exactly what the one real
     * caller does and is the only reason this was never seen.
     */
    @Test
    fun `growing past the capacity keeps the stars already added`() {
        val layer = StarLayer(initialCapacity = 2)
        val added = 9
        for (i in 0 until added) layer.add(10.0 * i, 5.0, i.toFloat(), i % 3)

        assertEquals(added, layer.count)
        for (i in 0 until added) {
            assertEquals("magnitude of star $i", i.toFloat(), layer.magnitude[i], 0f)
            assertEquals("colour band of star $i", i % 3, layer.colourBand[i])
            val len = layer.vx[i] * layer.vx[i] + layer.vy[i] * layer.vy[i] + layer.vz[i] * layer.vz[i]
            assertEquals("star $i is not a unit vector — it was overwritten", 1.0, len, 1e-12)
        }
    }

    /**
     * ⚠️ A layer built with no room would spin for ever in the doubling loop: zero times two is
     * zero. Not reachable today — every construction passes a positive constant — so this pins a
     * hang that cannot happen rather than one that can, which is the cheaper time to pin it.
     *
     * The timeout is what makes the assertion meaningful: without it a regression wedges CI instead
     * of failing it.
     */
    @Test(timeout = 5_000)
    fun `a layer with no initial room can still be filled`() {
        val layer = StarLayer(initialCapacity = 0)
        layer.add(0.0, 0.0, 1f, 0)
        assertEquals(1, layer.count)
        assertTrue("the arrays should have grown", layer.vx.isNotEmpty())
    }

    /** Growth is by doubling, so a request is met in one go rather than one element at a time. */
    @Test
    fun `capacity grows to hold what was asked for`() {
        val layer = StarLayer(initialCapacity = 4)
        layer.ensure(70)
        assertTrue("asked for 70, got ${layer.vx.size}", layer.vx.size >= 70)
        assertEquals("every array grows together", layer.vx.size, layer.magnitude.size)
        assertEquals("every array grows together", layer.vx.size, layer.colourBand.size)
    }

    /**
     * ⚠️ Clearing must not shrink the arrays. The whole point of the layer is that a reload writes
     * into buffers already the right size; releasing them would put an allocation of tens of
     * thousands of doubles on the path of every catalogue reload.
     */
    @Test
    fun `clearing empties the count and keeps the room`() {
        val layer = StarLayer(initialCapacity = 4)
        layer.ensure(100)
        val room = layer.vx.size
        layer.add(12.0, 34.0, 2f, 1)
        layer.clear()
        assertEquals(0, layer.count)
        assertEquals(room, layer.vx.size)
    }
}
