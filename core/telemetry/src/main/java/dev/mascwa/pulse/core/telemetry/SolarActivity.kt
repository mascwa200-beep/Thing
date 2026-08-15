package dev.mascwa.pulse.core.telemetry

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Solar-activity classification — the pure maths behind the space-weather console.
 *
 * The app already had `SpaceWeather.stormLevelForKp` buried as a companion on a serializable model
 * in the app module, so nothing could test it and the other two NOAA scales did not exist at all.
 * This is all three scales plus flare classification, in one CI-tested place.
 *
 * The three NOAA space-weather scales, and what each is actually measured from:
 *  - **R** (radio blackout) — GOES **long-channel** X-ray flux, 0.1-0.8 nm, in W/m².
 *  - **S** (solar radiation storm) — GOES integral proton flux at **>=10 MeV**, in pfu.
 *  - **G** (geomagnetic storm) — the planetary K-index.
 */
object SolarActivity {

    /** A GOES flare classification, e.g. `B5.8` — letter decade plus mantissa. */
    data class Flare(val letter: Char, val magnitude: Double) {
        /** `"B5.8"`, or `"X12"` where the mantissa runs past ten (X has no ceiling). */
        val label: String
            get() = if (magnitude >= 10.0) {
                // Round, never truncate: 4.5e-3 / 1e-4 lands on 44.99999... in binary floating
                // point, and toInt() would report a genuine X45 flare as X44.
                "$letter${magnitude.roundToInt()}"
            } else {
                String.format(Locale.US, "%c%.1f", letter, magnitude)
            }
    }

    // Flare decades in W/m² on the 0.1-0.8 nm channel.
    private const val A_FLOOR = 1e-8
    private const val B_FLOOR = 1e-7
    private const val C_FLOOR = 1e-6
    private const val M_FLOOR = 1e-5
    private const val X_FLOOR = 1e-4

    /**
     * Classify a long-channel X-ray flux. Below the A decade (a very quiet Sun, or a bad sample)
     * there is no class at all, so this returns null rather than inventing an "A0".
     */
    fun flareClass(longChannelWm2: Double?): Flare? {
        val f = longChannelWm2 ?: return null
        if (!f.isFinite() || f < A_FLOOR) return null
        // X is open-ended: 2e-3 is X20, not "twenty times into a decade that rolls over".
        val base = when {
            f >= X_FLOOR -> X_FLOOR
            f >= M_FLOOR -> M_FLOOR
            f >= C_FLOOR -> C_FLOOR
            f >= B_FLOOR -> B_FLOOR
            else -> A_FLOOR
        }
        val letter = when (base) {
            X_FLOOR -> 'X'
            M_FLOOR -> 'M'
            C_FLOOR -> 'C'
            B_FLOOR -> 'B'
            else -> 'A'
        }
        return Flare(letter, f / base)
    }

    /** R-scale (radio blackout) 0..5 from long-channel X-ray flux. */
    fun radioBlackout(longChannelWm2: Double?): Int {
        val f = longChannelWm2?.takeIf { it.isFinite() } ?: return 0
        return when {
            f >= 2e-3 -> 5   // X20
            f >= 1e-3 -> 4   // X10
            f >= 1e-4 -> 3   // X1
            f >= 5e-5 -> 2   // M5
            f >= 1e-5 -> 1   // M1
            else -> 0
        }
    }

    /** S-scale (solar radiation storm) 0..5 from the >=10 MeV integral proton flux in pfu. */
    fun radiationStorm(protonPfu10Mev: Double?): Int {
        val p = protonPfu10Mev?.takeIf { it.isFinite() } ?: return 0
        return when {
            p >= 1e5 -> 5
            p >= 1e4 -> 4
            p >= 1e3 -> 3
            p >= 1e2 -> 2
            p >= 1e1 -> 1
            else -> 0
        }
    }

    /** G-scale (geomagnetic storm) 0..5 from the planetary K-index. */
    fun geomagneticStorm(kp: Double?): Int {
        val k = kp?.takeIf { it.isFinite() } ?: return 0
        return when {
            k >= 9.0 -> 5
            k >= 8.0 -> 4
            k >= 7.0 -> 3
            k >= 6.0 -> 2
            k >= 5.0 -> 1
            else -> 0
        }
    }

    /** `"G0"` / `"G3"` — the bare scale token. */
    fun scaleToken(prefix: Char, level: Int): String = "$prefix${level.coerceIn(0, 5)}"

    /** The NOAA severity word for a scale level. */
    fun severityWord(level: Int): String = when (level.coerceIn(0, 5)) {
        0 -> "None"
        1 -> "Minor"
        2 -> "Moderate"
        3 -> "Strong"
        4 -> "Severe"
        else -> "Extreme"
    }

    /** `"G3 Strong"`, or `"None"` at level zero — the label a readout shows. */
    fun scaleLabel(prefix: Char, level: Int): String =
        if (level <= 0) "None" else "${scaleToken(prefix, level)} ${severityWord(level)}"

    /**
     * Plain-English consequence of a scale level, so a reading means something without a lookup.
     * Deliberately about what a person on the ground would notice.
     */
    fun effect(prefix: Char, level: Int): String = when (prefix) {
        'R' -> when (level.coerceIn(0, 5)) {
            0 -> "No radio blackout."
            1 -> "Brief loss of HF radio on the sunlit side; low-frequency navigation degraded."
            2 -> "HF radio lost for tens of minutes on the sunlit side."
            3 -> "Wide HF blackout on the sunlit side for about an hour."
            4 -> "HF blackout across the sunlit side for one to two hours."
            else -> "Complete HF blackout on the entire sunlit side for hours."
        }
        'S' -> when (level.coerceIn(0, 5)) {
            0 -> "No radiation storm."
            1 -> "Minor: brief HF issues at the poles; no practical effect at the ground."
            2 -> "Moderate: polar HF degraded, small radiation dose risk on polar flights."
            3 -> "Strong: polar flights rerouted, satellite noise and star-tracker upsets."
            4 -> "Severe: polar HF blackout, real dose risk on high-latitude flights."
            else -> "Extreme: satellites and polar aviation seriously affected."
        }
        else -> when (level.coerceIn(0, 5)) {
            0 -> "No geomagnetic storm."
            1 -> "Minor: aurora at high latitudes, weak grid fluctuations."
            2 -> "Moderate: aurora further south, HF fading at high latitudes."
            3 -> "Strong: aurora at mid-latitudes, satellite drag and grid alarms."
            4 -> "Severe: aurora well into mid-latitudes, grid protection may trip."
            else -> "Extreme: aurora near the tropics, widespread grid and satellite trouble."
        }
    }

    /**
     * Overall headline for the console — the worst of the three scales, since that is the one that
     * actually matters to you.
     */
    fun headline(rLevel: Int, sLevel: Int, gLevel: Int): String {
        val worst = maxOf(rLevel, sLevel, gLevel)
        if (worst <= 0) return "Quiet"
        // On a tie, name the geomagnetic storm first — it is the one people can go outside and see,
        // then the radio blackout, then the radiation storm.
        val prefix = when (worst) {
            gLevel -> 'G'
            rLevel -> 'R'
            else -> 'S'
        }
        return scaleLabel(prefix, worst)
    }
}
