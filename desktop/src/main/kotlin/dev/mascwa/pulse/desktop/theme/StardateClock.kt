package dev.mascwa.pulse.desktop.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.mascwa.pulse.desktop.telemetry.currentStardateText
import kotlinx.coroutines.delay

/** The stardate the console is showing. Empty until [ProvideStardate] is above you. */
val LocalStardate = compositionLocalOf { "" }

/**
 * Provides [LocalStardate], refreshed on the hour.
 *
 * ⚠️ **One coroutine, not one per screen.** Wrapped once around the whole window, so every framed
 * screen reads a value instead of starting its own timer — the same arrangement the phone uses, and
 * the reason a stardate could be added to thirty-five screens there without editing any of them.
 *
 * An hour, not a minute: the fractional digit is a tenth of a day, so it changes roughly every two
 * and a half hours. Ticking faster would wake the process to compute a string that has not moved.
 */
@Composable
fun ProvideStardate(content: @Composable () -> Unit) {
    var stamp by remember { mutableStateOf(currentStardateText()) }
    LaunchedEffect(Unit) {
        while (true) {
            stamp = currentStardateText()
            delay(REFRESH_MS)
        }
    }
    CompositionLocalProvider(LocalStardate provides stamp, content = content)
}

private const val REFRESH_MS = 60L * 60L * 1000L
