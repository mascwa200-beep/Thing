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
    return when (type) {
        WatchType.FOREX -> Formatters.number(quote.price, 4)
        WatchType.INDEX -> Formatters.number(quote.price, 2)
        WatchType.CRYPTO -> Formatters.currency(quote.price, quote.currency.ifBlank { "USD" },
            if ((quote.price ?: 0.0) < 1.0) 4 else 2)
        else -> if (quote.currency.isBlank()) Formatters.number(quote.price, 2)
        else Formatters.currency(quote.price, quote.currency, 2)
    }
}
