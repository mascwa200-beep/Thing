package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

/**
 * What has to hold about the star map's device budget.
 *
 * ⚠️ Every expected value below is computed from the exponential-filter relation the file documents,
 * or taken from the constant it is meant to equal — not typed from memory. The one place I wrote a
 * number by hand in this feature, I had the comparison inverted; see [SkyBudget.Budget].
 */
class SkyBudgetTest {

    /**
     * ⚠️ **The guarantee the whole file rests on.** If this fails, a change intended for a weak
     * phone has reached a good one, and every claim that this is safe to ship without hardware to
     * test it on stops being true.
     */
    @Test
    fun `a full-strength device gets exactly what the map did before this file existed`() {
        val b = SkyBudget.forTier(DeviceClass.Tier.FULL)
        assertEquals(20_000, b.sensorPeriodUs)
        assertEquals(0.25, b.pointSmoothing, 1e-12)
        assertEquals(56, b.milkyWaySamples)
        assertEquals(DeepSky.SHAPE_MIN_PX, b.deepSkyShapePx, 1e-12)
    }

    /**
     * The rule the smoothing exists for: the same lag in real time at any rate.
     *
     * Measured as the residual error after one time constant, which must be `1/e` whatever the
     * sampling period — that is the definition of a time constant, and it is the property that
     * carrying a fixed weight across rates would break.
     */
    @Test
    fun `the lag is the same length of time at every sampling rate`() {
        val tau = SkyBudget.TIME_CONSTANT_US
        for (periodUs in intArrayOf(20_000, 33_333, 66_667, 125_000, 200_000)) {
            val w = SkyBudget.smoothingFor(periodUs)
            // Error left after tau microseconds = (1 - w) ^ (tau / period).
            val remaining = Math.pow(1.0 - w, tau / periodUs)
            assertEquals("period $periodUs", 1.0 / Math.E, remaining, 1e-9)
        }
    }

    /**
     * ⚠️ The specific failure this replaced: a weight is per SAMPLE, so reusing the reference weight
     * at a slower rate multiplies the lag. Pinned as an inequality rather than a value, because what
     * matters is the direction and that it is substantial.
     */
    @Test
    fun `a slower rate takes more of each sample, or the map would trail the hand`() {
        val fast = SkyBudget.smoothingFor(20_000)
        val mid = SkyBudget.smoothingFor(66_667)
        val slow = SkyBudget.smoothingFor(125_000)
        assertTrue("$fast < $mid", fast < mid)
        assertTrue("$mid < $slow", mid < slow)
        // Had the weight been carried over unchanged, the lag at 125 ms would be 6.25x the
        // reference; holding the time constant instead means the weight more than trebles.
        assertTrue("$slow", slow > 3.0 * fast)
    }

    /** A weight is a fraction of one reading, and taking a whole reading is the slow-rate limit. */
    @Test
    fun `the weight never leaves the range a blend can use`() {
        for (periodUs in intArrayOf(-1, 0, 1, 20_000, 1_000_000, Int.MAX_VALUE)) {
            val w = SkyBudget.smoothingFor(periodUs)
            assertTrue("period $periodUs gave $w", w >= SkyBudget.FULL_SMOOTHING && w <= 1.0)
        }
    }

    /**
     * Every lever moves in the direction that costs less as the device gets weaker.
     *
     * ⚠️ This is the guard against the mistake actually made while writing the file: the deep-sky
     * threshold is a FLOOR on apparent size, so a smaller number draws MORE shapes. Written from the
     * wrong end it would have loaded the weakest phones with the most work, and nothing else here
     * would have noticed.
     */
    @Test
    fun `the ladder only ever asks for less`() {
        val order = listOf(
            DeviceClass.Tier.FULL,
            DeviceClass.Tier.MODEST,
            DeviceClass.Tier.LEAN,
            DeviceClass.Tier.MINIMAL,
        ).map { SkyBudget.forTier(it) }
        for (i in 1 until order.size) {
            val prev = order[i - 1]
            val next = order[i]
            assertTrue("sensor period must not shorten", next.sensorPeriodUs >= prev.sensorPeriodUs)
            assertTrue("glow samples must not rise", next.milkyWaySamples <= prev.milkyWaySamples)
            assertTrue("shape floor must not drop", next.deepSkyShapePx >= prev.deepSkyShapePx)
        }
    }

    /**
     * ⚠️ Below the glow's own floor the bilinear upscale creases on a strong gradient, so the cheaper
     * setting would be the worse picture rather than the same picture faster. And no period may land
     * on 0..3, which `SensorManager` reads as a named rate rather than as microseconds.
     */
    @Test
    fun `no tier asks for something the renderer or the platform cannot honour`() {
        for (tier in DeviceClass.Tier.entries) {
            val b = SkyBudget.forTier(tier)
            assertTrue("$tier samples ${b.milkyWaySamples}", b.milkyWaySamples >= 16)
            assertTrue("$tier samples ${b.milkyWaySamples}", b.milkyWaySamples <= SkyBudget.FULL_MILKY_WAY_SAMPLES)
            assertTrue("$tier period ${b.sensorPeriodUs}", b.sensorPeriodUs > 3)
        }
    }

    /** The reference constant is derived from the two it is defined by, so it cannot drift. */
    @Test
    fun `the time constant is the one the reference rate and weight imply`() {
        val expected = -20_000.0 / ln(1.0 - 0.25)
        assertEquals(expected, SkyBudget.TIME_CONSTANT_US, 1e-9)
        // ~69.5 ms, quoted in the file's own prose.
        assertEquals(69_521.2, SkyBudget.TIME_CONSTANT_US, 0.5)
        // And the inverse really does return the weight it was built from.
        assertEquals(0.25, 1.0 - exp(-20_000.0 / expected), 1e-12)
    }
}
