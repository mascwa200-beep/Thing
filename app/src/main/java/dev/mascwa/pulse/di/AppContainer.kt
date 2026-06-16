package dev.mascwa.pulse.di

import android.content.Context
import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.data.economy.EconomyRepository
import dev.mascwa.pulse.data.economy.WorldBankClient
import dev.mascwa.pulse.data.fuel.FuelRepository
import dev.mascwa.pulse.data.markets.MarketsRepository
import dev.mascwa.pulse.data.emergency.EmergencyService
import dev.mascwa.pulse.data.news.NewsRepository
import dev.mascwa.pulse.data.orbital.OrbitalRepository
import dev.mascwa.pulse.data.places.OverpassRepository
import dev.mascwa.pulse.data.sensors.CompassController
import dev.mascwa.pulse.data.sensors.SurvivalTools
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.space.SpaceWeatherRepository
import dev.mascwa.pulse.data.survival.SurvivalContentRepository
import dev.mascwa.pulse.data.weather.LocationProvider
import dev.mascwa.pulse.data.weather.WeatherRepository
import dev.mascwa.pulse.notifications.NotificationScheduler
import dev.mascwa.pulse.notifications.Notifier
import kotlinx.serialization.json.Json

/**
 * Manual dependency-injection graph. A single container of lazily-created
 * singletons held by the Application. Avoids annotation processors (Hilt/kapt)
 * which keeps the Gradle build robust across Android Studio / AGP versions.
 */
class AppContainer(private val appContext: Context) {

    val json: Json by lazy { HttpClient.defaultJson() }
    val http: HttpClient by lazy { HttpClient.create(json, appContext.cacheDir) }
    val diskCache: DiskCache by lazy { DiskCache(appContext, json) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext, json) }

    private val worldBank: WorldBankClient by lazy { WorldBankClient(http) }

    val newsRepository: NewsRepository by lazy {
        NewsRepository(http, diskCache, settingsRepository)
    }
    val marketsRepository: MarketsRepository by lazy {
        MarketsRepository(http, diskCache, settingsRepository)
    }
    val economyRepository: EconomyRepository by lazy {
        EconomyRepository(worldBank, diskCache, settingsRepository)
    }
    val fuelRepository: FuelRepository by lazy {
        FuelRepository(http, marketsRepository, worldBank, diskCache, settingsRepository)
    }
    val weatherRepository: WeatherRepository by lazy {
        WeatherRepository(http, diskCache, settingsRepository)
    }
    val spaceWeatherRepository: SpaceWeatherRepository by lazy {
        SpaceWeatherRepository(http, diskCache)
    }
    val orbitalRepository: OrbitalRepository by lazy {
        OrbitalRepository(http, diskCache, settingsRepository)
    }
    val locationProvider: LocationProvider by lazy { LocationProvider(appContext) }

    val connectivityObserver: dev.mascwa.pulse.core.connectivity.ConnectivityObserver by lazy {
        dev.mascwa.pulse.core.connectivity.ConnectivityObserver(appContext)
    }

    val overpassRepository: OverpassRepository by lazy { OverpassRepository(http, diskCache) }
    val safetyRepository: dev.mascwa.pulse.data.safety.SafetyRepository by lazy {
        dev.mascwa.pulse.data.safety.SafetyRepository(http, diskCache)
    }
    val survivalContentRepository: SurvivalContentRepository by lazy {
        SurvivalContentRepository(appContext, json)
    }
    val emergencyService: EmergencyService by lazy { EmergencyService(appContext) }
    val survivalTools: SurvivalTools by lazy { SurvivalTools(appContext) }
    val socialRepository: dev.mascwa.pulse.data.social.SocialRepository by lazy {
        dev.mascwa.pulse.data.social.SocialRepository(http, diskCache, settingsRepository)
    }
    val imageRepository: dev.mascwa.pulse.data.images.ImageRepository by lazy {
        dev.mascwa.pulse.data.images.ImageRepository(http)
    }

    val radarRepository: dev.mascwa.pulse.data.radar.RadarRepository by lazy {
        dev.mascwa.pulse.data.radar.RadarRepository(http, diskCache)
    }

    /** Compass is stateful per-screen, so hand out a fresh controller each time. */
    fun newCompassController(): CompassController = CompassController(appContext)

    /** Telemetry is stateful per-screen (sensor lifecycle), so hand out fresh. */
    fun newTelemetryController(): dev.mascwa.pulse.data.sensors.TelemetryController =
        dev.mascwa.pulse.data.sensors.TelemetryController(appContext)

    val notifier: Notifier by lazy { Notifier(appContext) }
    val notificationScheduler: NotificationScheduler by lazy { NotificationScheduler(appContext) }
}
