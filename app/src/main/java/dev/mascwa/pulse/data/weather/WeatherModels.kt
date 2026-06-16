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
)

@Serializable
data class HourlyPoint(
    val timeIso: String,
    val temperature: Double?,
    val precipProbability: Int?,
    val weatherCode: Int,
    val windSpeed: Double?,
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
