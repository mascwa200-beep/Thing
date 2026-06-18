package dev.mascwa.pulse.data.markets

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.settings.WatchItem
import dev.mascwa.pulse.data.settings.WatchType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * Market data from keyless sources:
 *  - Indices / stocks / forex / commodities via the Yahoo Finance chart API
 *    (one call yields the latest price, previous close, day range AND a sparkline).
 *    Stooq was dropped after it moved behind a JavaScript anti-bot wall that
 *    returns an HTML challenge instead of CSV to non-browser clients.
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
                // Fetch in small concurrent batches (not one big burst) with a retry, so Yahoo's anti-bot
                // throttling on a large parallel request doesn't silently drop most of the watchlist —
                // which left the home tape showing only the first few instruments.
                s.watchlist.chunked(YAHOO_BATCH).flatMap { batch ->
                    batch.map { item -> async { fetchYahooResilient(item) } }.awaitAll()
                }.filterNotNull()
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

    /** Fetch arbitrary instruments (used by the Fuel screen). */
    suspend fun quotesFor(items: List<WatchItem>): List<Quote> = coroutineScope {
        items.map { item -> async { runCatching { fetchYahoo(item) }.getOrNull() } }
            .mapNotNull { it.await() }
    }

    /** [fetchYahoo] with one jittered retry, so a transient throttle on a parallel burst doesn't drop the
     *  instrument from the tape / markets list. Returns null only if both attempts fail. */
    private suspend fun fetchYahooResilient(item: WatchItem): Quote? {
        runCatching { fetchYahoo(item) }.getOrNull()?.let { return it }
        delay(kotlin.random.Random.nextLong(250, 800))
        return runCatching { fetchYahoo(item) }.getOrNull()
    }

    /** Yahoo Finance chart API — keyless. One call gives price, previous close,
     *  day range, currency and a 1-month close series for the sparkline. */
    private suspend fun fetchYahoo(item: WatchItem): Quote {
        val sym = URLEncoder.encode(yahooSymbol(item), "UTF-8")
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$sym?interval=1d&range=1mo"
        val text = http.getString(url, mapOf("User-Agent" to YAHOO_UA))
        val result = http.json.parseToJsonElement(text).jsonObject["chart"]?.jsonObject
            ?.get("result")?.jsonArray?.firstOrNull()?.jsonObject ?: error("No data for ${item.id}")
        val meta = result["meta"]?.jsonObject ?: error("No meta for ${item.id}")
        fun metaD(k: String) = meta[k]?.jsonPrimitive?.doubleOrNull
        val price = metaD("regularMarketPrice") ?: error("No price for ${item.id}")
        val prev = metaD("chartPreviousClose") ?: metaD("previousClose")
        val change = if (prev != null) price - prev else null
        val pct = if (prev != null && prev != 0.0) (change!! / prev) * 100.0 else null
        val closes = result["indicators"]?.jsonObject?.get("quote")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("close")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.doubleOrNull } ?: emptyList()
        val currency = if (item.type == WatchType.FOREX) ""
        else meta["currency"]?.jsonPrimitive?.contentOrNull ?: currencyFor(item)

        return Quote(
            id = item.id,
            label = item.label,
            type = item.type.name,
            price = price,
            change = change,
            changePercent = pct,
            previousClose = prev,
            open = metaD("regularMarketOpen"),
            high = metaD("regularMarketDayHigh"),
            low = metaD("regularMarketDayLow"),
            volume = metaD("regularMarketVolume"),
            currency = currency,
            updatedEpochMs = System.currentTimeMillis(),
            sparkline = closes.takeLast(40),
        )
    }

    /** Maps the app's Stooq-style instrument ids to Yahoo symbols. */
    private fun yahooSymbol(item: WatchItem): String {
        YAHOO_OVERRIDES[item.id]?.let { return it }
        val id = item.id
        return when {
            id.startsWith("^") -> id.uppercase()
            id.endsWith(".us") -> id.removeSuffix(".us").uppercase()
            id.endsWith(".f") -> id.removeSuffix(".f").uppercase() + "=F"
            item.type == WatchType.FOREX -> id.uppercase() + "=X"
            else -> id.uppercase()
        }
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

    private companion object {
        /** Max concurrent Yahoo requests per batch — small enough to avoid the anti-bot throttle. */
        const val YAHOO_BATCH = 8
        const val YAHOO_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        /** Stooq id → Yahoo symbol where the simple rules don't apply. */
        val YAHOO_OVERRIDES = mapOf(
            "^spx" to "^GSPC",
            "^ndq" to "^NDX",
            "^dji" to "^DJI",
            "^ftm" to "^FTSE",
            "^dax" to "^GDAXI",
            "^cac" to "^FCHI",
            "^nkx" to "^N225",
            "cb.f" to "BZ=F", // Brent
        )
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
