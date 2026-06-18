package dev.mascwa.pulse.feature.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.mascwa.pulse.core.telemetry.Explainer
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * A small "what does this mean?" popup: a [title] over a list of plain-language [Explainer]s
 * (headline + sentence). Tap outside to dismiss. Used to demystify space-weather metrics and market
 * instruments — see [dev.mascwa.pulse.core.telemetry.SpaceWeatherExplainers] / `MarketExplainers`.
 */
@Composable
fun ExplainerDialog(title: String, explainers: List<Explainer>, onDismiss: () -> Unit) {
    val c = Pulse.colors
    Dialog(onDismissRequest = onDismiss) {
        NeonPanel(corners = true, borderColor = c.accent.copy(alpha = 0.6f)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    title.uppercase(),
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    color = c.accent, letterSpacing = 1.sp,
                )
                explainers.forEach { e ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            e.headline,
                            fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                            color = c.ink,
                        )
                        Text(
                            e.detail,
                            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                Text(
                    "tap outside to close",
                    fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
