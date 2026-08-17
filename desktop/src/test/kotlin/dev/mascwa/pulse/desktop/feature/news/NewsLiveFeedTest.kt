package dev.mascwa.pulse.desktop.feature.news

import dev.mascwa.pulse.desktop.feature.news.NewsViewModel.Companion.LIVE_INTERVAL_MS
import dev.mascwa.pulse.desktop.feature.news.NewsViewModel.Companion.nextTickDelayMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the live feed next reaches for the network.
 *
 * The loop around this is a `delay` and a flow, which a unit test cannot usefully observe; the decision
 * it takes can be, and it is the part with edges worth pinning.
 */
class NewsLiveFeedTest {

    private val now = 1_700_000_000_000L

    /**
     * ⚠️ Nothing loaded yet means **wait**, not "refresh immediately".
     *
     * A zero timestamp read as "infinitely old" would fire a second request against the same feed
     * milliseconds after the opening fetch, every single time the screen is opened — the exact opposite
     * of the constraint this feature was built under.
     */
    @Test
    fun anEmptyScreenWaitsRatherThanRacingItsOwnOpeningFetch() {
        assertEquals(LIVE_INTERVAL_MS, nextTickDelayMs(lastUpdatedEpochMs = 0L, nowMs = now))
        assertEquals(LIVE_INTERVAL_MS, nextTickDelayMs(lastUpdatedEpochMs = -1L, nowMs = now))
    }

    /**
     * Arriving at a tab whose cached copy has already aged past an interval refreshes on arrival.
     *
     * Without this, switching to a tab last fetched nine minutes ago shows nine-minute-old news and
     * then waits a further five before doing anything about it.
     */
    @Test
    fun anAlreadyStaleCopyIsRefreshedAtOnce() {
        val nineMinutes = 9 * 60 * 1000L
        assertEquals(0L, nextTickDelayMs(now - nineMinutes, now))
        assertEquals(0L, nextTickDelayMs(now - 24 * 60 * 60 * 1000L, now))
        // Exactly due is due.
        assertEquals(0L, nextTickDelayMs(now - LIVE_INTERVAL_MS, now))
    }

    /** A fresh copy waits only the remainder, so the feed lands on a steady five-minute beat. */
    @Test
    fun aFreshCopyWaitsOutTheRemainderOfTheInterval() {
        val twoMinutes = 2 * 60 * 1000L
        assertEquals(LIVE_INTERVAL_MS - twoMinutes, nextTickDelayMs(now - twoMinutes, now))
        assertEquals(LIVE_INTERVAL_MS, nextTickDelayMs(now, now))
    }

    /**
     * A timestamp from the future — a clock correction, a copy restored from a machine set ahead —
     * must not produce a wait longer than the interval, or the feed silently stops until it catches up.
     */
    @Test
    fun aFutureTimestampCannotStallTheFeed() {
        val ahead = nextTickDelayMs(now + 60 * 60 * 1000L, now)
        assertTrue("waited $ahead ms", ahead in 0L..LIVE_INTERVAL_MS)
        assertEquals(LIVE_INTERVAL_MS, ahead)
    }

    /** Five minutes, as asked for — pinned so a stray edit to the constant is a failing test. */
    @Test
    fun theBeatIsFiveMinutes() {
        assertEquals(300_000L, LIVE_INTERVAL_MS)
    }
}
