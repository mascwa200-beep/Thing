package dev.mascwa.pulse.feature.markets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.core.telemetry.MarketSession
import dev.mascwa.pulse.data.markets.Quote
import dev.mascwa.pulse.data.settings.WatchType
import dev.mascwa.pulse.feature.common.ChangePill
import dev.mascwa.pulse.feature.common.Sparkline
import dev.mascwa.pulse.ui.theme.trendColor

@Composable
fun QuoteRow(quote: Quote, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!quote.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = quote.imageUrl,
                contentDescription = null,
                modifier = Modifier.size(28.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        val type = runCatching { WatchType.valueOf(quote.type) }.getOrNull()
        Column(Modifier.weight(1f)) {
            Text(
                quote.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                quote.id.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (quote.low != null && quote.high != null && type != WatchType.CRYPTO) {
                // At a flat two decimals a day's range on EURUSD renders as "1.08 · 1.09" — the
                // digits that actually moved are the ones being rounded away.
                val digits = priceDigits(quote, type)
                Text(
                    "L ${Formatters.number(quote.low, digits)} · H ${Formatters.number(quote.high, digits)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Where the price sits in its own year — but only when that is news.
            //
            // A percentage move says what changed since yesterday and nothing about whether the
            // price is high or low, which is often the better question. The row stays quiet through
            // the broad middle deliberately: a line that appears on every instrument stops being
            // read, and "mid-range" is the answer most of the time. Tap through for the full range.
            MarketSession.describeRange(
                MarketSession.rangePosition(quote.price, quote.fiftyTwoWeekLow, quote.fiftyTwoWeekHigh),
            )?.takeIf { it.startsWith("at") || it.startsWith("near") }?.let { band ->
                Text(
                    band.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Sparkline(
            values = quote.sparkline,
            modifier = Modifier.width(64.dp).height(28.dp),
        )
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 4.dp)) {
            Text(
                formatPrice(quote),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            ChangePill(quote.changePercent)
        }
    }
}

fun formatPrice(quote: Quote): String {
    val type = runCatching { WatchType.valueOf(quote.type) }.getOrNull()
    val digits = priceDigits(quote, type)
    return when (type) {
        WatchType.FOREX, WatchType.INDEX -> Formatters.number(quote.price, digits)
        WatchType.CRYPTO -> Formatters.currency(quote.price, quote.currency.ifBlank { "USD" }, digits)
        else -> if (quote.currency.isBlank()) Formatters.number(quote.price, digits)
        else Formatters.currency(quote.price, quote.currency, digits)
    }
}

/**
 * How many decimals this instrument deserves.
 *
 * The venue states its own quoting precision, and it knows better than a guess keyed off the asset
 * class — but it is used as a **floor**, never a ceiling. Taking it outright would be a regression
 * the moment an exchange reported a coarser figure than the app already shows, and losing a digit
 * that moves is a worse failure than carrying one that does not.
 */
internal fun priceDigits(quote: Quote, type: WatchType?): Int {
    val default = when (type) {
        WatchType.FOREX -> 4
        WatchType.CRYPTO -> if ((quote.price ?: 0.0) < 1.0) 4 else 2
        else -> 2
    }
    val hint = quote.priceHint?.takeIf { it in 0..8 } ?: return default
    return maxOf(default, hint)
}
