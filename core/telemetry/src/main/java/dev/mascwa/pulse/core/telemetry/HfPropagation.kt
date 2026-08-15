package dev.mascwa.pulse.core.telemetry

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Shortwave propagation conditions computed from live space weather.
 *
 * The console already downloads solar flux, the K-index and X-ray flux; this turns them into the
 * thing an operator actually wants — *which bands are open right now, and which are dead*. Nothing
 * in the app did this before.
 *
 * **What this is, honestly:** a simplified single-hop F2 model of the kind printed on band-condition
 * dashboards, not a ray-tracing prediction. It uses three real physical relationships:
 *  1. The F2 layer's critical frequency rises with solar activity (F10.7 cm flux), so the maximum
 *     usable frequency rises with it.
 *  2. A geomagnetic storm depresses F2 ionisation, dropping the MUF — worse the higher the K-index.
 *  3. A solar flare floods the sunlit D layer, which *absorbs* the low bands rather than refracting
 *     them — a daytime-only effect that gets worse the harder the flare.
 *
 * It does not model path length, antenna, power, sporadic-E, grey-line or auroral scatter. Treat the
 * output as "which end of the spectrum is worth trying", which is exactly how a band chart is read.
 */
object HfPropagation {

    enum class Quality { CLOSED, POOR, FAIR, GOOD }

    /** One amateur band and how it should behave in daylight and in darkness. */
    data class BandReport(
        val name: String,
        val megahertz: Double,
        val day: Quality,
        val night: Quality,
    )

    /** The classic HF band plan, plus 6 m so a big sporadic-E opening has somewhere to show. */
    val BANDS: List<Pair<String, Double>> = listOf(
        "160m" to 1.8, "80m" to 3.5, "40m" to 7.0, "30m" to 10.1, "20m" to 14.0,
        "17m" to 18.1, "15m" to 21.0, "12m" to 24.9, "10m" to 28.0, "6m" to 50.0,
    )

    /** A quiet-Sun floor: F10.7 never really reads below about 64 sfu. */
    private const val QUIET_F107 = 70.0

    /**
     * F2 critical frequency in MHz. Empirically foF2 runs about 4 MHz at solar minimum and about
     * 12 MHz at a strong maximum for midday mid-latitudes, so this is linear in F10.7 over that
     * span; at night the layer decays to roughly 45% of its daytime value.
     */
    fun criticalFrequencyMhz(f107: Double?, daytime: Boolean): Double {
        val flux = (f107 ?: QUIET_F107).coerceIn(60.0, 300.0)
        val day = (4.0 + 0.055 * (flux - QUIET_F107)).coerceIn(2.0, 16.0)
        return if (daytime) day else day * 0.45
    }

    /**
     * Maximum usable frequency for a long single hop (the conventional MUF(3000) factor of ~3.0),
     * knocked down by geomagnetic activity. A K of 3 or less costs nothing; above that each unit
     * takes roughly 6% off, which is why a big storm shuts the high bands.
     */
    fun mufMhz(f107: Double?, kp: Double?, daytime: Boolean): Double {
        val fo = criticalFrequencyMhz(f107, daytime)
        val storm = 1.0 - 0.06 * ((kp ?: 0.0).coerceIn(0.0, 9.0) - 3.0).coerceAtLeast(0.0)
        return (fo * 3.0 * storm).coerceAtLeast(1.0)
    }

    /**
     * How many quality steps a flare knocks off the daylight low bands. R1 costs one step, R3 and
     * above shut them entirely. Night side is untouched — there is no D layer without sunlight,
     * which is the whole reason 80 m comes alive after dark.
     */
    fun absorptionSteps(xrayLongChannelWm2: Double?): Int =
        when (SolarActivity.radioBlackout(xrayLongChannelWm2)) {
            0 -> 0
            1 -> 1
            2 -> 2
            else -> 4 // more than enough to close anything it touches
        }

    /** The band table for the current conditions. */
    fun report(f107: Double?, kp: Double?, xrayLongChannelWm2: Double?): List<BandReport> {
        val dayMuf = mufMhz(f107, kp, daytime = true)
        val nightMuf = mufMhz(f107, kp, daytime = false)
        val absorb = absorptionSteps(xrayLongChannelWm2)
        return BANDS.map { (name, mhz) ->
            BandReport(
                name = name,
                megahertz = mhz,
                day = degrade(qualityAt(mhz, dayMuf, daytime = true), if (mhz <= 10.5) absorb else 0),
                night = qualityAt(mhz, nightMuf, daytime = false),
            )
        }
    }

    /**
     * Where a frequency sits relative to the MUF decides whether it propagates. Comfortably below
     * is the sweet spot; far below is absorbed (by day) or long-haul-quiet (by night); above the
     * MUF the signal punches through the layer and is gone.
     */
    private fun qualityAt(mhz: Double, muf: Double, daytime: Boolean): Quality = when {
        mhz > muf * 1.15 -> Quality.CLOSED
        mhz > muf -> Quality.POOR
        mhz >= muf * 0.45 -> Quality.GOOD
        // Well below the MUF: by day the D layer eats it, by night it is the reliable band.
        daytime -> Quality.FAIR
        else -> Quality.GOOD
    }

    private fun degrade(q: Quality, steps: Int): Quality {
        if (steps <= 0) return q
        val idx = (Quality.entries.indexOf(q) - steps).coerceAtLeast(0)
        return Quality.entries[idx]
    }

    /** The best band open right now, day side, or null when the whole spectrum is shut. */
    fun bestDayBand(report: List<BandReport>): BandReport? =
        report.filter { it.day == Quality.GOOD }.maxByOrNull { it.megahertz }
            ?: report.filter { it.day == Quality.FAIR }.maxByOrNull { it.megahertz }

    /** A one-line summary for a dense readout. */
    fun summary(f107: Double?, kp: Double?, xrayLongChannelWm2: Double?): String {
        val report = report(f107, kp, xrayLongChannelWm2)
        val best = bestDayBand(report)
        val blackout = SolarActivity.radioBlackout(xrayLongChannelWm2)
        // Defer to mufDisplay rather than computing one here. With no solar readings at all it
        // returns null by design, and quoting a figure derived from the quiet-Sun floor would
        // contradict it -- the two sit next to each other on the radio readout.
        val muf = mufDisplay(f107, kp)
        val head = if (muf == null) "MUF not yet measured"
            else String.format(Locale.US, "MUF ~%d MHz", muf)
        return when {
            blackout >= 3 -> "$head · daylight HF blacked out"
            best == null -> "$head · bands closed"
            else -> "$head · best by day ${best.name}"
        }
    }

    /** Day-side MUF rounded for display; null when there is nothing to base it on. */
    fun mufDisplay(f107: Double?, kp: Double?): Int? =
        if (f107 == null && kp == null) null else mufMhz(f107, kp, daytime = true).roundToInt()
}
