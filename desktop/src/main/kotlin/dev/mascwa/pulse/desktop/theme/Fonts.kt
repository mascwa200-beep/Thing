package dev.mascwa.pulse.desktop.theme

import androidx.compose.ui.text.font.FontFamily

/**
 * Stand-ins for the Android app's bundled ChakraPetch/JetBrainsMono `.ttf` assets. Porting the actual font
 * files needs Compose Desktop's own file/resource-based font loading (a different API than Android's
 * `Font(R.font.x)`) — a follow-up, not blocking this shell: system sans/monospace keep every ported LCARS
 * component's layout and proportions identical, only the letterforms differ until the real fonts land.
 */
val ChakraPetch = FontFamily.SansSerif
val JetBrainsMono = FontFamily.Monospace
