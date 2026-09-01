package dev.mascwa.pulse.feature.redalert

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.lcarsBlockShape
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Orbitron

/**
 * What a red alert looks like: the hazard, where, how long, and the issuer's own instruction.
 *
 * ⚠️ **The instruction text is reproduced verbatim and is never paraphrased, shortened for effect or
 * rewritten in the console's voice.** It is written by the agency that issued the warning and it is
 * the part that changes what a person does in the next few minutes. Everything else on this screen
 * is framing; that paragraph is the payload.
 *
 * Deliberately its own palette rather than the app theme's: this screen exists to be unmistakable
 * from across a room, and inheriting whatever condition the console happened to be in would defeat
 * that. The pulse is slow — a strobe is harder to read, and reading is the point.
 */
@Composable
fun RedAlertScreen(
    condition: String,
    hazard: String,
    area: String,
    timing: String?,
    remaining: String?,
    instruction: String?,
    source: String,
    receivedAt: String,
    onAcknowledge: () -> Unit,
) {
    // ⚠️ **No `by`, and the value is read in the DRAW lambda below.** A delegated read here puts the
    // snapshot read in this composable's scope, so the whole screen — a scrolling column of a dozen
    // Texts — recomposed on every frame of the pulse, for as long as the alert stood. That is the
    // opposite of what an emergency takeover should do to a phone that is already struggling.
    // `drawBehind` re-draws the bar without recomposing anything.
    val pulse = rememberInfiniteTransition(label = "alert").animateFloat(
        initialValue = 0.30f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0000))
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            // The condition bar. Full-bleed, pulsing, unambiguous.
            Row(
                Modifier
                    .fillMaxWidth()
                    .drawBehind { drawRect(RED.copy(alpha = pulse.value)) }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    condition,
                    fontFamily = Orbitron,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    letterSpacing = 4.sp,
                    color = Color.Black,
                )
            }

            Column(Modifier.padding(18.dp)) {
                Text(
                    hazard.uppercase(),
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                    lineHeight = 34.sp,
                    color = Color.White,
                )
                if (area.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        area,
                        fontFamily = ChakraPetch,
                        fontSize = 16.sp,
                        lineHeight = 21.sp,
                        color = Color(0xFFFFC9C4),
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    timing?.let { Tag(it, RED) }
                    remaining?.let { Tag(it, Color(0xFF8A1710)) }
                }

                // The one paragraph that matters.
                if (!instruction.isNullOrBlank()) {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "WHAT TO DO",
                        fontFamily = JetBrainsMono,
                        fontSize = 10.sp,
                        letterSpacing = 2.sp,
                        color = RED,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Box(Modifier.width(3.dp).height(1.dp))
                        Text(
                            instruction,
                            fontFamily = ChakraPetch,
                            fontSize = 17.sp,
                            lineHeight = 25.sp,
                            color = Color.White,
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                // Who said so and when we heard it — so the reader can judge it, and so the app
                // never implies it knew before the source did.
                Text(
                    "ISSUED BY $source · RECEIVED $receivedAt",
                    fontFamily = JetBrainsMono,
                    fontSize = 10.sp,
                    letterSpacing = 1.4.sp,
                    color = Color(0xFFB08883),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Your phone's own emergency alert system operates separately and may also " +
                        "sound. This is not a substitute for it.",
                    fontFamily = ChakraPetch,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Color(0xFF9A7A76),
                )

                Spacer(Modifier.height(28.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(lcarsBlockShape(sweep = 22.dp, corner = LcarsCorner.TopStart))
                        .background(RED)
                        .clickable { onAcknowledge() }
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "ACKNOWLEDGE · SILENCE ALARM",
                        fontFamily = Orbitron,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 2.sp,
                        color = Color.Black,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Tag(text: String, colour: Color) {
    Box(
        Modifier
            .clip(lcarsBlockShape(sweep = 8.dp, corner = LcarsCorner.TopStart))
            .background(colour)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text.uppercase(),
            fontFamily = JetBrainsMono,
            fontSize = 10.sp,
            letterSpacing = 1.4.sp,
            color = Color.Black,
            fontWeight = FontWeight.Bold,
        )
    }
}

private val RED = Color(0xFFFF3B30)
