package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Learned normality — the piece that turns readings into judgment. For each (hour-of-day ×
 * weekday/weekend) bucket, EWMA baselines of the numeric environment (noise level, log-light, motion,
 * crowd) accumulate; [anomalies] then names what is UNUSUAL right now in plain English — "unusually
 * loud for 3am on a Tuesday" — instead of what is merely true. A cell refuses to judge until it has
 * seen enough samples ([MIN_SAMPLES]); a young baseline produces no anomalies rather than wrong ones.
 *
 * Pure and dependency-free: state is plain data the app-layer store serializes (core:telemetry
 * deliberately has no serialization dependency). Both the mean and the mean absolute deviation are
 * EWMAs, so "unusual" adapts to how variable YOUR environment actually is at that hour — a home that
 * is always noisy at 18:00 never flags dinner noise; the same level at 03:00 does.
 */

/** One bucket's learned normal: EWMA mean + EWMA mean-absolute-deviation per metric, plus sample count. */
data class BaselineCell(
    val noiseMean: Float = 0f, val noiseDev: Float = 0f,
    val lightMean: Float = 0f, val lightDev: Float = 0f,
    val motionMean: Float = 0f, val motionDev: Float = 0f,
    val crowdMean: Float = 0f, val crowdDev: Float = 0f,
    /**
     * How many observations this cell has absorbed.
     *
     * ⚠️ Counts EVERY observation, including ones that carried no light reading — so on a phone with
     * no ambient-light sensor a mature cell has a `lightMean` of exactly 0 that was never learned
     * from anything. That is safe because [SensoriumBaseline.anomalies] skips light entirely when
     * the current observation has none, and a phone with no sensor never has one. A separate
     * `lightSamples` was considered and left out on measurement: the light sensor is registered
     * for change, and once it has delivered once the last value is retained, so with a sensor
     * present the two counts differ by at most the handful of samples before the first event.
     */
    val samples: Int = 0,
)

/** The whole learned map — [cells] keyed by [SensoriumBaseline.bucket]. Plain data, app-layer persisted. */
data class BaselineState(val cells: Map<Int, BaselineCell> = emptyMap())

/** The numeric shadow of one [EnvReading], the thing baselines are learned over. */
data class EnvMetrics(
    /** [NoiseProfile] ordinal as a float (SILENT 0 … LOUD 4). */
    val noise: Float,
    /**
     * log10(lux + 1) — light varies over decades, so the log keeps the EWMA meaningful.
     *
     * ⚠️ **Null when nothing measured the light**, which on a phone with no ambient-light sensor is
     * every single sample. This used to be `lux ?: 0f`, so such a phone folded `log10(1) = 0` into
     * the learned normal forever and ended up with a confident baseline for a quantity it has no
     * instrument for. It could not fire a false anomaly — a constant leaves both the mean and the
     * deviation at zero and the absolute floor then swallows a zero delta — so this is a latent
     * fault rather than one you would have seen. It is still a learned normal built from nothing,
     * and the moment anything else reads `lightMean` it becomes visible.
     */
    val light: Float?,
    /** The movement EWMA itself. */
    val motion: Float,
    /** BT-device count (people density proxy). */
    val crowd: Float,
) {
    companion object {
        fun of(reading: EnvReading, frame: SenseFrame): EnvMetrics = EnvMetrics(
            noise = reading.noise.ordinal.toFloat(),
            light = frame.lightLux?.let { log10(it + 1f) },
            motion = frame.movement ?: 0f,
            crowd = (frame.btDeviceCount ?: 0).toFloat(),
        )
    }
}

/** One named deviation from the learned normal, ready for the scanner/ORACLE/Computer. */
data class EnvAnomaly(val metric: String, val text: String, val strength: Float)

object SensoriumBaseline {

    /** EWMA smoothing per update — slow enough that one odd evening doesn't rewrite the normal. */
    const val ALPHA = 0.06f
    /** A cell judges nothing until it has absorbed this many samples. */
    const val MIN_SAMPLES = 16
    /** A deviation must exceed k × learned-deviation AND an absolute floor to be an anomaly. */
    const val DEV_MULTIPLE = 2.5f

    // Absolute floors per metric — below these, a deviation is within sensor noise even if the
    // learned deviation is tiny (a perfectly regular home would otherwise flag everything).
    const val NOISE_FLOOR = 0.9f
    const val LIGHT_FLOOR = 0.5f
    const val MOTION_FLOOR = 0.05f
    const val CROWD_FLOOR = 2.5f

    /** 48 buckets: 24 hours × {weekday, weekend}. */
    fun bucket(hourOfDay: Int, weekend: Boolean): Int =
        (hourOfDay.coerceIn(0, 23)) * 2 + if (weekend) 1 else 0

    /** Absorb one observation into the learned normal for its bucket. */
    fun update(state: BaselineState, m: EnvMetrics, hourOfDay: Int, weekend: Boolean): BaselineState {
        val key = bucket(hourOfDay, weekend)
        val c = state.cells[key] ?: BaselineCell()
        fun ewma(mean: Float, value: Float) =
            if (c.samples == 0) value else mean + ALPHA * (value - mean)
        fun dev(devMean: Float, mean: Float, value: Float) =
            if (c.samples == 0) 0f else devMean + ALPHA * (abs(value - mean) - devMean)
        val next = BaselineCell(
            noiseMean = ewma(c.noiseMean, m.noise), noiseDev = dev(c.noiseDev, c.noiseMean, m.noise),
            // ⚠️ An unmeasured light leaves the learned light exactly where it was. Not folded in
            // as a zero, and not reset either — a phone that simply has not had its first light
            // event yet must not lose what it already knows about this hour.
            lightMean = if (m.light == null) c.lightMean else ewma(c.lightMean, m.light),
            lightDev = if (m.light == null) c.lightDev else dev(c.lightDev, c.lightMean, m.light),
            motionMean = ewma(c.motionMean, m.motion), motionDev = dev(c.motionDev, c.motionMean, m.motion),
            crowdMean = ewma(c.crowdMean, m.crowd), crowdDev = dev(c.crowdDev, c.crowdMean, m.crowd),
            samples = c.samples + 1,
        )
        return BaselineState(state.cells + (key to next))
    }

    /** What is unusual about [m] for this hour, judged against the learned normal. Empty when the cell
     *  is too young, or when now looks like every other (hour, weekend) of its kind. */
    fun anomalies(state: BaselineState, m: EnvMetrics, hourOfDay: Int, weekend: Boolean): List<EnvAnomaly> {
        val c = state.cells[bucket(hourOfDay, weekend)] ?: return emptyList()
        if (c.samples < MIN_SAMPLES) return emptyList()
        val hourLabel = "%02d:00".format(hourOfDay)
        val dayLabel = if (weekend) "a weekend" else "a weekday"
        val out = mutableListOf<EnvAnomaly>()

        fun judge(metric: String, value: Float, mean: Float, devMean: Float, floor: Float,
                  higher: String, lower: String) {
            val threshold = max(DEV_MULTIPLE * devMean, floor)
            val delta = value - mean
            if (abs(delta) < threshold) return
            val word = if (delta > 0) higher else lower
            out += EnvAnomaly(
                metric = metric,
                text = "unusually $word for $hourLabel on $dayLabel",
                strength = abs(delta) / threshold,
            )
        }
        judge("noise", m.noise, c.noiseMean, c.noiseDev, NOISE_FLOOR, "loud", "quiet")
        // Nothing measured it, so there is nothing to call unusual. Deliberately silent rather
        // than reporting "unusually dark" at a phone that cannot see.
        m.light?.let { judge("light", it, c.lightMean, c.lightDev, LIGHT_FLOOR, "bright", "dark") }
        judge("motion", m.motion, c.motionMean, c.motionDev, MOTION_FLOOR, "active", "still")
        judge("crowd", m.crowd, c.crowdMean, c.crowdDev, CROWD_FLOOR, "crowded", "empty")
        return out.sortedByDescending { it.strength }
    }

    /** "Your typical weekday 15:00: calm, lit, a few people around" — the comparison line the scanner
     *  shows under the live reading. Null while the cell is still learning. */
    fun describeNormal(state: BaselineState, hourOfDay: Int, weekend: Boolean): String? {
        val c = state.cells[bucket(hourOfDay, weekend)] ?: return null
        if (c.samples < MIN_SAMPLES) return null
        val noise = NoiseProfile.entries[c.noiseMean.toInt().coerceIn(0, NoiseProfile.entries.size - 1)]
        val crowd = when {
            c.crowdMean >= Sensorium.BT_CROWD -> "crowded"
            c.crowdMean >= Sensorium.BT_FEW -> "a few people around"
            else -> "alone"
        }
        val day = if (weekend) "weekend" else "weekday"
        return "typical $day %02d:00 here: ${noise.name.lowercase()}, $crowd".format(hourOfDay)
    }
}
