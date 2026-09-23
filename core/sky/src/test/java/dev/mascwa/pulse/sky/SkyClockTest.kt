package dev.mascwa.pulse.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * The time control's arithmetic: its bounds, its labels and its one text parser.
 *
 * Every expected instant below was computed with `python3 -c` over `datetime` in the named zone
 * before the assertion was written, and is stated in the comment beside it.
 */
class SkyClockTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val london = TimeZone.getTimeZone("Europe/London")
    private val auckland = TimeZone.getTimeZone("Pacific/Auckland")

    /** 2026-09-23T22:15:00Z. */
    private val now = 1_790_201_700_000L

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

    @Test
    fun `the bounds are the first moment of 1900 and the last of 2100`() {
        // datetime(1900,1,1,tzinfo=utc).timestamp() = -2208988800
        assertEquals(-2_208_988_800_000L, SkyClock.MIN_INSTANT_MS)
        // datetime(2100,12,31,23,59,59,999,tzinfo=utc) → 4133980799.999
        assertEquals(4_133_980_799_999L, SkyClock.MAX_INSTANT_MS)
    }

    @Test
    fun `an offset inside the span is untouched and one past either end lands exactly on it`() {
        assertEquals(3_600_000L, SkyClock.boundedOffset(now, 3_600_000L))
        assertEquals(0L, SkyClock.boundedOffset(now, 0L))
        // A century forward from 2026 is past the end; the map parks on the last instant.
        val far = 200L * 365 * 86_400_000L
        assertEquals(SkyClock.MAX_INSTANT_MS, now + SkyClock.boundedOffset(now, far))
        assertEquals(SkyClock.MIN_INSTANT_MS, now + SkyClock.boundedOffset(now, -far))
    }

    @Test
    fun `the frame bucket changes at a day boundary and nowhere inside one`() {
        val dayStart = 1_790_208_000_000L // 2026-09-24T00:00Z
        assertEquals(SkyClock.frameBucket(dayStart - 1), SkyClock.frameBucket(dayStart - 86_000_000L))
        assertEquals(SkyClock.frameBucket(dayStart) - 1, SkyClock.frameBucket(dayStart - 1))
        // ⚠️ Before 1970 the instant is NEGATIVE, and 1900–1969 is inside the span this map draws.
        // A truncating division puts the millisecond before a pre-1970 midnight in the SAME bucket
        // as the midnight, so the frame would rebuild a day late for seventy years of the range.
        // 1950-01-01T00:00Z = −631152000 s (twenty years, five of them leap, of 86400 s);
        // python: -631152000000 // 86400000 = -7305, one less for the millisecond before.
        val midCentury = -631_152_000_000L
        assertEquals(-7305L, SkyClock.frameBucket(midCentury))
        assertEquals(-7306L, SkyClock.frameBucket(midCentury - 1))
    }

    @Test
    fun `the local day start is the calendar's, not a UTC multiple`() {
        // 2026-09-23T22:15Z is 24 Sep 10:15 in Auckland (UTC+12 before their DST starts 27 Sep);
        // the local day started at 2026-09-23T12:00Z = 1790164800.
        assertEquals(1_790_164_800_000L, SkyClock.localDayStart(now, auckland))
        // London is on BST (UTC+1): 23 Sep 23:15 local, day start 23 Sep 00:00 BST = 22 Sep 23:00Z = 1790118000.
        assertEquals(1_790_118_000_000L, SkyClock.localDayStart(now, london))
        assertEquals(1_790_121_600_000L, SkyClock.localDayStart(now, utc))
    }

    @Test
    fun `labels say the local wall clock and the offset in words`() {
        assertEquals("Wed 23 Sep 2026 · 22:15", SkyClock.formatLocal(now, utc))
        assertEquals("Wed 23 Sep 2026 · 23:15", SkyClock.formatLocal(now, london))
        assertEquals("Thu 24 Sep 2026 · 10:15", SkyClock.formatLocal(now, auckland))
        assertEquals("now", SkyClock.describeOffset(0L))
        assertEquals("+10m", SkyClock.describeOffset(600_000L))
        assertEquals("−1h", SkyClock.describeOffset(-3_600_000L))
        assertEquals("+1d 2h 5m", SkyClock.describeOffset(93_900_000L))
        assertEquals("−3d", SkyClock.describeOffset(-3L * 86_400_000L))
        assertEquals("+<1m", SkyClock.describeOffset(500L))
        assertEquals("now", SkyClock.whenLabel(now, 0L, utc))
        assertEquals(
            "Thu 24 Sep 2026 · 00:15 (+2h)",
            SkyClock.whenLabel(now + 7_200_000L, 7_200_000L, utc),
        )
    }

    @Test
    fun `a typed local date-time parses in the zone given, and nonsense is refused not rolled`() {
        // 2026-09-23 22:15 London (BST) = 21:15Z = 1790198100
        assertEquals(1_790_198_100_000L, SkyClock.parseLocal("2026-09-23 22:15", london))
        assertEquals(1_790_201_700_000L, SkyClock.parseLocal(" 2026-09-23T22:15 ", utc))
        // A date alone is midnight.
        assertEquals(1_790_121_600_000L, SkyClock.parseLocal("2026-09-23", utc))
        assertNull(SkyClock.parseLocal("2026-13-01 00:00", utc))
        assertNull(SkyClock.parseLocal("2026-02-30 00:00", utc))
        assertNull(SkyClock.parseLocal("1899-12-31 23:59", utc))
        assertNull(SkyClock.parseLocal("2101-01-01 00:00", utc))
        assertNull(SkyClock.parseLocal("tonight", utc))
        // The picker's own two halves round-trip.
        val fields = SkyClock.localFields(now, auckland)
        assertEquals(listOf(2026, 9, 24, 10, 15), fields.toList())
        assertEquals(now, SkyClock.localInstant(2026, 9, 24, 10, 15, auckland))
    }

    @Test
    fun `the entry form round-trips through the parser, and the picker speaks UTC midnight of the LOCAL date`() {
        assertEquals("2026-09-23 23:15", SkyClock.formatEntry(now, london))
        assertEquals("2026-09-24 10:15", SkyClock.formatEntry(now, auckland))
        for (zone in listOf(utc, london, auckland)) {
            assertEquals(zone.id, now, SkyClock.parseLocal(SkyClock.formatEntry(now, zone), zone))
        }
        // London is still on the 23rd at 23:15 and Auckland is already on the 24th, so the two
        // pickers must open a day apart. Both from python: datetime(2026,9,23,tzinfo=utc) =
        // 1790121600, and a day later 1790208000.
        assertEquals(1_790_121_600_000L, SkyClock.pickerDateOf(now, london))
        assertEquals(1_790_208_000_000L, SkyClock.pickerDateOf(now, auckland))
        assertEquals(listOf(2026, 9, 24), SkyClock.pickerFields(1_790_208_000_000L).toList())
        // The picker's date joined to a time is the instant the map then draws.
        val d = SkyClock.pickerFields(SkyClock.pickerDateOf(now, auckland))
        assertEquals(now, SkyClock.localInstant(d[0], d[1], d[2], 10, 15, auckland))
    }
}
