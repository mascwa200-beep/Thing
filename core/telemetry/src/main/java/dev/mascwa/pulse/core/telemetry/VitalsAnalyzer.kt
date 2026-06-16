package dev.mascwa.pulse.core.telemetry

import kotlin.math.sqrt

/** A heart-rate sample at a point in time. */
data class HrSample(val timestampMs: Long, val bpm: Int)

/** Fired when heart-rate acceleration looks anomalous relative to recent variance. */
data class CheckInEvent(
    val bpm: Int,
    val accelBpmPerSec: Double,
    val sigma: Double,
    val timestampMs: Long,
)

/**
 * Pure, dependency-free vitals analyzer (unit-tested). It keeps a ring buffer of (t, hr),
 * estimates the HR derivative (bpm/sec) by finite difference — f'(t) ≈ [f(t+Δt) − f(t)] / Δt —
 * tracks a rolling mean/σ of that derivative, and raises a [CheckInEvent] when the latest
 * acceleration exceeds k·σ **and** step cadence is not rising. The cadence gate decouples a
 * genuine anomaly from ordinary exertion (climbing stairs, running).
 */
class VitalsAnalyzer(
    private val capacity: Int = 32,
    private val sigmaThreshold: Double = 3.0,
    private val minSamples: Int = 8,
    private val minBpmForCheck: Int = 90,
    private val stepRiseThreshold: Double = 0.5, // steps/sec above which motion = exertion
) {
    private val samples = ArrayDeque<HrSample>()
    private val derivatives = ArrayDeque<Double>()

    /**
     * Feed a HR sample (and optional step cadence in steps/sec). Returns a [CheckInEvent]
     * when this sample trips the anomaly criteria, else null.
     */
    fun addSample(timestampMs: Long, bpm: Int, stepsPerSec: Double = 0.0): CheckInEvent? {
        if (bpm <= 0) return null
        val previous = samples.lastOrNull()
        samples.addLast(HrSample(timestampMs, bpm))
        while (samples.size > capacity) samples.removeFirst()
        if (previous == null) return null

        val dtSeconds = (timestampMs - previous.timestampMs) / 1000.0
        if (dtSeconds <= 0.0) return null
        val derivative = (bpm - previous.bpm) / dtSeconds

        // Score this derivative against the distribution of PRIOR derivatives (fair z-score).
        val event = if (derivatives.size >= minSamples) {
            val mean = derivatives.average()
            val variance = derivatives.sumOf { (it - mean) * (it - mean) } / derivatives.size
            val sigma = sqrt(variance)
            val exerting = stepsPerSec > stepRiseThreshold
            if (sigma > 0.0 && bpm >= minBpmForCheck && !exerting &&
                (derivative - mean) > sigmaThreshold * sigma
            ) {
                CheckInEvent(bpm, derivative, sigma, timestampMs)
            } else {
                null
            }
        } else {
            null
        }

        derivatives.addLast(derivative)
        while (derivatives.size > capacity) derivatives.removeFirst()
        return event
    }

    fun reset() {
        samples.clear()
        derivatives.clear()
    }
}
