package dev.mascwa.nutrition.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.mascwa.nutrition.BundleReport
import dev.mascwa.nutrition.DeviceFacts
import dev.mascwa.nutrition.ui.SectionCard
import dev.mascwa.nutrition.ui.StatRow
import java.text.NumberFormat
import java.util.Locale

/**
 * What actually arrived on this phone.
 *
 * ⚠️ **This was the app's whole first screen and it has earned a smaller place rather than deletion.**
 * The one question the packaging poses is whether a third of a gigabyte of barcode database reached
 * a device this build was never tested on, and nothing else on any screen answers it: an empty search
 * result looks the same whether the database is missing or the food genuinely is not in it.
 */
@Composable
fun AboutCard() {
    val context = LocalContext.current
    var report by remember { mutableStateOf<BundleReport?>(null) }
    val device = remember { DeviceFacts.read() }

    // Opening it on a first run unpacks the asset, which is slow and must not be on the main thread.
    LaunchedEffect(Unit) { report = BundleReport.read(context) }

    SectionCard("This build") {
        val r = report
        when {
            r == null -> Text(
                "Opening the food database…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            r.present -> {
                StatRow("Products", count(r.products))
                StatRow("With nutrition", count(r.withNutrition))
                StatRow("Further nutrients", "${count(r.extraFigures)} figures")
                r.builtAt?.let { StatRow("Built", it) }
                r.attribution?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> Text(
                r.failure ?: "No food database in this build — barcodes need a network.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        StatRow("Phone", "${device.model} · Android ${device.android}")
    }
}

/** ⚠️ The reader's own conventions: this is a figure being read, not one crossing a boundary. */
private fun count(n: Int?): String =
    n?.let { NumberFormat.getIntegerInstance(Locale.getDefault()).format(it) } ?: "—"
