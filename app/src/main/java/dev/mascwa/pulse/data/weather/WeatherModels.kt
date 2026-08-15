package dev.mascwa.pulse.data.weather

import kotlinx.serialization.Serializable

@Serializable
data class CurrentWeather(
    val temperature: Double?,
    val apparentTemperature: Double?,
    val humidity: Double?,
    val weatherCode: Int,
    val isDay: Boolean,
    val precipitation: Double?,
    val windSpeed: Double?,
    val windDirection: Double?,
    val pressure: Double?,
    val cloudCover: Double?,
    /** All defaulted, so a cache blob written by an older build still decodes. */
    val dewPoint: Double? = null,
    val windGust: Double? = null,
    /**
     * Horizontal visibility in whatever distance unit the request asked for — metres under metric,
     * feet under imperial. Not named "meters" for exactly that reason: the provider switches it
     * with the precipitation unit even though its documentation says otherwise.
     */
    val visibility: Double? = null,
    val surfacePressure: Double? = null,
    val rain: Double? = null,
    val showers: Double? = null,
    val snowfall: Double? = null,
)

@Serializable
data class HourlyPoint(
    val timeIso: String,
    val temperature: Double?,
    val precipProbability: Int?,
    val weatherCode: Int,
    val windSpeed: Double?,
    val windGust: Double? = null,
    val apparentTemperature: Double? = null,
    val dewPoint: Double? = null,
    val uvIndex: Double? = null,
    val capeJkg: Double? = null,
    /** Metres or feet, following the request's unit — see [CurrentWeather.visibility]. */
    val visibility: Double? = null,
)

@Serializable
data class DailyPoint(
    val dateIso: String,
    val weatherCode: Int,
    val tempMax: Double?,
    val tempMin: Double?,
    val sunrise: String?,
    val sunset: String?,
    val precipitationSum: Double?,
    val precipProbabilityMax: Int?,
    val windMax: Double?,
    val uvIndexMax: Double?,
    val gustMax: Double? = null,
    val windDirectionDominant: Double? = null,
    val apparentMax: Double? = null,
    val apparentMin: Double? = null,
    val precipitationHours: Double? = null,
    val sunshineSeconds: Double? = null,
    val daylightSeconds: Double? = null,
    val snowfallSum: Double? = null,
)

@Serializable
data class AirQuality(
    val europeanAqi: Double?,
    val usAqi: Double?,
    val pm10: Double?,
    val pm25: Double?,
)

@Serializable
data class WeatherData(
    val locationName: String,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val current: CurrentWeather?,
    val hourly: List<HourlyPoint>,
    val daily: List<DailyPoint>,
    val airQuality: AirQuality?,
    val tempUnitSymbol: String,
    val windUnitSymbol: String,
    val precipUnitSymbol: String,
    val updatedEpochMs: Long = System.currentTimeMillis(),
)
