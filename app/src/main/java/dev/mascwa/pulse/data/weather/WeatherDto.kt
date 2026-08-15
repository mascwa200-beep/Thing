package dev.mascwa.pulse.data.weather

import kotlinx.serialization.Serializable

/** Open-Meteo forecast response (field names match the JSON; unknowns ignored). */
@Serializable
data class OmForecast(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val timezone: String = "",
    val current: OmCurrent? = null,
    val hourly: OmHourly? = null,
    val daily: OmDaily? = null,
)

@Serializable
data class OmCurrent(
    val time: String? = null,
    val temperature_2m: Double? = null,
    val relative_humidity_2m: Double? = null,
    val apparent_temperature: Double? = null,
    val is_day: Int? = null,
    val precipitation: Double? = null,
    val weather_code: Int? = null,
    val wind_speed_10m: Double? = null,
    val wind_direction_10m: Double? = null,
    val pressure_msl: Double? = null,
    val cloud_cover: Double? = null,
    // Everything below was already on offer from the same request and simply never asked for.
    val dew_point_2m: Double? = null,
    val wind_gusts_10m: Double? = null,
    /**
     * Horizontal visibility, in **metres or feet depending on the unit request** — verified by
     * probing the same place twice: 25240.0 metric, 82808.4 imperial, which is the same distance.
     * The documentation says metres; the service does not agree when asked in inches.
     */
    val visibility: Double? = null,
    val surface_pressure: Double? = null,
    val rain: Double? = null,
    val showers: Double? = null,
    val snowfall: Double? = null,
)

@Serializable
data class OmHourly(
    val time: List<String> = emptyList(),
    val temperature_2m: List<Double?> = emptyList(),
    val precipitation_probability: List<Int?> = emptyList(),
    val weather_code: List<Int?> = emptyList(),
    val wind_speed_10m: List<Double?> = emptyList(),
    val wind_gusts_10m: List<Double?> = emptyList(),
    val apparent_temperature: List<Double?> = emptyList(),
    val dew_point_2m: List<Double?> = emptyList(),
    val uv_index: List<Double?> = emptyList(),
    /** Convective available potential energy, J/kg — how much fuel a thunderstorm would have. */
    val cape: List<Double?> = emptyList(),
    val visibility: List<Double?> = emptyList(),
)

@Serializable
data class OmDaily(
    val time: List<String> = emptyList(),
    val weather_code: List<Int?> = emptyList(),
    val temperature_2m_max: List<Double?> = emptyList(),
    val temperature_2m_min: List<Double?> = emptyList(),
    val sunrise: List<String> = emptyList(),
    val sunset: List<String> = emptyList(),
    val precipitation_sum: List<Double?> = emptyList(),
    val precipitation_probability_max: List<Int?> = emptyList(),
    val wind_speed_10m_max: List<Double?> = emptyList(),
    val uv_index_max: List<Double?> = emptyList(),
    val wind_gusts_10m_max: List<Double?> = emptyList(),
    val wind_direction_10m_dominant: List<Double?> = emptyList(),
    val apparent_temperature_max: List<Double?> = emptyList(),
    val apparent_temperature_min: List<Double?> = emptyList(),
    /** Hours of the day with measurable precipitation — how long it rains, not just how much. */
    val precipitation_hours: List<Double?> = emptyList(),
    /** Seconds. Sunshine is what actually reaches the ground; daylight is what the geometry allows. */
    val sunshine_duration: List<Double?> = emptyList(),
    val daylight_duration: List<Double?> = emptyList(),
    val snowfall_sum: List<Double?> = emptyList(),
)

@Serializable
data class OmGeoResponse(val results: List<OmGeoResult> = emptyList())

@Serializable
data class OmGeoResult(
    val id: Long = 0,
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val country: String? = null,
    val country_code: String? = null,
    val admin1: String? = null,
    val timezone: String? = null,
)

@Serializable
data class OmAir(val current: OmAirCurrent? = null)

@Serializable
data class OmAirCurrent(
    val european_aqi: Double? = null,
    val us_aqi: Double? = null,
    val pm10: Double? = null,
    val pm2_5: Double? = null,
    // All µg/m³, all in the same response the two index numbers already came from.
    val carbon_monoxide: Double? = null,
    val nitrogen_dioxide: Double? = null,
    val sulphur_dioxide: Double? = null,
    val ozone: Double? = null,
    val dust: Double? = null,
    // grains/m³, and null everywhere outside the European model domain — probed and confirmed, so
    // the screen must be able to say nothing rather than show a row of zeroes.
    val alder_pollen: Double? = null,
    val birch_pollen: Double? = null,
    val grass_pollen: Double? = null,
    val mugwort_pollen: Double? = null,
    val olive_pollen: Double? = null,
    val ragweed_pollen: Double? = null,
)
