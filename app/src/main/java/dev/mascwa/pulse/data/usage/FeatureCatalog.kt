package dev.mascwa.pulse.data.usage

import dev.mascwa.pulse.core.telemetry.FeatureMeta
import dev.mascwa.pulse.navigation.GROUPS
import dev.mascwa.pulse.navigation.Routes

/**
 * Display names + one-line pitches for the features the assistant can recommend, keyed by nav route.
 * Kept in one place so [dev.mascwa.pulse.core.telemetry.UsageInsights] stays pure (it takes this list)
 * and the recommendations read in plain language. Pitches are only shown for features the user hasn't
 * tried yet, so they double as gentle discovery.
 *
 * DERIVED from the menu directory rather than written out again. This used to be a hand-kept list of
 * 20 routes in an app with ~35 — and its labels had drifted from the menu's (the recommendation said
 * "Nav" where the menu said "Map", "Survive" where the menu said "Search Survival"), so the feature a
 * recommendation named was not findable under that name. One inventory, one name per feature.
 */
object FeatureCatalog {
    /**
     * The only destinations the directory's GROUPS deliberately do not list: the six bottom-nav tabs
     * (their own top level) and the two Markets sub-screens (reached as sub-tabs, but old shortcuts
     * and notifications still deep-link them, so they still need names).
     */
    private val OFF_MENU = listOf(
        FeatureMeta(Routes.HOME, "Home", "Your at-a-glance dashboard."),
        FeatureMeta(Routes.NEWS, "News", "Headlines from free, public feeds."),
        FeatureMeta(Routes.MARKETS, "Markets", "Indices, stocks, FX, commodities & crypto."),
        FeatureMeta(Routes.WEATHER, "Weather", "Forecast, feels-like & air quality."),
        FeatureMeta(Routes.JARVIS, "Computer", "Your private, on-device assistant — ask it anything."),
        FeatureMeta(Routes.MENU, "Menu", "Every feature, one tap, plain English."),
        FeatureMeta(Routes.ECONOMY, "Economy", "Inflation, GDP & jobs."),
        FeatureMeta(Routes.FUEL, "Fuel & Energy", "Benchmarks & pump prices."),
    )

    val entries: List<FeatureMeta> =
        OFF_MENU + GROUPS.flatMap { g -> g.entries.map { FeatureMeta(it.route, it.label, it.description) } }

    /** Display label for a route key, or the key itself if unknown. */
    fun labelFor(key: String): String = entries.firstOrNull { it.key == key }?.label ?: key
}
