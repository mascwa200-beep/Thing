package dev.mascwa.pulse.notifications

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.mascwa.pulse.core.telemetry.BriefSignals
import dev.mascwa.pulse.core.telemetry.EmergencyNews
import dev.mascwa.pulse.core.telemetry.Oracle
import dev.mascwa.pulse.core.telemetry.OracleMover
import dev.mascwa.pulse.core.telemetry.StoryLedger
import dev.mascwa.pulse.core.telemetry.Urgency
import dev.mascwa.pulse.core.telemetry.TaskBoard
import dev.mascwa.pulse.core.telemetry.UnifiedBriefComposer
import dev.mascwa.pulse.data.news.NewsCategory
import dev.mascwa.pulse.data.oracle.DayAheadEngine
import dev.mascwa.pulse.data.oracle.OracleEngine
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.data.study.localDayIndex
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
        // ⚠️ Two different bars, and conflating them is what put the app permanently into condition
        // red. `emergencyMajor` also covers a notable death and a historic verdict — worth a card,
        // not worth an alert condition. Only a STRONG disaster (severity 2) reaches the board's
        // ALERT row, and even then only as yellow. See EmergencyNews.isMajor and AlertPolicyTest.
        var emergencySevere = false
        // Spare headlines for the news row to fall back to when the lead has already been shown.
        val spareHeadlines = mutableListOf<String>()
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
                emergencySevere = EmergencyNews.severity(worst.title, worst.summary) == 2
            }
            // Everything after the lead, for the ledger to fall back through. Without these the
            // news row simply vanishes once the lead has been seen, trading a repeating row for an
            // absent one.
            spareHeadlines += top.asSequence().drop(1).map { it.title }.filter { it.isNotBlank() }.take(8)
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

        // The one thing on the timeline worth interrupting for. Cheap to ask: it checks the local
        // calendar first and returns before any network when nothing is starting soon, which is
        // most passes. Gated on the agenda row, because a user who has turned their calendar off
        // the board has said what they think of calendar interruptions.
        val departure = if (prefs.showAgendaRow) {
            runCatching { DayAheadEngine.imminentDeparture(container, settings) }.getOrNull()
        } else null

        val advisory = advisory(container, settings)

        // ⚠️ Read BEFORE composing, not after: the composer needs to know which stories this board
        // has already printed, and that is the whole fix for "the notification keeps saying the same
        // thing". The later read-modify-write still re-reads the newest blob before touching it,
        // because this file shares `notify_state` with the resident poller and the worker.
        val state = container.diskCache.readAny(STATE_KEY, NotifyState.serializer())?.value ?: NotifyState()

        val brief = UnifiedBriefComposer.compose(
            BriefSignals(
                nowMs = now,
                topHeadline = topHeadline,
                topSource = topSource,
                moreHeadlines = spareHeadlines,
                seenStories = state.seenStories.toSet(),
                emergencyHeadline = emergencyHeadline,
                emergencyMajor = emergencyMajor,
                emergencySevere = emergencySevere,
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
                advisory = advisory?.line,
                advisoryUrgent = advisory?.urgent == true,
                advisoryKey = advisory?.key,
                lesson = lesson(container, pendingTasks.map { it.title }),
                departureNotice = departure?.first,
                departureKey = departure?.second,
            ),
        )
        if (brief == null) {
            container.notifier.cancelBrief()
            // Nothing to report is the definition of routine. Stand the console down too, or a
            // cleared situation would leave the app red until something else happened to publish.
            setCondition(container, AlertCondition.ROUTINE)
            return
        }

        // Local val: urgencyKey is a public property from another module, so it can't smart-cast directly.
        val urgentKey = brief.urgencyKey
        val alertNew = when {
            reminderNow != null -> true // the user asked to be interrupted at exactly this moment
            urgentKey != null && urgentKey != state.lastUrgentKey && prefs.urgentAlertsEnabled -> true
            else -> false
        }
        container.notifier.notifyBrief(brief, alertNew)
        // The console follows the board. Set on every publish, in both directions, so a situation
        // that resolves stands the ship down as surely as one that arises takes it to red.
        setCondition(
            container,
            when (brief.urgency) {
                dev.mascwa.pulse.core.telemetry.BriefUrgency.RED -> AlertCondition.RED
                dev.mascwa.pulse.core.telemetry.BriefUrgency.YELLOW -> AlertCondition.YELLOW
                dev.mascwa.pulse.core.telemetry.BriefUrgency.ROUTINE -> AlertCondition.ROUTINE
            },
            brief.headline,
        )

        // Burn the key only when it actually alerted — if urgent alerts were toggled off, the item keeps
        // its right to buzz once the toggle comes back on (an unburned key is a pending alert, not a bug).
        //
        // ⚠️ The story is recorded only once it has actually been PRINTED, and only the one that was
        // printed. Recording what was merely considered would burn stories the reader never saw, and
        // they would then never be shown at all.
        val burnKey = alertNew && urgentKey != null && urgentKey != state.lastUrgentKey
        val shownStory = brief.newsIdentity
        if (burnKey || shownStory != null) {
            // Read-modify-write the LATEST blob so the other notify_state writers' fields are never clobbered.
            val latest = container.diskCache.readAny(STATE_KEY, NotifyState.serializer())?.value ?: NotifyState()
            container.diskCache.write(
                STATE_KEY,
                latest.copy(
                    lastUrgentKey = if (burnKey && urgentKey != null) urgentKey else latest.lastUrgentKey,
                    seenStories = shownStory?.let { StoryLedger.remember(it, latest.seenStories) }
                        ?: latest.seenStories,
                ),
                NotifyState.serializer(),
            )
        }
    }

    /**
     * The Oracle's single most important call to action, or null.
     *
     * This is the foresight engine's way back to the user. Its proactive push was retired when the
     * app consolidated to one notification, and nothing replaced it — so for a while the app's whole
     * cross-signal reasoning layer only existed if you navigated to Advisories. It belongs on the
     * board: every other row reports the world, and this one reports what to do about it.
     *
     * The bar is [Urgency.IMPORTANT] and above. Below that the insight is worth reading when you
     * open the app and is not worth a sixth row on a notification you see all day.
     *
     * Cost is reasoning, not fetching: `snapshot` reads every store with `force = false`, so it
     * consumes the caches this pass has already warmed. Best-effort throughout — a failure anywhere
     * mutes the row rather than costing you the board.
     */
    /**
     * The Oracle's call to action for the board, and whether it is worth announcing.
     *
     * @param urgent the insight clears `Oracle.pushWorthy`. Of the rules that reach that bar, a
     *   departure, a major emergency and a security notice each already have their own alert path,
     *   so in practice this is extreme heat danger — a health risk that would otherwise be delivered
     *   as a silent routine row.
     * @param key the rule's stable family, never the sentence: the text carries live values that
     *   move through the day, and keying on it would re-buzz on every rewrite.
     */
    private data class Advisory(val line: String, val urgent: Boolean, val key: String)

    private suspend fun advisory(container: AppContainer, settings: AppSettings): Advisory? = runCatching {
        val signals = OracleEngine.snapshot(container, settings)
        val top = Oracle.focus(signals) ?: return@runCatching null
        if (top.urgency.weight < Urgency.IMPORTANT.weight) return@runCatching null
        Advisory(
            // The title carries the instruction and the detail carries why — the board wants both, in
            // that order, because a suggestion without a reason is just noise you learn to ignore.
            line = listOf(top.title, top.detail).filter { it.isNotBlank() }.joinToString(" — "),
            // ⚠️ The Oracle's own definition of "worth interrupting for", not a second threshold
            // written here. Two definitions of that is how the board and the assistant quietly start
            // disagreeing about what an emergency is.
            urgent = Oracle.pushWorthy(listOf(top)).isNotEmpty(),
            key = top.family,
        )
    }.getOrNull()

    /**
     * Today's study item, or null.
     *
     * The library holds hundreds of guides and, until the study cores existed, the only way to be
     * taught anything from it was to remember to go looking. A line on a board you already read all
     * day is the difference between a library you own and a library you use.
     *
     * ⚠️ It is the first row shed when the board is busy and it never raises the alert condition —
     * both enforced in `UnifiedBriefComposer`, not here. Best-effort: the index is resident and the
     * pick is a rank over it, so this costs no fetch, and any failure simply drops the row.
     */
    private suspend fun lesson(container: AppContainer, pendingTasks: List<String>): String? = runCatching {
        val interests = container.profileStore.all().sortedByDescending { it.weight }.map { it.text }
        container.studyStore.today(interests, pendingTasks, localDayIndex())?.boardLine
    }.getOrNull()

    /**
     * Put the console back into the condition the last board was in.
     *
     * Called once at process start. Without it a cold launch opens routine-orange until the next
     * worker pass, which can be a refresh interval away while the tray still reads RED ALERT.
     */
    suspend fun restoreCondition(container: AppContainer) {
        val state = container.diskCache.readAny(STATE_KEY, NotifyState.serializer())?.value ?: return
        AlertStatus.set(AlertStatus.parse(state.lastCondition))
    }

    /**
     * Publish the condition to the live console and persist it for the next cold launch.
     *
     * Writes only on a real change, because the read-modify-write shares `notify_state` with the
     * other writers and a no-op write is a chance to lose one of their fields for nothing.
     */
    private suspend fun setCondition(
        container: AppContainer,
        condition: AlertCondition,
        headline: String = "",
    ) {
        AlertStatus.set(condition, headline)
        val latest = container.diskCache.readAny(STATE_KEY, NotifyState.serializer())?.value ?: NotifyState()
        if (latest.lastCondition == condition.name) return
        container.diskCache.write(STATE_KEY, latest.copy(lastCondition = condition.name), NotifyState.serializer())
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
