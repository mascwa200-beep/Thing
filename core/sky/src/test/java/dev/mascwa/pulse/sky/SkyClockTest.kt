package dev.mascwa.pulse.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyClockTest {

    @Test
    fun `the drawn instant is the wall clock plus the scrubber's offset, in either direction`() {
        assertEquals(1_790_128_800_000L, SkyClock.instantOf(1_790_128_800_000L, 0L))
        assertEquals(1_790_128_800_000L + 3_600_000L, SkyClock.instantOf(1_790_128_800_000L, 3_600_000L))
        assertEquals(1_790_128_800_000L - 6 * 3_600_000L, SkyClock.instantOf(1_790_128_800_000L, -6 * 3_600_000L))
    }

    /**
     * The sidereal rate is 15.041 arcseconds a second; the drift a tick permits is that times the
     * tick. The bar is one degree per minute — a tick that let the sky slip further than that
     * between redraws would be the frozen-clock defect in slow motion, and 500 ms is 7.5″.
     */
    @Test
    fun `one tick lets the sky move well under a pixel at any ordinary field`() {
        val arcsecPerTick = 15.041 * SkyClock.TICK_MS / 1000.0
        assertTrue("a tick is $arcsecPerTick arcsec of sky", arcsecPerTick < 60.0)
        // At the widest field one screen unit is 75°, so 7.5″ is a fifth of a pixel on 1080 px.
        val pixelsAtWidest = arcsecPerTick / 3600.0 / 75.0 * 540.0
        assertTrue("$pixelsAtWidest px at the widest field", pixelsAtWidest < 1.0)
    }

    @Test
    fun `reload buckets are whole minutes and floor rather than truncate toward zero`() {
        assertEquals(0L, SkyClock.reloadBucket(0L))
        assertEquals(0L, SkyClock.reloadBucket(59_999L))
        assertEquals(1L, SkyClock.reloadBucket(60_000L))
        // A negative instant is not a case the map produces, but floorDiv is what makes the bucket
        // boundary land on the minute in both directions rather than one either side of zero.
        assertEquals(-1L, SkyClock.reloadBucket(-1L))
    }

    @Test
    fun `two instants half a second apart share a bucket unless a minute boundary falls between`() {
        val a = 1_790_128_800_000L
        assertEquals(SkyClock.reloadBucket(a), SkyClock.reloadBucket(a + SkyClock.TICK_MS))
        val boundary = (a / SkyClock.RELOAD_BUCKET_MS + 1) * SkyClock.RELOAD_BUCKET_MS
        assertEquals(SkyClock.reloadBucket(boundary - 1) + 1, SkyClock.reloadBucket(boundary))
    }
}
