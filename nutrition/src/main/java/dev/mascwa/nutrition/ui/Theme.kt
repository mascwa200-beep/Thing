package dev.mascwa.nutrition.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Plain Material 3, and that is the point of the whole module.
 *
 * ⚠️ **No novelty.** The LCARS application is a deliberate piece of theatre — a 1966 console with
 * its own palette, its own typeface, its own sounds and a boot sequence — and none of it belongs in
 * front of somebody working out what to have for lunch. This is the system's own design language,
 * so the app looks like the phone it is installed on rather than like somebody's project.
 *
 * ⚠️ It also follows the device's dynamic colour where the device offers it (Android 12 and later).
 * That is the opposite decision from `:app`, which fixes its palette because the palette IS the
 * feature. Here the right colour is whatever the user already chose.
 */
@Composable
fun NutritionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme(primary = Leaf, secondary = LeafDim)
        else -> lightColorScheme(primary = LeafDeep, secondary = LeafDim)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// The fallback below Android 12, where there is no wallpaper palette to take. Green because the
// launcher icon is, and for no deeper reason than that they should agree.
private val Leaf = Color(0xFF6FD39C)
private val LeafDeep = Color(0xFF1F6F52)
private val LeafDim = Color(0xFF4E9C7B)
