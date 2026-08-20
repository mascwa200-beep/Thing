package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VitalsAnalyzerTest {

    /** Seed + 10 alternating samples → derivatives [1,0,1,0,…]: mean 0.5, sigma 0.5. */
    private fun feedBaseline(analyzer: VitalsAnalyzer, startBpm: Int) {
        analyzer.addSample(0L, startBpm)
        var t = 1000L
        var bpm = startBpm
        repeat(10) { i ->
            if (i % 2 == 0) bpm += 1
            analyzer.addSample(t, bpm)
            t += 1000
        }
    }

    @Test
    fun steadyBaselineRaisesNoCheckIn() {
        val analyzer = VitalsAnalyzer()
        analyzer.addSample(0L, 82)
        var t = 1000L
        var bpm = 82
        repeat(12) { i ->
            if (i % 2 == 0) bpm += 1
            assertNull(analyzer.addSample(t, bpm))
            t += 1000
        }
    }

    @Test
    fun hrSpikeWithoutMovementTriggersCheckIn() {
        val analyzer = VitalsAnalyzer()
        feedBaseline(analyzer, startBpm = 82)
        // Sharp jump to 120 bpm with no step cadence → anomaly.
        assertNotNull(analyzer.addSample(11_000L, 120, stepsPerSec = 0.0))
    }

    @Test
    fun hrSpikeDuringExertionIsSuppressed() {
        val analyzer = VitalsAnalyzer()
        feedBaseline(analyzer, startBpm = 82)
        // Same jump, but the user is moving (steps rising) → treated as exertion, not anomaly.
        assertNull(analyzer.addSample(11_000L, 120, stepsPerSec = 2.0))
    }

    @Test
    fun spikeBelowMinimumBpmIsIgnored() {
        val analyzer = VitalsAnalyzer()
        feedBaseline(analyzer, startBpm = 50)
        // Derivative is large but absolute HR (85) is below the check threshold (90).
        assertNull(analyzer.addSample(11_000L, 85, stepsPerSec = 0.0))
    }

    // ── the exertion gate, reached the way the device actually reaches it ────────────────────────
    //
    // ⚠️ The four tests above pass `stepsPerSec` explicitly, which no production caller ever did —
    // which is exactly why the gate could be green in CI and dead on the device. These reach it
    // through `movement`, the signal the service can actually supply, and pin the difference
    // between "measured: still" and "nobody looked".

    @Test
    fun hrSpikeWhileMovingIsSuppressedOnTheAccelerometerAlone() {
        val analyzer = VitalsAnalyzer()
        feedBaseline(analyzer, startBpm = 82)
        // 0.30 is comfortably above MOVEMENT_THRESHOLD (0.09) — walking, not handling.
        assertNull(analyzer.addSample(11_000L, 120, movement = 0.30))
    }

    @Test
    fun hrSpikeWhileStillStillTriggersOnTheAccelerometerAlone() {
        val analyzer = VitalsAnalyzer()
        feedBaseline(analyzer, startBpm = 82)
        // 0.01 is at rest. The same spike must still be raised, or the fix would have traded a
        // false alarm for a missed one.
        assertNotNull(analyzer.addSample(11_000L, 120, movement = 0.01))
    }

    @Test
    fun measuredStillnessIsReportedAsChecked() {
        val analyzer = VitalsAnalyzer()
        feedBaseline(analyzer, startBpm = 82)
        val event = analyzer.addSample(11_000L, 120, movement = 0.01)
        assertNotNull(event)
        assertTrue("movement was measured, so the alert may say so", event!!.motionChecked)
    }

    @Test
    fun anUnmeasuredSampleNeverClaimsStillness() {
        val analyzer = VitalsAnalyzer()
        feedBaseline(analyzer, startBpm = 82)
        // No motion argument at all — the exact shape of the shipped production call. The spike is
        // still raised (an absent sensor must not silence a real anomaly) but the event says the
        // stillness was never checked, so the notification cannot claim it.
        val event = analyzer.addSample(11_000L, 120)
        assertNotNull(event)
        assertFalse("nothing measured movement, so nothing may claim there was none", event!!.motionChecked)
    }

    @Test
    fun aMotionReadingIsNotSilentlyTreatedAsStillness() {
        // The root of the original defect: `stepsPerSec` was non-null with a `0.0` default, so
        // "nobody told me" and "measured: standing still" were the same value and the caller's
        // silence was read as a reading. Nullable now, and the two cases are distinguishable.
        val quiet = VitalsAnalyzer()
        feedBaseline(quiet, startBpm = 82)
        val unmeasured = quiet.addSample(11_000L, 120)

        val measured = VitalsAnalyzer()
        feedBaseline(measured, startBpm = 82)
        val still = measured.addSample(11_000L, 120, stepsPerSec = 0.0)

        assertNotNull(unmeasured)
        assertNotNull(still)
        assertEquals("same spike, same verdict", still!!.bpm, unmeasured!!.bpm)
        assertFalse(unmeasured.motionChecked)
        assertTrue(still.motionChecked)
    }
}
