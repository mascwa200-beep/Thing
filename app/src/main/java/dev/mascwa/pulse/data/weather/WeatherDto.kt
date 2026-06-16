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
)

@Serializable
data class OmHourly(
    val time: List<String> = emptyList(),
    val temperature_2m: List<Double?> = emptyList(),
    val precipitation_probability: List<Int?> = emptyList(),
    val weather_code: List<Int?> = emptyList(),
    val wind_speed_10m: List<Double?> = emptyList(),
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
)
