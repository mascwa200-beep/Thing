package dev.mascwa.pulse.data.usage

import dev.mascwa.pulse.core.telemetry.FeatureMeta
import dev.mascwa.pulse.navigation.Routes

/**
 * Display names + one-line pitches for the features the assistant can recommend, keyed by nav route.
 * Kept in one place so [dev.mascwa.pulse.core.telemetry.UsageInsights] stays pure (it takes this list)
 * and the recommendations read in plain language. Pitches are only shown for features the user hasn't
 * tried yet, so they double as gentle discovery.
 */
object FeatureCatalog {
    val entries: List<FeatureMeta> = listOf(
        FeatureMeta(Routes.HOME, "Pulse", "Your at-a-glance dashboard."),
        FeatureMeta(Routes.NEWS, "News", "Headlines from free, public feeds."),
        FeatureMeta(Routes.MARKETS, "Markets", "Indices, stocks, FX, commodities & crypto."),
        FeatureMeta(Routes.WEATHER, "Weather", "Forecast, feels-like & air quality."),
        FeatureMeta(Routes.JARVIS, "Computer", "Your private, on-device assistant — ask it anything."),
        FeatureMeta(Routes.SPACE_WX, "Space Weather", "Aurora odds, Kp & solar storms."),
        FeatureMeta(Routes.NAV, "Nav", "A 3D, heading-up cyber-map with a trail."),
        FeatureMeta(Routes.OBJECTIVES, "Objectives", "Track waypoints & calendar missions."),
        FeatureMeta(Routes.COMPASS, "Compass", "Offline heading."),
        FeatureMeta(Routes.SURVIVE, "Survive", "Nearest help, SOS & offline guides."),
        FeatureMeta(Routes.TACNET, "LCARS", "Radar, telemetry, orbital & space weather."),
        FeatureMeta(Routes.ECONOMY, "Economy", "Inflation, GDP & jobs."),
        FeatureMeta(Routes.FUEL, "Fuel & Energy", "Benchmarks & pump prices."),
        FeatureMeta(Routes.SOCIAL, "Social", "Lemmy, Mastodon & Hacker News."),
        FeatureMeta(Routes.SEARCH, "Search", "Private web search."),
        FeatureMeta(Routes.SKY, "Sky", "Space weather, orbital & compass."),
        FeatureMeta(Routes.ORBITAL, "Orbital", "ISS, sun, moon & near-Earth objects."),
        FeatureMeta(Routes.SETTINGS, "Settings", "Tune Pulse to your taste."),
    )

    /** Display label for a route key, or the key itself if unknown. */
    fun labelFor(key: String): String = entries.firstOrNull { it.key == key }?.label ?: key
}
