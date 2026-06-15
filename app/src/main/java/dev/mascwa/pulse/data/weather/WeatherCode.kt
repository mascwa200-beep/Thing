package dev.mascwa.pulse.data.weather

/** Maps WMO weather-interpretation codes to a description and severity. */
object WeatherCode {

    data class Info(val description: String, val severe: Boolean)

    fun describe(code: Int): String = info(code).description

    fun isSevere(code: Int): Boolean = info(code).severe

    fun info(code: Int): Info = when (code) {
        0 -> Info("Clear sky", false)
        1 -> Info("Mainly clear", false)
        2 -> Info("Partly cloudy", false)
        3 -> Info("Overcast", false)
        45, 48 -> Info("Fog", false)
        51 -> Info("Light drizzle", false)
        53 -> Info("Drizzle", false)
        55 -> Info("Dense drizzle", false)
        56, 57 -> Info("Freezing drizzle", true)
        61 -> Info("Light rain", false)
        63 -> Info("Rain", false)
        65 -> Info("Heavy rain", true)
        66, 67 -> Info("Freezing rain", true)
        71 -> Info("Light snow", false)
        73 -> Info("Snow", false)
        75 -> Info("Heavy snow", true)
        77 -> Info("Snow grains", false)
        80 -> Info("Light showers", false)
        81 -> Info("Showers", false)
        82 -> Info("Violent showers", true)
        85, 86 -> Info("Snow showers", true)
        95 -> Info("Thunderstorm", true)
        96, 99 -> Info("Thunderstorm w/ hail", true)
        else -> Info("—", false)
    }

    /** A unicode glyph for compact rows where a full icon is overkill. */
    fun emoji(code: Int, isDay: Boolean = true): String = when (code) {
        0 -> if (isDay) "☀️" else "🌙"
        1, 2 -> if (isDay) "⛅" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..57 -> "🌦️"
        in 61..67 -> "🌧️"
        in 71..77 -> "❄️"
        in 80..82 -> "🌧️"
        85, 86 -> "🌨️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }
}
