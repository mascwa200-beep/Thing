package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassThrottleTest {

    private val hour = 3_600_000L
    private val base = 1_800_000_000_000L // an ordinary epoch, well clear of zero

    @Test
    fun `a pass that has never run is due`() {
        assertTrue(PassThrottle.due(lastMs = 0L, nowMs = base, floorMs = 6 * hour))
    }

    @Test
    fun `a negative stamp reads as never run rather than as the distant past`() {
        assertTrue(PassThrottle.due(lastMs = -1L, nowMs = base, floorMs = 6 * hour))
    }

    @Test
    fun `a stamp in the future is due rather than locking the pass out until the clock catches up`() {
        // The case this rule exists for: a phone whose clock was briefly four years fast stamped
        // 2030, and the clock has since been corrected. now - last is about -126 billion ms, so a
        // plain `>= floor` would switch the pass off for four years over a mistake already fixed.
        val fourYears = 4L * 365 * 24 * hour
        assertTrue(PassThrottle.due(lastMs = base + fourYears, nowMs = base, floorMs = 6 * hour))
    }

    @Test
    fun `exactly at the floor is due, and one millisecond short is not`() {
        val floor = 6 * hour // 21_600_000
        assertTrue(PassThrottle.due(lastMs = base, nowMs = base + floor, floorMs = floor))
        assertFalse(PassThrottle.due(lastMs = base, nowMs = base + floor - 1, floorMs = floor))
    }

    @Test
    fun `a floor of zero is always due, so a caller can disable the gate without a second path`() {
        assertTrue(PassThrottle.due(lastMs = base, nowMs = base, floorMs = 0L))
        assertTrue(PassThrottle.due(lastMs = base, nowMs = base + 1, floorMs = 0L))
    }

    @Test
    fun `a six hour floor turns ninety-six worker ticks a day into four runs`() {
        // This is what was actually wrong: the three stamps were written and read by nothing, so a
        // pass ran on every tick. The interval picker's lowest setting is 15 minutes, which is 96
        // ticks in 24 hours (96 * 900_000 == 86_400_000). Against a 6h floor only every 24th tick
        // clears it (21_600_000 / 900_000 == 24), so the runs land on ticks 0, 24, 48 and 72.
        val tick = 900_000L
        val floor = 6 * hour
        var last = 0L
        var runs = 0
        val ranOn = mutableListOf<Int>()
        for (i in 0 until 96) {
            val now = base + i * tick
            if (PassThrottle.due(last, now, floor)) {
                runs++
                ranOn += i
                last = now
            }
        }
        assertEquals(4, runs)
        assertEquals(listOf(0, 24, 48, 72), ranOn)
        // And the delta this fixes, stated rather than implied: ungated, all 96 ticks run.
        assertEquals(96, (0 until 96).count { PassThrottle.due(lastMs = 0L, nowMs = base + it * tick, floorMs = 0L) })
    }

    @Test
    fun `a day floor lets a pass run once a day even when the trigger fires every tick`() {
        // The ledger anchor's case: its real guard is that the chain head advanced, which happens on
        // every launch, so the floor is what bounds outbound calls to a free public service.
        val tick = 900_000L
        val floor = 20 * hour
        var last = 0L
        var runs = 0
        for (i in 0 until 96) { // one day of 15-minute ticks
            val now = base + i * tick
            if (PassThrottle.due(last, now, floor)) { runs++; last = now }
        }
        // 20h is 72_000_000ms; 72_000_000 / 900_000 == 80, so ticks 0 and 80 clear it inside one day.
        assertEquals(2, runs)
    }
}
