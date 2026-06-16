package dev.mascwa.pulse.ui.effects

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import kotlinx.coroutines.delay

private data class BootLine(val text: String, val ok: Boolean = true)

private val BOOT_SEQUENCE = listOf(
    BootLine("> initializing nightwire kernel", true),
    BootLine("> mounting secure feed bus", true),
    BootLine("> linking market data uplink", true),
    BootLine("> calibrating weather grid", true),
    BootLine("> decrypting world signal", true),
    BootLine("> arming push relays", true),
    BootLine("WELCOME, OPERATOR", false),
)

/**
 * Terminal-style boot sequence shown once on launch. Types lines in, then
 * fades away. Tap anywhere to skip.
 */
@Composable
fun BootScreen(onFinished: () -> Unit) {
    var shown by remember { mutableStateOf(0) }
    var visible by remember { mutableStateOf(true) }
    val c = Pulse.colors

    fun finish() {
        if (!visible) return
        visible = false
    }

    LaunchedEffectSafe {
        BOOT_SEQUENCE.forEachIndexed { i, _ ->
            delay(if (i == 0) 250 else 360)
            shown = i + 1
        }
        delay(650)
        finish()
    }

    AnimatedVisibility(
        visible = visible,
        exit = fadeOut() + scaleOut(targetScale = 1.04f),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // Opaque backdrop so the live app underneath is fully masked until
                // the boot sequence finishes and fades out (no "blend-through").
                .background(c.void)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { finish() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row {
                    Text("NIGHT", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                        fontSize = 30.sp, color = c.ink)
                    Text("WIRE", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                        fontSize = 30.sp, color = c.accent)
                }
                Text(
                    "GLOBAL SIGNAL TERMINAL",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 3.sp,
                    color = c.muted,
                    modifier = Modifier.padding(top = 6.dp, bottom = 26.dp),
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(BOOT_SEQUENCE.take(shown)) { line ->
                        Row(Modifier.fillMaxWidth()) {
                            if (line.ok) {
                                Text(line.text, fontFamily = JetBrainsMono, fontSize = 11.sp,
                                    color = c.ink2, modifier = Modifier.weight(1f))
                                Text("[OK]", fontFamily = JetBrainsMono, fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold, color = c.positive)
                            } else {
                                Text(
                                    line.text, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp, letterSpacing = 3.sp, color = c.accent,
                                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
                Text(
                    "[ TAP TO SKIP ]",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 2.sp,
                    color = c.faint, modifier = Modifier.padding(top = 28.dp),
                )
            }
        }
    }

    // Fire onFinished after the exit animation has had time to play.
    LaunchedEffectKey(visible) {
        if (!visible) {
            delay(520)
            onFinished()
        }
    }
}

/* Small wrappers so the file stays import-light. */
@Composable
private fun LaunchedEffectSafe(block: suspend () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { block() }
}

@Composable
private fun LaunchedEffectKey(key: Any?, block: suspend () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(key) { block() }
}
