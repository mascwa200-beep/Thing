package dev.mascwa.pulse.desktop.ledger

import dev.mascwa.pulse.core.telemetry.Novelty
import dev.mascwa.pulse.data.markets.MarketsRepository
import dev.mascwa.pulse.data.orbital.OrbitalRepository
import dev.mascwa.pulse.data.radar.RadarRepository
import dev.mascwa.pulse.data.safety.SafetyRepository
import dev.mascwa.pulse.data.space.SpaceWeatherRepository
import dev.mascwa.pulse.data.weather.WeatherRepository
import dev.mascwa.pulse.desktop.feature.world.here
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore

/**
 * One pass of the long watch: ask what is due, write down what came back.
 *
 * Shaped after `standby/OracleSnapshot.gatherOracleSignals`, the existing read-everything-at-once
 * function — defensively per feed, so a provider having a bad afternoon costs its own domain and
 * nothing else. There is no partial-failure state to model here: a domain that could not be read
 * simply has no row this pass, and a gap in a series is a thing the scoring already understands.
 *
 * ## What "due" means, and why it is not one tick
 *
 * ⚠️ Each domain declares its own cadence in [MetricRegistry.Domain]. Space weather moves in
 * minutes; a market close does not change between two Tuesdays in August. Asking everything every
 * quarter hour would be wasteful, and against free public APIs it would be rude — this repo has
 * already had its IP durably banned by one provider from an eleven-request burst.
 *
 * Dueness is asked of the **ledger**, not of a timer, so it survives the process dying: whatever
 * was last written is when that domain was last read, whichever process wrote it.
 *
 * ## ⚠️ This is deliberately not the app's HTTP client
 *
 * The headless pass is given its own [MarketsRepository] and friends built over a client with no
 * disk cache and its own scratch directory. `DesktopUpdater` documents the reason — two OkHttp
 * caches over one directory corrupt each other — and a scheduled task can fire while the console is
 * open. The collector wants fresh readings anyway, so it loses nothing by not sharing a warm cache.
 */
class Collector(
    private val ledger: WorldLedger,
    private val settings: DesktopSettingsStore,
    private val weather: WeatherRepository,
    private val space: SpaceWeatherRepository,
    private val markets: MarketsRepository,
    private val radar: RadarRepository,
    private val safety: SafetyRepository,
    private val orbital: OrbitalRepository,
    private val backfill: Backfill? = null,
) {

    /** What a pass did, in one sentence, for the log and the diagnostics line. */
    data class Pass(
        val recorded: Int,
        val domains: List<String>,
        val skipped: List<String>,
        val backfilled: Backfill.Report? = null,
    ) {
        fun describe(): String {
            val collected = when {
                recorded == 0 && domains.isEmpty() -> "nothing was due"
                recorded == 0 -> "nothing came back from ${domains.joinToString(", ")}"
                else -> "$recorded readings from ${domains.joinToString(", ")}"
            }
            return backfilled?.let { "$collected; backfill: ${it.describe()}" } ?: collected
        }
    }

    /**
     * Collect everything due at [nowMs].
     *
     * ⚠️ Takes the cross-process lock first and returns null if another pass holds it. See
     * [CollectorLock]: two processes appending to one ledger is how a series ends up with duplicate
     * readings that quietly inflate every sample count.
     */
    suspend fun runPass(nowMs: Long = System.currentTimeMillis()): Pass? {
        if (!CollectorLock.tryAcquire()) return null
        return try {
            val pass = collect(nowMs)
            // Pruned inside the same lock, so the fold can never race an append. Cheap on the passes
            // where nothing is stale: it lists a few dozen directories and finds no month behind the
            // cutoff. Doing it here rather than on its own timer means there is exactly one place
            // that touches the ledger's shape.
            runCatching { ledger.prune(nowMs) }
            pass
        } finally {
            CollectorLock.release()
        }
    }

    private suspend fun collect(nowMs: Long): Pass {
        val place = settings.here()
        val placeKey = place?.let { MetricRegistry.placeKey(it.first, it.second) }

        // ⚠️ Before the live readings, and inside the same lock. Before, because a metric filled from
        // history is scoreable on this very pass rather than the next one; inside the lock, because a
        // year of hourly weather appended twice would double every sample count in it. Its own marker
        // files mean this is a no-op on all but the first pass at a given place.
        val backfilled = place?.let { (lat, lon) ->
            runCatching { backfill?.runOnce(lat, lon, placeKey, nowMs) }.getOrNull()
        }

        var recorded = 0
        val read = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        for (domain in MetricRegistry.Domain.entries) {
            if (!due(domain, placeKey, nowMs)) continue
            if (domain.locationBound && place == null) {
                // Not a failure: a desktop that has never been told where it is genuinely cannot
                // record the weather outside. The scanner says so rather than showing a silent gap.
                skipped += "${domain.label} (nowhere set)"
                continue
            }

            val values = runCatching { readDomain(domain, place) }.getOrElse { emptyList() }
            if (values.isEmpty()) {
                skipped += domain.label
                continue
            }

            read += domain.label
            values.forEach { (id, v) ->
                val spec = MetricRegistry.BY_ID[id] ?: return@forEach
                ledger.append(spec.key(placeKey), Novelty.Observation(nowMs, v))
                recorded++
            }
        }

        return Pass(recorded, read, skipped, backfilled)
    }

    /**
     * Whether [domain] is due, judged by when its metrics were last written.
     *
     * The **newest** write across the domain's metrics, so one metric that a provider stopped
     * returning cannot hold the whole domain permanently due and re-fetch it every pass forever.
     */
    private suspend fun due(domain: MetricRegistry.Domain, placeKey: String?, nowMs: Long): Boolean {
        val last = MetricRegistry.of(domain).mapNotNull { ledger.lastAt(it.key(placeKey)) }.maxOrNull()
        return isDue(last, domain.cadenceMs, nowMs)
    }

    private suspend fun readDomain(
        domain: MetricRegistry.Domain,
        place: Pair<Double, Double>?,
    ): List<Pair<String, Double>> = when (domain) {
        // Weather and air quality arrive in one response, so the same fetch feeds both — and their
        // cadences match, so it is fetched once and split rather than asked for twice.
        MetricRegistry.Domain.WEATHER, MetricRegistry.Domain.AIR -> {
            val (lat, lon) = place ?: return emptyList()
            val d = weather.fetch(lat, lon, "", force = true).data
            MetricRegistry.fromWeather(d).filter { MetricRegistry.BY_ID[it.first]?.domain == domain }
        }

        // ⚠️ `heavy = false`. The full suite is about 596 KB; the fields recorded here are in the
        // 50 KB half, and the repository carries the rest forward from its own cache. Fetching
        // half a megabyte four times an hour to record seven numbers would be indefensible.
        MetricRegistry.Domain.SPACE ->
            MetricRegistry.fromSpace(
                space.fetch(force = true, lat = place?.first, lon = place?.second, heavy = false).data,
            )

        // The declared basket, not the user's watch list — see MetricRegistry.INSTRUMENTS. Goes
        // through the repository's own `yahooGate`, which caps simultaneous requests at five.
        MetricRegistry.Domain.MARKETS ->
            MetricRegistry.fromQuotes(markets.quotesFor(MetricRegistry.INSTRUMENTS))

        MetricRegistry.Domain.AVIATION -> {
            val (lat, lon) = place ?: return emptyList()
            MetricRegistry.fromRadar(radar.fetch(lat, lon, force = true).data)
        }

        MetricRegistry.Domain.SAFETY -> {
            val (lat, lon) = place ?: return emptyList()
            MetricRegistry.fromSafety(safety.fetch(lat, lon, force = true).data)
        }

        MetricRegistry.Domain.ORBITAL ->
            MetricRegistry.fromOrbital(orbital.fetch(place?.first, place?.second, force = true).data)
    }

    companion object {
        /**
         * How much early is early enough.
         *
         * ⚠️ Without slack the collector loses a whole cadence whenever a pass lands a hair before the
         * boundary — and it always does, because a task fired "every 15 minutes" drifts by seconds and
         * the previous write is stamped when the fetch RETURNED, not when the pass began. A
         * half-hourly domain would then quietly become hourly.
         */
        const val SLACK_MS = 60_000L

        /**
         * Whether a domain last written at [lastMs] is owed another reading at [nowMs].
         *
         * Pulled out as a pure function so the one rule that decides how often this machine talks to
         * other people's servers can be tested without a network, a clock or six repositories.
         */
        fun isDue(lastMs: Long?, cadenceMs: Long, nowMs: Long): Boolean {
            // Never recorded is always due — that is a first run, not an overdue one.
            if (lastMs == null) return true
            // ⚠️ A reading stamped in the FUTURE means the clock moved backwards, not that the world
            // was read ahead of time. Treating it as "recently done" would stall that domain until
            // real time caught up, which after a timezone change could be hours.
            if (lastMs > nowMs) return true
            return nowMs - lastMs >= cadenceMs - SLACK_MS
        }
    }
}
