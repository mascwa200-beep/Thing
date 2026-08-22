package dev.mascwa.pulse.desktop.ledger

import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.data.markets.MarketsRepository
import dev.mascwa.pulse.data.orbital.OrbitalRepository
import dev.mascwa.pulse.data.orbital.TleRepository
import dev.mascwa.pulse.data.radar.RadarRepository
import dev.mascwa.pulse.data.safety.SafetyRepository
import dev.mascwa.pulse.data.settings.MarketPreferences
import dev.mascwa.pulse.data.space.SpaceWeatherRepository
import dev.mascwa.pulse.data.weather.WeatherRepository
import dev.mascwa.pulse.desktop.AppPaths
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.settings.DesktopUnits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Putting the long watch together, for the two processes that run it.
 *
 * The console collects on a timer while it is open; the scheduled task collects while it is not.
 * Both go through [CollectorLock], so only one is ever writing.
 */
object LongWatch {

    /** How often the in-app timer looks for work. Domains decide for themselves whether they are due. */
    const val TICK_MS = 5 * 60 * 1000L

    /**
     * A collector for the **headless** `--collect` pass, with everything of its own.
     *
     * ⚠️ `cacheDir = null`, so this has no OkHttp disk cache at all. `DesktopUpdater` already
     * documents why — two OkHttp caches over one directory corrupt each other — and this process can
     * be running while the console is open. The scratch [DiskCache] gets its own root for the same
     * reason. Neither costs anything real: the collector wants fresh readings, so a warm cache would
     * be actively unhelpful.
     */
    fun headless(settings: DesktopSettingsStore): Collector {
        val json = HttpClient.defaultJson()
        val http = HttpClient.create(json, cacheDir = null)
        val cache = DiskCache(AppPaths.dataDir.resolve(SCRATCH_CACHE).toFile(), json)
        return build(settings, http, cache)
    }

    /**
     * A collector over the console's own client and cache.
     *
     * The shell hoists one `HttpClient` and one `DiskCache` for everything precisely so the
     * connection pool and the per-host rate gates stay honest, and a second set inside the running
     * process would undo that.
     */
    fun inApp(
        settings: DesktopSettingsStore,
        http: HttpClient,
        cache: DiskCache,
        weather: WeatherRepository,
        space: SpaceWeatherRepository,
        markets: MarketsRepository,
    ): Collector = Collector(
        ledger = WorldLedger(),
        settings = settings,
        weather = weather,
        space = space,
        markets = markets,
        radar = RadarRepository(http, cache, TleRepository(http, cache)),
        safety = SafetyRepository(http, cache),
        orbital = OrbitalRepository(http, cache) { NASA_DEMO_KEY },
    )

    private fun build(settings: DesktopSettingsStore, http: HttpClient, cache: DiskCache): Collector =
        Collector(
            ledger = WorldLedger(),
            settings = settings,
            weather = WeatherRepository(http, cache) { DesktopUnits.weatherPreferences(settings.current()) },
            space = SpaceWeatherRepository(http, cache),
            // ⚠️ Deliberately the DEFAULTS, not the reader's watch list. The recorded basket is
            // declared in MetricRegistry.INSTRUMENTS and passed to `quotesFor` directly; these
            // preferences only supply the currency, and a series must not change shape because
            // somebody edited a watch list.
            markets = MarketsRepository(http, cache) { MarketPreferences() },
            radar = RadarRepository(http, cache, TleRepository(http, cache)),
            safety = SafetyRepository(http, cache),
            orbital = OrbitalRepository(http, cache) { NASA_DEMO_KEY },
        )

    /**
     * Run exactly one pass and describe it — the whole of what `--collect` does.
     *
     * Never throws. A scheduled task that fails loudly has nobody to tell, so the outcome goes in
     * the one-line log the scanner reads instead.
     */
    suspend fun runOnce(settings: DesktopSettingsStore): String {
        val line = runCatching {
            val pass = headless(settings).runPass()
            pass?.describe() ?: "another pass was already running"
        }.getOrElse { "the pass failed: ${it.message.orEmpty().ifBlank { it::class.simpleName.orEmpty() }}" }
        return ScheduledCollect.record(line)
    }

    /**
     * The console's own timer.
     *
     * ⚠️ **Not gated on window visibility**, unlike the news live feed. That one is a courtesy to a
     * screen nobody is looking at; this is a recorder, and a hole in the record is the one thing it
     * exists to prevent. It stops when the process does, and the scheduled task covers that.
     */
    class Session(private val scope: CoroutineScope, private val collector: Collector) {
        private var job: Job? = null

        fun start() {
            if (job?.isActive == true) return
            job = scope.launch {
                while (isActive) {
                    runCatching { collector.runPass() }
                    delay(TICK_MS)
                }
            }
        }

        fun stop() {
            job?.cancel()
            job = null
        }
    }

    /** Where the headless pass keeps its own scratch cache, away from the console's. */
    private const val SCRATCH_CACHE = "collector-cache"

    /**
     * NASA's shared demo key, as the console's own orbital repository uses. The only field recorded
     * from it is a hazardous-object count, and the key-gated half is not read here.
     */
    private const val NASA_DEMO_KEY = "DEMO_KEY"
}
