package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherUnitsTest {

    @Test fun canonicalUnitsPassStraightThrough() {
        assertEquals(21.5, WeatherUnits.toCelsius(21.5, WeatherUnits.Temperature.CELSIUS)!!, 0.0)
        assertEquals(30.0, WeatherUnits.toKmh(30.0, WeatherUnits.Speed.KMH)!!, 0.0)
        assertEquals(9000.0, WeatherUnits.toMetres(9000.0, WeatherUnits.Distance.METRIC)!!, 0.0)
    }

    @Test fun temperatureConvertsAtTheFixedPoints() {
        assertEquals(0.0, WeatherUnits.toCelsius(32.0, WeatherUnits.Temperature.FAHRENHEIT)!!, 1e-9)
        assertEquals(100.0, WeatherUnits.toCelsius(212.0, WeatherUnits.Temperature.FAHRENHEIT)!!, 1e-9)
        // The one place the two scales agree.
        assertEquals(-40.0, WeatherUnits.toCelsius(-40.0, WeatherUnits.Temperature.FAHRENHEIT)!!, 1e-9)
    }

    @Test fun everySpeedUnitLandsOnTheSameGround() {
        // 100 km/h expressed four ways must come back as 100 km/h.
        assertEquals(100.0, WeatherUnits.toKmh(100.0, WeatherUnits.Speed.KMH)!!, 1e-9)
        assertEquals(100.0, WeatherUnits.toKmh(100.0 / 1.609344, WeatherUnits.Speed.MPH)!!, 1e-9)
        assertEquals(100.0, WeatherUnits.toKmh(100.0 / 3.6, WeatherUnits.Speed.MS)!!, 1e-9)
        assertEquals(100.0, WeatherUnits.toKmh(100.0 / 1.852, WeatherUnits.Speed.KNOTS)!!, 1e-9)
    }

    @Test fun theVisibilityFactorIsTheOneTheServiceActuallyUses() {
        // Not a textbook constant for its own sake. The forecast service documents visibility as
        // metres and returns feet under an imperial request: the same place at the same moment
        // came back 25240.0 and 82808.4, which is this ratio. Pinned so the discovery survives.
        assertEquals(82808.4, 25240.0 * WeatherUnits.FEET_PER_METRE, 0.1)
        assertEquals(25240.0, WeatherUnits.toMetres(82808.4, WeatherUnits.Distance.IMPERIAL)!!, 0.1)
    }

    @Test fun nullAndNonsenseSurviveWithoutBecomingNumbers() {
        assertNull(WeatherUnits.toCelsius(null, WeatherUnits.Temperature.FAHRENHEIT))
        assertNull(WeatherUnits.toKmh(null, WeatherUnits.Speed.MPH))
        assertNull(WeatherUnits.toMetres(null, WeatherUnits.Distance.IMPERIAL))
        assertNull(WeatherUnits.toCelsius(Double.NaN, WeatherUnits.Temperature.CELSIUS))
        assertNull(WeatherUnits.toKmh(Double.POSITIVE_INFINITY, WeatherUnits.Speed.KMH))
    }

    @Test fun convertingFeedsTheIndicesTheNumbersTheyExpect() {
        // The whole reason this core exists. A 95°F, 40 mph reading is 35°C and 64 km/h; handing
        // the raw Fahrenheit and miles per hour to the indices would produce confident nonsense
        // rather than an error.
        val c = WeatherUnits.toCelsius(95.0, WeatherUnits.Temperature.FAHRENHEIT)!!
        assertEquals(35.0, c, 0.001)
        assertEquals(64.37, WeatherUnits.toKmh(40.0, WeatherUnits.Speed.MPH)!!, 0.01)
        // Heat index on the converted value is a real answer; on the raw 95 it would not be.
        assertEquals(40.6754, WeatherComfort.heatIndexC(c, 50.0)!!, 0.001)
    }

    @Test fun visibilityIsDescribedTheWayAPersonWouldSayIt() {
        assertEquals("Clear", WeatherUnits.describeVisibility(25_000.0, imperial = false))
        assertEquals("8.0 km", WeatherUnits.describeVisibility(8000.0, imperial = false))
        assertEquals("400 m", WeatherUnits.describeVisibility(450.0, imperial = false))
        assertEquals("5.0 mi", WeatherUnits.describeVisibility(8046.72, imperial = true))
        assertTrue(WeatherUnits.describeVisibility(300.0, imperial = true)!!.endsWith("ft"))
        assertNull(WeatherUnits.describeVisibility(null, imperial = false))
        assertNull(WeatherUnits.describeVisibility(-5.0, imperial = false))
    }

    @Test fun theOneDecimalHelperRoundsHalfUpRatherThanToEven() {
        assertEquals("1.0 km", WeatherUnits.describeVisibility(999.9 + 0.1, imperial = false))
        assertEquals("2.0 km", WeatherUnits.describeVisibility(1999.0, imperial = false))
        // Both of these are exact ties. Kotlin's own round() is Math.rint, which rounds to even
        // and would print 1.4 here and 1.6 just below -- a display that changes direction with the
        // parity of the preceding digit. Half-up keeps them consistent.
        assertEquals("1.5 km", WeatherUnits.describeVisibility(1450.0, imperial = false))
        assertEquals("1.6 km", WeatherUnits.describeVisibility(1550.0, imperial = false))
    }
}
