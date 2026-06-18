package dev.mascwa.pulse.core.telemetry

import kotlin.math.roundToInt

/**
 * Plain-English explanations of the less-obvious weather metrics — "feels like", humidity, UV, air
 * quality, pressure — reusing the shared [Explainer] model. Pure + CI-tested. Returns null when the
 * value is missing (caller hides the affordance). UV/AQI/humidity/pressure are unit-stable; the
 * "feels like" gap is read relative to the actual temperature, so it's unit-agnostic in direction.
 */
object WeatherExplainers {

    /** Why "feels like" differs from the air temperature. [unit] is the degree symbol (e.g. "°C"). */
    fun feelsLike(actual: Double?, feels: Double?, unit: String): Explainer? {
        if (actual == null || feels == null) return null
        val diff = feels - actual
        val detail = when {
            diff <= -3 -> "Wind chill and dry air make it feel colder than the actual air temperature."
            diff >= 3 -> "Humidity (and strong sun) make it feel hotter than the actual air temperature."
            else -> "Close to the air temperature — little wind-chill or humidity effect right now."
        }
        return Explainer("Feels like ${feels.roundToInt()}$unit · air ${actual.roundToInt()}$unit", detail)
    }

    /** Relative humidity %. */
    fun humidity(pct: Double?): Explainer? {
        if (pct == null) return null
        val band = when {
            pct < 30 -> "Dry" to "Skin and airways can feel dry; static is more likely."
            pct < 60 -> "Comfortable" to "A comfortable range for most people."
            pct < 80 -> "Humid" to "Sweat evaporates slowly, so warm air feels hotter and muggier."
            else -> "Very humid" to "Air is near saturation — sticky, and heat feels significantly worse."
        }
        return Explainer("Humidity ${pct.roundToInt()}% — ${band.first}", band.second)
    }

    /** UV index (0..11+). */
    fun uvIndex(uv: Double?): Explainer? {
        if (uv == null) return null
        val band = when {
            uv < 3 -> "Low" to "Minimal risk — no protection needed for most."
            uv < 6 -> "Moderate" to "Take care near midday; hat and sunscreen if out a while."
            uv < 8 -> "High" to "Burns in ~30 min — sunscreen, hat and shade midday."
            uv < 11 -> "Very high" to "Burns fast — minimise midday sun; SPF 30+, cover up."
            else -> "Extreme" to "Burns in minutes — avoid midday sun; full protection essential."
        }
        return Explainer("UV ${uv.roundToInt()} — ${band.first}", band.second)
    }

    /** Air Quality Index — prefers US AQI, falls back to the European scale. */
    fun airQuality(usAqi: Double?, euAqi: Double?): Explainer? {
        usAqi?.let {
            val band = when {
                it <= 50 -> "Good" to "Air quality is healthy; enjoy outdoor activity."
                it <= 100 -> "Moderate" to "Fine for most; unusually sensitive people may notice it."
                it <= 150 -> "Unhealthy for sensitive groups" to "Sensitive groups should ease up on hard outdoor exertion."
                it <= 200 -> "Unhealthy" to "Everyone may feel effects; limit prolonged outdoor exertion."
                it <= 300 -> "Very unhealthy" to "Health alert — avoid outdoor exertion; mask/indoors if sensitive."
                else -> "Hazardous" to "Emergency conditions — stay indoors with air filtered."
            }
            return Explainer("US AQI ${it.roundToInt()} — ${band.first}", band.second)
        }
        euAqi?.let {
            val band = when {
                it <= 20 -> "Good" to "Clean air; no precautions needed."
                it <= 40 -> "Fair" to "Air quality is acceptable for most."
                it <= 60 -> "Moderate" to "Sensitive people may want to limit heavy outdoor exertion."
                it <= 80 -> "Poor" to "Reduce prolonged or heavy outdoor exertion."
                it <= 100 -> "Very poor" to "Limit time outdoors, especially if sensitive."
                else -> "Extremely poor" to "Avoid outdoor exertion; keep windows shut."
            }
            return Explainer("EU AQI ${it.roundToInt()} — ${band.first}", band.second)
        }
        return null
    }

    /** Surface pressure in hPa (sea-level ~1013). A single reading hints at conditions; the trend matters more. */
    fun pressure(hPa: Double?): Explainer? {
        if (hPa == null) return null
        val band = when {
            hPa < 1000 -> "Low" to "Low pressure favours clouds, wind and rain — often unsettled."
            hPa <= 1022 -> "Normal" to "Near the average (~1013 hPa). A falling trend hints at worsening weather."
            else -> "High" to "High pressure favours calm, clear, settled weather."
        }
        return Explainer("Pressure ${hPa.roundToInt()} hPa — ${band.first}", band.second)
    }
}
