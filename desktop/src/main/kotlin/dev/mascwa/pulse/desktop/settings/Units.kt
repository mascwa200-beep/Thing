package dev.mascwa.pulse.desktop.settings

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import dev.mascwa.pulse.data.settings.PrecipUnit
import dev.mascwa.pulse.data.settings.TemperatureUnit
import dev.mascwa.pulse.data.settings.WeatherPreferences
import dev.mascwa.pulse.data.settings.WindUnit
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The one place the unit switches turn into the things that read them.
 *
 * ⚠️ This exists because the switches were doing nothing. `fahrenheit`, `miles` and
 * `twelveHourClock` were declared, offered on the Settings page, written to disk — and read by no
 * code at all. The weather repository was handed a bare `WeatherPreferences()`, so it asked
 * Open-Meteo for Celsius whatever the switch said; three screens each carried their own private
 * copy of a metres-to-kilometres formatter; and the observatory's clock was a hardcoded `HH:mm`.
 * Flipping any of the three changed nothing on screen, which is worse than not offering them.
 *
 * It is also the fix for a duplicated definition: `distance()` existed three times, identically, in
 * Places, Radar and Safety. Three copies of one rule is how they drift, and this project has had to
 * correct that four times over.
 */
object DesktopUnits {

    /**
     * What to ask the weather API for.
     *
     * ⚠️ Temperature and distance are separate switches on purpose — plenty of people want Celsius
     * with miles, and one combined "imperial" toggle would deny it. Precipitation follows the
     * DISTANCE switch rather than the temperature one, because inches of rain belong with miles.
     */
    fun weatherPreferences(s: DesktopSettings): WeatherPreferences = WeatherPreferences(
        temperatureUnit = if (s.fahrenheit) TemperatureUnit.FAHRENHEIT else TemperatureUnit.CELSIUS,
        windUnit = if (s.miles) WindUnit.MPH else WindUnit.KMH,
        precipUnit = if (s.miles) PrecipUnit.INCH else PrecipUnit.MM,
    )

    private const val METRES_PER_MILE = 1609.344
    private const val FEET_PER_METRE = 3.280839895

    /**
     * A distance somebody has to read, from metres.
     *
     * Short distances stay in feet or metres rather than becoming "0.1 mi", because the question
     * being answered at that range — how far is the door — is not one a fraction of a mile answers.
     */
    fun distance(metres: Double, miles: Boolean): String = when {
        miles && metres < METRES_PER_MILE / 4 -> "${(metres * FEET_PER_METRE).toInt()} ft"
        miles -> String.format(Locale.US, "%.1f mi", metres / METRES_PER_MILE)
        metres < 1000 -> "${metres.toInt()} m"
        else -> String.format(Locale.US, "%.1f km", metres / 1000.0)
    }

    /** A long distance quoted in whole units — an orbit's altitude, a search radius, a miss distance. */
    fun longDistance(kilometres: Double, miles: Boolean): String =
        if (miles) "${(kilometres / 1.609344).toInt()} mi" else "${kilometres.toInt()} km"

    /**
     * ⚠️ Built fresh rather than held as a constant. `DateTimeFormatter` is immutable and would be
     * safe to cache, but the setting can change while the app runs and a cached pair keyed on
     * nothing would keep showing the old clock until a restart.
     */
    fun clock(twelveHour: Boolean): DateTimeFormatter =
        DateTimeFormatter.ofPattern(if (twelveHour) "h:mm a" else "HH:mm", Locale.US)

    fun stamp(twelveHour: Boolean): DateTimeFormatter =
        DateTimeFormatter.ofPattern(if (twelveHour) "d MMM, h:mm a" else "d MMM, HH:mm", Locale.US)
}

/**
 * How this reader wants numbers shown, for the screens that draw them.
 *
 * ⚠️ A composition local rather than a parameter on nine view models, and rather than each screen
 * reaching for the settings store. It follows the arrangement the shell already uses for the
 * header's location readout and the stardate: provided once around the whole app, so adding a
 * screen that shows a distance costs one read and no plumbing. It also means changing the switch
 * redraws every screen holding a distance, which is exactly what a units toggle should do.
 */
data class UnitPrefs(val miles: Boolean = false, val twelveHourClock: Boolean = false)

/**
 * ⚠️ `compositionLocalOf`, not `staticCompositionLocalOf`: this value CHANGES while the app runs,
 * and the static kind recomposes the whole provided subtree rather than only the readers. Here the
 * readers are what should redraw.
 */
val LocalUnits: ProvidableCompositionLocal<UnitPrefs> = compositionLocalOf { UnitPrefs() }
