package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expected phrases are taken from [TemporalReasoner.describeElapsed]'s own bands, not from memory —
 * 3 min falls in "under 45 minutes" so it reads "3 minutes ago", and 3 h falls in "under 22 hours" so
 * it reads "3 hours ago".
 */
class FreshnessTest {

    private val now = 1_700_000_000_000L
    private fun minutes(n: Long) = n * 60_000L
    private fun hours(n: Long) = n * 3_600_000L

    @Test
    fun aCleanLiveFetchSaysNothing() {
        val r = Freshness.assess(
            lastUpdatedMs = now, nowMs = now, online = true, servingStored = false, refreshFailed = false,
        )
        assertEquals(Freshness.State.LIVE, r.state)
        assertEquals("", r.label)
        assertFalse(r.worthShowing)
    }

    /** The whole point of the grace period: a blip next to data seconds old is not worth a notice. */
    @Test
    fun aFailureMomentsAfterAGoodFetchIsNotWorthMentioning() {
        val r = Freshness.assess(
            lastUpdatedMs = now - 30_000L, nowMs = now,
            online = true, servingStored = true, refreshFailed = true,
        )
        assertEquals(Freshness.State.LIVE, r.state)
        assertFalse(r.worthShowing)
    }

    @Test
    fun pastTheGracePeriodAFailedRefreshIsReported() {
        val r = Freshness.assess(
            lastUpdatedMs = now - minutes(3), nowMs = now,
            online = true, servingStored = true, refreshFailed = true,
        )
        assertEquals(Freshness.State.FAILED, r.state)
        assertTrue(r.label, r.label.contains("Couldn't update"))
        assertTrue(r.label, r.label.contains("3 minutes ago"))
    }

    @Test
    fun offlineIsReportedAsOfflineWithTheAge() {
        val r = Freshness.assess(
            lastUpdatedMs = now - hours(3), nowMs = now,
            online = false, servingStored = true, refreshFailed = false,
        )
        assertEquals(Freshness.State.OFFLINE, r.state)
        assertTrue(r.label, r.label.contains("Offline"))
        assertTrue(r.label, r.label.contains("3 hours ago"))
    }

    /**
     * With no connection the refresh has obviously failed. Reporting that instead of the disconnection
     * would name the symptom and hide the cause.
     */
    @Test
    fun whenBothAreTrueTheDisconnectionIsTheExplanationGiven() {
        val r = Freshness.assess(
            lastUpdatedMs = now - hours(3), nowMs = now,
            online = false, servingStored = true, refreshFailed = true,
        )
        assertEquals(Freshness.State.OFFLINE, r.state)
    }

    /** A source serving its own old cache, online, with nothing thrown — real staleness, no blame. */
    @Test
    fun onlineWithNoErrorButGenuinelyOldDataIsStillReported() {
        val r = Freshness.assess(
            lastUpdatedMs = now - hours(5), nowMs = now,
            online = true, servingStored = true, refreshFailed = false,
        )
        assertEquals(Freshness.State.STORED, r.state)
        assertTrue(r.label, r.label.contains("5 hours ago"))
    }

    @Test
    fun anUnrecordedTimestampIsSaidToBeUnknownRatherThanGuessed() {
        val r = Freshness.assess(
            lastUpdatedMs = 0L, nowMs = now, online = true, servingStored = true, refreshFailed = false,
        )
        assertEquals(Freshness.State.UNKNOWN, r.state)
        assertEquals(0L, r.ageMs)
        // Must not invent an age, and must not read as 1970.
        assertFalse(r.label, r.label.contains("ago"))
        assertFalse(r.label, r.label.contains("1970"))
    }

    /** A clock moved backwards must not produce a negative age that reads as the future. */
    @Test
    fun aTimestampFromTheFutureIsClampedRatherThanRenderedBackwards() {
        val r = Freshness.assess(
            lastUpdatedMs = now + hours(2), nowMs = now,
            online = false, servingStored = true, refreshFailed = false,
        )
        assertEquals(0L, r.ageMs)
        assertEquals(Freshness.State.LIVE, r.state)
    }

    @Test
    fun theLabelIsEmptyExactlyWhenThereIsNothingToReport() {
        val cases = listOf(
            Freshness.assess(now, now, true, servingStored = false, refreshFailed = false),
            Freshness.assess(now - hours(3), now, false, servingStored = true, refreshFailed = false),
            Freshness.assess(now - hours(3), now, true, servingStored = true, refreshFailed = true),
            Freshness.assess(0L, now, true, servingStored = true, refreshFailed = false),
        )
        cases.forEach { r ->
            assertEquals("state ${r.state}", r.state == Freshness.State.LIVE, r.label.isEmpty())
            assertEquals("state ${r.state}", r.state != Freshness.State.LIVE, r.worthShowing)
        }
    }

    /** A screen is only as current as its stalest source. */
    @Test
    fun theOldestSourceIsTheOneReported() {
        assertEquals(now - hours(9), Freshness.oldestOf(now - hours(1), now - hours(9), now))
    }

    /**
     * An unrecorded timestamp is 0, and treating that as an epoch date would make any mixed screen
     * claim to be decades stale.
     */
    @Test
    fun anUnrecordedSourceDoesNotDragTheWholeScreenBackToNineteenSeventy() {
        assertEquals(now - hours(2), Freshness.oldestOf(0L, now - hours(2), now))
        assertEquals(0L, Freshness.oldestOf(0L, 0L))
        assertEquals(0L, Freshness.oldestOf())
    }

    /** A grace period the caller widens must actually be honoured. */
    @Test
    fun theGracePeriodIsTheCallersToSet() {
        val args = { g: Long ->
            Freshness.assess(
                lastUpdatedMs = now - minutes(10), nowMs = now,
                online = false, servingStored = true, refreshFailed = false, graceMs = g,
            ).state
        }
        assertEquals(Freshness.State.OFFLINE, args(Freshness.GRACE_MS))
        assertEquals(Freshness.State.LIVE, args(minutes(30)))
    }
}
