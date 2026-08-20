package dev.mascwa.pulse.data.weather

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.WeatherUnits
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.data.settings.PrecipUnit
import dev.mascwa.pulse.data.settings.SavedLocation
import dev.mascwa.pulse.data.settings.WeatherPreferences
import dev.mascwa.pulse.data.settings.TemperatureUnit
import dev.mascwa.pulse.data.settings.WindUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

class WeatherRepository(
    private val http: HttpClient,
    private val cache: DiskCache,
    /**
     * The units to ask for and render in.
     *
     * ⚠️ A function returning three values, not a settings object — see [WeatherPreferences]. Read per
     * fetch, so changing the unit in settings takes effect on the next refresh.
     */
    private val preferences: suspend () -> WeatherPreferences,
) {
    private val ttl = 30 * 60 * 1000L

    suspend fun fetch(
        latitude: Double,
        longitude: Double,
        locationName: String,
        force: Boolean,
    ): Fetched<WeatherData> {
        val s = preferences()
        val key = "weather_${"%.3f".format(latitude)}_${"%.3f".format(longitude)}" +
            "_${s.temperatureUnit.apiValue}_${s.windUnit.apiValue}_${s.precipUnit.apiValue}"
        if (!force) {
            cache.read(key, ttl, WeatherData.serializer())?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
        }
        return try {
            val data = coroutineScope {
                val forecastD = async { loadForecast(latitude, longitude, locationName, s) }
                val airD = async { runCatching { loadAir(latitude, longitude) }.getOrNull() }
                forecastD.await().copy(airQuality = airD.await())
            }
            cache.write(key, data, WeatherData.serializer())
            Fetched(data, false)
        } catch (e: Exception) {
            cache.readAny(key, WeatherData.serializer())?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
            throw e
        }
    }

    suspend fun searchLocations(query: String): List<SavedLocation> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=$q&count=10&language=en&format=json"
        val resp = http.getJson(url, OmGeoResponse.serializer())
        return resp.results.map { r ->
            SavedLocation(
                name = listOfNotNull(r.name, r.admin1).distinct().joinToString(", "),
                country = r.country.orEmpty(),
                latitude = r.latitude,
                longitude = r.longitude,
                timezone = r.timezone ?: "auto",
            )
        }
    }

    private suspend fun loadForecast(
        lat: Double, lon: Double, name: String, s: WeatherPreferences,
    ): WeatherData {
        val url = buildString {
            append("https://api.open-meteo.com/v1/forecast")
            append("?latitude=$lat&longitude=$lon")
            append("&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,")
            append("precipitation,weather_code,wind_speed_10m,wind_direction_10m,pressure_msl,cloud_cover,")
            // Same request, no extra round trip: these were always on offer and never asked for.
            append("dew_point_2m,wind_gusts_10m,visibility,surface_pressure,rain,showers,snowfall")
            append("&hourly=temperature_2m,precipitation_probability,weather_code,wind_speed_10m,")
            append("wind_gusts_10m,apparent_temperature,dew_point_2m,uv_index,cape,visibility")
            append("&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,")
            append("precipitation_sum,precipitation_probability_max,wind_speed_10m_max,uv_index_max,")
            append("wind_gusts_10m_max,wind_direction_10m_dominant,apparent_temperature_max,")
            append("apparent_temperature_min,precipitation_hours,sunshine_duration,daylight_duration,snowfall_sum")
            append("&timezone=auto&forecast_days=7")
            append("&temperature_unit=${s.temperatureUnit.apiValue}")
            append("&wind_speed_unit=${s.windUnit.apiValue}")
            append("&precipitation_unit=${s.precipUnit.apiValue}")
        }
        val f = http.getJson(url, OmForecast.serializer())

        // The unit setting is known here and nowhere downstream, so this is where the canonical
        // conversion belongs. Display values below are left exactly as they were: half a dozen
        // consumers read them and none of them should shift.
        val tempUnit = if (s.temperatureUnit == TemperatureUnit.FAHRENHEIT) {
            WeatherUnits.Temperature.FAHRENHEIT
        } else {
            WeatherUnits.Temperature.CELSIUS
        }
        val speedUnit = when (s.windUnit) {
            WindUnit.MPH -> WeatherUnits.Speed.MPH
            WindUnit.MS -> WeatherUnits.Speed.MS
            WindUnit.KNOTS -> WeatherUnits.Speed.KNOTS
            else -> WeatherUnits.Speed.KMH
        }
        // Visibility follows the precipitation family, not the wind one — established by probing
        // the service rather than from its documentation, which says metres either way.
        val distanceUnit = if (s.precipUnit == PrecipUnit.INCH) {
            WeatherUnits.Distance.IMPERIAL
        } else {
            WeatherUnits.Distance.METRIC
        }
        fun degC(v: Double?) = WeatherUnits.toCelsius(v, tempUnit)
        fun kmh(v: Double?) = WeatherUnits.toKmh(v, speedUnit)
        fun metres(v: Double?) = WeatherUnits.toMetres(v, distanceUnit)

        val current = f.current?.let { c ->
            CurrentWeather(
                temperature = c.temperature_2m,
                apparentTemperature = c.apparent_temperature,
                humidity = c.relative_humidity_2m,
                weatherCode = c.weather_code ?: 0,
                isDay = (c.is_day ?: 1) == 1,
                precipitation = c.precipitation,
                windSpeed = c.wind_speed_10m,
                windDirection = c.wind_direction_10m,
                pressure = c.pressure_msl,
                cloudCover = c.cloud_cover,
                dewPoint = c.dew_point_2m,
                windGust = c.wind_gusts_10m,
                visibility = c.visibility,
                surfacePressure = c.surface_pressure,
                rain = c.rain,
                showers = c.showers,
                snowfall = c.snowfall,
                temperatureC = degC(c.temperature_2m),
                dewPointC = degC(c.dew_point_2m),
                windKmh = kmh(c.wind_speed_10m),
                gustKmh = kmh(c.wind_gusts_10m),
                visibilityMetres = metres(c.visibility),
            )
        }

        val hourly = f.hourly?.let { h ->
            h.time.indices.map { i ->
                HourlyPoint(
                    timeIso = h.time[i],
                    temperature = h.temperature_2m.getOrNull(i),
                    precipProbability = h.precipitation_probability.getOrNull(i),
                    weatherCode = h.weather_code.getOrNull(i) ?: 0,
                    windSpeed = h.wind_speed_10m.getOrNull(i),
                    windGust = h.wind_gusts_10m.getOrNull(i),
                    apparentTemperature = h.apparent_temperature.getOrNull(i),
                    dewPoint = h.dew_point_2m.getOrNull(i),
                    uvIndex = h.uv_index.getOrNull(i),
                    capeJkg = h.cape.getOrNull(i),
                    visibility = h.visibility.getOrNull(i),
                    temperatureC = degC(h.temperature_2m.getOrNull(i)),
                    windKmh = kmh(h.wind_speed_10m.getOrNull(i)),
                    gustKmh = kmh(h.wind_gusts_10m.getOrNull(i)),
                )
            }
        }.orEmpty()

        val daily = f.daily?.let { d ->
            d.time.indices.map { i ->
                DailyPoint(
                    dateIso = d.time[i],
                    weatherCode = d.weather_code.getOrNull(i) ?: 0,
                    tempMax = d.temperature_2m_max.getOrNull(i),
                    tempMin = d.temperature_2m_min.getOrNull(i),
                    sunrise = d.sunrise.getOrNull(i),
                    sunset = d.sunset.getOrNull(i),
                    precipitationSum = d.precipitation_sum.getOrNull(i),
                    precipProbabilityMax = d.precipitation_probability_max.getOrNull(i),
                    windMax = d.wind_speed_10m_max.getOrNull(i),
                    uvIndexMax = d.uv_index_max.getOrNull(i),
                    gustMax = d.wind_gusts_10m_max.getOrNull(i),
                    windDirectionDominant = d.wind_direction_10m_dominant.getOrNull(i),
                    apparentMax = d.apparent_temperature_max.getOrNull(i),
                    apparentMin = d.apparent_temperature_min.getOrNull(i),
                    precipitationHours = d.precipitation_hours.getOrNull(i),
                    sunshineSeconds = d.sunshine_duration.getOrNull(i),
                    daylightSeconds = d.daylight_duration.getOrNull(i),
                    snowfallSum = d.snowfall_sum.getOrNull(i),
                    tempMaxC = degC(d.temperature_2m_max.getOrNull(i)),
                    tempMinC = degC(d.temperature_2m_min.getOrNull(i)),
                    windMaxKmh = kmh(d.wind_speed_10m_max.getOrNull(i)),
                    gustMaxKmh = kmh(d.wind_gusts_10m_max.getOrNull(i)),
                )
            }
        }.orEmpty()

        return WeatherData(
            locationName = name,
            latitude = lat,
            longitude = lon,
            timezone = f.timezone,
            current = current,
            hourly = hourly,
            daily = daily,
            airQuality = null,
            tempUnitSymbol = s.temperatureUnit.symbol,
            windUnitSymbol = s.windUnit.symbol,
            precipUnitSymbol = s.precipUnit.symbol,
        )
    }

    private suspend fun loadAir(lat: Double, lon: Double): AirQuality? {
        // The two index numbers were all this asked for, while the pollutants they are computed
        // from — and the pollen — came back in the same round trip for free. An index hides the
        // only question worth asking, which is what is actually in the air.
        val url = "https://air-quality-api.open-meteo.com/v1/air-quality" +
            "?latitude=$lat&longitude=$lon&current=european_aqi,us_aqi,pm10,pm2_5," +
            "carbon_monoxide,nitrogen_dioxide,sulphur_dioxide,ozone,dust," +
            "alder_pollen,birch_pollen,grass_pollen,mugwort_pollen,olive_pollen,ragweed_pollen" +
            "&timezone=auto"
        val resp = http.getJson(url, OmAir.serializer())
        return resp.current?.let { c ->
            AirQuality(
                europeanAqi = c.european_aqi,
                usAqi = c.us_aqi,
                pm10 = c.pm10,
                pm25 = c.pm2_5,
                carbonMonoxide = c.carbon_monoxide,
                nitrogenDioxide = c.nitrogen_dioxide,
                sulphurDioxide = c.sulphur_dioxide,
                ozone = c.ozone,
                dust = c.dust,
                // Absent species are dropped rather than carried as zero: outside Europe the feed
                // returns null for every one of them, and a wall of "0" reads as a measurement.
                pollen = listOfNotNull(
                    c.alder_pollen?.let { PollenCount("Alder", it) },
                    c.birch_pollen?.let { PollenCount("Birch", it) },
                    c.grass_pollen?.let { PollenCount("Grass", it) },
                    c.mugwort_pollen?.let { PollenCount("Mugwort", it) },
                    c.olive_pollen?.let { PollenCount("Olive", it) },
                    c.ragweed_pollen?.let { PollenCount("Ragweed", it) },
                ),
            )
        }
    }
}
