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

    /**
     * Dew point — the better humidity measure, and the less familiar one.
     *
     * Relative humidity is a percentage *of what the air could hold at this temperature*, so eighty
     * percent means something entirely different on a cold morning than on a hot afternoon. Dew
     * point is an absolute figure and does not move when the temperature does.
     */
    fun dewPoint(dewPointC: Double?, unit: String): Explainer? {
        if (dewPointC == null) return null
        val mugginess = WeatherComfort.mugginess(dewPointC) ?: return null
        val shown = if (unit.contains("F")) dewPointC * 9.0 / 5.0 + 32.0 else dewPointC
        val detail = when (mugginess) {
            "Oppressive" -> "Sweat barely evaporates. Exertion is genuinely harder and hotter than the thermometer suggests."
            "Very humid" -> "Sticky and uncomfortable; cooling off takes noticeably longer."
            "Humid" -> "Noticeably muggy, though still workable."
            "Comfortable" -> "About ideal — the air neither drains you nor dries you out."
            "Dry" -> "Pleasant and dry; sweat evaporates readily."
            else -> "Very dry air. Skin, lips and eyes will feel it, and static builds easily."
        }
        return Explainer("Dew point ${shown.roundToInt()}$unit — $mugginess", detail)
    }

    /**
     * Wind gusts, against the mean the forecast usually quotes.
     *
     * The mean is an average over a period; the gust is the peak within it, and the peak is what
     * takes a branch down, pushes a vehicle across a lane, or catches a tent.
     */
    fun gusts(windKmh: Double?, gustKmh: Double?, unit: String, displayGust: Double?): Explainer? {
        if (gustKmh == null || displayGust == null) return null
        val (force, description) = WeatherComfort.beaufort(gustKmh)
        val over = if (windKmh != null && windKmh > 0.0) {
            " That is about ${((gustKmh / windKmh - 1.0) * 100).roundToInt()}% above the mean wind."
        } else {
            ""
        }
        return Explainer(
            "Gusting ${displayGust.roundToInt()} $unit — force $force, $description",
            "A forecast quotes the average wind; the gust is the peak inside it, and the peak is " +
                "what actually moves things.$over",
        )
    }

    /** How far you can see, and what limits it. */
    fun visibility(metres: Double?, imperial: Boolean): Explainer? {
        if (metres == null) return null
        val shown = WeatherUnits.describeVisibility(metres, imperial) ?: return null
        val detail = when {
            metres < 200.0 -> "Dense fog. Driving is hazardous and landmarks disappear at close range."
            metres < 1000.0 -> "Fog or heavy precipitation. Allow far more stopping distance than usual."
            metres < 4000.0 -> "Mist, haze or rain is cutting the view well below normal."
            metres < 10_000.0 -> "Slightly hazy, but everything nearby is plainly visible."
            else -> "Clear. The figure is capped by the model rather than by the air, so this means " +
                "\"nothing in the way\" rather than a measured distance."
        }
        return Explainer("Visibility $shown", detail)
    }

    /**
     * A single pollutant: what it is, where it came from, and what it does.
     *
     * The averaging period is stated because a live reading is one hour and every guideline is a
     * longer average, so the comparison is a direction rather than a verdict — and saying so is the
     * difference between an honest reading and a reassuring one.
     */
    fun pollutant(reading: AirQualityGuide.Reading?): Explainer? {
        if (reading == null) return null
        val p = reading.pollutant
        return Explainer(
            "${p.label} ${reading.value.roundToInt()} µg/m³ — ${AirQualityGuide.describeRatio(reading.ratio)}",
            "${p.source} ${p.effect} Measured against the World Health Organization's 2021 guideline " +
                "of ${p.guideline.roundToInt()} µg/m³, stated as a ${p.averaging}. This reading covers " +
                "one hour, so read it as a direction rather than a verdict.",
        )
    }

    /** Why the two published air-quality indices disagree about identical air. */
    fun aqiScales(euAqi: Double?, usAqi: Double?): Explainer? {
        val detail = AirQualityGuide.scaleGap(euAqi, usAqi) ?: return null
        return Explainer("Two indices, one set of air", detail)
    }

    /** Pollen, with the caveat that makes the number usable. */
    fun pollen(species: String, grainsPerM3: Double?): Explainer? {
        val band = AirQualityGuide.pollenBand(grainsPerM3) ?: return null
        return Explainer(
            "$species pollen ${grainsPerM3!!.roundToInt()} grains/m³ — $band",
            "The count that provokes symptoms differs by species and differs enormously between " +
                "people, so treat this as a magnitude rather than a clinical threshold. The forecast " +
                "model only covers Europe, which is why pollen is absent elsewhere rather than zero.",
        )
    }

    /**
     * Convective available potential energy — how much fuel a thunderstorm would have here.
     *
     * Fuel is not a trigger. High CAPE with nothing to lift the air produces a pleasant afternoon,
     * which is why this is phrased as potential throughout rather than as a forecast.
     */
    fun cape(capeJkg: Double?): Explainer? {
        val summary = WeatherComfort.thunderPotential(capeJkg) ?: return null
        return Explainer(
            "CAPE ${capeJkg!!.roundToInt()} J/kg — $summary",
            "CAPE measures how much energy a rising parcel of air would gain. It is the fuel, not " +
                "the spark: without a front, a sea breeze or heating to set air rising, a high " +
                "figure can pass as a quiet day.",
        )
    }
}
