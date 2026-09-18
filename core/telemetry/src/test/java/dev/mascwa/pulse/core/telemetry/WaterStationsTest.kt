package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ Every distance below was computed from the SHIPPED [Geodesy.distanceMeters] (haversine on a
 * 6,371,000 m sphere) before the assertion was written, not estimated from a degrees-to-kilometres
 * rule of thumb. Stations sit due north of an observer at 0°,0°, so the offset in degrees is the
 * only variable:
 *
 * ```
 *   0.50°  ->   55.597 km    inside both reaches
 *   0.80°  ->   88.956 km    inside TIDE_REACH_KM (90.0) by a kilometre
 *   1.35°  ->  150.113 km    OUTSIDE tide, INSIDE level  <- the case the design turns on
 *   2.50°  ->  277.987 km    outside both
 * ```
 */
class WaterStationsTest {

    private fun tide(id: String, latOffset: Double, name: String = "Somewhere, ST") =
        WaterStations.Station(id, latOffset, 0.0, WaterStations.Kind.TIDE, name)

    private fun level(id: String, latOffset: Double, name: String = "Somewhere, ST") =
        WaterStations.Station(id, latOffset, 0.0, WaterStations.Kind.LEVEL, name)

    // ---- parse ---------------------------------------------------------------------------

    @Test
    fun `a well formed row becomes a station`() {
        val s = WaterStations.parse("9087031\t42.7733\t-86.2128\tW\tHolland, MI")
        assertNotNull(s)
        assertEquals("9087031", s!!.id)
        assertEquals(42.7733, s.lat, 1e-9)
        assertEquals(-86.2128, s.lon, 1e-9)
        assertEquals(WaterStations.Kind.LEVEL, s.kind)
        assertEquals("Holland, MI", s.name)
    }

    @Test
    fun `a tide row is a tide station`() {
        val s = WaterStations.parse("8594900\t38.8733\t-77.0217\tT\tWASHINGTON, D.C.")
        assertEquals(WaterStations.Kind.TIDE, s!!.kind)
    }

    @Test
    fun `a bad row costs its own station and not the file`() {
        // Each of these is a whole row the bundled list could plausibly carry after an upstream
        // change. None may throw: one malformed line must not take 3,562 good ones with it.
        assertNull(WaterStations.parse(""))
        assertNull(WaterStations.parse("9087031\t42.7733\t-86.2128\tW"))          // no name column
        assertNull(WaterStations.parse("9087031\tnorth\t-86.2128\tW\tHolland"))   // latitude not a number
        assertNull(WaterStations.parse("9087031\t42.7733\twest\tW\tHolland"))     // longitude not a number
        assertNull(WaterStations.parse("\t42.7733\t-86.2128\tW\tHolland"))        // no id
        assertNull(WaterStations.parse("9087031\t42.7733\t-86.2128\tW\t"))        // no name
        assertNull(WaterStations.parse("9087031\t42.7733\t-86.2128\tW\t   "))     // name is spaces
    }

    @Test
    fun `an unknown product code is refused rather than guessed at`() {
        // NOAA publishes more than these two products. A row for one we cannot render must be
        // dropped: defaulting it to TIDE would ask a station for predictions it does not have and
        // draw a permanently empty block.
        assertNull(WaterStations.parse("1234567\t42.0\t-86.0\tX\tSomewhere"))
        assertNull(WaterStations.parse("1234567\t42.0\t-86.0\t\tSomewhere"))
        assertNull(WaterStations.parse("1234567\t42.0\t-86.0\tt\tSomewhere"))     // case matters
    }

    // ---- nearest, and the per-kind reach ----------------------------------------------------

    @Test
    fun `the same distance is near enough for a lake and too far for a tide`() {
        // THE rule the whole design rests on. Both stations sit at 150.113 km. A shared reach
        // would either discard the lake gauge (which is telling the truth) or admit the tide
        // gauge (which is right about the height and wrong about the hour).
        val far = 1.35
        assertNull(WaterStations.nearest(listOf(tide("T1", far)), 0.0, 0.0))

        val lake = WaterStations.nearest(listOf(level("W1", far)), 0.0, 0.0)
        assertNotNull(lake)
        assertEquals("W1", lake!!.station.id)
        assertEquals(150.113, lake.km, 0.01)
    }

    @Test
    fun `a tide station just inside its reach is kept`() {
        // 88.956 km against a 90.0 km reach — the boundary, so a reach quietly halved or a
        // metres-for-kilometres slip shows up here rather than in the field.
        val near = WaterStations.nearest(listOf(tide("T1", 0.8)), 0.0, 0.0)
        assertNotNull(near)
        assertEquals(88.956, near!!.km, 0.01)
    }

    @Test
    fun `beyond every reach there is no station at all`() {
        // Absent is honest. A block reporting the tide 278 km away is not.
        val stations = listOf(tide("T1", 2.5), level("W1", 2.5))
        assertNull(WaterStations.nearest(stations, 0.0, 0.0))
    }

    @Test
    fun `the closest is chosen, not the first in range`() {
        val stations = listOf(level("W_far", 1.35), level("W_near", 0.5), level("W_mid", 0.8))
        val n = WaterStations.nearest(stations, 0.0, 0.0)
        assertEquals("W_near", n!!.station.id)
        assertEquals(55.597, n.km, 0.01)
    }

    @Test
    fun `a nearer station of the wrong kind does not hide a usable one`() {
        // A tide gauge at 150 km is unusable, so the lake gauge behind it at 150 km is the answer.
        // Picking the closest first and testing the reach afterwards would return nothing here.
        val stations = listOf(tide("T_nearer", 1.30), level("W_further", 1.35))
        val n = WaterStations.nearest(stations, 0.0, 0.0)
        assertEquals("W_further", n!!.station.id)
    }

    @Test
    fun `no stations and no fix both mean no reading`() {
        assertNull(WaterStations.nearest(emptyList(), 0.0, 0.0))
        assertNull(WaterStations.nearest(listOf(level("W1", 0.5)), Double.NaN, 0.0))
        assertNull(WaterStations.nearest(listOf(level("W1", 0.5)), 0.0, Double.NaN))
    }

    // ---- upcoming ------------------------------------------------------------------------

    private val turns = listOf(
        WaterStations.Turn("2026-08-31 03:12", high = false, feet = 0.4),
        WaterStations.Turn("2026-08-31 09:41", high = true, feet = 5.1),
        WaterStations.Turn("2026-08-31 15:58", high = false, feet = 0.9),
        WaterStations.Turn("2026-08-31 22:05", high = true, feet = 4.7),
        WaterStations.Turn("2026-09-01 04:20", high = false, feet = 0.2),
    )

    @Test
    fun `only what is still ahead`() {
        val next = WaterStations.upcoming(turns, "2026-08-31 10:00")
        assertEquals(listOf("2026-08-31 15:58", "2026-08-31 22:05"), next.map { it.at })
    }

    @Test
    fun `the day rolls over on its own`() {
        // Late in the evening the next turn is tomorrow's, and lexicographic order on a
        // fixed-width `yyyy-MM-dd HH:mm` string gets that right with no calendar involved.
        val next = WaterStations.upcoming(turns, "2026-08-31 23:00")
        assertEquals(listOf("2026-09-01 04:20"), next.map { it.at })
    }

    @Test
    fun `out of order input still comes back in order`() {
        val shuffled = listOf(turns[3], turns[0], turns[4], turns[2], turns[1])
        val next = WaterStations.upcoming(shuffled, "2026-08-31 00:00", max = 5)
        assertEquals(turns.map { it.at }, next.map { it.at })
    }

    @Test
    fun `past the last prediction there is nothing to say`() {
        assertTrue(WaterStations.upcoming(turns, "2026-09-02 00:00").isEmpty())
        assertNull(WaterStations.describeTides(turns, "2026-09-02 00:00"))
    }

    // ---- the lines -------------------------------------------------------------------------

    @Test
    fun `the tide line names the next two turns by the clock`() {
        assertEquals(
            "TIDE  LOW 15:58  ·  HIGH 22:05",
            WaterStations.describeTides(turns, "2026-08-31 10:00"),
        )
    }

    @Test
    fun `the lake line names the place and the datum`() {
        assertEquals(
            "WATER  Holland 579.6 FT IGLD",
            WaterStations.describeLevel("Holland, MI", 579.62, "IGLD"),
        )
    }

    @Test
    fun `a station name with no comma is used whole`() {
        assertEquals(
            "WATER  Duluth Superior Entry 601.9 FT IGLD",
            WaterStations.describeLevel("Duluth Superior Entry", 601.85, "IGLD"),
        )
    }

    @Test
    fun `an unreadable gauge draws nothing`() {
        assertNull(WaterStations.describeLevel("Holland, MI", Double.NaN, "IGLD"))
    }

    @Test
    fun `the clock is the time and only the time`() {
        assertEquals("15:58", WaterStations.clock("2026-08-31 15:58"))
        // Anything not of that shape comes back whole rather than being sliced into nonsense.
        assertEquals("15:58", WaterStations.clock("15:58"))
        assertEquals("", WaterStations.clock(""))
    }

    @Test
    fun `a negative height keeps its sign`() {
        // ⚠️ MLLW puts low water below the datum, so this is a real reading and not a
        // hypothetical. The obvious implementation takes the whole part with `toLong()`, which is
        // 0 for everything between -1 and 0, and prints a foot of water where there is none.
        assertEquals("-0.4", WaterStations.trim1(-0.42))
        assertEquals("-1.3", WaterStations.trim1(-1.28))
        assertEquals("0.4", WaterStations.trim1(0.42))
        assertEquals("579.6", WaterStations.trim1(579.62))
        assertEquals("0.0", WaterStations.trim1(0.0))
        // Rounds to nothing, so there is no sign to keep. "-0.0" is not a water level.
        assertEquals("0.0", WaterStations.trim1(-0.02))
    }

    @Test
    fun `a tie rounds away from zero, in both directions`() {
        // ⚠️ NOAA publishes two decimals, so one reading in ten lands exactly here. Under
        // `kotlin.math.round` (banker's) 601.85 gives 601.8 and 601.75 gives 601.8 as well — the
        // direction flipping with the parity of the digit before it, which this tree has already
        // corrected once for a displayed figure.
        assertEquals("601.9", WaterStations.trim1(601.85))
        assertEquals("601.8", WaterStations.trim1(601.75))
        // Symmetric, so a low water reads exactly as deep as the matching high water reads tall.
        assertEquals("-0.5", WaterStations.trim1(-0.45))
        assertEquals("0.5", WaterStations.trim1(0.45))
    }
}
