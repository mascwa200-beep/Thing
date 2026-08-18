package dev.mascwa.pulse.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import dev.mascwa.pulse.core.telemetry.Stardate
import kotlinx.coroutines.delay
import java.util.TimeZone

/**
 * The current stardate, computed once for the whole app.
 *
 * [Stardate] is pure and clock-free by design, which is right for the core and leaves somebody to
 * supply the instant. This is that somebody — once, at the root — so the header, the masthead and
 * anything else that shows one are reading the same number rather than each calling the clock and
 * risking a disagreement across a tenth boundary.
 *
 * ⚠️ **One coroutine, not thirty-five.** Provided beside `LocalConsoleSection` in `PulseApp`, which
 * is the pattern that gave every screen a location readout without editing one of them. A per-screen
 * timer would be waste against the owner's standing "least resource intensive" instruction, for a
 * value that changes ten times a day.
 *
 * ⚠️ And not a plain `remember {}` either. That is right for the boot screen, which lives for a few
 * seconds; here it would let a screen left open read a stardate up to two and a half hours old.
 */
val LocalStardate = compositionLocalOf { "" }

/**
 * The stardate for this instant in the device's own time zone.
 *
 * The offset is taken **at** the instant rather than as a fixed zone offset, so it is the correct
 * one across a daylight-saving change instead of the one that happened to apply when the app
 * started.
 */
fun currentStardate(nowMs: Long = System.currentTimeMillis()): Double =
    Stardate.at(nowMs, TimeZone.getDefault().getOffset(nowMs) / 1000)

/** "26621.5" — the bare number, for chrome. The boot reveal is the one place that spells the word. */
fun currentStardateText(nowMs: Long = System.currentTimeMillis()): String =
    Stardate.format(currentStardate(nowMs))

/**
 * Provides [LocalStardate], refreshed on the hour.
 *
 * A stardate's last digit is `hour * 10 / 24`, so it advances at most ten times a day and only ever
 * on an hour boundary — which is exactly what this sleeps to. At most twenty-four wake-ups a day,
 * each of them a handful of integer operations.
 *
 * ⚠️ `delay` schedules nothing with the platform and so **cannot wake the device**; if the phone
 * dozes through a boundary the update simply arrives late, and the next pass recomputes the boundary
 * from the clock as it then is, so the lateness never accumulates.
 */
@Composable
fun ProvideStardate(content: @Composable () -> Unit) {
    val stardate by produceState(initialValue = currentStardateText()) {
        while (true) {
            val now = System.currentTimeMillis()
            val nextHour = (now / HOUR_MS + 1) * HOUR_MS
            delay((nextHour - now).coerceAtLeast(1_000L))
            value = currentStardateText()
        }
    }
    CompositionLocalProvider(LocalStardate provides stardate, content = content)
}

private const val HOUR_MS = 3_600_000L
