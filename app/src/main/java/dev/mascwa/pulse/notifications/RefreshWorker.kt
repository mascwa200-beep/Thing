package dev.mascwa.pulse.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.collect
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.news.NewsCategory
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.data.weather.WeatherCode
import dev.mascwa.pulse.data.weather.WeatherData
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Periodic background job that powers push notifications: breaking news,
 * market/price threshold alerts, severe-weather alerts and a daily digest.
 * Keyless data sources; dedup state persisted in the disk cache.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val app get() = applicationContext as PulseApplication
    private val container get() = app.container

    override suspend fun doWork(): Result {
        val settings = runCatching { container.settingsRepository.current() }.getOrNull()
            ?: return Result.success()
        val prefs = settings.notifications
        if (!prefs.masterEnabled) return Result.success()

        val cal = Calendar.getInstance()
        val today = dayKey(cal)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        if (inQuietHours(prefs.quietHoursEnabled, prefs.quietStartHour, prefs.quietEndHour, hour)) {
            return Result.success()
        }

        val notifier = container.notifier

        // --- App update available? (in-app updater; dedup by build number) ---
        if (prefs.updateChecks) {
            runCatching {
                val info = container.updateRepository.check().available
                if (info != null && info.versionCode > settings.lastUpdateNotifiedCode) {
                    notifier.notifyUpdate(
                        id = 7401,
                        title = "J.A.R.V.I.S. update available",
                        body = "Build #${info.versionCode} is ready — tap to download & install.",
                    )
                    container.settingsRepository.update { it.copy(lastUpdateNotifiedCode = info.versionCode) }
                }
            }
        }

        // --- Self-coding: auto-merge J.A.R.V.I.S.'s own PRs once CI is green (opt-in) ---
        val jcfg = settings.jarvis
        if (jcfg.selfCodingEnabled && jcfg.selfCodeAutoMerge) {
            runCatching {
                container.gitHubRepo.openSelfPrs().forEach { pr ->
                    if (container.gitHubRepo.checksState(pr.headSha) == "success") {
                        // Merge closes the PR, so it won't reappear next cycle — notify once, no dedup state.
                        if (container.gitHubRepo.merge(pr.number)) {
                            notifier.notifyUpdate(
                                id = 7402 + (pr.number and 0xFF),
                                title = "J.A.R.V.I.S. shipped a change",
                                body = "Merged PR #${pr.number} — a new build will follow; you'll be prompted to install it.",
                            )
                        }
                    }
                }
            }
        }

        // --- J.A.R.V.I.S. autonomous curiosity (opt-in, cloud-gated, throttled): research a standing
        // interest or the device itself, record ONE finding via the agent's `finding` tool, then notify. ---
        if (jcfg.autonomousCuriosity && settings.jarvis.cloudActive) {
            runCatching {
                val now = System.currentTimeMillis()
                val curiosityIntervalMs = 4L * 60 * 60 * 1000 // at most every ~4 hours
                if (now - settings.lastCuriosityMs >= curiosityIntervalMs) {
                    // Rotate over the standing interests + a "your own device" subject so it covers both the
                    // owner's orders and J.A.R.V.I.S.'s own substrate over time.
                    val subjects = container.interestStore.all().map { it.topic } +
                        "your own device — its sensors, capabilities and settings (your substrate)"
                    val subject = subjects[settings.curiosityIndex.mod(subjects.size)]
                    val before = container.findingStore.unseenCount()
                    val query =
                        "Quietly investigate \"$subject\" on the owner's behalf. Use your web tools to find ONE " +
                            "genuinely remarkable, recent idea or development (or, for the device, one notable " +
                            "capability or setting). Be selective — only something worth the owner's attention. " +
                            "Record it with the `finding` tool (`finding <headline> | <body>`, include a source " +
                            "URL; lead with [device] if it's about the device). Then reply with just the headline."
                    container.agentOrchestrator.run(query, dev.mascwa.pulse.jarvis.JarvisPersona.SYSTEM_PROMPT)
                        .collect { /* drive the agent loop to completion; the `finding` tool stores the result */ }
                    if (container.findingStore.unseenCount() > before) {
                        val latest = container.findingStore.findingsFlow.value.firstOrNull { !it.seen }
                        notifier.notifyFinding(
                            id = 7501,
                            title = "J.A.R.V.I.S. has a finding",
                            body = latest?.headline ?: "I came across something — ready when you are.",
                        )
                    }
                    container.settingsRepository.update {
                        it.copy(lastCuriosityMs = now, curiosityIndex = it.curiosityIndex + 1)
                    }
                }
            }
        }

        // --- Mnemosyne reflection: synthesise recent episodic observations into higher-level REFLECTION
        // memories (cloud-gated + throttled inside the engine; silent — surfaces in the Memory screen). ---
        if (jcfg.reflectionEnabled && settings.jarvis.cloudActive) {
            runCatching { container.reflectionEngine.reflectIfDue() }
        }

        // --- Blackbox ledger: periodic RFC-3161 anchor (opt-in, throttled ~daily, best-effort) so the head
        // is independently timestamped between manual anchors. Sends only a hash to a public TSA. ---
        if (settings.autoAnchorLedger) {
            runCatching {
                val now = System.currentTimeMillis()
                val anchorIntervalMs = 24L * 60 * 60 * 1000 // at most once a day
                if (now - settings.lastLedgerAnchorMs >= anchorIntervalMs) {
                    val head = container.auditLedgerStore.headHash()
                    // Only anchor when the head advanced since the last anchor (don't re-stamp the same head).
                    if (head != dev.mascwa.pulse.core.telemetry.HashChain.GENESIS_HASH &&
                        head != container.auditLedgerStore.anchoredHead()
                    ) {
                        if (container.auditLedgerStore.anchorHead()) {
                            container.settingsRepository.update { it.copy(lastLedgerAnchorMs = now) }
                        }
                    }
                }
            }
        }

        // --- Hardware-attestation posture into the ledger: record ONLY when the verdict changes (a posture
        // change — bootloader unlocked, GrapheneOS key mismatch, hardware-backing lost — is a real security
        // event; identical verdicts are deduped so the append-only log isn't spammed). Local-only probe, no
        // network; the secure-element probe is throttled to ~6h so it doesn't run on every worker tick. ---
        runCatching {
            val now = System.currentTimeMillis()
            val attestIntervalMs = 6L * 60 * 60 * 1000 // probe the secure element at most every ~6h
            if (now - settings.lastAttestationCheckMs >= attestIntervalMs) {
                val report = container.deviceAttestation.run()
                val v = report.verdict
                val sig: String
                val detail: String
                if (v != null) {
                    sig = "${v.grapheneVerified}|${v.hardwareBacked}|${v.strongBox}|${v.bootloaderLocked}|" +
                        "${v.verifiedBoot}|${report.info?.verifiedBootKeyHex.orEmpty()}"
                    detail = v.summary
                } else {
                    sig = "unavailable|${report.error.orEmpty()}"
                    detail = "attestation unavailable" + (report.error?.let { ": ${it.take(80)}" }.orEmpty())
                }
                if (sig != settings.lastAttestationSig) {
                    container.auditLedgerStore.record(
                        dev.mascwa.pulse.core.telemetry.AuditEventType.SECURITY,
                        "device.attestation",
                        detail,
                    )
                    container.settingsRepository.update {
                        it.copy(lastAttestationSig = sig, lastAttestationCheckMs = now)
                    }
                } else {
                    container.settingsRepository.update { it.copy(lastAttestationCheckMs = now) }
                }
            }
        }

        // --- Periodic security audit (read-only, local-only; only after the user has run it once) ---
        runCatching {
            val now = System.currentTimeMillis()
            val auditIntervalMs = 55L * 60 * 1000 // ~hourly
            container.securityAuditStore.load()
            val lastScan = container.securityAuditStore.auditFlow.value.lastScanMs
            if (lastScan > 0 && now - lastScan >= auditIntervalMs) {
                val crit = dev.mascwa.pulse.core.telemetry.SecurityAudit.Severity.CRITICAL
                val prevCritical = container.securityAuditStore.auditFlow.value.findings
                    .filter { it.severity == crit }.map { it.id }.toSet()
                val result = container.securityAuditor.runAudit(container.securityAuditStore.snapshot())
                container.securityAuditStore.saveResult(result)
                val newCriticals = container.securityAuditStore.auditFlow.value.findings
                    .filter { it.severity == crit && it.id !in prevCritical }
                if (newCriticals.isNotEmpty()) {
                    notifier.notifySecurity(
                        id = 7620,
                        title = "Security audit: ${newCriticals.size} new critical finding(s)",
                        body = newCriticals.first().title,
                    )
                }
            }
        }

        // --- Breaking news (shared with the resident live poller; manages its own notify_state) ---
        if (prefs.breakingNews) {
            runCatching { BreakingNewsPulse.check(container) }
        }

        // Read the rest of the dedup state AFTER the breaking check so we don't clobber its
        // seenTopUrls update when we persist the other sections below.
        var state = readState()

        // --- Market / price alerts ---
        if (prefs.marketAlerts) {
            runCatching {
                val alreadyToday = if (state.marketAlertDay == today) {
                    state.marketAlertedSymbols.toMutableSet()
                } else mutableSetOf()
                val quotes = container.marketsRepository.fetchAll(force = true).data
                quotes.forEach { q ->
                    val pct = q.changePercent ?: return@forEach
                    if (abs(pct) >= prefs.marketMovePercent && q.id !in alreadyToday) {
                        val dir = if (pct >= 0) "▲" else "▼"
                        notifier.notifyMarket(
                            id = 2000 + (q.id.hashCode() and 0xFFF),
                            title = "${q.label} $dir ${Formatters.signedPercent(pct)}",
                            body = "Now ${Formatters.number(q.price, 2)} ${q.currency}".trim(),
                        )
                        alreadyToday += q.id
                    }
                }
                state = state.copy(marketAlertDay = today, marketAlertedSymbols = alreadyToday.toList())
            }
        }

        // --- Severe weather alerts ---
        var weather: WeatherData? = null
        if (prefs.weatherAlerts && state.weatherAlertDay != today) {
            runCatching {
                weather = resolveWeather(settings)
                weather?.daily?.firstOrNull()?.let { day ->
                    val severe = WeatherCode.isSevere(day.weatherCode)
                    val wet = (day.precipProbabilityMax ?: 0) >= 70
                    if (severe || wet) {
                        notifier.notifyWeather(
                            id = 3001,
                            title = "${weather!!.locationName}: ${WeatherCode.describe(day.weatherCode)}",
                            body = buildString {
                                append("High ${Formatters.number(day.tempMax, 0)}${weather!!.tempUnitSymbol}")
                                append(" · Low ${Formatters.number(day.tempMin, 0)}${weather!!.tempUnitSymbol}")
                                day.precipProbabilityMax?.let { append(" · ${it}% precip") }
                            },
                        )
                        state = state.copy(weatherAlertDay = today)
                    }
                }
            }
        }

        // --- Space weather (geomagnetic storm) ---
        if (prefs.spaceAlerts && state.spaceAlertDay != today) {
            runCatching {
                val sw = container.spaceWeatherRepository.fetch(force = true).data
                val kp = sw.kp
                if (kp != null && kp >= 5.0) {
                    notifier.notifySpace(
                        id = 5001,
                        title = "Geomagnetic storm: ${sw.stormLevel}",
                        body = "Planetary Kp ${"%.1f".format(kp)}. Aurora: ${sw.auroraChance}.",
                    )
                    state = state.copy(spaceAlertDay = today)
                }
            }
        }

        // --- Aurora likely (NOAA OVATION at the user's location) ---
        if (prefs.auroraAlerts && state.auroraAlertDay != today) {
            runCatching {
                val loc = container.locationProvider.current()
                if (loc != null) {
                    val sw = container.spaceWeatherRepository.fetch(true, loc.latitude, loc.longitude).data
                    val pct = sw.auroraProbabilityPct
                    if (pct != null && pct >= 25) {
                        notifier.notifySpace(
                            id = 5003,
                            title = "Aurora likely — $pct% overhead",
                            body = "NOAA OVATION puts aurora at $pct% over your location" +
                                (sw.kp?.let { " · Kp ${"%.1f".format(it)}" } ?: "") + ".",
                        )
                        state = state.copy(auroraAlertDay = today)
                    }
                }
            }
        }

        // --- Hazardous near-Earth object ---
        if (prefs.spaceAlerts && state.neoAlertDay != today) {
            runCatching {
                val orbital = container.orbitalRepository.fetch(null, null, force = true).data
                val hazard = orbital.neos.firstOrNull { it.hazardous }
                if (hazard != null) {
                    notifier.notifySpace(
                        id = 5002,
                        title = "Close approach: ${orbital.neoHazardousCount} flagged object(s)",
                        body = "${hazard.name.removeSurrounding("(", ")")} passes today" +
                            (hazard.missDistanceKm?.let { " · ${Formatters.compact(it)} km" } ?: ""),
                    )
                    state = state.copy(neoAlertDay = today)
                }
            }
        }

        // --- Nearby safety incidents ---
        if (prefs.safetyAlerts) {
            runCatching {
                val loc = container.locationProvider.current()
                if (loc != null) {
                    val safety = container.safetyRepository.fetch(loc.latitude, loc.longitude, force = true).data
                    val radiusM = settings.safetyRadiusKm * 1000.0
                    val already = state.safetyAlertedIds.toMutableSet()
                    val severe = safety.incidents.filter {
                        val sev = runCatching { dev.mascwa.pulse.data.safety.Severity.valueOf(it.severity) }
                            .getOrDefault(dev.mascwa.pulse.data.safety.Severity.LOW)
                        (sev == dev.mascwa.pulse.data.safety.Severity.HIGH ||
                            sev == dev.mascwa.pulse.data.safety.Severity.EXTREME) &&
                            it.distanceMeters <= radiusM && it.id !in already
                    }
                    severe.take(3).forEach { inc ->
                        notifier.notifySafety(
                            id = 6000 + (inc.id.hashCode() and 0xFFF),
                            title = "Safety: ${inc.title.take(60)}",
                            body = (if (inc.distanceMeters > 0)
                                "${Formatters.compact(inc.distanceMeters / 1000)} km away · " else "Your area · ") + inc.source,
                        )
                        already += inc.id
                    }
                    if (severe.isNotEmpty()) {
                        state = state.copy(safetyAlertedIds = already.toList().takeLast(100))
                    }
                }
            }
        }

        // --- Overhead flight (live ADS-B) ---
        if (prefs.flightAlerts) {
            runCatching {
                val loc = container.locationProvider.current()
                if (loc != null) {
                    val radar = container.radarRepository.fetch(loc.latitude, loc.longitude, force = true).data
                    val already = state.flightAlertedIds.toMutableSet()
                    val overhead = radar.contacts
                        .filter {
                            it.kind == dev.mascwa.pulse.data.radar.ContactKind.AIRCRAFT.name &&
                                it.altitudeM != null && it.distanceMeters <= 3000.0 && it.id !in already
                        }
                        .sortedBy { it.distanceMeters }
                    overhead.firstOrNull()?.let { ac ->
                        val altFt = ((ac.altitudeM ?: 0.0) / 0.3048).toInt()
                        notifier.notifyFlight(
                            id = 7000 + (ac.id.hashCode() and 0xFFF),
                            title = "Overhead: ${ac.label}",
                            body = "$altFt ft" +
                                (ac.groundSpeedKmh?.let { " · ${it.toInt()} km/h" } ?: "") +
                                " · ${Formatters.compact(ac.distanceMeters / 1000)} km",
                        )
                        state = state.copy(flightAlertedIds = (already + ac.id).toList().takeLast(50))
                    }
                }
            }
        }

        // --- Life-sim survival check-ins: nudge when a real-decaying need runs low (throttled per need,
        // critical ones sooner) + remind when a real appointment is imminent (once per event). The needs
        // bleed into reality, so this doubles as a "drink / eat / rest / wash" reminder. Keeps you alive. ---
        if (prefs.survivalAlerts) {
            runCatching {
                val now = System.currentTimeMillis()
                val life = container.specialGameStore.lifeSnapshot()
                val throttleMs = 3L * 60 * 60 * 1000 // low needs: at most every ~3h
                val fired = state.survivalFiredMs.toMutableMap()
                dev.mascwa.pulse.core.telemetry.SurvivalAlerts.evaluate(life, now).forEach { alert ->
                    val critical = alert.level == dev.mascwa.pulse.core.telemetry.AlertLevel.CRITICAL
                    val gate = if (critical) throttleMs / 2 else throttleMs
                    if (now - (fired[alert.need.name] ?: 0L) >= gate) {
                        notifier.notifySurvival(
                            id = 7700 + alert.need.ordinal,
                            title = alert.title,
                            body = alert.body,
                            urgent = critical,
                        )
                        fired[alert.need.name] = now
                    }
                }
                state = state.copy(survivalFiredMs = fired)

                // Imminent real appointment → an agenda reminder, once per event while it's imminent.
                val agenda = dev.mascwa.pulse.core.telemetry.CalendarQuests.compose(
                    container.calendarRepository.upcoming(now), now, max = 5,
                )
                val notified = state.agendaNotifiedIds.toMutableSet()
                agenda.filter { it.imminent && it.id.toString() !in notified }.take(3).forEach { q ->
                    notifier.notifyAgenda(
                        id = 7720 + (q.id.hashCode() and 0xFF),
                        title = "⏰ Incoming: ${q.title.take(60)}",
                        body = "${q.countdown} — ${q.briefing}",
                    )
                    notified += q.id.toString()
                }
                // Keep only ids still in the agenda window so the dedup set can't grow without bound.
                val liveIds = agenda.map { it.id.toString() }.toSet()
                state = state.copy(agendaNotifiedIds = notified.filter { it in liveIds })
            }
        }

        // --- Frequent survival tips: push the next tip from the 300+ rotating catalog (quiet, low-priority,
        // fixed id so each silently replaces the last). Fires roughly every worker tick — the worker interval
        // is the cadence — gated by the master toggle + quiet hours like everything else. ---
        if (prefs.survivalTips) {
            runCatching {
                val now = System.currentTimeMillis()
                if (now - state.survivalTipLastMs >= SURVIVAL_TIP_MIN_GAP_MS) {
                    val idx = state.survivalTipIndex
                    notifier.notifyTip(7760, "⛑ Survival tip", dev.mascwa.pulse.core.telemetry.SurvivalTips.tip(idx))
                    state = state.copy(survivalTipIndex = idx + 1, survivalTipLastMs = now)
                }
            }
        }

        // --- Daily digest ---
        if (prefs.dailyDigest && hour >= prefs.digestHour && state.lastDigestDay != today) {
            runCatching {
                val lines = buildDigestLines(settings, weather)
                if (lines.isNotEmpty()) {
                    notifier.notifyDigest(
                        id = 4001,
                        title = "Your daily Pulse",
                        body = lines.first(),
                        lines = lines,
                    )
                    state = state.copy(lastDigestDay = today)
                }
            }
        }

        // Refresh the Nova/TeslaUnread unread-count badge on the app icon (best-effort).
        runCatching {
            dev.mascwa.pulse.shortcuts.UnreadBadge.publish(applicationContext, container.findingStore.unseenCount())
        }
        // Nudge the live-feed widget to reload from this fetch (its own update period is the 30-min floor).
        runCatching {
            val mgr = android.appwidget.AppWidgetManager.getInstance(applicationContext)
            val ids = mgr.getAppWidgetIds(
                android.content.ComponentName(applicationContext, dev.mascwa.pulse.widget.FeedWidgetProvider::class.java),
            )
            if (ids.isNotEmpty()) {
                mgr.notifyAppWidgetViewDataChanged(ids, dev.mascwa.pulse.R.id.widget_feed_flipper)
            }
        }

        writeState(state)
        return Result.success()
    }

    private suspend fun resolveWeather(settings: AppSettings): WeatherData? {
        // Prefer device location when enabled & permitted; else the selected save.
        if (settings.useDeviceLocation) {
            container.locationProvider.current()?.let { loc ->
                return container.weatherRepository.fetch(loc.latitude, loc.longitude, loc.name, true).data
            }
        }
        val saved = settings.savedLocations.getOrNull(settings.selectedLocationIndex)
            ?: settings.savedLocations.firstOrNull() ?: return null
        return container.weatherRepository.fetch(saved.latitude, saved.longitude, saved.name, true).data
    }

    private suspend fun buildDigestLines(settings: AppSettings, weather: WeatherData?): List<String> {
        val lines = mutableListOf<String>()
        runCatching {
            val top = container.newsRepository.fetchCategory(NewsCategory.TOP, force = false).data
            top.firstOrNull()?.let { lines += "📰 ${it.title}" }
        }
        runCatching {
            val quotes = container.marketsRepository.fetchAll(force = false).data
            val mover = quotes.maxByOrNull { abs(it.changePercent ?: 0.0) }
            mover?.let { lines += "📈 ${it.label} ${Formatters.signedPercent(it.changePercent)}" }
        }
        val w = weather ?: runCatching { resolveWeather(settings) }.getOrNull()
        w?.let { wd ->
            wd.daily.firstOrNull()?.let { d ->
                lines += "🌤️ ${wd.locationName}: ${WeatherCode.describe(d.weatherCode)}, " +
                    "${Formatters.number(d.tempMax, 0)}/${Formatters.number(d.tempMin, 0)}${wd.tempUnitSymbol}"
            }
        }
        runCatching {
            val econ = container.economyRepository.fetchDashboard(force = false).data
            val infl = econ.series.firstOrNull { it.indicatorId == "FP.CPI.TOTL.ZG" }?.latest
            infl?.let { lines += "💹 Inflation (${it.year}): ${Formatters.percent(it.value)}" }
        }
        // Today in the sky (sun/moon/planets/aurora) — keyless, mostly offline.
        runCatching {
            val (lat, lon) = run {
                if (settings.useDeviceLocation) {
                    container.locationProvider.current()?.let { return@run it.latitude to it.longitude }
                }
                val saved = settings.savedLocations.getOrNull(settings.selectedLocationIndex)
                    ?: settings.savedLocations.firstOrNull()
                if (saved != null) saved.latitude to saved.longitude else 51.5074 to -0.1278
            }
            val orb = container.orbitalRepository.fetch(lat, lon, false).data
            val sw = runCatching { container.spaceWeatherRepository.fetch(false, lat, lon).data }.getOrNull()
            dev.mascwa.pulse.data.orbital.SkyDigest.lines(orb, sw, lat, lon).take(2).forEach { lines += it }
        }
        return lines
    }

    private suspend fun readState(): NotifyState =
        container.diskCache.readAny("notify_state", NotifyState.serializer())?.value ?: NotifyState()

    private suspend fun writeState(state: NotifyState) =
        container.diskCache.write("notify_state", state, NotifyState.serializer())

    private fun dayKey(cal: Calendar): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)

    private fun inQuietHours(enabled: Boolean, start: Int, end: Int, hour: Int): Boolean {
        if (!enabled) return false
        return if (start <= end) hour in start until end
        else hour >= start || hour < end // overnight window
    }

    companion object {
        const val UNIQUE_NAME = "pulse_periodic_refresh"
        // Minimum gap between survival tips — a floor so double-runs don't double-fire; the worker's own
        // period (≥15 min) is the real cadence, so tips arrive roughly every tick while awake.
        private const val SURVIVAL_TIP_MIN_GAP_MS = 12L * 60 * 1000
    }
}
