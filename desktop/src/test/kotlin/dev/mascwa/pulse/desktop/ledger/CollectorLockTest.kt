package dev.mascwa.pulse.desktop.ledger

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class CollectorLockTest {

    @Before
    fun useOwnDirectory() {
        // ⚠️ Never the real path. A test taking the genuine collector lock would block a pass that
        // somebody's actual machine was in the middle of.
        CollectorLock.root = Files.createTempDirectory("collectorlock").toFile()
    }

    @After
    fun tidy() {
        CollectorLock.release()
        CollectorLock.root = null
    }

    @Test
    fun theLockCanBeTakenAndGivenBack() {
        assertTrue(CollectorLock.tryAcquire())
        CollectorLock.release()
        assertTrue("released means available again", CollectorLock.tryAcquire())
    }

    /**
     * ⚠️ THE PROPERTY THE WHOLE FILE EXISTS FOR. Two passes appending to one ledger is how a series
     * ends up with duplicate readings that quietly inflate every sample count — and an inflated count
     * raises the surprisal ceiling, so the scoring would start claiming a precision it does not have.
     */
    @Test
    fun aSecondPassIsRefusedWhileTheFirstHoldsIt() {
        assertTrue(CollectorLock.tryAcquire())
        assertTrue("re-entering must be refused, not silently allowed", !CollectorLock.tryAcquire())
    }

    @Test
    fun withLockRunsTheBlockAndAlwaysReleases() {
        val ran = CollectorLock.withLock { "did it" }
        assertEquals("did it", ran)
        assertTrue("the lock must not be left held", CollectorLock.tryAcquire())
    }

    @Test
    fun withLockReturnsNullRatherThanRunningTwice() {
        assertTrue(CollectorLock.tryAcquire())
        assertNull("nothing may run while another pass holds it", CollectorLock.withLock { "should not run" })
    }

    /** A throwing pass must not strand the lock, or nothing collects again until the process dies. */
    @Test
    fun aFailingPassStillReleasesTheLock() {
        try {
            CollectorLock.withLock { error("the feed exploded") }
        } catch (_: IllegalStateException) {
            // expected
        }
        assertTrue("a crash inside a pass must not wedge the collector", CollectorLock.tryAcquire())
    }
}

/** The one rule that decides how often this machine talks to other people's servers. */
class CollectorDuenessTest {

    private val minute = 60_000L
    private val now = 1_754_006_400_000L

    @Test
    fun neverRecordedIsAlwaysDue() {
        assertTrue(Collector.isDue(null, 15 * minute, now))
    }

    @Test
    fun aDomainReadAMomentAgoIsNotDue() {
        assertTrue(!Collector.isDue(now - minute, 15 * minute, now))
    }

    @Test
    fun aDomainPastItsCadenceIsDue() {
        assertTrue(Collector.isDue(now - 16 * minute, 15 * minute, now))
    }

    /**
     * ⚠️ Without slack a pass landing a hair before the boundary loses a whole cadence — and it
     * always does, because a task fired "every 15 minutes" drifts by seconds and the last write is
     * stamped when the fetch RETURNED. A half-hourly domain would quietly become hourly.
     */
    @Test
    fun arrivingSlightlyEarlyStillCounts() {
        assertTrue("30 seconds early must still collect", Collector.isDue(now - 29 * minute - 30_000L, 30 * minute, now))
        assertTrue("but five minutes early must not", !Collector.isDue(now - 25 * minute, 30 * minute, now))
    }

    /**
     * ⚠️ A reading stamped in the future means the clock moved backwards — a timezone change, a
     * daylight-saving shift, an NTP correction. Reading it as "done very recently" would stall that
     * domain until real time caught up, which can be hours.
     */
    @Test
    fun aReadingFromTheFutureDoesNotStallTheDomain() {
        assertTrue(Collector.isDue(now + 3 * 60 * minute, 15 * minute, now))
    }
}
