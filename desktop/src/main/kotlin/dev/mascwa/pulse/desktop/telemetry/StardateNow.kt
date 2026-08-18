package dev.mascwa.pulse.desktop.telemetry

import java.util.TimeZone

/**
 * The current stardate on this machine.
 *
 * Not a mirror and not mirrorable: [Stardate] itself is shared and byte-identical across the two
 * platforms, but reading a clock and a time zone is exactly the platform-facing part it keeps out,
 * and the phone's equivalent lives in `app/.../ui/StardateClock.kt`. The arithmetic that matters —
 * the civil calendar and the scale — is the shared core; this is the one line each side supplies.
 *
 * The offset is taken **at** the instant rather than as a fixed zone offset, so it is correct across
 * a daylight-saving change instead of whichever one applied when the window opened.
 */
fun currentStardate(nowMs: Long = System.currentTimeMillis()): Double =
    Stardate.at(nowMs, TimeZone.getDefault().getOffset(nowMs) / 1000)

/** "26621.5" — the bare number, for chrome. */
fun currentStardateText(nowMs: Long = System.currentTimeMillis()): String =
    Stardate.format(currentStardate(nowMs))
