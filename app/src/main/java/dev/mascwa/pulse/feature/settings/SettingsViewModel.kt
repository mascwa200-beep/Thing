package dev.mascwa.pulse.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.notifications.NotificationScheduler
import dev.mascwa.pulse.notifications.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val scheduler: NotificationScheduler,
    private val diskCache: DiskCache,
    private val notifier: Notifier,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _cacheSize = MutableStateFlow(0L)
    val cacheSize: StateFlow<Long> = _cacheSize

    init {
        refreshCacheSize()
        // Keep the background worker in sync with notification/refresh prefs.
        viewModelScope.launch {
            repo.settings
                .map { Triple(it.notifications.masterEnabled, it.refreshIntervalMinutes, it.refreshOnlyOnWifi) }
                .distinctUntilChanged()
                .collect { (master, interval, wifi) ->
                    if (master) scheduler.schedule(interval, wifi) else scheduler.cancel()
                }
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    fun resetToDefaults() {
        viewModelScope.launch { repo.replace(AppSettings()) }
    }

    fun refreshCacheSize() {
        viewModelScope.launch { _cacheSize.value = diskCache.sizeBytes() }
    }

    fun clearCache() {
        viewModelScope.launch {
            diskCache.clear()
            refreshCacheSize()
        }
    }

    fun sendTestNotification() {
        notifier.notifyDigest(
            id = 9999,
            title = "Pulse notifications are working",
            body = "This is a test notification.",
            lines = listOf(
                "📰 Breaking-news alerts are enabled",
                "📈 Market & price alerts are enabled",
                "🌤️ Weather alerts are enabled",
            ),
        )
    }
}
