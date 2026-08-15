package dev.mascwa.pulse.feature.tacnet

import androidx.compose.ui.graphics.Color

/**
 * LCARS palette shared across the TACNET / TOOLS screens — flat black with contact types and readouts
 * told apart by hue (periwinkle standard, orange primary, white highlight, red-orange alert) rather
 * than CRT brightness levels. Internal to the tacnet package.
 */
internal object Pip {
    val bg = Color(0xFF000000)        // LCARS black
    val gridSoft = Color(0xFF232326)  // faint grid
    val grid = Color(0xFF3A3A3D)      // range rings / ticks
    val dim = Color(0xFFB8B8BD)       // secondary text
    val mid = Color(0xFF9999FF)       // standard contact (periwinkle)
    val bright = Color(0xFFFF9900)    // sweep / primary (LCARS orange)
    val glow = Color(0xFFFFFFFF)      // brightest highlight (ISS/selected text)
    val alert = Color(0xFFCC6666)     // emergency
    val violet = Color(0xFFCC99CC)    // secondary hero accent (mauve)
}
