package dev.mascwa.pulse.scan

import dev.mascwa.pulse.core.telemetry.BarcodeScan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which of the several ways a scan can be going the person is actually in.
 *
 * ⚠️ **The old scanner said "Line the barcode up in the frame" for all of them**, including a camera
 * that never opened and a room too dark to read anything in. It is the difference between a scanner
 * that seems broken and one that is telling you what to do, and it is a `when` over five booleans —
 * so it is the kind of thing that is right until somebody adds a sixth and reorders it by accident.
 */
class ScanHintTest {

    private fun state(
        candidate: String = "",
        seen: Int = 0,
        running: Boolean = true,
        failure: String? = null,
        dark: Boolean = false,
        quietMs: Long = 0,
    ) = ScanState(
        progress = BarcodeScan.Progress(candidate, seen),
        running = running,
        failure = failure,
        tooDark = dark,
        quietMs = quietMs,
    )

    /**
     * ⚠️ **A scanner that has just succeeded must never be telling somebody to move closer.** The
     * confirmation and the teardown overlap: frames keep arriving for a moment after the code is
     * handed over, and every one of them carries whatever the light and the timers say.
     */
    @Test
    fun aConfirmedCodeOutranksEverythingElseAtOnce() {
        val confirmed = state(
            candidate = "3017624010701", seen = BarcodeScan.CONFIRMATIONS,
            running = false, failure = "the camera fell over", dark = true, quietMs = 60_000,
        )
        assertEquals(ScanHint.GOT_IT, confirmed.hint)
    }

    /**
     * ⚠️ **A camera that did not open outranks the rest**, because every other message would be a
     * statement about a viewfinder that is not running — "hold still" over a black rectangle.
     */
    @Test
    fun aBrokenCameraOutranksEveryMessageAboutTheFrame() {
        assertEquals(
            ScanHint.BROKEN,
            state(failure = "in use by another app", running = false, dark = true, quietMs = 99_000).hint,
        )
        // ...but not a confirmation, which is the case above.
        assertEquals(
            ScanHint.GOT_IT,
            state(candidate = "1", seen = BarcodeScan.CONFIRMATIONS, failure = "x").hint,
        )
    }

    /** Before the camera has bound there is nothing to say about the picture yet. */
    @Test
    fun beforeTheCameraIsRunningItIsSimplyLooking() {
        assertEquals(ScanHint.LOOKING, state(running = false).hint)
        assertEquals(ScanHint.LOOKING, state(running = false, dark = true, quietMs = 99_000).hint)
    }

    /**
     * ⚠️ **A code coming through outranks the light and the timer.** A dim frame that is nonetheless
     * decoding does not need the torch, and telling somebody to steady up while the counter is
     * climbing is telling them to change what is already working.
     */
    @Test
    fun aCodeComingThroughOutranksTheLightAndTheTimer() {
        assertEquals(
            ScanHint.READING,
            state(candidate = "3017624010701", seen = 1, dark = true, quietMs = 99_000).hint,
        )
    }

    /**
     * ⚠️ **Darkness outranks struggling because it is actionable and struggling is not.** "Too dark —
     * try the torch" is something a person can do; "still trying" is an admission.
     */
    @Test
    fun darknessOutranksMerelyStruggling() {
        assertEquals(ScanHint.TOO_DARK, state(dark = true, quietMs = 99_000).hint)
        assertEquals(ScanHint.STRUGGLING, state(dark = false, quietMs = 99_000).hint)
    }

    /**
     * The threshold, at the boundary. Below it the scanner says nothing, because a scanner that
     * starts apologising after a second is one somebody stops reading.
     */
    @Test
    fun strugglingOnlyAfterTheThresholdHasActuallyPassed() {
        assertEquals(ScanHint.LOOKING, state(quietMs = 0).hint)
        assertEquals(ScanHint.LOOKING, state(quietMs = ScanTuning.STRUGGLING_AFTER_MS - 1).hint)
        assertEquals(ScanHint.STRUGGLING, state(quietMs = ScanTuning.STRUGGLING_AFTER_MS).hint)
    }

    /** The ordinary case: bound, lit, nothing found yet, no reason to think anything is wrong. */
    @Test
    fun theOrdinaryCaseIsJustLooking() {
        assertEquals(ScanHint.LOOKING, ScanState(running = true).hint)
    }
}
