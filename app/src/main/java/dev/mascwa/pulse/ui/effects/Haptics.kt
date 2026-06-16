package dev.mascwa.pulse.ui.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** Whether subtle UI haptics are enabled (driven by Settings). */
val LocalHaptics = staticCompositionLocalOf { true }

/**
 * Returns a haptic-tick lambda that fires only when haptics are enabled. Use for
 * light confirmations (radar contact select, range/filter changes, etc.).
 */
@Composable
fun rememberHaptics(): (HapticFeedbackType) -> Unit {
    val enabled = LocalHaptics.current
    val hf = LocalHapticFeedback.current
    return remember(enabled, hf) { { type: HapticFeedbackType -> if (enabled) hf.performHapticFeedback(type) } }
}
