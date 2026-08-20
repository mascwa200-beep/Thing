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
            val name = series.firstOrNull { it.points.isNotEmpty() }?.countryName ?: country
            val dash = EconomyDashboard(country, name, series)
            cache.write(key, dash, EconomyDashboard.serializer())
            Fetched(dash, false)
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
