package dev.mascwa.pulse.desktop.standby

import dev.mascwa.pulse.core.telemetry.Freshness
import dev.mascwa.pulse.core.telemetry.MarketMood
import dev.mascwa.pulse.core.telemetry.Oracle
import dev.mascwa.pulse.core.telemetry.SatellitePasses
import dev.mascwa.pulse.core.telemetry.SpaceWeatherExplainers
import dev.mascwa.pulse.core.telemetry.Stardate
import dev.mascwa.pulse.core.telemetry.WeatherComfort
import dev.mascwa.pulse.data.markets.MarketsRepository
import dev.mascwa.pulse.desktop.news.NewsCategory
import dev.mascwa.pulse.desktop.news.NewsRepository
import dev.mascwa.pulse.data.orbital.TleRepository
import dev.mascwa.pulse.data.space.SpaceWeatherRepository
import dev.mascwa.pulse.data.weather.WeatherCode
import dev.mascwa.pulse.data.weather.WeatherRepository
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.settings.DesktopUnits
import dev.mascwa.pulse.desktop.study.StudyStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Gathers everything the standby display shows, and records what happened to each feed.
 *
 * ⚠️ **Per-source budgets, never one shared timeout.** The phone's widget shipped with all its
 * sources inside a single `withTimeoutOrNull`, so one slow feed discarded every result including
 * the ones that had already finished, and each blank line then hid itself — the widget appeared to
 * lose features and nothing recorded that it had happened. Here the feeds run **concurrently, each
 * on its own rope**, so a slow one costs its own panel and nothing else, and there is deliberately
 * no outer timeout to throw away what already arrived.
 *
 * Nothing here is forced. Every read warms off the caches the ordinary screens already filled.
 */
class StandbyEngine(
    private val settings: DesktopSettingsStore,
    private val weather: WeatherRepository,
    private val markets: MarketsRepository,
    private val space: SpaceWeatherRepository,
    private val news: NewsRepository,
    private val study: StudyStore,
    private val tle: TleRepository? = null,
    /**
     * What the radio is playing, if anything is playing at all.
     *
     * A lambda rather than the player itself, because the screensaver and lock-image renders run in
     * their own short-lived processes where no player exists — and "there is no player here" is a
     * [StandbyDiagnostics.Outcome.Skipped], not a failure to hide.
     */
    private val nowPlaying: () -> String? = { null },
) {

    /** What the concurrent pass produced, so the assembly below reads as one place. */
    private data class Gathered(
        val insights: List<dev.mascwa.pulse.core.telemetry.Insight>,
        val wx: dev.mascwa.pulse.data.weather.WeatherData?,
        val quotes: List<dev.mascwa.pulse.data.markets.Quote>,
        val headlines: List<String>,
        val deck: Pair<Int, Int>?,
        val kp: Double?,
        val iss: String?,
        val playing: String?,
        val machine: MachineVitals?,
    )

    suspend fun gather(): StandbyState {
        val started = System.currentTimeMillis()
        val outcomes = java.util.Collections.synchronizedMap(
            LinkedHashMap<StandbyDiagnostics.Source, StandbyDiagnostics.Outcome>(),
        )

        val prefs = runCatching { settings.current() }.getOrNull()
        val lat = prefs?.latitude
        val lon = prefs?.longitude
        val place = prefs?.placeLabel?.ifBlank { null }
        val twelveHour = prefs?.twelveHourClock ?: false
        val fahrenheit = prefs?.fahrenheit ?: false

        // ⚠️ Written from several coroutines at once, so not a plain `var`. The feeds below run
        // concurrently and each reports the age of what it was served.
        val oldest = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
        fun sawFetch(ms: Long) {
            if (ms > 0) oldest.accumulateAndGet(ms, ::minOf)
        }

        val now = System.currentTimeMillis()
        val local = LocalDateTime.now()

        // ⚠️ Concurrent, and each on its OWN budget. Run in sequence the worst case would be the
        // sum of every rope; run under one shared timeout a single slow feed would discard the
        // results that had already arrived — which is precisely the defect the phone's widget
        // shipped with. Concurrent-with-private-budgets is the only arrangement that is neither.
        val gathered = coroutineScope {
            val oracleJob = async {
                // The Oracle is the only reader that weighs several feeds against each other, so it
                // gets the longest rope.
                source(StandbyDiagnostics.Source.ORACLE, outcomes, BUDGET_ORACLE) {
                    val signals = gatherOracleSignals(settings, weather, markets, space, news, study)
                    Oracle.divine(signals).ifEmpty { null }
                }.orEmpty()
            }
            val wxJob = async {
                if (lat == null || lon == null) {
                    outcomes[StandbyDiagnostics.Source.WEATHER] =
                        StandbyDiagnostics.Outcome.Skipped("this machine does not know where it is")
                    null
                } else {
                    source(StandbyDiagnostics.Source.WEATHER, outcomes) {
                        weather.fetch(lat, lon, place ?: "Here", force = false)
                            .also { sawFetch(it.timestampEpochMs) }.data
                    }
                }
            }
            val quotesJob = async {
                source(StandbyDiagnostics.Source.MARKETS, outcomes) {
                    markets.fetchAll(force = false).also { sawFetch(it.timestampEpochMs) }
                        .data.filter { it.changePercent != null }.ifEmpty { null }
                }.orEmpty()
            }
            val newsJob = async {
                source(StandbyDiagnostics.Source.NEWS, outcomes) {
                    news.headlines(NewsCategory.TOP, force = false).getOrNull()
                        ?.also { sawFetch(it.timestampEpochMs) }
                        ?.data?.take(HEADLINES)?.map { "${it.source.uppercase()} · ${it.title}" }
                        ?.ifEmpty { null }
                }.orEmpty()
            }
            val deckJob = async {
                source(StandbyDiagnostics.Source.STUDY, outcomes) {
                    val due = study.dueCount()
                    val streak = study.progress().streakDays
                    if (due <= 0 && streak <= 0) null else due to streak
                }
            }
            val kpJob = async {
                source(StandbyDiagnostics.Source.SPACE, outcomes) {
                    space.fetch(force = false, heavy = false).also { sawFetch(it.timestampEpochMs) }.data.kp
                }
            }
            val issJob = async {
                if (tle == null || lat == null || lon == null) {
                    outcomes[StandbyDiagnostics.Source.SKY] = StandbyDiagnostics.Outcome.Skipped(
                        if (tle == null) {
                            "no orbit feed in this process"
                        } else {
                            "this machine does not know where it is"
                        },
                    )
                    null
                } else {
                    source(StandbyDiagnostics.Source.SKY, outcomes) {
                        // ⚠️ `cachedElement`, never `element`. A display that redraws on a timer
                        // must not start a Celestrak fetch; the observatory keeps the orbit current
                        // and this reads what it left behind. No cache is nothing to report.
                        val elements = tle.cachedElement(ISS_NORAD_ID) ?: return@source null
                        val sight = SatellitePasses.sighting(
                            elements,
                            SatellitePasses.Site(lat, lon),
                            System.currentTimeMillis(),
                        ) ?: return@source null
                        // Only when it is genuinely worth walking outside for. Announcing a station
                        // three degrees up behind a building, or one in Earth's shadow, teaches a
                        // reader to ignore the line.
                        if (!sight.worthLookingUp || sight.kind != SatellitePasses.PassKind.VISIBLE) {
                            return@source null
                        }
                        "ISS ${sight.look.altitudeDeg.roundToInt()}° UP · " +
                            dev.mascwa.pulse.core.telemetry.Geodesy.cardinal(sight.look.azimuthDeg)
                    }
                }
            }
            val radioJob = async { source(StandbyDiagnostics.Source.RADIO, outcomes) { nowPlaying() } }
            val machineJob = async { source(StandbyDiagnostics.Source.MACHINE, outcomes) { readVitals() } }

            Gathered(
                insights = oracleJob.await(),
                wx = wxJob.await(),
                quotes = quotesJob.await(),
                headlines = newsJob.await(),
                deck = deckJob.await(),
                kp = kpJob.await(),
                iss = issJob.await(),
                playing = radioJob.await(),
                machine = machineJob.await(),
            )
        }

        val insights = gathered.insights
        val wx = gathered.wx
        val quotes = gathered.quotes
        val headlines = gathered.headlines
        val deck = gathered.deck
        val kp = gathered.kp
        val iss = gathered.iss
        val playing = gathered.playing
        val machine = gathered.machine

        val current = wx?.current
        val unit = wx?.tempUnitSymbol.orEmpty()
        val mood = MarketMood.summarize(quotes.mapNotNull { it.changePercent })

        val report = StandbyDiagnostics.Report(
            atMs = now,
            outcomes = LinkedHashMap(outcomes),
            rungs = StandbyDiagnostics.last?.rungs.orEmpty(),
            elapsedMs = System.currentTimeMillis() - started,
        )
        StandbyDiagnostics.record(report)

        return StandbyState(
            stardate = runCatching {
                val offset = TimeZone.getDefault().getOffset(now) / 1000
                "STARDATE ${Stardate.format(Stardate.at(now, offset))}"
            }.getOrDefault(""),
            clock = runCatching { local.format(DesktopUnits.clock(twelveHour)) }.getOrDefault(""),
            dateLine = runCatching {
                local.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())).uppercase()
            }.getOrDefault(""),
            placeName = place.orEmpty().uppercase(),
            insights = insights,
            briefing = Oracle.briefing(insights),
            temperature = current?.temperature?.let { "${it.roundToInt()}$unit" }.orEmpty(),
            condition = current?.let { WeatherCode.describe(it.weatherCode) }.orEmpty(),
            feelsLike = WeatherComfort.compactFeelsLike(
                temperatureC = current?.temperatureC,
                humidityPercent = current?.humidity,
                windKmh = current?.windKmh,
                unitSymbol = if (fahrenheit) "°F" else "°C",
            ).orEmpty(),
            weatherDetail = listOfNotNull(
                wx?.daily?.firstOrNull()?.let { d ->
                    // ⚠️ Hoisted to locals rather than null-checked in place. `DailyPoint` is
                    // declared in `:core:feeds`, and Kotlin will not smart-cast a public property
                    // from another module — the trap that has cost this project three CI rounds and
                    // that no local gate can see.
                    val hi = d.tempMax
                    val lo = d.tempMin
                    if (hi != null && lo != null) "↑${hi.roundToInt()}$unit ↓${lo.roundToInt()}$unit" else null
                },
                current?.humidity?.let { "${it.roundToInt()}% RH" },
                wx?.airQuality?.usAqi?.let { "AQI ${it.roundToInt()}" },
            ).joinToString("   ·   "),
            hourlyTemps = wx?.hourly.orEmpty().mapNotNull { it.temperatureC }.take(HOURS),
            mood = mood,
            movers = quotes.sortedByDescending { abs(it.changePercent ?: 0.0) }
                .take(MOVERS).map { it.label to (it.changePercent ?: 0.0) },
            headlines = headlines,
            reviewsDue = deck?.first ?: 0,
            studyStreakDays = deck?.second ?: 0,
            spaceWeather = kp?.let { "KP ${"%.1f".format(Locale.US, it)} · ${SpaceWeatherExplainers.kp(it).headline}" }
                .orEmpty(),
            issLine = iss.orEmpty(),
            nowPlaying = playing.orEmpty(),
            machine = machine ?: MachineVitals(),
            freshness = if (oldest.get() == Long.MAX_VALUE) {
                ""
            } else {
                Freshness.assess(
                    lastUpdatedMs = oldest.get(),
                    nowMs = now,
                    online = true,
                    servingStored = false,
                    refreshFailed = report.unavailable.isNotEmpty(),
                ).let { if (it.worthShowing) it.label else "" }
            },
            report = report,
        )
    }

    /**
     * Run one feed on its own budget, and record what happened either way.
     *
     * Returns null when there is nothing to draw — but the *reason* is never lost: it lands in
     * [outcomes] as failed, timed out or genuinely empty.
     */
    private suspend fun <T> source(
        which: StandbyDiagnostics.Source,
        outcomes: MutableMap<StandbyDiagnostics.Source, StandbyDiagnostics.Outcome>,
        budgetMs: Long = BUDGET_ONE,
        block: suspend () -> T?,
    ): T? {
        val result = withTimeoutOrNull(budgetMs) { runCatching { block() } }
        if (result == null) {
            outcomes[which] = StandbyDiagnostics.Outcome.TimedOut
            return null
        }
        result.exceptionOrNull()?.let {
            outcomes[which] = StandbyDiagnostics.Outcome.Failed(StandbyDiagnostics.describe(it))
            return null
        }
        val value = result.getOrNull()
        val blank = value == null ||
            (value is String && value.isBlank()) ||
            (value is Collection<*> && value.isEmpty())
        outcomes[which] = if (blank) StandbyDiagnostics.Outcome.Empty else StandbyDiagnostics.Outcome.Ok
        return if (blank) null else value
    }

    /**
     * What the machine itself is doing.
     *
     * ⚠️ `com.sun.management.OperatingSystemMXBean` is the only way to see real machine memory and
     * CPU load from the JVM, and it lives in the `jdk.management` module — which jpackage's jlink
     * step strips unless it is listed. The cast is guarded and the JVM-heap fallback below is what
     * makes a missing module cost a number rather than the whole display.
     */
    private fun readVitals(): MachineVitals {
        val os = runCatching {
            java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                as? com.sun.management.OperatingSystemMXBean
        }.getOrNull()

        val cpu = os?.cpuLoad?.takeIf { it in 0.0..1.0 }?.let { (it * 100).roundToInt() } ?: -1
        val memPct = os?.let {
            val total = it.totalMemorySize
            val free = it.freeMemorySize
            if (total > 0) (((total - free).toDouble() / total) * 100).roundToInt() else -1
        } ?: runCatching {
            val rt = Runtime.getRuntime()
            (((rt.totalMemory() - rt.freeMemory()).toDouble() / rt.maxMemory()) * 100).roundToInt()
        }.getOrDefault(-1)

        val disk = runCatching {
            val root = File(System.getProperty("user.home") ?: ".")
            val total = root.totalSpace
            if (total > 0) (((total - root.usableSpace).toDouble() / total) * 100).roundToInt() else -1
        }.getOrDefault(-1)

        val uptime = runCatching {
            val ms = java.lang.management.ManagementFactory.getRuntimeMXBean().uptime
            val h = ms / 3_600_000
            val m = (ms % 3_600_000) / 60_000
            if (h > 0) "${h}h ${m}m" else "${m}m"
        }.getOrDefault("")

        return MachineVitals(
            cpuLoadPct = cpu,
            memoryUsedPct = memPct,
            diskUsedPct = disk,
            uptime = uptime,
            build = runCatching { dev.mascwa.pulse.desktop.update.BuildInfo.display }.getOrDefault(""),
        )
    }

    private companion object {
        /** One feed's rope. Generous enough for a cold cache, short enough to never own the render. */
        const val BUDGET_ONE = 4_000L

        /** The Oracle reads several feeds itself, so it gets more. */
        const val BUDGET_ORACLE = 9_000L

        // ⚠️ There is deliberately NO outer budget. The feeds run concurrently, so the worst case
        // is already bounded by the longest single rope — and a timeout wrapping the whole gather
        // would discard the results that had already arrived, which is the exact defect this file's
        // header describes. A constant declared here and never enforced would be worse than absent.

        const val HEADLINES = 5
        const val MOVERS = 6
        const val HOURS = 24
        const val ISS_NORAD_ID = 25544
    }
}
