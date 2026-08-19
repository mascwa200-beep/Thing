package dev.mascwa.pulse.core.telemetry

import kotlin.math.sqrt

/** A heart-rate sample at a point in time. */
data class HrSample(val timestampMs: Long, val bpm: Int)

/**
 * Fired when heart-rate acceleration looks anomalous relative to recent variance.
 *
 * ⚠️ [motionChecked] is what lets the alert be honest. An alert that says "without movement" when
 * nothing ever looked at movement is a claim the device did not make — and that is exactly what
 * shipped: the one production caller omitted the exertion argument, its `0.0` default was
 * indistinguishable from a real reading of "standing still", and the notification asserted the
 * unchecked half anyway. False when no motion evidence reached this sample; the caller words the
 * alert accordingly rather than assuming.
 */
data class CheckInEvent(
    val bpm: Int,
    val accelBpmPerSec: Double,
    val sigma: Double,
    val timestampMs: Long,
    val motionChecked: Boolean = false,
)

/**
 * Pure, dependency-free vitals analyzer (unit-tested). It keeps a ring buffer of (t, hr),
 * estimates the HR derivative (bpm/sec) by finite difference — f'(t) ≈ [f(t+Δt) − f(t)] / Δt —
 * tracks a rolling mean/σ of that derivative, and raises a [CheckInEvent] when the latest
 * acceleration exceeds k·σ **and** the wearer is not moving. That gate decouples a genuine anomaly
 * from ordinary exertion (climbing stairs, running).
 *
 * ⚠️ **Both motion inputs are nullable, and that is the fix rather than a nicety.** They used to be
 * one non-null `stepsPerSec` defaulting to `0.0`, so "nobody told me" and "measured: standing
 * still" were the same value. The single production caller passed nothing, `0.0 > 0.5` is never
 * true, and the exertion gate could therefore never fire on a real device — green in CI the whole
 * time, because only the tests ever passed the argument. Null now means unknown, a number means
 * measured, and [CheckInEvent.motionChecked] carries the difference outward.
 *
 * Either input is enough. A step cadence is the better signal and needs ACTIVITY_RECOGNITION, which
 * this app does not hold; the smoothed accelerometer intensity needs no permission at all, so it is
 * what the service actually feeds.
 */
class VitalsAnalyzer(
    private val capacity: Int = 32,
    private val sigmaThreshold: Double = 3.0,
    private val minSamples: Int = 8,
    private val minBpmForCheck: Int = 90,
    private val stepRiseThreshold: Double = 0.5, // steps/sec above which motion = exertion
    // Smoothed |accelG − 1| above which the wearer is moving rather than being handled. The same
    // 0.09 the Sensorium settled on after the recorded "it thinks I'm moving while stationary"
    // fix — deviation from rest, never the raw ~1 g magnitude.
    private val movementThreshold: Double = Sensorium.MOVEMENT_THRESHOLD.toDouble(),
) {
    private val samples = ArrayDeque<HrSample>()
    private val derivatives = ArrayDeque<Double>()

    /**
     * Feed a HR sample, with whatever motion evidence the caller has. Returns a [CheckInEvent] when
     * this sample trips the anomaly criteria, else null.
     *
     * @param stepsPerSec step cadence, or null if no step source is available.
     * @param movement smoothed accelerometer intensity (|accelG − 1|), or null if not measured.
     */
    fun addSample(
        timestampMs: Long,
        bpm: Int,
        stepsPerSec: Double? = null,
        movement: Double? = null,
    ): CheckInEvent? {
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
            val motionChecked = stepsPerSec != null || movement != null
            // Either signal alone is enough to say "moving"; neither being present says nothing,
            // which is why an absent reading cannot suppress and cannot be reported as stillness.
            val exerting = (stepsPerSec ?: 0.0) > stepRiseThreshold ||
                (movement ?: 0.0) > movementThreshold
            if (sigma > 0.0 && bpm >= minBpmForCheck && !exerting &&
                (derivative - mean) > sigmaThreshold * sigma
            ) {
                CheckInEvent(bpm, derivative, sigma, timestampMs, motionChecked)
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
