package dev.mascwa.pulse.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard for [NotifId].
 *
 * ⚠️ This exists because both of the things it checks had already gone wrong, silently, on a real
 * build:
 *
 *  - `SensoriumService` and `BreakingOverlayService` both used 7401. An id with no tag IS the
 *    identity of a notification, so two concurrently-running foreground services sharing one meant
 *    each replaced the other's, and whichever stopped first removed the survivor's — leaving a
 *    service holding the camera and microphone with no notification to show it or stop it.
 *  - `Notifier.sweepLegacyOnce` cancels everything outside a keep set. That set was hand-typed
 *    before the Sensorium and the emergency watch existed, so the first board post of every process
 *    cancelled both of their ongoing notifications.
 *
 * Neither is detectable by compiling, by reading one file, or by any runtime check short of watching
 * the tray on a device. They are detectable by comparing the numbers to each other, which is all this
 * does. Pure Kotlin — no Android types — so it runs in `:app:testDebugUnitTest` on CI.
 */
class NotifIdTest {

    /** Every id in the registry, paired with its name so a failure says which two clashed. */
    private val all: List<Pair<String, Int>> = listOf(
        "BRIEF" to NotifId.BRIEF,
        "TAKEOVER" to NotifId.TAKEOVER,
        "FGS_ACTIVE_MATRIX" to NotifId.FGS_ACTIVE_MATRIX,
        "FGS_VITALS" to NotifId.FGS_VITALS,
        "FGS_REMOTE_LINK" to NotifId.FGS_REMOTE_LINK,
        "FGS_SENSORIUM" to NotifId.FGS_SENSORIUM,
        "FGS_EMERGENCY_WATCH" to NotifId.FGS_EMERGENCY_WATCH,
        "FGS_BREAKING_OVERLAY" to NotifId.FGS_BREAKING_OVERLAY,
        "FGS_RADIO" to NotifId.FGS_RADIO,
    )

    @Test
    fun `no two notification ids are the same`() {
        val clashes = all.groupBy { it.second }.filterValues { it.size > 1 }
        assertTrue(
            "notification ids must be unique — clashing: " +
                clashes.map { (id, names) -> "$id used by ${names.map { it.first }}" },
            clashes.isEmpty(),
        )
    }

    /**
     * A foreground service's notification is mandatory for as long as it runs, so the sweep must
     * never cancel one. This is the assertion the hand-written keep set could not make about itself.
     */
    @Test
    fun `the sweep keeps every foreground service notification`() {
        val missing = NotifId.FOREGROUND - NotifId.PERSISTENT
        assertTrue("foreground ids absent from the sweep's keep set: $missing", missing.isEmpty())
    }

    /** The board and the takeover survive a sweep too — they are the two the user is meant to see. */
    @Test
    fun `the sweep keeps the board and the takeover`() {
        assertTrue(NotifId.BRIEF in NotifId.PERSISTENT)
        assertTrue(NotifId.TAKEOVER in NotifId.PERSISTENT)
    }

    /**
     * `PERSISTENT` is exactly the registry — nothing extra, nothing forgotten. Without this, adding a
     * constant and forgetting to list it would pass the two checks above whenever the new id happened
     * not to be a foreground one, which is how the keep set drifted in the first place.
     */
    @Test
    fun `every declared id is accounted for in the sweep's keep set`() {
        assertEquals(all.map { it.second }.toSet(), NotifId.PERSISTENT)
    }
}
