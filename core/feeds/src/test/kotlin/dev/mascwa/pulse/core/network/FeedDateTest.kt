package dev.mascwa.pulse.core.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * ⚠️ Every expected value here was computed from a real ISO-8601 string rather than written from
 * memory, and the arithmetic is on the line beside it. Two assertions in a sibling date test turned
 * out to be inventions of mine that happened to look plausible, so the epoch numbers are derived by
 * [at] from a string a reader can check.
 */
class FeedDateTest {

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    // ── the ordinary RSS case: RFC 822 ─────────────────────────────────────────────────────────

    @Test
    fun `an RFC 822 stamp with a zone name reads to the instant it names`() {
        assertEquals(at("2026-08-28T14:30:00Z"), FeedDate.parse("Fri, 28 Aug 2026 14:30:00 GMT"))
        assertEquals(at("2026-08-28T14:30:00Z"), FeedDate.parse("Fri, 28 Aug 2026 14:30:00 UTC"))
    }

    @Test
    fun `a numeric offset is applied in the right direction`() {
        // 14:30 at +01:00 is 13:30 UTC — an hour EARLIER, which is the direction that is easy to invert.
        assertEquals(at("2026-08-28T13:30:00Z"), FeedDate.parse("Fri, 28 Aug 2026 14:30:00 +0100"))
        assertEquals(at("2026-08-28T19:30:00Z"), FeedDate.parse("Fri, 28 Aug 2026 14:30:00 -0500"))
    }

    @Test
    fun `seconds are optional, as RFC 822 makes them`() {
        assertEquals(at("2026-08-28T14:30:00Z"), FeedDate.parse("Fri, 28 Aug 2026 14:30 GMT"))
    }

    @Test
    fun `the obsolete US zone names are the fixed offsets the standard defines`() {
        // ⚠️ THE POINT OF THE TABLE. EST is -05:00 by definition; a library that resolves the name to
        // America/New_York answers -04:00 in August, an hour out, on a string whose meaning is fixed.
        assertEquals(at("2026-08-28T19:30:00Z"), FeedDate.parse("Fri, 28 Aug 2026 14:30:00 EST"))
        assertEquals(at("2026-08-28T18:30:00Z"), FeedDate.parse("Fri, 28 Aug 2026 14:30:00 EDT"))
        assertEquals(at("2026-08-28T22:30:00Z"), FeedDate.parse("Fri, 28 Aug 2026 14:30:00 PST"))
        assertEquals(at("2026-08-28T21:30:00Z"), FeedDate.parse("Fri, 28 Aug 2026 14:30:00 PDT"))
    }

    @Test
    fun `the weekday is dropped rather than checked against the date`() {
        // 8 August 2026 is a Saturday. A feed that writes "Fri" is wrong about a fact the date
        // already settles, and refusing the whole stamp over it would lose a real article — which is
        // what a strict RFC-1123 parse does. Both spellings, and none at all, read the same.
        val expected = at("2026-08-08T14:30:00Z")
        assertEquals(expected, FeedDate.parse("Sat, 8 Aug 2026 14:30:00 GMT"))
        assertEquals(expected, FeedDate.parse("Fri, 8 Aug 2026 14:30:00 GMT"))
        assertEquals(expected, FeedDate.parse("Saturday, 8 Aug 2026 14:30:00 GMT"))
        assertEquals(expected, FeedDate.parse("8 Aug 2026 14:30:00 GMT"))
    }

    // ── ISO 8601 / Atom ───────────────────────────────────────────────────────────────────────

    @Test
    fun `an ISO stamp reads to the instant it names`() {
        assertEquals(at("2026-08-28T14:30:00Z"), FeedDate.parse("2026-08-28T14:30:00Z"))
        assertEquals(at("2026-08-28T13:30:00Z"), FeedDate.parse("2026-08-28T14:30:00+01:00"))
    }

    @Test
    fun `fractional seconds beyond three digits are a fraction, not more milliseconds`() {
        // ⚠️ THE DEFECT THIS CORE EXISTS FOR, and it is the sharpest one measured.
        //   .123456    read as 123,456 ms = 2m 03.456s  -> the story was stamped into its own future
        //   .123456789 read as 123,456,789 ms = 1d 10h  -> two days away
        // RFC 3339 permits any number of fractional digits and Atom feeds emit six routinely.
        val expected = at("2026-08-28T14:30:00.123Z")
        assertEquals(expected, FeedDate.parse("2026-08-28T14:30:00.123Z"))
        assertEquals(expected, FeedDate.parse("2026-08-28T14:30:00.123456Z"))
        assertEquals(expected, FeedDate.parse("2026-08-28T14:30:00.123456789Z"))
        assertEquals(at("2026-08-28T13:30:00.123Z"), FeedDate.parse("2026-08-28T14:30:00.123456+01:00"))
    }

    @Test
    fun `a stamp with no offset keeps its time of day`() {
        // ⚠️ The second defect. This matched no offset pattern, fell through to the date-only one and
        // became midnight — 14.5 hours out, on the number articles are sorted by. Reading it as UTC
        // is a stated assumption; losing the time of day was not an assumption, it was a loss.
        assertEquals(at("2026-08-28T14:30:00Z"), FeedDate.parse("2026-08-28T14:30:00"))
    }

    @Test
    fun `a date with no time at all is midnight UTC`() {
        assertEquals(at("2026-08-28T00:00:00Z"), FeedDate.parse("2026-08-28"))
    }

    // ── refusal ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `something that is not a date is zero rather than a plausible time`() {
        // Zero is what every call site already reads as "unknown", sorting the item last. It must
        // never be a guess: an invented time would order a story among real ones.
        assertEquals(0L, FeedDate.parse("not a date at all"))
        assertEquals(0L, FeedDate.parse(""))
        assertEquals(0L, FeedDate.parse("   "))
        assertEquals(0L, FeedDate.parse("x"))
        assertEquals(0L, FeedDate.parse("2026-99-99T99:99:99Z"))
    }

    @Test
    fun `surrounding whitespace is not a reason to refuse`() {
        assertEquals(at("2026-08-28T14:30:00Z"), FeedDate.parse("  2026-08-28T14:30:00Z\n"))
        assertEquals(at("2026-08-28T14:30:00Z"), FeedDate.parse("\tFri, 28 Aug 2026 14:30:00 GMT  "))
    }

    @Test
    fun `the shape test cannot read past the end of a short string`() {
        // parse() indexes character 4 to decide which family a string belongs to, so anything shorter
        // than that has to be refused before the test rather than by it.
        for (n in 0..12) assertEquals(0L, FeedDate.parse("2".repeat(n)))
    }
}
