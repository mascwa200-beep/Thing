package dev.mascwa.nutrition.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * The few shapes every screen here is built from.
 *
 * ⚠️ **Stock Material 3 and nothing else, on purpose.** The LCARS application has a whole geometry
 * kit — swept corners, rails, a bespoke type scale — and reusing it was never an option: it is the
 * novelty this app was asked to be free of. What is here is a card, a row and a bar, so that
 * somebody who has used any other Android app already knows how to read it.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

/** A label on the left, a value on the right — the workhorse of every panel here. */
@Composable
fun StatRow(label: String, value: String, emphasis: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = if (emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

/**
 * How much of a target has been eaten.
 *
 * ⚠️ The bar is clamped but the NUMBER beside it is not, so going over shows as a full bar and a
 * figure that keeps climbing. A bar that silently stopped at the target would make 2,900 against
 * 2,000 look identical to hitting it exactly, which is the one case somebody most needs to see.
 */
@Composable
fun ProgressRow(label: String, eaten: Double, target: Int, unit: String, tint: Color? = null) {
    val share = if (target > 0) (eaten / target).coerceIn(0.0, 1.0) else 0.0
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatRow(label, if (target > 0) "${round(eaten)} / $target $unit" else "${round(eaten)} $unit")
        LinearProgressIndicator(
            progress = { share.toFloat() },
            modifier = Modifier.fillMaxWidth(),
            color = tint ?: MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * A figure a person reads, in their own conventions.
 *
 * ⚠️ `Locale.getDefault()`, deliberately, and the opposite of the rule this project keeps relearning
 * for files: a number crossing a boundary another program parses is `Locale.US`; a number on a screen
 * belongs to whoever is looking at it.
 */
fun round(v: Double, places: Int = 0): String =
    String.format(Locale.getDefault(), "%.${places}f", v)
