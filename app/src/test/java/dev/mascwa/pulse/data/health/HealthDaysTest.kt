package dev.mascwa.pulse.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The day rules, against a named zone with real daylight-saving transitions rather than wherever the
 * test happens to run.
 *
 * ⚠️ Europe/London in 2026: the clocks go forward on **29 March**, making that day 23 hours long, and
 * back on **25 October**, making it 25. Both are asserted rather than assumed, because a test that
 * quietly ran over two ordinary 24-hour days would pass against the arithmetic this exists to replace.
 */
class HealthDaysTest {

    private val z: ZoneId = ZoneId.of("Europe/London")
    private val short = LocalDate.of(2026, 3, 29)   // 23 hours
    private val long = LocalDate.of(2026, 10, 25)   // 25 hours

    private fun at(d: LocalDate, h: Int = 0, m: Int = 0): Long =
        LocalDateTime.of(d, java.time.LocalTime.of(h, m)).atZone(z).toInstant().toEpochMilli()

    private fun start(d: LocalDate): Long = d.atStartOfDay(z).toInstant().toEpochMilli()

    private val DAY_MS = 86_400_000L

    @Test
    fun `the fixtures really are the short and long days`() {
        assertEquals("29 March 2026 is 23 hours", 23 * 3_600_000L, start(short.plusDays(1)) - start(short))
        assertEquals("25 October 2026 is 25 hours", 25 * 3_600_000L, start(long.plusDays(1)) - start(long))
    }

    // ------------------------------------------------------------------------------------- plus

    @Test
    fun `the next day is where the day really ends, not 24 hours later`() {
        // Short day: a fixed day OVERSHOOTS into the next one, and BodyStore's window DELETES.
        assertEquals(start(short.plusDays(1)), HealthDays.plus(start(short), 1, z))
        assertNotEquals(
            "a fixed day would reach into the next morning",
            start(short) + DAY_MS,
            HealthDays.plus(start(short), 1, z),
        )
        // Long day: a fixed day FALLS SHORT, leaving the last hour outside its own day.
        assertEquals(start(long.plusDays(1)), HealthDays.plus(start(long), 1, z))
        assertNotEquals(start(long) + DAY_MS, HealthDays.plus(start(long), 1, z))
    }

    @Test
    fun `a reading in the last hour of a long day is inside that day`() {
        // The exact reading a fixed 24-hour window would leave unreplaced, keeping two weigh-ins for
        // one day and double-weighting it in the trend.
        val late = at(long, 23, 30)
        assertTrue("a fixed window stops before it", late >= start(long) + DAY_MS)
        assertTrue("the real day still contains it", late < HealthDays.plus(start(long), 1, z))
    }

    @Test
    fun `a reading in the first hour after a short day is NOT inside it`() {
        val nextMorning = at(short.plusDays(1), 0, 30)
        assertTrue("a fixed window would have swallowed it", nextMorning < start(short) + DAY_MS)
        assertTrue("the real day has already ended", nextMorning >= HealthDays.plus(start(short), 1, z))
    }

    @Test
    fun `plus goes backwards too`() {
        assertEquals(start(short.minusDays(3)), HealthDays.plus(start(short), -3, z))
    }

    // ----------------------------------------------------------------------------------- daysAgo

    @Test
    fun `yesterday evening is yesterday at nine the next morning`() {
        val d = LocalDate.of(2026, 6, 10)
        val lastNight = at(d.minusDays(1), 20, 0)
        val now = at(d, 9, 0)
        // Thirteen hours: the elapsed rule this replaced divided that by a day and got zero.
        assertEquals(13 * 3_600_000L, now - lastNight)
        assertEquals(0, ((now - lastNight) / DAY_MS).toInt())
        assertEquals(1, HealthDays.daysAgo(lastNight, now, z))
    }

    @Test
    fun `a minute ago is today and a minute from now is not tomorrow`() {
        val now = at(LocalDate.of(2026, 6, 10), 9, 0)
        assertEquals(0, HealthDays.daysAgo(now - 60_000L, now, z))
        assertEquals("never negative", 0, HealthDays.daysAgo(now + 60_000L, now, z))
    }

    @Test
    fun `daysAgo counts calendar days across a short one`() {
        // 28 -> 30 March is two calendar days but only 47 hours.
        val then = at(short.minusDays(1), 12, 0)
        val now = at(short.plusDays(1), 12, 0)
        assertEquals(47 * 3_600_000L, now - then)
        assertEquals(2, HealthDays.daysAgo(then, now, z))
    }

    // -------------------------------------------------------------------------------------- grid

    @Test
    fun `a week's grid lands on real day starts across a transition`() {
        val today = short.plusDays(3)
        val g = HealthDays.grid(start(today), 7, z)
        assertEquals(7, g.size)
        assertEquals("oldest first", start(today.minusDays(6)), g.first())
        assertEquals("today last", start(today), g.last())
        for (i in 0 until 7) {
            assertEquals("slot $i", start(today.minusDays((6 - i).toLong())), g[i])
        }
    }

    @Test
    fun `the stride this replaces misses four of seven`() {
        // The measured symptom: four bars of a weekly chart vanish for a week after a transition,
        // each reading as a day nobody logged.
        //
        // ⚠️ The stride is anchored where the shipped expression anchors it — on TODAY, since
        // `windowStartMs` is `todayStartMs - (window - 1) * DAY_MS` and the chart then walks forward
        // from there. My first version of this anchored on the oldest real day start instead, which is
        // a different (also broken) expression and misses a different three.
        val today = short.plusDays(3)
        val g = HealthDays.grid(start(today), 7, z)
        val windowStart = start(today) - 6 * DAY_MS
        val stride = (0 until 7).map { windowStart + it * DAY_MS }
        assertEquals("the four days before the transition all miss", 4, (0 until 7).count { g[it] != stride[it] })
        assertEquals("the days after it line up", 3, (0 until 7).count { g[it] == stride[it] })
    }

    @Test
    fun `a grid of one is just that day, and zero is not allowed to be empty`() {
        assertEquals(listOf(start(short)), HealthDays.grid(start(short), 1, z))
        assertEquals(listOf(start(short)), HealthDays.grid(start(short), 0, z))
    }

    @Test
    fun `startOf collapses any moment in a day onto its start`() {
        assertEquals(start(long), HealthDays.startOf(at(long, 23, 59), z))
        assertEquals(start(long), HealthDays.startOf(start(long), z))
    }
}
