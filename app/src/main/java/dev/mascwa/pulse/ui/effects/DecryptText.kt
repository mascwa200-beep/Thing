package dev.mascwa.pulse.ui.effects

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.delay
import kotlin.random.Random

private const val GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#%&/<>*+="

/**
 * A [Text] that "decrypts" into place — random glyphs resolve left-to-right to
 * the target string on first composition. Gated by [LocalGlitchEnabled] so it
 * respects the user's reduced-motion (glitch) setting; otherwise renders plain.
 */
@Composable
fun DecryptText(
    text: String,
    fontFamily: FontFamily,
    fontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    durationMs: Int = 480,
) {
    val glitch = LocalGlitchEnabled.current
    if (!glitch) {
        Text(
            text, modifier, color = color, fontFamily = fontFamily, fontSize = fontSize,
            fontWeight = fontWeight, letterSpacing = letterSpacing, maxLines = maxLines, overflow = overflow,
        )
        return
    }
    var display by remember(text) { mutableStateOf(text) }
    LaunchedEffect(text) {
        val frames = 12
        for (f in 0..frames) {
            val reveal = text.length * f / frames
            display = buildString {
                text.forEachIndexed { i, ch ->
                    append(if (i < reveal || ch == ' ') ch else GLYPHS[Random.nextInt(GLYPHS.length)])
                }
            }
            delay((durationMs / frames).toLong())
        }
        display = text
    }
    Text(
        display, modifier, color = color, fontFamily = fontFamily, fontSize = fontSize,
        fontWeight = fontWeight, letterSpacing = letterSpacing, maxLines = maxLines, overflow = overflow,
    )
}
