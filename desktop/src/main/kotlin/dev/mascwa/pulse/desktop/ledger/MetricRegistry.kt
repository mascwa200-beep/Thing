package dev.mascwa.pulse.desktop.ledger

import dev.mascwa.pulse.data.markets.Quote
import dev.mascwa.pulse.data.orbital.OrbitalData
import dev.mascwa.pulse.data.radar.ContactKind
import dev.mascwa.pulse.data.radar.RadarData
import dev.mascwa.pulse.data.safety.SafetyResult
import dev.mascwa.pulse.data.settings.WatchItem
import dev.mascwa.pulse.data.settings.WatchType
import dev.mascwa.pulse.data.space.SpaceWeather
import dev.mascwa.pulse.data.weather.WeatherData
import dev.mascwa.pulse.desktop.Screen
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Everything the long watch records, declared in one place.
 *
 * One line adds a metric. The collector reads its cadence from here, the ledger stores it under the id
 * from here, and the wall labels and routes it from here — so the three can never disagree about what
 * is being measured.
 *
 * ## What is deliberately NOT recorded, and why
 *
 * Leaving things out is most of the design work here; a ledger full of series that cannot be scored
 * honestly is worse than a smaller one.
 *
 * - ⚠️ **Anything in a user-toggleable unit.** Only the canonical companions (`temperatureC`, `windKmh`,
 *   `visibilityMetres`, …) are recorded. Flip the Fahrenheit switch and a display-unit series jumps
 *   thirty units overnight, and every baseline built on it becomes nonsense with nothing to say so.
 * - ⚠️ **Precipitation**, which is both unit-toggleable and mostly zero. A series that is zero most of
 *   the time has a median and a MAD of zero, so the robust score cannot say anything about it.
 * - ⚠️ **The economic indicators.** They are *annual* figures. Recording one daily manufactures 365
 *   identical samples a year, which drives the spread to zero and makes the one real change of the year
 *   look like a record. No baseline is reachable in a human lifetime, so this is not a novelty question
 *   and pretending otherwise would be the dishonest kind of feature.
 * - **Social trend counts**, because the top tag is a different tag every hour — the identity moves, so
 *   consecutive readings are not measurements of the same thing.
 * - **Overpass place counts**, which barely change, and cost an expensive query to learn nothing.
 * - **Moon illumination**, which is perfectly periodic. An anomaly detector on a sine wave is a
 *   thermometer for the season.
 *
 * ## ⚠️ The market basket is declared here, not read from the watch list
 *
 * Two reasons, and the second is the important one. The desktop's watch list is **empty by default** and
 * has no settings surface. And a series is only meaningful if it is the same measurement over time — tie
 * the recorded set to something the user edits and instruments appear and vanish from their own history.
 */
object MetricRegistry {

    /**
     * How often a domain is worth asking, and where its screen lives.
     *
     * ⚠️ Cadence is per domain rather than one global tick. Space weather moves in minutes; a market
     * close does not change between two Tuesdays in August. Asking everything every quarter hour would
     * be both wasteful and, against free public APIs, rude.
     */
    enum class Domain(
        val label: String,
        val cadenceMs: Long,
        val screen: Screen?,
        val locationBound: Boolean,
    ) {
        WEATHER("Weather", 30 * MINUTE, Screen.WEATHER, locationBound = true),
        AIR("Air quality", 30 * MINUTE, Screen.WEATHER, locationBound = true),
        SPACE("Space weather", 15 * MINUTE, Screen.SPACE_WEATHER, locationBound = false),
        MARKETS("Markets", 60 * MINUTE, Screen.MARKETS, locationBound = false),
        AVIATION("Aviation", 15 * MINUTE, Screen.RADAR, locationBound = true),
        SAFETY("Nearby danger", 15 * MINUTE, Screen.SAFETY, locationBound = true),
        ORBITAL("Near space", 60 * MINUTE, Screen.OBSERVATORY, locationBound = false),
    }

    /**
     * @param diurnal whether the metric swings with time of day, so it must be judged against its own
     *   hour. ⚠️ Declared rather than measured: a wrong silent guess is worse than a decision made by
     *   whoever added the metric. Comparing a 3 a.m. aircraft count against a whole-day distribution
     *   flags every night; bucketing the Kp index divides its sample by 24 for nothing.
     * @param scored whether it belongs on the wall at all. ⚠️ A **price level is non-stationary** — the
     *   S&P drifts upward for years, so "highest on record" is true most months and means nothing. The
     *   level is recorded for the chart; the daily percentage move is what gets ranked.
     */
    data class Spec(
        val id: String,
        val label: String,
        val domain: Domain,
        val unit: String,
        val diurnal: Boolean,
        val scored: Boolean = true,
        val decimals: Int = 1,
    ) {
        /**
         * Where this metric's series lives in the ledger.
         *
         * ⚠️ A location-bound metric carries the place in its key. Move more than about ten kilometres
         * and the weather outside is a different measurement; without this the old city's readings would
         * silently become part of the new city's baseline. Keying it instead means a move starts a clean
         * series — and moving back finds the old one still there.
         */
        fun key(placeKey: String?): String =
            if (domain.locationBound && !placeKey.isNullOrBlank()) "$id.$placeKey" else id
    }

    // ------------------------------------------------------------------ the declared set

    val WEATHER: List<Spec> = listOf(
        Spec("weather.temp", "Temperature", Domain.WEATHER, "°C", diurnal = true),
        Spec("weather.dew-point", "Dew point", Domain.WEATHER, "°C", diurnal = true),
        Spec("weather.humidity", "Humidity", Domain.WEATHER, "%", diurnal = true, decimals = 0),
        Spec("weather.pressure", "Surface pressure", Domain.WEATHER, "hPa", diurnal = false),
        Spec("weather.wind", "Wind speed", Domain.WEATHER, "km/h", diurnal = true),
        Spec("weather.gust", "Wind gust", Domain.WEATHER, "km/h", diurnal = true),
        Spec("weather.cloud", "Cloud cover", Domain.WEATHER, "%", diurnal = true, decimals = 0),
        Spec("weather.visibility", "Visibility", Domain.WEATHER, "m", diurnal = true, decimals = 0),
    )

    val AIR: List<Spec> = listOf(
        Spec("air.pm25", "PM2.5", Domain.AIR, "µg/m³", diurnal = true),
        Spec("air.pm10", "PM10", Domain.AIR, "µg/m³", diurnal = true),
        Spec("air.ozone", "Ozone", Domain.AIR, "µg/m³", diurnal = true),
        Spec("air.no2", "Nitrogen dioxide", Domain.AIR, "µg/m³", diurnal = true),
        Spec("air.so2", "Sulphur dioxide", Domain.AIR, "µg/m³", diurnal = true),
        Spec("air.co", "Carbon monoxide", Domain.AIR, "µg/m³", diurnal = true, decimals = 0),
        Spec("air.aqi-eu", "European AQI", Domain.AIR, "", diurnal = true, decimals = 0),
        Spec("air.aqi-us", "US AQI", Domain.AIR, "", diurnal = true, decimals = 0),
    )

    val SPACE: List<Spec> = listOf(
        // None of these are diurnal: the Sun does not care which way the Earth is facing.
        Spec("space.kp", "Kp index", Domain.SPACE, "", diurnal = false),
        Spec("space.solar-wind", "Solar wind speed", Domain.SPACE, "km/s", diurnal = false, decimals = 0),
        Spec("space.bz", "IMF Bz", Domain.SPACE, "nT", diurnal = false),
        Spec("space.xray", "X-ray flux", Domain.SPACE, "W/m²", diurnal = false, decimals = 9),
        Spec("space.protons", "Proton flux", Domain.SPACE, "pfu", diurnal = false, decimals = 2),
        Spec("space.f107", "F10.7 flux", Domain.SPACE, "sfu", diurnal = false, decimals = 0),
        Spec("space.aurora", "Aurora probability", Domain.SPACE, "%", diurnal = false, decimals = 0),
    )

    val AVIATION: List<Spec> = listOf(
        Spec("aviation.aircraft", "Aircraft overhead", Domain.AVIATION, "", diurnal = true, decimals = 0),
        Spec("aviation.altitude", "Mean aircraft altitude", Domain.AVIATION, "m", diurnal = true, decimals = 0),
    )

    val SAFETY: List<Spec> = listOf(
        Spec("safety.incidents", "Incidents nearby", Domain.SAFETY, "", diurnal = false, decimals = 0),
    )

    val ORBITAL: List<Spec> = listOf(
        Spec("orbital.hazardous", "Hazardous near-Earth objects", Domain.ORBITAL, "", diurnal = false, decimals = 0),
    )

    /**
     * The instruments recorded, and the metric ids each produces.
     *
     * ⚠️ Ten instruments at hourly cadence is 240 requests a day to one provider. This repo has already
     * had its IP durably banned by that provider from an eleven-request burst, so the basket is
     * deliberately small, the cadence deliberately hourly, and every fetch goes through the
     * repository's own `yahooGate`.
     *
     * ⚠️ Marked diurnal because the recorded change is the day's move *so far*, which necessarily grows
     * through a session and sits still overnight. Judged against a whole day, every evening reading
     * would look extreme and every small-hours one flat. Judged against its own hour, it is comparable.
     */
    val INSTRUMENTS: List<WatchItem> = listOf(
        WatchItem("^spx", "S&P 500", WatchType.INDEX),
        WatchItem("^ndq", "Nasdaq 100", WatchType.INDEX),
        WatchItem("^ftm", "FTSE 100", WatchType.INDEX),
        WatchItem("^dax", "DAX", WatchType.INDEX),
        WatchItem("^vix", "Volatility index", WatchType.INDEX),
        WatchItem("cb.f", "Brent crude", WatchType.COMMODITY),
        WatchItem("cl.f", "WTI crude", WatchType.COMMODITY),
        WatchItem("ng.f", "Natural gas", WatchType.COMMODITY),
        WatchItem("gc.f", "Gold", WatchType.COMMODITY),
        WatchItem("eurusd", "Euro / US dollar", WatchType.FOREX),
    )

    /** `^spx` is not a legal ledger id — the alphabet is deliberately narrow. `spx` is. */
    fun slugOf(instrumentId: String): String =
        instrumentId.removePrefix("^").replace('.', '-').lowercase()

    val MARKETS: List<Spec> = INSTRUMENTS.flatMap { item ->
        val slug = slugOf(item.id)
        listOf(
            Spec("market.$slug.move", "${item.label} — daily move", Domain.MARKETS, "%", diurnal = true, decimals = 2),
            // Recorded for the chart, never ranked: see [Spec.scored].
            Spec("market.$slug.price", item.label, Domain.MARKETS, "", diurnal = true, scored = false, decimals = 2),
        )
    }

    val ALL: List<Spec> = WEATHER + AIR + SPACE + MARKETS + AVIATION + SAFETY + ORBITAL

    val BY_ID: Map<String, Spec> = ALL.associateBy { it.id }

    /** Specs belonging to one domain, which is how the collector walks them. */
    fun of(domain: Domain): List<Spec> = ALL.filter { it.domain == domain }

    /** The base id behind a stored key, undoing [Spec.key]'s place suffix. Null when unrecognised. */
    fun specForKey(key: String): Spec? =
        BY_ID[key] ?: ALL.firstOrNull { it.domain.locationBound && key.startsWith("${it.id}.") }

    // ------------------------------------------------------------------ extractors

    /**
     * Extractors are **pure functions of a repository's model**, which is what makes the registry
     * testable without a network. `MetricRegistryTest` feeds each one a fully populated model and
     * asserts that every metric declared above actually comes out — the guard against the defect this
     * repo has shipped at least six times, where something is declared and then never fed.
     */
    fun fromWeather(d: WeatherData): List<Pair<String, Double>> {
        val c = d.current ?: return emptyList()
        val a = d.airQuality
        return buildList {
            put("weather.temp", c.temperatureC)
            put("weather.dew-point", c.dewPointC)
            put("weather.humidity", c.humidity)
            put("weather.pressure", c.surfacePressure ?: c.pressure)
            put("weather.wind", c.windKmh)
            put("weather.gust", c.gustKmh)
            put("weather.cloud", c.cloudCover)
            put("weather.visibility", c.visibilityMetres)
            if (a != null) {
                put("air.pm25", a.pm25)
                put("air.pm10", a.pm10)
                put("air.ozone", a.ozone)
                put("air.no2", a.nitrogenDioxide)
                put("air.so2", a.sulphurDioxide)
                put("air.co", a.carbonMonoxide)
                put("air.aqi-eu", a.europeanAqi)
                put("air.aqi-us", a.usAqi)
            }
        }
    }

    fun fromSpace(s: SpaceWeather): List<Pair<String, Double>> = buildList {
        put("space.kp", s.kp)
        put("space.solar-wind", s.solarWindSpeed)
        put("space.bz", s.bz)
        put("space.xray", s.xrayFlux)
        put("space.protons", s.protonFlux)
        put("space.f107", s.f107)
        put("space.aurora", s.auroraProbabilityPct?.toDouble())
    }

    fun fromQuotes(quotes: List<Quote>): List<Pair<String, Double>> = buildList {
        quotes.forEach { q ->
            val slug = slugOf(q.id)
            put("market.$slug.move", q.changePercent)
            put("market.$slug.price", q.price)
        }
    }

    fun fromRadar(r: RadarData): List<Pair<String, Double>> = buildList {
        // `kind` is stored as the enum's name, not the enum — the same comparison the repository and
        // the radar screen already make.
        val aircraft = r.contacts.filter { it.kind == ContactKind.AIRCRAFT.name }
        put("aviation.aircraft", aircraft.size.toDouble())
        val altitudes = aircraft.mapNotNull { it.altitudeM }.filter { it.isFinite() }
        // Zero aircraft is a real reading; a mean altitude of nothing is not.
        if (altitudes.isNotEmpty()) put("aviation.altitude", altitudes.average())
    }

    fun fromSafety(s: SafetyResult): List<Pair<String, Double>> =
        listOf("safety.incidents" to s.incidents.size.toDouble())

    fun fromOrbital(o: OrbitalData): List<Pair<String, Double>> =
        listOf("orbital.hazardous" to o.neoHazardousCount.toDouble())

    /**
     * A short, stable name for a coordinate, at about ten kilometres.
     *
     * Tenths of a degree is roughly 11 km north-south, which is the scale at which the weather outside
     * genuinely differs. Finer would start a new series every time a geolocation lookup wobbled.
     */
    fun placeKey(lat: Double, lon: Double): String {
        val ns = if (lat >= 0) "n" else "s"
        val ew = if (lon >= 0) "e" else "w"
        return "$ns${(abs(lat) * 10).roundToInt()}-$ew${(abs(lon) * 10).roundToInt()}"
    }

    private const val MINUTE = 60_000L

    private fun MutableList<Pair<String, Double>>.put(id: String, v: Double?) {
        if (v != null && v.isFinite()) add(id to v)
    }
}
