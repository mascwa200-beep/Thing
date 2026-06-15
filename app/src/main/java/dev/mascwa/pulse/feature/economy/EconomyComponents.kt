package dev.mascwa.pulse.feature.economy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.economy.IndicatorSeries
import dev.mascwa.pulse.data.economy.ValueFormat
import dev.mascwa.pulse.feature.common.LineChart
import dev.mascwa.pulse.ui.theme.trendColor

/** A curated, World-Bank-compatible country list for the picker. */
val COMMON_COUNTRIES = listOf(
    "WLD" to "World",
    "US" to "United States",
    "GB" to "United Kingdom",
    "EMU" to "Euro area",
    "DE" to "Germany",
    "FR" to "France",
    "IT" to "Italy",
    "ES" to "Spain",
    "CA" to "Canada",
    "AU" to "Australia",
    "JP" to "Japan",
    "CN" to "China",
    "IN" to "India",
    "BR" to "Brazil",
    "MX" to "Mexico",
    "ZA" to "South Africa",
    "NG" to "Nigeria",
    "SA" to "Saudi Arabia",
    "RU" to "Russia",
    "KR" to "South Korea",
)

fun countryName(code: String): String =
    COMMON_COUNTRIES.firstOrNull { it.first.equals(code, true) }?.second ?: code

@Composable
fun CountryPicker(current: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }) {
            Text(countryName(current))
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            COMMON_COUNTRIES.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { expanded = false; onSelect(code) },
                )
            }
        }
    }
}

fun formatLatest(series: IndicatorSeries): String {
    val v = series.latest?.value ?: return "—"
    return when (series.format) {
        ValueFormat.PERCENT -> Formatters.percent(v)
        ValueFormat.CURRENCY_USD -> Formatters.currency(v, "USD", 0)
        ValueFormat.COMPACT -> Formatters.compact(v)
        ValueFormat.NUMBER -> Formatters.number(v)
    }
}

@Composable
fun IndicatorCard(series: IndicatorSeries, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(series.indicatorTitle, style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        formatLatest(series),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "  ${series.unit}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                val yoy = series.yoyChange
                val year = series.latest?.year
                if (yoy != null && year != null) {
                    val improving = if (series.higherIsBetter) yoy >= 0 else yoy <= 0
                    Text(
                        "${if (yoy >= 0) "+" else ""}${Formatters.number(yoy, 2)} vs ${year - 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = trendColor(improving),
                    )
                } else if (year != null) {
                    Text(
                        "as of $year",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (series.points.size >= 2) {
                LineChart(
                    values = series.points.map { it.value },
                    modifier = Modifier.width(96.dp).height(48.dp),
                    showZeroBaseline = series.format == ValueFormat.PERCENT,
                )
            }
        }
    }
}
