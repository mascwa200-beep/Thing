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
const val WIDGET_LOAD_TIMEOUT_MS = 7_000L

/**
 * How long any ONE feed may take before the widget stops waiting for it.
 *
 * ⚠️ **This constant is the fix for the defect that made the widget look like it was losing
 * features.** The load used to wrap all seven sources in a single `withTimeoutOrNull`, so when one
 * was slow the elvis on the far side discarded **every** result — including the six that had already
 * finished — and each blank line then hid itself. The widget silently shrank to a greeting and a
 * battery percentage, and nothing recorded that it had happened.
 *
 * Per-source now, so a slow feed costs its own line and nothing else. [WIDGET_LOAD_TIMEOUT_MS]
 * remains as the outer bound on the whole batch, comfortably inside the ~10 s a `goAsync()` receiver
 * gets, and it is now a backstop rather than the thing that fires.
 */
const val WIDGET_SOURCE_TIMEOUT_MS = 4_000L

/**
 * Run one feed, and record what happened to it either way.
 *
 * Returns the value, or null when there was nothing to draw — but the *reason* for the null is never
 * lost: it lands in [outcomes] as failed, timed out or genuinely empty. That distinction is the
 * whole point; see [WidgetDiagnostics.Outcome].
 *
 * ⚠️ [outcomes] is written from several coroutines at once, so callers must hand in a map that
 * tolerates it. Sources run in parallel — that is what keeps the widget inside its window.
 */
suspend fun <T> widgetSource(
    source: WidgetDiagnostics.Source,
    outcomes: MutableMap<WidgetDiagnostics.Source, WidgetDiagnostics.Outcome>,
    budgetMs: Long = WIDGET_SOURCE_TIMEOUT_MS,
    block: suspend () -> T?,
): T? {
    val result = kotlinx.coroutines.withTimeoutOrNull(budgetMs) {
        runCatching { block() }
    }
    if (result == null) {
        outcomes[source] = WidgetDiagnostics.Outcome.TimedOut
        return null
    }
    result.exceptionOrNull()?.let {
        outcomes[source] = WidgetDiagnostics.Outcome.Failed(WidgetDiagnostics.describe(it))
        return null
    }
    val value = result.getOrNull()
    val blank = value == null || (value is String && value.isBlank()) || (value is Collection<*> && value.isEmpty())
    outcomes[source] = if (blank) WidgetDiagnostics.Outcome.Empty else WidgetDiagnostics.Outcome.Ok
    return if (blank) null else value
}

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

/** Where the widget believes it is. */
data class WidgetPlace(val latitude: Double, val longitude: Double, val name: String)

/**
 * The one answer to "where are we", for every feed that needs a coordinate.
 *
 * Prefers a saved location; falls back to the device's own only when the user has actually turned
 * that on, so the widget does not wake the GPS on a schedule the user never asked for. Null means
 * we genuinely do not know — which is a [WidgetDiagnostics.Outcome.Skipped], not a failure, and the
 * caller is expected to say so rather than leave a row mysteriously absent.
 *
 * ⚠️ Extracted rather than repeated. Weather, nearby safety and the ISS all need this, and the last
 * time this rule existed in more than one place one of the copies had silently dropped its
 * `useDeviceLocation` branch — see the note at the top of this file.
 */
suspend fun widgetPlace(c: AppContainer, s: AppSettings?): WidgetPlace? {
    s ?: return null
    val saved = s.savedLocations.getOrNull(s.selectedLocationIndex) ?: s.savedLocations.firstOrNull()
    if (saved != null) return WidgetPlace(saved.latitude, saved.longitude, saved.name)
    if (!s.useDeviceLocation) return null
    return c.locationProvider.current()?.let { WidgetPlace(it.latitude, it.longitude, it.name) }
}

/**
 * Today's weather for a place already resolved.
 *
 * `force = false`, so this warms off the caches the background worker has already filled rather
 * than starting a fetch of its own wherever it can avoid one.
 */
suspend fun resolveWeather(c: AppContainer, place: WidgetPlace?): WeatherData? {
    val p = place ?: return null
    return c.weatherRepository.fetch(p.latitude, p.longitude, p.name, force = false).data
}

/** Today's weather without waking the GPS, resolving the place itself. */
suspend fun resolveWeather(c: AppContainer, s: AppSettings?): WeatherData? =
    resolveWeather(c, widgetPlace(c, s))
