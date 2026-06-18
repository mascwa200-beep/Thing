package dev.mascwa.pulse.feature.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * Renders a unified-style diff (see [dev.mascwa.pulse.data.selfcode.LineDiff]) with green/red line
 * coloring so a self-code change is readable before approving. Each line is monospace and single-line
 * (long lines ellipsize) to keep the layout tight.
 */
@Composable
fun DiffText(diff: String?, modifier: Modifier = Modifier, maxLines: Int = 400) {
    val c = Pulse.colors
    if (diff.isNullOrBlank()) return
    val lines = diff.split("\n")
    Column(modifier) {
        lines.take(maxLines).forEach { ln ->
            val color = when {
                ln.startsWith("+++") || ln.startsWith("---") -> c.sky
                ln.startsWith("+") -> c.positive
                ln.startsWith("-") -> c.magenta
                else -> c.muted
            }
            Text(
                ln.ifBlank { " " },
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = color,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (lines.size > maxLines) {
            Text(
                "… (${lines.size - maxLines} more lines)",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            )
        }
    }
}
