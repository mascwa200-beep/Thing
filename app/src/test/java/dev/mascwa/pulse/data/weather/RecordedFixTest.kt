package dev.mascwa.pulse.data.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedFixTest {

    @Test
    fun `a kept fix is rounded to about a kilometre`() {
        // Holland, Michigan, to five places: 42.78765, -86.10894.
        val fix = RecordedFix.of(42.78765, -86.10894, "Holland", NOW)
        assertEquals(42.79, fix.latitude, 1e-9)
        assertEquals(-86.11, fix.longitude, 1e-9)
    }

    @Test
    fun `a half rounds up, not to the even neighbour`() {
        // 0.125 × 100 is exactly 12.5 in binary. `Math.round` gives 13; `kotlin.math.round` is
        // `Math.rint`, banker's rounding, and gives 12 — a coordinate that moves with the parity of
        // the digit before it.
        assertEquals(0.13, RecordedFix.roundCoordinate(0.125), 1e-9)
    }

    @Test
    fun `a fix is usable for a day and no longer`() {
        assertTrue(fixAgo(0L).usableAt(NOW))
        assertTrue(fixAgo(RecordedFix.MAX_AGE_MS).usableAt(NOW))
        assertFalse(fixAgo(RecordedFix.MAX_AGE_MS + 1).usableAt(NOW))
    }

    @Test
    fun `a stamp a little in the future is believed, one far in the future is not`() {
        assertTrue(fixAgo(-RecordedFix.CLOCK_SLACK_MS).usableAt(NOW))
        assertFalse(fixAgo(-RecordedFix.CLOCK_SLACK_MS - 1).usableAt(NOW))
    }

    @Test
    fun `age is measured from when the fix was taken`() {
        assertEquals(3_600_000L, fixAgo(3_600_000L).ageMs(NOW))
    }

    private fun fixAgo(ms: Long) = RecordedFix(42.79, -86.11, "Holland", NOW - ms)

    private companion object {
        /** 2026-09-29T16:29:00Z — the moment of the screenshot this fixes. */
        const val NOW = 1_790_699_340_000L
    }
}
