package dev.mascwa.pulse.data.oracle

import android.os.StatFs
import dev.mascwa.pulse.core.telemetry.EmergencyNews
import dev.mascwa.pulse.core.telemetry.Insight
import dev.mascwa.pulse.core.telemetry.NetworkKind
import dev.mascwa.pulse.core.telemetry.Oracle
import dev.mascwa.pulse.core.telemetry.OracleEvent
import dev.mascwa.pulse.core.telemetry.OracleMover
import dev.mascwa.pulse.core.telemetry.OracleSignals
import dev.mascwa.pulse.core.telemetry.ProfileCategory
import dev.mascwa.pulse.core.telemetry.TaskBoard
import dev.mascwa.pulse.core.telemetry.UsageInsights
import dev.mascwa.pulse.core.telemetry.UserProfile
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.data.news.NewsCategory
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.data.settings.TemperatureUnit
import dev.mascwa.pulse.data.usage.FeatureCatalog
import dev.mascwa.pulse.di.AppContainer
import kotlinx.serialization.Serializable
import java.util.Calendar

/** Per-insight last-fired bookkeeping so a proactive Oracle push can't repeat too soon. */
@Serializable
data class OracleState(val firedMs: Map<String, Long> = emptyMap())

/**
 * The on-device ORACLE — gathers a full [OracleSignals] snapshot from every store/repository, runs the pure
 * [Oracle] reasoning engine, and (from the background worker) fires ONE throttled proactive push for the most
 * important thing worth interrupting you for. All reads are defensive + `force = false` (warm the caches the
 * worker already fills); a missing signal (no permission / null) simply mutes its rules.
 */
object OracleEngine {

    private const val STATE_KEY = "oracle_state"
    private const val PUSH_THROTTLE_MS = 3 * 60 * 60 * 1000L // don't repeat the same insight within 3h

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

        val life = runCatching { container.specialGameStore.lifeSnapshot() }.getOrNull()
        val needs = if (life != null) mapOf(
            "HYDRATION" to life.hydration, "ENERGY" to life.energy,
            "NOURISHMENT" to life.nourishment, "HYGIENE" to life.hygiene,
        ) else emptyMap()

        // Weather — device location first, else a saved location. Convert temperature to °C (the API returns
        // it in the user's chosen unit).
        val wxLat = loc?.latitude ?: settings.savedLocations.firstOrNull()?.latitude
        val wxLon = loc?.longitude ?: settings.savedLocations.firstOrNull()?.longitude
        val wxName = loc?.name ?: settings.savedLocations.firstOrNull()?.name ?: ""
        val wx = if (wxLat != null && wxLon != null)
            runCatching { container.weatherRepository.fetch(wxLat, wxLon, wxName, force = false).data }.getOrNull()
        else null
        val tempC = wx?.current?.temperature?.let {
            if (settings.temperatureUnit == TemperatureUnit.FAHRENHEIT) (it - 32.0) * 5.0 / 9.0 else it
        }
        val precip = wx?.daily?.firstOrNull()?.precipProbabilityMax
        val uv = wx?.daily?.firstOrNull()?.uvIndexMax

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

        return OracleSignals(
            nowMs = now, hourOfDay = hour, minuteOfDay = minute, dayOfWeek = dow,
            lat = loc?.latitude, lon = loc?.longitude, placeName = loc?.name, speedMps = loc?.speedMps,
            events = events, pendingTasks = pendingTasks, interests = interests, needs = needs,
            tempC = tempC, precipChancePct = precip, uvIndex = uv,
            movers = movers, emergencyHeadline = emergency, kpIndex = kp,
            batteryPct = dc?.batteryPct?.takeIf { it >= 0 }, charging = dc?.isCharging ?: false,
            storageFreePct = storageFreePct, onCellular = dc?.let { it.network == NetworkKind.CELLULAR },
            habitualRoute = feat?.key, habitualLabel = feat?.let { FeatureCatalog.labelFor(it.key) },
            stepsToday = life?.stepsToday?.takeIf { it > 0 },
        )
    }

    /** Compute the full ranked read (for the surface / ViewModel). */
    suspend fun read(container: AppContainer, settings: AppSettings): List<Insight> =
        Oracle.divine(snapshot(container, settings))

    /**
     * Background pass: fire ONE throttled push for the most important interrupt-worthy insight. Called from
     * [dev.mascwa.pulse.notifications.RefreshWorker]; gated by the caller's opt-out + master switch.
     */
    suspend fun run(container: AppContainer, settings: AppSettings) {
        val signals = snapshot(container, settings)
        val insights = Oracle.divine(signals)

        // The WORLD PULSE — a quiet, always-latest ambient feed of the world woven with your day. It updates
        // in place every pass (silent MIN channel), so it just reflects the current read (no throttle/dedup).
        if (settings.notifications.worldPulse) {
            Oracle.worldPulse(signals, insights)?.let { container.notifier.notifyWorldPulse(it) }
        }

        if (!settings.notifications.oracleEnabled) return
        val push = Oracle.pushWorthy(insights)
        if (push.isEmpty()) return
        val state = runCatching { container.diskCache.readAny(STATE_KEY, OracleState.serializer())?.value }.getOrNull()
            ?: OracleState()
        val now = signals.nowMs
        val top = push.firstOrNull { now - (state.firedMs[it.id] ?: 0L) >= PUSH_THROTTLE_MS } ?: return
        container.notifier.notifyOracle(top)
        val fired = (state.firedMs + (top.id to now)).entries
            .sortedByDescending { it.value }.take(40).associate { it.key to it.value }
        runCatching { container.diskCache.write(STATE_KEY, OracleState(fired), OracleState.serializer()) }
    }
}
