package dev.mascwa.pulse.desktop.settings

import dev.mascwa.pulse.data.settings.PrecipUnit
import dev.mascwa.pulse.data.settings.TemperatureUnit
import dev.mascwa.pulse.data.settings.WindUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unit switches used to be inert. These are the assertions that would fail if they went back to
 * being inert — each one pins a rule rather than a rendering.
 */
class DesktopUnitsTest {

    @Test
    fun `the defaults ask for metric`() {
        val p = DesktopUnits.weatherPreferences(DesktopSettings())
        assertEquals(TemperatureUnit.CELSIUS, p.temperatureUnit)
        assertEquals(WindUnit.KMH, p.windUnit)
        assertEquals(PrecipUnit.MM, p.precipUnit)
    }

    /**
     * ⚠️ The load-bearing one. Before this existed, the repository was handed a bare
     * `WeatherPreferences()` whatever the settings said — flipping the switch changed the file on
     * disk and nothing else. Asserting the SWITCH reaches the REQUEST is the whole point.
     */
    @Test
    fun `fahrenheit reaches the request`() {
        val p = DesktopUnits.weatherPreferences(DesktopSettings(fahrenheit = true))
        assertEquals(TemperatureUnit.FAHRENHEIT, p.temperatureUnit)
    }

    /**
     * ⚠️ Temperature and distance are SEPARATE switches, and this pins that they stay separate.
     * Somebody wanting °C with miles is an ordinary combination, and a single "imperial" toggle
     * would deny it — so asking for miles must not silently change the temperature unit.
     */
    @Test
    fun `distance and temperature do not drag each other`() {
        val milesOnly = DesktopUnits.weatherPreferences(DesktopSettings(miles = true))
        assertEquals(TemperatureUnit.CELSIUS, milesOnly.temperatureUnit)
        assertEquals(WindUnit.MPH, milesOnly.windUnit)
        // Rain follows distance rather than temperature: inches belong with miles.
        assertEquals(PrecipUnit.INCH, milesOnly.precipUnit)

        val fahrenheitOnly = DesktopUnits.weatherPreferences(DesktopSettings(fahrenheit = true))
        assertEquals(WindUnit.KMH, fahrenheitOnly.windUnit)
        assertEquals(PrecipUnit.MM, fahrenheitOnly.precipUnit)
    }

    @Test
    fun `metric distances read as metres then kilometres`() {
        assertEquals("400 m", DesktopUnits.distance(400.0, miles = false))
        assertEquals("1.5 km", DesktopUnits.distance(1500.0, miles = false))
    }

    /**
     * ⚠️ Short distances stay in feet rather than becoming "0.1 mi". The question at that range is
     * how far away the door is, and a fraction of a mile does not answer it.
     *
     * Expected values computed from the shipped constants rather than recalled: 400 m × 3.280839895
     * = 1312.3 ft, truncated to 1312; 1500 m ÷ 1609.344 = 0.932 mi, which is over the quarter-mile
     * boundary so it renders as miles to one place.
     */
    @Test
    fun `imperial short distances stay in feet`() {
        assertEquals("1312 ft", DesktopUnits.distance(400.0, miles = true))
        assertEquals("0.9 mi", DesktopUnits.distance(1500.0, miles = true))
    }

    /** 10 km ÷ 1.609344 = 6.214 mi, truncated to 6. */
    @Test
    fun `long distances convert whole`() {
        assertEquals("10 km", DesktopUnits.longDistance(10.0, miles = false))
        assertEquals("6 mi", DesktopUnits.longDistance(10.0, miles = true))
    }

    /**
     * The pattern, not a formatted instant — a formatted one would depend on this machine's zone and
     * the test would say more about the runner than about the rule.
     */
    @Test
    fun `the clock follows the switch`() {
        assertTrue(DesktopUnits.clock(twelveHour = false).toString().contains("HourOfDay"))
        assertTrue(DesktopUnits.clock(twelveHour = true).toString().contains("ClockHourOfAmPm"))
        assertTrue(DesktopUnits.stamp(twelveHour = true).toString().contains("AmPmOfDay"))
    }
}
