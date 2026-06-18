package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure turn-by-turn helpers for the glasses HUD nav card. Given the true-north bearing to a target and
 * the device's current true-north heading, work out which way to turn — a glanceable relative arrow and a
 * short "40° right" hint. Kept free of Android / sensor deps so the (fiddly, modular-arithmetic) maths is
 * unit-tested in CI, with the great-circle bearing/distance supplied by the caller (`Geo`).
 */
object NavGuidance {

    /** 8-way relative-direction arrows, clockwise from "straight ahead" (index 0). */
    private val ARROWS = listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")

    /**
     * The signed turn needed to face [bearingTrue] when currently facing [headingTrue], in degrees
     * normalised to (-180, 180]. Positive = turn right (clockwise), negative = turn left. Inputs are
     * any degrees (they're wrapped), so callers don't have to pre-normalise sensor values.
     */
    fun relativeBearing(bearingTrue: Double, headingTrue: Double): Double {
        // (bearing - heading + 540) is always positive for any finite inputs once wrapped, so the
        // Kotlin `%` (which keeps the sign of the dividend) can't yield a negative remainder here.
        val wrapped = ((bearingTrue - headingTrue) % 360.0 + 360.0) % 360.0
        return ((wrapped + 540.0) % 360.0) - 180.0
    }

    /**
     * Arrow pointing where the target lies relative to where you're facing. A null [headingTrue] (no
     * compass fix yet) falls back to the absolute compass arrow for [bearingTrue] — i.e. as if facing
     * due north — so the card still shows a sensible direction before the first heading sample.
     */
    fun relativeArrow(bearingTrue: Double, headingTrue: Double?): String {
        val rel = if (headingTrue == null) ((bearingTrue % 360.0) + 360.0) % 360.0
        else (relativeBearing(bearingTrue, headingTrue) + 360.0) % 360.0
        val idx = (rel / 45.0).roundToInt() % 8
        return ARROWS[idx]
    }

    /**
     * A short turn hint: "ahead", "behind", or "40° right" / "120° left". Null when there's no heading
     * fix yet (the absolute cardinal carries the direction in that case).
     */
    fun turnHint(bearingTrue: Double, headingTrue: Double?): String? {
        if (headingTrue == null) return null
        val rel = relativeBearing(bearingTrue, headingTrue)
        val mag = abs(rel).roundToInt()
        return when {
            mag <= 10 -> "ahead"
            mag >= 170 -> "behind"
            rel > 0 -> "$mag° right"
            else -> "$mag° left"
        }
    }
}
