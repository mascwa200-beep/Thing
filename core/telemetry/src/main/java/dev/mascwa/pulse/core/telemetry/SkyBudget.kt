package dev.mascwa.pulse.core.telemetry

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * What the star map may spend on the handset it is actually running on.
 *
 * [DeviceClass] answers how much machine there is; this answers what the map does about it. The two
 * are separate on purpose — that one is shared by everything in these applications and knows nothing
 * about sky charts, and this one knows nothing about how a phone is measured.
 *
 * ## ⚠️ FULL is byte-for-byte today's behaviour, and that is the discipline
 *
 * Every value at [DeviceClass.Tier.FULL] equals the constant it replaces, so a flagship is provably
 * unaffected by this file existing. That is what made the application-wide potato pass safe to ship
 * from a machine with no phone on it, and it is the only reason a set of numbers nobody here can
 * measure on real hardware is safe to add at all: the worst case is that a weak phone gets a
 * different experience, never that a good one does. A test pins it.
 *
 * ## ⚠️ What is NOT here, and why — measured rather than assumed
 *
 * The plan this came from listed star labels and a star-depth cap as levers too. Both were dropped
 * after reading the code:
 *
 *  - **Labels.** [StarGlyph.LABEL_HEADROOM] bounds them to about seventeen on screen whatever the
 *    zoom. Seventeen text draws is not what makes a frame slow, and a knob whose benefit cannot be
 *    measured is speculative tuning on a screen nothing here can render.
 *  - **Capping how deep into the catalogue the map reads.** Tempting, and genuinely the largest
 *    star-side saving — but the drawn count is ALREADY bounded at every zoom by
 *    [SkyProjection.magnitudeLimit], which is the whole premise that makes a three-million-star
 *    catalogue and a Canvas compatible. So the cap bites only in the deep half of the zoom range,
 *    and it would make the depth readout say "everything it holds down to magnitude 10.5 is on
 *    screen" about a catalogue that holds 12 — the app being more confident than its data, which is
 *    the defect class this repository keeps correcting. Not worth it for a saving the existing law
 *    already bounds.
 *
 * What is left is the two things whose cost is per-frame and NOT bounded by an existing rule — how
 * often the map is asked to redraw, and how many samples the Milky Way glow takes — plus one
 * threshold on the deep-sky pass.
 */
object SkyBudget {

    /**
     * The map's settings for one device.
     *
     * @param sensorPeriodUs how often to ask the rotation-vector sensor for a reading, in
     *   microseconds. ⚠️ **This is the frame rate while following**, because the chart redraws on
     *   every change of view and the sensor is what changes it — so it is the single biggest lever
     *   here and the reason the whole file exists.
     * @param pointSmoothing how much of each sample to take, derived from [sensorPeriodUs] by
     *   [smoothingFor] so the map lags the hand by the same length of TIME at every rate.
     * @param milkyWaySamples the cap on samples across the narrow screen axis for the glow pass.
     * @param deepSkyShapePx how long an object's major axis must be, in pixels, before it is drawn
     *   as a shape rather than as the hollow marker — [DeepSky.drawsShape]'s threshold. RAISING it
     *   means fewer procedural ellipses and stipples, which is the direction that saves work.
     *   ⚠️ I had this inverted on first writing and the numbers below were smaller than the shipped
     *   constant, which would have drawn MORE shapes on the weakest phones. Read the declaration.
     */
    data class Budget(
        val sensorPeriodUs: Int,
        val pointSmoothing: Double,
        val milkyWaySamples: Int,
        val deepSkyShapePx: Double,
    )

    /**
     * The reference: what the map has always done, on a device with room to do it.
     *
     * ⚠️ **20,000 µs is `SENSOR_DELAY_GAME`, and that is read from the platform rather than
     * recalled.** Disassembling `SensorManager.getDelay` in the shipped android-all jar gives the
     * whole table — 0 → 0 µs, 1 (GAME) → 20,000, 2 (UI) → 66,667, 3 (NORMAL) → 200,000, and
     * `default:` returns the argument unchanged. So `registerListener` genuinely accepts a raw
     * microsecond period, and only the values 0 to 3 are special-cased. Every number below is far
     * above 3, so none of them can be mistaken for a named rate.
     */
    const val FULL_SENSOR_PERIOD_US = 20_000

    /** [Budget.pointSmoothing] at the reference rate — the constant this replaced. */
    const val FULL_SMOOTHING = 0.25

    /** [Budget.milkyWaySamples] at full strength — the cap the glow pass was measured against. */
    const val FULL_MILKY_WAY_SAMPLES = 56

    /**
     * How large an object must appear before it earns a drawn shape, at full strength.
     *
     * ⚠️ Taken FROM [DeepSky.SHAPE_MIN_PX] rather than restated, so the reference value and the one
     * the renderer defaults to cannot drift apart — the same rule [DeviceClass.FULL_PARALLELISM]
     * follows one file over.
     */
    const val FULL_DEEP_SKY_SHAPE_PX = DeepSky.SHAPE_MIN_PX

    /**
     * How long the map takes to catch up with the hand, in microseconds.
     *
     * An exponential blend of weight `w` applied every `Δt` leaves `(1 - w)` of the error per
     * sample, so after time `t` the remaining error is `(1 - w)^(t/Δt)` — which is `exp(-t/τ)` for
     * `τ = -Δt / ln(1 - w)`. At the reference rate that is
     * `-20000 / ln(0.75)` ≈ **69.5 ms**, and holding it constant is what [smoothingFor] does.
     */
    val TIME_CONSTANT_US: Double = -FULL_SENSOR_PERIOD_US / ln(1.0 - FULL_SMOOTHING)

    /**
     * The blend weight that gives [TIME_CONSTANT_US] at this sampling period.
     *
     * ⚠️ **THIS IS THE TRAP THE WHOLE FILE IS SHAPED AROUND.** `pointSmoothing` is a weight applied
     * PER SAMPLE, so carrying the same 0.25 to a device sampling a quarter as often gives four times
     * the lag — the map would visibly trail the handset on exactly the phones this exists to help,
     * and it would look like the sensor being wrong rather than like a setting. Inverting the
     * relation above gives `w = 1 - exp(-Δt/τ)`, which holds the lag in real time at every rate:
     * 0.25 at 20 ms, 0.38 at 33 ms, 0.62 at 67 ms, 0.83 at 125 ms.
     *
     * ⚠️ Capped at 1.0, which is the correct limit rather than a guard against nonsense: a weight of
     * one means take each reading whole, and at a slow enough rate a reading IS the whole answer.
     * Floored at the reference weight so this can only ever loosen the filter, never tighten it past
     * what a full-strength device does.
     */
    fun smoothingFor(sensorPeriodUs: Int): Double {
        if (sensorPeriodUs <= 0) return FULL_SMOOTHING
        val w = 1.0 - exp(-sensorPeriodUs / TIME_CONSTANT_US)
        return w.coerceIn(FULL_SMOOTHING, 1.0)
    }

    /**
     * What this tier may spend.
     *
     * ⚠️ **A ladder chosen for its shape, and said plainly rather than dressed up as a measurement.**
     * Nothing in this container can render a frame or hold a slow phone, so what is defensible is the
     * ORDER and the reasoning — halving the rate roughly halves the per-frame cost, and the glow's
     * cost goes as the square of its sample count, so 56 → 32 is about three times cheaper and
     * 56 → 16 about twelve. Whether MINIMAL reads as "smooth" or as "degraded" is a question only a
     * handset can answer, and every number here is one line.
     *
     * ⚠️ MODEST's 33,333 µs is not one of the platform's three named rates. That is deliberate — the
     * named ladder jumps straight from 20 ms to 67 ms, which is more than a mid-range phone needs
     * giving up — and it is safe because the pass-through behaviour is proven, see
     * [FULL_SENSOR_PERIOD_US].
     *
     * ⚠️ [DeviceClass.Pressure] is NOT taken here. A thermal reading is a momentary thing and the
     * sensor rate is chosen once when following starts; re-registering the listener because the
     * phone warmed up for a minute would be a jerk in the picture in exchange for very little. The
     * pressure levers that matter — decode size, background cadence, fan-out — are already in
     * [DeviceClass.Budget] and already read.
     */
    fun forTier(tier: DeviceClass.Tier): Budget = when (tier) {
        DeviceClass.Tier.FULL -> budget(FULL_SENSOR_PERIOD_US, FULL_MILKY_WAY_SAMPLES, FULL_DEEP_SKY_SHAPE_PX)
        DeviceClass.Tier.MODEST -> budget(33_333, 44, FULL_DEEP_SKY_SHAPE_PX)
        DeviceClass.Tier.LEAN -> budget(66_667, 32, 11.0)
        // ⚠️ The floor is MilkyWayGlow.MIN_SAMPLES: below it the bilinear upscale creases visibly on
        // a strong gradient, which is a worse picture rather than a cheaper one.
        DeviceClass.Tier.MINIMAL -> budget(125_000, 16, 16.0)
    }

    private fun budget(periodUs: Int, samples: Int, shapePx: Double) =
        Budget(periodUs, smoothingFor(periodUs), samples, shapePx)

    /**
     * What is being done differently on this device, in words, or null when nothing is.
     *
     * ⚠️ **Null at full strength, and that is the point rather than a shortcut.** A line saying "no
     * settings have been reduced" on every flagship is noise on a readout somebody reads once; a
     * line that only appears when something IS reduced is the one worth reading. It is also the
     * honest inverse of the guarantee this file rests on — if there is nothing to say, nothing was
     * changed.
     *
     * ⚠️ Rounded to whole frames a second, because that is the unit somebody has an intuition for.
     * The microsecond period is what the platform is told; hertz is what a person can judge.
     */
    fun describe(budget: Budget): String? {
        if (budget == forTier(DeviceClass.Tier.FULL)) return null
        val hz = (1_000_000.0 / budget.sensorPeriodUs).roundToInt()
        val parts = ArrayList<String>(3)
        parts += "following at about $hz frames a second"
        if (budget.milkyWaySamples < FULL_MILKY_WAY_SAMPLES) parts += "a softer Milky Way"
        if (budget.deepSkyShapePx > FULL_DEEP_SKY_SHAPE_PX) parts += "only the largest galaxies drawn as shapes"
        return "This phone is set to spend less: " + parts.joinToString(", ") + "."
    }
}
