package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
