package dev.mascwa.pulse.notifications

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.mascwa.pulse.core.telemetry.BriefSignals
import dev.mascwa.pulse.core.telemetry.EmergencyNews
import dev.mascwa.pulse.core.telemetry.OracleMover
import dev.mascwa.pulse.core.telemetry.TaskBoard
import dev.mascwa.pulse.core.telemetry.UnifiedBriefComposer
import dev.mascwa.pulse.data.news.NewsCategory
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.data.weather.WeatherCode
import dev.mascwa.pulse.data.weather.WeatherData
import dev.mascwa.pulse.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gathers a full [BriefSignals] snapshot from the app's stores/repositories, composes THE one LCARS
 * notification via the pure [UnifiedBriefComposer], and posts it — silently on refreshes, alerting exactly
 * once per NEW urgent item (dedup via [NotifyState.lastUrgentKey], which this engine owns). Every read is
 * defensive: a missing signal, denied permission or failed fetch simply mutes its row.
 *
 * Callers: the periodic worker (with warmed caches), the resident live-news poller, the reminder worker at
 * a reminder's set moment ([reminderNow]), and the Settings test button.
 */
object BriefEngine {

    private const val STATE_KEY = "notify_state"

    suspend fun publish(
        context: Context,
        container: AppContainer,
        settings: AppSettings,
        forceNews: Boolean = false,
        opsNotice: String? = null,
        /** An urgent nearby-incident line, paired with a stable dedup key. */
        safetyNotice: Pair<String, String>? = null,
        /** A user-set reminder firing RIGHT NOW — always alerts, regardless of any dedup. */
        reminderNow: String? = null,
        securityNotice: String? = null,
        securityCritical: Boolean = false,
    ) {
        val prefs = settings.notifications
        val now = System.currentTimeMillis()

        // --- News + emergency scan (TOP always; WORLD widens the emergency net, best-effort). ---
        var topHeadline: String? = null
        var topSource: String? = null
        var emergencyHeadline: String? = null
        var emergencyMajor = false
        runCatching {
            val top = container.newsRepository.fetchCategory(NewsCategory.TOP, force = forceNews).data
            top.firstOrNull { it.title.isNotBlank() }?.let {
                topHeadline = it.title
                topSource = it.source
            }
            val world = runCatching {
                container.newsRepository.fetchCategory(NewsCategory.WORLD, force = false).data
            }.getOrDefault(emptyList())
            val worst = (top + world).asSequence()
                .filter { it.title.isNotBlank() }
                .distinctBy { it.title }
                .map { it to EmergencyNews.severity(it.title, it.summary) }
                .filter { it.second > 0 }
                .maxByOrNull { it.second }?.first
            if (worst != null) {
                emergencyHeadline = worst.title
                emergencyMajor = EmergencyNews.isMajor(worst.title, worst.summary)
            }
        }

        // --- Markets: every quote with a live daily change; the composer applies the user's threshold. ---
        val movers = runCatching {
            container.marketsRepository.fetchAll(force = false).data.mapNotNull { q ->
                q.changePercent?.let { OracleMover(q.label, it) }
            }
        }.getOrDefault(emptyList())

        // --- Weather + the always-on current temperature, in the user's own display unit. ---
        var tempNow: Double? = null
        var tempUnit = "°C"
        var conditionText: String? = null
        var tempHi: Double? = null
        var tempLo: Double? = null
        var precipPct: Int? = null
        var uvIndex: Double? = null
        var severe = false
        // Canonical companions, so the row can say what the temperature does rather than only what
        // it reads. Converted upstream at the one point the unit setting is known.
        var tempC: Double? = null
        var humidityPct: Double? = null
        var windKmh: Double? = null
        runCatching {
            val w = resolveWeather(container, settings) ?: return@runCatching
            tempUnit = w.tempUnitSymbol
            tempNow = w.current?.temperature
            tempC = w.current?.temperatureC
            humidityPct = w.current?.humidity
            windKmh = w.current?.windKmh
            conditionText = w.current?.let { WeatherCode.describe(it.weatherCode) }
            w.daily.firstOrNull()?.let { d ->
                tempHi = d.tempMax
                tempLo = d.tempMin
                precipPct = d.precipProbabilityMax
                uvIndex = d.uvIndexMax
                severe = WeatherCode.isSevere(d.weatherCode)
            }
        }
        val kp = runCatching { container.spaceWeatherRepository.fetch(false).data.kp }.getOrNull()

        // --- Agenda: next calendar event + open tasks + how many reminders are queued. ---
        // upcoming() is a blocking ContentResolver query, not suspend — keep it off the caller's dispatcher.
        val event = withContext(Dispatchers.IO) {
            runCatching { container.calendarRepository.upcoming(now).firstOrNull() }.getOrNull()
        }
        val pendingTasks = runCatching { TaskBoard.pending(container.taskStore.all()) }.getOrDefault(emptyList())
        val reminderCount = withContext(Dispatchers.IO) {
            runCatching {
                WorkManager.getInstance(context).getWorkInfosByTag(ReminderWorker.TAG).get()
                    .count { it.state == WorkInfo.State.ENQUEUED }
            }.getOrDefault(0)
        }

        val brief = UnifiedBriefComposer.compose(
            BriefSignals(
                nowMs = now,
                topHeadline = topHeadline,
                topSource = topSource,
                emergencyHeadline = emergencyHeadline,
                emergencyMajor = emergencyMajor,
                movers = movers,
                moveThresholdPct = prefs.marketMovePercent,
                tempNow = tempNow,
                tempUnit = tempUnit,
                conditionText = conditionText,
                tempHi = tempHi,
                tempLo = tempLo,
                precipPct = precipPct,
                uvIndex = uvIndex,
                severeWeather = severe,
                tempC = tempC,
                humidityPct = humidityPct,
                windKmh = windKmh,
                kpIndex = kp,
                nextEventTitle = event?.title,
                nextEventStartMs = event?.startMs,
                pendingTaskCount = pendingTasks.size,
                topTask = pendingTasks.firstOrNull()?.title,
                pendingReminderCount = reminderCount,
                reminderNow = reminderNow,
                securityNotice = securityNotice,
                securityCritical = securityCritical,
                safetyNotice = safetyNotice?.first,
                safetyKey = safetyNotice?.second,
                opsNotice = opsNotice,
                showNews = prefs.showNewsRow,
                showMarkets = prefs.showMarketsRow,
                showWeather = prefs.showWeatherRow,
                showAgenda = prefs.showAgendaRow,
            ),
        )
        if (brief == null) {
            container.notifier.cancelBrief()
            return
        }

        val state = container.diskCache.readAny(STATE_KEY, NotifyState.serializer())?.value ?: NotifyState()
        // Local val: urgencyKey is a public property from another module, so it can't smart-cast directly.
        val urgentKey = brief.urgencyKey
        val alertNew = when {
            reminderNow != null -> true // the user asked to be interrupted at exactly this moment
            urgentKey != null && urgentKey != state.lastUrgentKey && prefs.urgentAlertsEnabled -> true
            else -> false
        }
        container.notifier.notifyBrief(brief, alertNew)

        // Burn the key only when it actually alerted — if urgent alerts were toggled off, the item keeps
        // its right to buzz once the toggle comes back on (an unburned key is a pending alert, not a bug).
        if (alertNew && urgentKey != null && urgentKey != state.lastUrgentKey) {
            // Read-modify-write the LATEST blob so the other notify_state writers' fields are never clobbered.
            val latest = container.diskCache.readAny(STATE_KEY, NotifyState.serializer())?.value ?: NotifyState()
            container.diskCache.write(STATE_KEY, latest.copy(lastUrgentKey = urgentKey), NotifyState.serializer())
        }
    }

    /** Device location when enabled and permitted, else the selected saved location — warm caches only
     *  (the periodic worker does its own force-fetch warm-ups before calling [publish]). */
    private suspend fun resolveWeather(container: AppContainer, settings: AppSettings): WeatherData? {
        if (settings.useDeviceLocation) {
            container.locationProvider.current()?.let { loc ->
                return container.weatherRepository.fetch(loc.latitude, loc.longitude, loc.name, false).data
            }
        }
        val saved = settings.savedLocations.getOrNull(settings.selectedLocationIndex)
            ?: settings.savedLocations.firstOrNull() ?: return null
        return container.weatherRepository.fetch(saved.latitude, saved.longitude, saved.name, false).data
    }
}
