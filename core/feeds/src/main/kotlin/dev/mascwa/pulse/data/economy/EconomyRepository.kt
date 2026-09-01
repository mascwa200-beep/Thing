package dev.mascwa.pulse.data.economy

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.util.Fetched
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class EconomyRepository(
    private val worldBank: WorldBankClient,
    private val cache: DiskCache,
    /** Whose figures, as an ISO country code. Read per fetch; blank falls back to the United States. */
    private val countryCode: suspend () -> String,
) {
    private val ttl = 12 * 60 * 60 * 1000L // 12h — annual data changes slowly

    /** All headline indicators for the dashboard / Economy screen. */
    suspend fun fetchDashboard(force: Boolean, countryOverride: String? = null): Fetched<EconomyDashboard> {
        val country = (countryOverride ?: countryCode()).ifBlank { "US" }
        val key = "economy_dashboard_$country"
        if (!force) {
            cache.read(key, ttl, EconomyDashboard.serializer())?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
        }
        return try {
            val series = coroutineScope {
                EconomyIndicator.entries.map { ind ->
                    async { runCatching { worldBank.series(ind, country) }.getOrNull() }
                }.mapNotNull { it.await() }
            }
            // ⚠️ **A partial fetch must never shrink the dashboard.** Nineteen indicators are
            // fetched at once through one gated client, so a rate limit or a moment's network
            // trouble takes most of them together — and this used to write whatever survived
            // straight over a complete cached copy and report a clean fresh fetch. Eighteen cards
            // would vanish with nothing on screen to say why, and the impoverished version would
            // then be served for the next twelve hours. Same rule, and the same reason, as
            // `MarketsRepository.mergeWithCache`.
            val previous = cache.readAny(key, EconomyDashboard.serializer())?.value
            val merged = LinkedHashMap<String, IndicatorSeries>()
            previous?.series?.forEach { merged[it.indicatorId] = it }
            series.forEach { merged[it.indicatorId] = it }
            // ⚠️ Nothing at all is a failure, not an empty dashboard. Caching one would tell the
            // screen the country genuinely has no figures, and hold that answer for the whole TTL.
            if (merged.isEmpty()) throw IllegalStateException("no economic series could be fetched")
            // Declaration order, so a merged-in cached series lands where the screen expects it
            // rather than after everything fetched this time.
            val ordered = EconomyIndicator.entries.mapNotNull { merged[it.id] }
            val name = ordered.firstOrNull { it.points.isNotEmpty() }?.countryName ?: country
            val dash = EconomyDashboard(country, name, ordered)
            cache.write(key, dash, EconomyDashboard.serializer())
            // ⚠️ Reported STALE when anything had to be carried over, because that is what it is:
            // some of what is on screen did not come from this fetch, and the banner is the only
            // place a reader could ever learn that.
            Fetched(dash, series.size < EconomyIndicator.entries.size)
        } catch (e: Exception) {
            cache.readAny(key, EconomyDashboard.serializer())?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
            throw e
        }
    }

    /** Single series (used by Inflation screen with full history). */
    suspend fun fetchSeries(
        indicator: EconomyIndicator,
        force: Boolean,
        countryOverride: String? = null,
    ): Fetched<IndicatorSeries> {
        val country = (countryOverride ?: countryCode()).ifBlank { "US" }
        val key = "economy_series_${indicator.id}_$country"
        if (!force) {
            cache.read(key, ttl, IndicatorSeries.serializer())?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
        }
        return try {
            val s = worldBank.series(indicator, country, startYear = 1990)
            cache.write(key, s, IndicatorSeries.serializer())
            Fetched(s, false)
        } catch (e: Exception) {
            cache.readAny(key, IndicatorSeries.serializer())?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
            throw e
        }
    }
}
