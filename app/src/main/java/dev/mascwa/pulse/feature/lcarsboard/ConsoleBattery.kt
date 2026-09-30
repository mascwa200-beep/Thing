package dev.mascwa.pulse.feature.lcarsboard

/**
 * The battery readout in the console's header — what the hidden status bar used to show.
 *
 * The console hides Android's status bar, so whatever it said that still matters has to be said here.
 * The clock was already in the header; this is the battery.
 *
 * Pure: the numbers Android's battery broadcast carries go in, and a line comes out. That keeps the
 * two rules below tested rather than remembered.
 *
 * - **A reading Android did not give is no reading.** Level and scale come as extras that default to
 *   -1 when absent, and a scale of zero is not a battery at all. Each of those is null, never "0%" —
 *   a phone told it is at zero percent when nothing was measured has been told something false and
 *   alarming.
 * - **Charging means plugged in**, whichever source it is. `EXTRA_PLUGGED` is a bit set (AC, USB,
 *   wireless, dock), and any bit counts. A phone on a charger that reports FULL is still on the
 *   charger.
 */
object ConsoleBattery {

    data class Reading(val percent: Int, val charging: Boolean)

    fun reading(level: Int, scale: Int, plugged: Int): Reading? {
        if (level < 0 || scale <= 0) return null
        val percent = ((level * 100.0) / scale).let { Math.round(it).toInt() }.coerceIn(0, 100)
        return Reading(percent, charging = plugged > 0)
    }

    /** The header's line: "84%", or "84% CHG" while charging. */
    fun label(reading: Reading): String =
        "${reading.percent}%" + if (reading.charging) " CHG" else ""
}
