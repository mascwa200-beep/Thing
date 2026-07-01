package dev.mascwa.pulse.core.telemetry

import kotlin.math.roundToInt

/**
 * The physical-gesture layer for the S.P.E.C.I.A.L. game — the "Cyberpunk-2077-on-mobile" resolution model.
 * An encounter approach is gated by a stat ([Choice.stat] + [Choice.difficulty], shown CP2077-style), and
 * you commit to it by *performing the action* with the phone rather than tapping a button. The device's
 * accelerometer/gyro grade how well you performed (0..1), and [performanceRoll] turns that into the check's
 * die value — a flawless gesture over a stat you've built lands a crit; a sloppy one under-statted hurts.
 *
 * This file is the pure, CI-tested part: which gesture a stat calls for, and the performance→roll mapping.
 * The live sensor detection + grading lives on-device in the STAT-tab UI.
 */
enum class GestureType(val label: String, val cue: String) {
    SHAKE("SHAKE", "Shake the phone — hard and fast"),
    FLICK("FLICK", "Flick the phone sharply, once"),
    HOLD("HOLD STILL", "Hold the phone dead still"),
}

object Gestures {
    /**
     * The gesture a [stat] check is performed with. Brute stats shake, reflexes flick, and the mind/nerve
     * stats demand a steady hold (the "scan"). Kept to three clearly-distinct motions so the sensors can
     * tell them apart reliably.
     */
    fun forStat(s: Special): GestureType = when (s) {
        Special.STRENGTH, Special.ENDURANCE, Special.LUCK -> GestureType.SHAKE
        Special.AGILITY -> GestureType.FLICK
        Special.PERCEPTION, Special.INTELLIGENCE, Special.CHARISMA -> GestureType.HOLD
    }

    /** Map a gesture performance in 0f..1f to the check's d-roll (1..[SpecialGame.DIE]); 1f = a natural crit. */
    fun performanceRoll(performance: Float): Int =
        (1 + performance.coerceIn(0f, 1f) * (SpecialGame.DIE - 1)).roundToInt().coerceIn(1, SpecialGame.DIE)
}
