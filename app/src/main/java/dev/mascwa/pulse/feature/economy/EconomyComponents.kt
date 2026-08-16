package dev.mascwa.pulse.feature.economy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.economy.IndicatorSeries
import dev.mascwa.pulse.data.economy.ValueFormat
import dev.mascwa.pulse.feature.common.LineChart
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.core.telemetry.EconomyVintage
import dev.mascwa.pulse.ui.theme.Pulse
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
    val c = Pulse.colors
    NeonPanel(modifier.fillMaxWidth(), corners = true, padding = PaddingValues(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    series.indicatorTitle.uppercase(),
                    fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 0.8.sp, color = c.ink2,
                )
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        formatLatest(series),
                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = c.ink,
                    )
                    Text(
                        "  ${series.unit}",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
                val yoy = series.yoyChange
                val year = series.latest?.year
                if (yoy != null && year != null) {
                    // Neutral when the indicator has no agreed good direction. Colouring a rise in
                    // military spending green or red would be the app taking a side; the number is
                    // still shown, just not judged.
                    val color = when (series.higherIsBetter) {
                        null -> c.ink2
                        true -> trendColor(yoy >= 0)
                        false -> trendColor(yoy <= 0)
                    }
                    Text(
                        "${if (yoy >= 0) "+" else ""}${Formatters.number(yoy, 2)} vs ${year - 1}",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = color,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                // The vintage, always — not only when there is no year-over-year line to show.
                //
                // This used to sit in the `else` branch, so in the normal case (a series with two
                // points) the number above carried no date at all. The World Bank publishes annually
                // and revises late, so that number is routinely a year or two old; read in 2026, the
                // newest US inflation figure is the 2024 one. Shown bare, it reads as current.
                if (year != null) {
                    val now = System.currentTimeMillis()
                    val band = EconomyVintage.band(year, now)
                    Text(
                        EconomyVintage.describe(year, now).uppercase(),
                        fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 0.6.sp,
                        // Dim while the lag is what you would expect of an annual statistic; amber
                        // once it is genuinely behind, so the eye is drawn only when it should be.
                        color = when (band) {
                            EconomyVintage.Vintage.CURRENT, EconomyVintage.Vintage.RECENT -> c.muted
                            else -> c.amber
                        },
                        modifier = Modifier.padding(top = 2.dp),
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
