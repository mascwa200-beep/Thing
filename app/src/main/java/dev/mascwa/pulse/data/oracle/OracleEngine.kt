package dev.mascwa.pulse.data.oracle

import android.os.StatFs
import dev.mascwa.pulse.core.telemetry.EmergencyNews
import dev.mascwa.pulse.data.study.localDayIndex
import dev.mascwa.pulse.core.telemetry.Insight
import dev.mascwa.pulse.core.telemetry.NetworkKind
import dev.mascwa.pulse.core.telemetry.Oracle
import dev.mascwa.pulse.core.telemetry.OracleEvent
import dev.mascwa.pulse.core.telemetry.OracleMemory
import dev.mascwa.pulse.core.telemetry.OracleMover
import dev.mascwa.pulse.core.telemetry.OracleSignals
import dev.mascwa.pulse.core.telemetry.PressureTrend
import dev.mascwa.pulse.core.telemetry.ProfileCategory
import dev.mascwa.pulse.core.telemetry.TaskBoard
import dev.mascwa.pulse.core.telemetry.UsageInsights
import dev.mascwa.pulse.core.telemetry.UserProfile
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.data.news.NewsCategory
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.data.usage.FeatureCatalog
import dev.mascwa.pulse.di.AppContainer
import dev.mascwa.pulse.feature.weather.WeatherFormat
import java.util.Calendar

/**
 * The on-device ORACLE — gathers a full [OracleSignals] snapshot from every store/repository, runs the pure
 * [Oracle] reasoning engine, and (from the background worker) fires a proactive push for the most important
 * thing worth interrupting you for on every eligible pass. All reads are defensive + `force = false` (warm
 * the caches the worker already fills); a missing signal (no permission / null) simply mutes its rules.
 */
object OracleEngine {

    /** Snapshot every subsystem into the pure signal bundle the [Oracle] reasons over. */
    suspend fun snapshot(container: AppContainer, settings: AppSettings): OracleSignals {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = hour * 60 + cal.get(Calendar.MINUTE)
        val dow = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1 // 1=Mon..7=Sun

        val loc = runCatching { container.locationProvider.current() }.getOrNull()

        // Calendar: times from CalendarRepository, coordinates (when present) from the geocoded objectives.
        val events = runCatching {
            val timed = container.calendarRepository.upcoming(now)
            val located = runCatching { container.calendarObjectives.upcoming(2) }.getOrDefault(emptyList())
            val coordByEventId = located.associateBy {
                it.id.removePrefix("cal_").substringBefore('_').toLongOrNull()
            }
            timed.map { e ->
                val obj = coordByEventId[e.id]
                val dist = if (obj != null && loc != null)
                    Geo.distanceMeters(loc.latitude, loc.longitude, obj.latitude, obj.longitude) else null
                OracleEvent(title = e.title, startMs = e.startMs, hasLocation = obj != null, distanceM = dist)
            }
        }.getOrDefault(emptyList())

        val pendingTasks = runCatching {
            TaskBoard.pending(container.taskStore.all()).map { it.title }
        }.getOrDefault(emptyList())

        val interests = runCatching {
            UserProfile.inCategory(container.profileStore.all(), ProfileCategory.INTEREST).map { it.text }
        }.getOrDefault(emptyList())

        // Weather — device location first, else a saved location.
        val wxLat = loc?.latitude ?: settings.savedLocations.firstOrNull()?.latitude
        val wxLon = loc?.longitude ?: settings.savedLocations.firstOrNull()?.longitude
        val wxName = loc?.name ?: settings.savedLocations.firstOrNull()?.name ?: ""
        val wx = if (wxLat != null && wxLon != null)
            runCatching { container.weatherRepository.fetch(wxLat, wxLon, wxName, force = false).data }.getOrNull()
        else null
        // The comfort indices are defined in Celsius and km/h, and the repository now carries
        // canonical companions converted at the one point the unit setting is known. Reading those
        // replaces the hand conversion that used to live here and never covered wind at all.
        val tempC = wx?.current?.temperatureC
        val precip = wx?.daily?.firstOrNull()?.precipProbabilityMax
        val uv = wx?.daily?.firstOrNull()?.uvIndexMax
        // Tonight's low, taken as the minimum of the next twelve hourly readings rather than from a
        // daily figure. A daily minimum belongs to a calendar day, so in the evening today's is
        // already behind you and tomorrow's covers a night that has not started.
        val overnightLow = wx?.hourly.orEmpty()
            .dropWhile { h -> WeatherFormat.parseHourly(h.timeIso)?.time?.let { it < now } ?: true }
            .take(12)
            .mapNotNull { it.temperatureC }
            .minOrNull()

        val movers = runCatching {
            container.marketsRepository.fetchAll(force = false).data
                .mapNotNull { q -> q.changePercent?.let { OracleMover(name = q.label, changePct = it, onWatchlist = true) } }
        }.getOrDefault(emptyList())

        val kp = runCatching { container.spaceWeatherRepository.fetch(force = false).data.kp }.getOrNull()

        val emergency = runCatching {
            container.newsRepository.fetchCategory(NewsCategory.TOP, force = false).data
                .maxByOrNull { EmergencyNews.severity(it.title, it.summary) }
                ?.takeIf { EmergencyNews.isMajor(it.title, it.summary) }?.title
        }.getOrNull()

        val dc = runCatching { container.deviceContextProvider.snapshot() }.getOrNull()
        val storageFreePct = runCatching {
            val st = StatFs(container.applicationContext.filesDir.path)
            (st.availableBytes * 100 / st.totalBytes).toInt()
        }.getOrNull()

        val feat = runCatching {
            UsageInsights.featureForHour(container.usageRepository.snapshot(), hour)
        }.getOrNull()

        // Sensorium: the live environment read + the top learned-normal deviation + the storm signal.
        // movement comes from the fusion EWMA; awayFromHome comes from the Trusted-Network home-SSID
        // concept (the app's only honest "home" definition — never the INDOOR/OUTDOOR scene guess),
        // and stays null when no home SSID is configured or the SSID is unreadable.
        val sensed = runCatching { container.sensoriumEngine.reading.value }.getOrNull()
        val topAnomaly = runCatching {
            container.sensoriumEngine.anomalies.value.firstOrNull()?.let { "${it.metric} ${it.text}" }
        }.getOrNull()
        val movement = runCatching { container.sensorFusion.snapshot.value.movement }.getOrDefault(0f)
        val plunging = sensed?.pressureTrend == PressureTrend.PLUNGING
        val awayFromHome = runCatching {
            val home = settings.security.homeSsids
            if (home.isEmpty()) null
            else container.wifiPolicyController.currentSsid()
                ?.let { ssid -> home.none { it.equals(ssid, ignoreCase = true) } }
        }.getOrNull()

        // Study — how the bundled library is going. Warm reads on stores that are already loaded, and
        // every failure leaves the field at its neutral default, which mutes that field's own rules.
        val study = runCatching { container.studyStore.progress() }.getOrNull()
        val reviewsDue = runCatching { container.studyStore.dueCount() }.getOrDefault(0)
        // "Studied today" is decided by the same local-day index the deck itself counts by — deriving a
        // day boundary here from UTC would tell somebody outside Greenwich their streak was at risk on
        // the wrong evening.
        val today = localDayIndex(now)
        val studiedToday = study != null && study.lastStudiedAtMs > 0L &&
            localDayIndex(study.lastStudiedAtMs) == today
        val shaky = runCatching {
            container.studyStore.weakestGuide()
        }.getOrNull()

        return OracleSignals(
            nowMs = now, hourOfDay = hour, minuteOfDay = minute, dayOfWeek = dow,
            lat = loc?.latitude, lon = loc?.longitude, placeName = loc?.name, speedMps = loc?.speedMps,
            movement = movement, awayFromHome = awayFromHome,
            events = events, pendingTasks = pendingTasks, interests = interests,
            tempC = tempC, precipChancePct = precip, uvIndex = uv,
            humidityPct = wx?.current?.humidity, dewPointC = wx?.current?.dewPointC,
            windKmh = wx?.current?.windKmh, gustKmh = wx?.current?.gustKmh,
            overnightLowC = overnightLow,
            movers = movers, emergencyHeadline = emergency, kpIndex = kp,
            batteryPct = dc?.batteryPct?.takeIf { it >= 0 }, charging = dc?.isCharging ?: false,
            storageFreePct = storageFreePct, onCellular = dc?.let { it.network == NetworkKind.CELLULAR },
            habitualRoute = feat?.key, habitualLabel = feat?.let { FeatureCatalog.labelFor(it.key) },
            envDescription = sensed?.describe(),
            envAnomaly = topAnomaly,
            pressureFallingFast = plunging,
            reviewsDue = reviewsDue,
            studyStreakDays = study?.streakDays ?: 0,
            studiedToday = studiedToday,
            shakyGuideTitle = shaky?.first,
            shakyGuideDetail = shaky?.second,
        )
    }

    /**
     * The full ranked read, re-ranked by what has actually helped.
     *
     * The pure engine's urgency × timeliness × relevance is a good prior that knows nothing about
     * this particular person; [OracleLearningStore] supplies the correction from the app's own visit
     * log. It closes the loop as a side effect of reading: settle the previous read against where
     * the user went, then record this one.
     *
     * ⚠️ [visible] is how many of the returned insights the caller will actually put in front of the
     * user, and it bounds what gets recorded as shown. This is not a detail. A rule that never earns
     * a row cannot be acted on, so counting it as shown would drive its hit rate toward the floor
     * for a reason that has nothing to do with the user — the statistic would measure screen space,
     * not usefulness. Home renders three; the Advisories screen renders the lot and leaves the
     * default alone. The full list is always returned either way.
     *
     * **Zero means this read teaches nothing and disturbs nothing** — it skips the learning step
     * entirely rather than recording an empty show. That distinction is load-bearing: recording
     * would also clear the pending attribution from whichever surface last showed something, so a
     * background or speculative read landing between a show and the user acting on it would erase
     * the very evidence the learning exists to collect. Callers that cannot know whether the user
     * saw the result — the `oracle` tool, whose output the model may summarise or drop — pass zero.
     *
     * Re-ranking happens BEFORE recording so that "the first [visible]" is the order the surface
     * renders, and it reads the state this pass is about to update — the ranking the user sees
     * should reflect what was learned before this read, not part-way through it.
     *
     * Best-effort — if learning fails for any reason the caller still gets the engine's own ranking,
     * which is what it got before any of this existed.
     */
    suspend fun read(
        container: AppContainer,
        settings: AppSettings,
        visible: Int = Int.MAX_VALUE,
    ): List<Insight> {
        val signals = snapshot(container, settings)
        val raw = Oracle.divine(signals)
        return runCatching {
            val store = container.oracleLearningStore
            val ranked = store.reweight(raw)
            if (visible > 0) {
                val visits = container.usageRepository.recentActivity(VISIT_LOOKBACK)
                    .filter { it.category == "nav" }
                    .map { OracleMemory.Visit(it.label, it.epochMs) }
                store.settleAndRecord(ranked.take(visible), visits, signals.habitualRoute, signals.nowMs)
            }
            ranked
        }.getOrDefault(raw)
    }

    /**
     * How far back to read the visit log when attributing.
     *
     * The attribution window is half an hour, so this only has to be deep enough that a busy half
     * hour of navigation cannot push the relevant visit off the end of the buffer.
     */
    private const val VISIT_LOOKBACK = 120
}
