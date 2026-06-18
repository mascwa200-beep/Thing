package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherExplainersTest {

    @Test
    fun feelsLikeNullWhenMissing() {
        assertNull(WeatherExplainers.feelsLike(null, 10.0, "°C"))
        assertNull(WeatherExplainers.feelsLike(10.0, null, "°C"))
    }

    @Test
    fun feelsLikeColderWarmerSame() {
        assertTrue(WeatherExplainers.feelsLike(10.0, 4.0, "°C")!!.detail.contains("colder"))
        assertTrue(WeatherExplainers.feelsLike(20.0, 26.0, "°C")!!.detail.contains("hotter"))
        assertTrue(WeatherExplainers.feelsLike(20.0, 21.0, "°C")!!.detail.contains("Close to"))
    }

    @Test
    fun humidityBands() {
        assertTrue(WeatherExplainers.humidity(20.0)!!.headline.contains("Dry"))
        assertTrue(WeatherExplainers.humidity(45.0)!!.headline.contains("Comfortable"))
        assertTrue(WeatherExplainers.humidity(70.0)!!.headline.contains("Humid"))
        assertTrue(WeatherExplainers.humidity(90.0)!!.headline.contains("Very humid"))
    }

    @Test
    fun uvBands() {
        assertTrue(WeatherExplainers.uvIndex(1.0)!!.headline.contains("Low"))
        assertTrue(WeatherExplainers.uvIndex(4.0)!!.headline.contains("Moderate"))
        assertTrue(WeatherExplainers.uvIndex(7.0)!!.headline.contains("High"))
        assertTrue(WeatherExplainers.uvIndex(9.0)!!.headline.contains("Very high"))
        assertTrue(WeatherExplainers.uvIndex(12.0)!!.headline.contains("Extreme"))
    }

    @Test
    fun aqiPrefersUsThenEu() {
        assertTrue(WeatherExplainers.airQuality(40.0, 70.0)!!.headline.contains("US AQI"))
        assertTrue(WeatherExplainers.airQuality(null, 15.0)!!.headline.contains("EU AQI"))
        assertNull(WeatherExplainers.airQuality(null, null))
    }

    @Test
    fun aqiUsBands() {
        assertTrue(WeatherExplainers.airQuality(40.0, null)!!.headline.contains("Good"))
        assertTrue(WeatherExplainers.airQuality(180.0, null)!!.headline.contains("Unhealthy"))
        assertTrue(WeatherExplainers.airQuality(350.0, null)!!.headline.contains("Hazardous"))
    }

    @Test
    fun pressureBands() {
        assertTrue(WeatherExplainers.pressure(990.0)!!.headline.contains("Low"))
        assertTrue(WeatherExplainers.pressure(1013.0)!!.headline.contains("Normal"))
        assertTrue(WeatherExplainers.pressure(1030.0)!!.headline.contains("High"))
        assertNull(WeatherExplainers.pressure(null))
    }
}
