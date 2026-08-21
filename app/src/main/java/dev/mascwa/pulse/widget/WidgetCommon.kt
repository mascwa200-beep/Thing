package dev.mascwa.pulse.widget

import dev.mascwa.pulse.R
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.data.weather.WeatherData
import dev.mascwa.pulse.di.AppContainer
import dev.mascwa.pulse.notifications.AlertCondition
import dev.mascwa.pulse.notifications.AlertStatus

/**
 * The few things every widget needs and none of them should own privately.
 *
 * ⚠️ This file exists because all three of them were owned privately. [resolveWeather] was
 * *triplicated* — and the third copy had quietly drifted: the lock widget's version dropped the
 * `useDeviceLocation` branch, so a user with location granted but no saved place got a permanently
 * blank weather line and nothing anywhere said why. Three copies of a rule is three chances for one
 * of them to be wrong, and the one that was wrong was on the widget actually in use.
 */

/**
 * How long a widget may spend loading before it renders whatever it has.
 *
 * ⚠️ `force = false` on a repository is **not** "cache only" — it serves the cache within its TTL
 * and otherwise goes to the network. `goAsync()` gives a receiver roughly ten seconds; blow that and
 * the widget silently never renders at all. Only the lock widget bounded itself, and it is the
 * reason it is the one that has always worked.
 */
const val WIDGET_LOAD_TIMEOUT_MS = 6_000L

/**
 * The accent colour a widget should draw right now.
 *
 * The ship's condition is a process-wide `StateFlow` needing no Compose, no coroutine and no
 * container, so a `RemoteViews` surface can read it as easily as the console can. Nothing outside
 * the app UI and the notification tray was reading it; this is what lets the home screen swing to
 * red off the same single flag rather than off a second opinion about what counts as an emergency.
 *
 * Only RED moves it, matching `NightwireTheme`: yellow means pay attention, and recolouring for it
 * would spend the signal long before anything is actually wrong.
 */
fun widgetAccentRes(): Int =
    if (runCatching { AlertStatus.condition.value }.getOrNull() == AlertCondition.RED) {
        R.color.nw_alert_accent
    } else {
        R.color.nw_accent
    }

/**
 * A percentage with its sign always shown, so a rise and a fall are told apart by more than colour.
 *
 * Deliberately the **device** locale, matching `Formatters.number`, which is what the rest of the
 * app renders user-facing numbers with. (This is not the default-locale trap recorded elsewhere in
 * this project: that one bites where a formatted number is compared, keyed on, or fed back into
 * something that parses it. Here it is read by a person.)
 */
fun signedPercent(v: Double): String = (if (v >= 0) "+" else "") + "%.2f".format(v)

/**
 * Today's weather without waking the GPS.
 *
 * Prefers a saved location; falls back to the device's own only when the user has actually turned
 * that on. Both branches are `force = false`, so this warms off the caches the background worker
 * has already filled rather than starting a fetch of its own wherever it can avoid one.
 */
suspend fun resolveWeather(c: AppContainer, s: AppSettings?): WeatherData? {
    s ?: return null
    val saved = s.savedLocations.getOrNull(s.selectedLocationIndex) ?: s.savedLocations.firstOrNull()
    return when {
        saved != null ->
            c.weatherRepository.fetch(saved.latitude, saved.longitude, saved.name, force = false).data
        s.useDeviceLocation ->
            c.locationProvider.current()
                ?.let { c.weatherRepository.fetch(it.latitude, it.longitude, it.name, force = false).data }
        else -> null
    }
}
