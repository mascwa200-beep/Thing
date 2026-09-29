package dev.mascwa.pulse.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.mascwa.pulse.MainActivity
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

/**
 * Where the widget believes it is.
 *
 * [recordedAtMs] is non-null exactly when this is a [RecordedFix] standing in for a live one — the
 * moment that fix was TAKEN — so the caller can say how old "here" is rather than let weather at
 * this morning's place pass for weather where the phone is now.
 */
data class WidgetPlace(
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val recordedAtMs: Long? = null,
)

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
suspend fun widgetPlace(c: AppContainer, s: AppSettings?): WidgetPlace? =
    widgetPlaceLookup(c, s).place

/**
 * A place, or the reason there is none — in words the owner can act on.
 *
 * [why] is null exactly when [place] is not.
 */
data class PlaceLookup(val place: WidgetPlace?, val why: String?)

/**
 * [widgetPlace], answering WHY when it cannot answer where.
 *
 * ⚠️ The same rule, not a second copy of it — [widgetPlace] is this with the reason thrown away. The
 * reason is what the widget was missing: four feeds need a coordinate, and all four used to go quiet
 * together with one line in the crash console saying "no saved location" even when the owner HAD
 * turned on device location and the real cause was a permission, or a phone that had not taken a fix
 * since it went to sleep. Those are three different things to do about it, so they are three
 * different sentences.
 *
 * [hasLocationPermission] and [isLocationOn] are asked only to choose the sentence, never to decide
 * the place — the provider already refuses without them, and the rule must not fork on the caller's
 * context. Both default to true, which is exactly [widgetPlace]'s behaviour.
 *
 * ⚠️ The system Location switch is checked BEFORE asking for a fix. With it off, every path to a
 * coordinate fails fast, and the only sentence left used to be "no location fix yet — open LCARS
 * once": the wrong remedy, since LCARS asks the same provider and gets the same nothing. The switch
 * is the fix, so the switch is what gets named.
 *
 * ⚠️ Pass both lambdas by NAME. With two trailing function parameters, a bare trailing lambda binds
 * to the LAST one, so `widgetPlaceLookup(c, s) { permission }` would quietly be read as the Location
 * switch.
 */
suspend fun widgetPlaceLookup(
    c: AppContainer,
    s: AppSettings?,
    hasLocationPermission: () -> Boolean = { true },
    isLocationOn: () -> Boolean = { true },
): PlaceLookup {
    s ?: return PlaceLookup(null, "settings could not be read")
    val saved = s.savedLocations.getOrNull(s.selectedLocationIndex) ?: s.savedLocations.firstOrNull()
    if (saved != null) return PlaceLookup(WidgetPlace(saved.latitude, saved.longitude, saved.name), null)
    if (!s.useDeviceLocation) return PlaceLookup(null, "add a place, or turn on device location")
    if (!hasLocationPermission()) return PlaceLookup(null, "location permission not granted")
    if (!isLocationOn()) return PlaceLookup(null, "turn on the phone's Location")
    val here = c.locationProvider.current()
    if (here != null) return PlaceLookup(WidgetPlace(here.latitude, here.longitude, here.name), null)
    // ⚠️ The live read above answers nothing whenever this runs in the BACKGROUND, which is nearly
    // always: the app holds foreground-only location, so the platform refuses a widget render every
    // fix it asks for. The fix LCARS last took stands in for it, within a day — see [RecordedFix].
    // This is also what makes "open LCARS once" true: before it, nothing kept that fix.
    val kept = c.locationProvider.recorded()
        ?: return PlaceLookup(null, "no location fix yet — open LCARS once")
    if (!kept.usableAt(System.currentTimeMillis())) {
        return PlaceLookup(null, "last location over a day old — open LCARS")
    }
    return PlaceLookup(WidgetPlace(kept.latitude, kept.longitude, kept.name, recordedAtMs = kept.atMs), null)
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

/**
 * Open LCARS on [route] — the one tap target every widget region and every lock-screen board region
 * uses, so the two surfaces cannot open different screens for the same line.
 *
 * ⚠️ [requestCode] must differ between regions whose routes differ: `Intent.filterEquals` ignores the
 * route extra, so two regions sharing a code share ONE PendingIntent and every tap lands wherever the
 * last-built one pointed — the collision already corrected once in `NotifId`.
 */
fun widgetOpenIntent(context: Context, route: String, requestCode: Int): PendingIntent =
    PendingIntent.getActivity(
        context, requestCode, openRouteIntent(context, route),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

/**
 * The intent that opens LCARS on [route] — the one definition every surface uses, whether it wraps
 * it in a PendingIntent (the widget) or starts it directly (the lock console and the home screen).
 *
 * ⚠️ The consoles start this directly rather than going through [widgetOpenIntent], and that is
 * load-bearing: minting a PendingIntent under one of the widget's request codes with
 * `FLAG_UPDATE_CURRENT` would rewrite the route on the placed widget's own tap target.
 */
fun openRouteIntent(context: Context, route: String): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(MainActivity.EXTRA_ROUTE, route)
