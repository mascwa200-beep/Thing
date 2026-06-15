package dev.mascwa.pulse.data.markets

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.Csv
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.settings.WatchItem
import dev.mascwa.pulse.data.settings.WatchType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Market data from keyless sources:
 *  - Indices / stocks / forex / commodities via Stooq daily-history CSV
 *    (gives both the latest close vs previous close AND a sparkline in one call)
 *  - Crypto via the CoinGecko public API (price, 24h change, market cap, sparkline)
 */
class MarketsRepository(
    private val http: HttpClient,
    private val cache: DiskCache,
    private val settings: SettingsRepository,
) {
    private val ttl = 5 * 60 * 1000L

    suspend fun fetchWatchlist(force: Boolean): Fetched<List<Quote>> {
        val s = settings.current()
        val key = "markets_watchlist_${s.currencyCode}"
        if (!force) {
            cache.read(key, ttl, ListSerializer(Quote.serializer()))?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
        }
        return try {
            val quotes = coroutineScope {
                s.watchlist.map { item ->
                    async { runCatching { fetchStooq(item) }.getOrNull() }
                }.mapNotNull { it.await() }
            }
            cache.write(key, quotes, ListSerializer(Quote.serializer()))
            Fetched(quotes, false)
        } catch (e: Exception) {
            cache.readAny(key, ListSerializer(Quote.serializer()))?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
            throw e
        }
    }

    suspend fun fetchCrypto(force: Boolean): Fetched<List<Quote>> {
        val s = settings.current()
        val vs = s.currencyCode.lowercase()
        val key = "markets_crypto_$vs"
        if (!force) {
            cache.read(key, ttl, ListSerializer(Quote.serializer()))?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
        }
        return try {
            val ids = s.cryptoList.joinToString(",") { it.id }
            val quotes = if (ids.isBlank()) emptyList() else {
                val url = "https://api.coingecko.com/api/v3/coins/markets" +
                    "?vs_currency=$vs&ids=$ids&order=market_cap_desc&per_page=100&page=1" +
                    "&price_change_percentage=24h&sparkline=true"
                val coins = http.getJson(url, ListSerializer(CoinMarket.serializer()))
                val byId = coins.associateBy { it.id }
                s.cryptoList.mapNotNull { item -> byId[item.id]?.toQuote(item, s.currencyCode) }
            }
            cache.write(key, quotes, ListSerializer(Quote.serializer()))
            Fetched(quotes, false)
        } catch (e: Exception) {
            cache.readAny(key, ListSerializer(Quote.serializer()))?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
            throw e
        }
    }

    /** Combined view used by the Markets screen and Home card. */
    suspend fun fetchAll(force: Boolean): Fetched<List<Quote>> {
        val w = fetchWatchlist(force)
        val c = runCatching { fetchCrypto(force) }.getOrNull()
        val combined = w.data + (c?.data ?: emptyList())
        return Fetched(combined, w.fromCache && (c?.fromCache ?: true))
    }

    /** Fetch arbitrary Stooq-quoted instruments (used by the Fuel screen). */
    suspend fun stooqQuotes(items: List<WatchItem>): List<Quote> = coroutineScope {
        items.map { item -> async { runCatching { fetchStooq(item) }.getOrNull() } }
            .mapNotNull { it.await() }
    }

    private suspend fun fetchStooq(item: WatchItem): Quote {
        val cal = Calendar.getInstance()
        val d2 = SimpleDateFormat("yyyyMMdd", Locale.US).format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, -120)
        val d1 = SimpleDateFormat("yyyyMMdd", Locale.US).format(cal.time)
        val url = "https://stooq.com/q/d/l/?s=${item.id}&d1=$d1&d2=$d2&i=d"

        val csv = http.getString(url)
        val rows = Csv.parseWithHeader(csv)
        val closes = rows.mapNotNull { it["close"]?.toDoubleOrNull() }
        if (closes.isEmpty()) error("No data for ${item.id}")

        val last = rows.last()
        val price = closes.last()
        val prev = if (closes.size >= 2) closes[closes.size - 2] else null
        val change = if (prev != null) price - prev else null
        val pct = if (prev != null && prev != 0.0) (change!! / prev) * 100.0 else null

        return Quote(
            id = item.id,
            label = item.label,
            type = item.type.name,
            price = price,
            change = change,
            changePercent = pct,
            previousClose = prev,
            open = last["open"]?.toDoubleOrNull(),
            high = last["high"]?.toDoubleOrNull(),
            low = last["low"]?.toDoubleOrNull(),
            volume = last["volume"]?.toDoubleOrNull(),
            currency = currencyFor(item),
            updatedEpochMs = System.currentTimeMillis(),
            sparkline = closes.takeLast(40),
        )
    }

    private fun currencyFor(item: WatchItem): String = when (item.type) {
        WatchType.FOREX -> "" // pair, no single currency symbol
        else -> settingsCurrencyGuess(item)
    }

    // Stooq prices for US symbols are USD; indices/commodities are quoted in
    // their native unit. We display the symbol's natural quote and let the UI
    // avoid forcing a currency symbol on indices.
    private fun settingsCurrencyGuess(item: WatchItem): String = when {
        item.id.endsWith(".us") -> "USD"
        item.id == "^ftm" -> "GBP"
        item.id == "^dax" -> "EUR"
        item.id == "^nkx" -> "JPY"
        else -> "USD"
    }

    private fun CoinMarket.toQuote(item: WatchItem, currency: String) = Quote(
        id = item.id,
        label = item.label,
        type = WatchType.CRYPTO.name,
        price = current_price,
        change = price_change_24h,
        changePercent = price_change_percentage_24h,
        previousClose = if (current_price != null && price_change_24h != null)
            current_price - price_change_24h else null,
        high = high_24h,
        low = low_24h,
        volume = total_volume,
        marketCap = market_cap,
        currency = currency,
        imageUrl = image,
        updatedEpochMs = System.currentTimeMillis(),
        sparkline = sparkline_in_7d?.price?.let { p ->
            // Down-sample the 7d hourly sparkline to ~48 points for the chart.
            if (p.size <= 48) p else p.filterIndexed { i, _ -> i % (p.size / 48) == 0 }
        } ?: emptyList(),
    )
}
