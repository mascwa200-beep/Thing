package dev.mascwa.pulse.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.collect
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.core.telemetry.QuietHours
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.data.weather.WeatherData
import java.util.Calendar
import dev.mascwa.pulse.core.telemetry.Seismic

/**
 * Periodic background job behind THE one LCARS notification: runs the silent maintenance passes
 * (update check, self-merge, curiosity, reflection, ledger anchor, attestation, security audit), fires
 * the breaking-news TAKEOVER on a major event, warms the row caches, distils the urgent overlays
 * (ops/security/safety), and publishes the board via [BriefEngine]. Keyless data sources; dedup state
 * persisted in the disk cache.
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

        // Sensorium self-heal — BEFORE the notification gates (service liveness isn't a notification
        // preference). A background FGS start can be refused by the OS; then the next app-open arms
        // it ("Unrestricted battery" is the owner-setup step that makes this reliably succeed).
        if (settings.sensing.enabled) {
            runCatching {
                dev.mascwa.pulse.data.sensing.SensoriumService.start(applicationContext, foregroundLaunch = false)
            }
        }

        // ⚠️ The emergency watch self-heals here too, and like Sensorium it is ABOVE the notification
        // gates — deliberately. Whether a life-safety service is running is not a notification
        // preference, and it must survive both the master switch being off and quiet hours: quiet
        // hours mean "do not tell me about the news", never "do not tell me the building is on fire".
        if (settings.notifications.emergencyTakeover) {
            runCatching { EmergencyWatchService.start(applicationContext) }
        }

        // ⚠️ Widgets refresh ABOVE the notification gates, for the same reason the two services do:
        // a home-screen widget is not a notification, and it should not go stale because you turned
        // notifications off or because it is the middle of the night. It used to sit at the very
        // bottom of this method, below both gates — so with the master switch off the feed widget
        // simply stopped updating, silently, and the only other thing that could refresh it was the
        // OS's 30-minute floor.
        refreshWidgets()

        val prefs = settings.notifications
        if (!prefs.masterEnabled) return Result.success()

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        if (QuietHours.isQuiet(prefs.quietHoursEnabled, prefs.quietStartHour, prefs.quietEndHour, hour)) {
            return Result.success()
        }

        // The board's overlay signals, gathered across the passes below and rendered by BriefEngine as the
        // single LCARS notification's ALERT row.
        var opsNotice: String? = null
        var securityNotice: String? = null
        var securityCritical = false
        var safetyNotice: Pair<String, String>? = null

        // --- App update available? Standing ops note while a newer build waits (routine, never buzzes). ---
        runCatching {
            val info = container.updateRepository.check().available
            if (info != null) {
                opsNotice = "A new app build (#${info.versionCode}) is ready to install in Settings"
            }
        }

        // --- Self-coding: auto-merge the Computer's own PRs once CI is green (opt-in) ---
        val jcfg = settings.jarvis
        if (jcfg.selfCodingEnabled && jcfg.selfCodeAutoMerge) {
            runCatching {
                container.gitHubRepo.openSelfPrs().forEach { pr ->
                    if (container.gitHubRepo.checksState(pr.headSha) == "success") {
                        if (container.gitHubRepo.merge(pr.number)) {
                            opsNotice = "The Computer shipped a change — a new build will follow"
                        }
                    }
                }
            }
        }

        // --- Computer autonomous curiosity (opt-in, cloud-gated, throttled): research a standing
        // interest or the device itself, record ONE finding via the agent's `finding` tool, then notify. ---
        if (jcfg.autonomousCuriosity && settings.jarvis.cloudActive) {
            runCatching {
                val now = System.currentTimeMillis()
                run {
                    // Rotate over the standing interests + a "your own device" subject so it covers both the
                    // owner's orders and the Computer's own substrate over time.
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
                    // Findings surface in-app (Home + unread badge) — no push; the board stays for signals.
                    if (container.findingStore.unseenCount() > before && opsNotice == null) {
                        opsNotice = "The Computer has a new finding waiting for you"
                    }
                    container.settingsRepository.update {
                        it.copy(lastCuriosityMs = now, curiosityIndex = it.curiosityIndex + 1)
                    }
                }
            }
        }

        // --- Mnemosyne reflection: synthesise recent episodic observations into higher-level REFLECTION
        // memories (cloud-gated; silent — surfaces in the Memory screen). No cooldown — `since` inside the
        // engine is a bookmark of what's already been reflected on, not a suppression timer. ---
        if (jcfg.reflectionEnabled && settings.jarvis.cloudActive) {
            runCatching { container.reflectionEngine.reflectIfDue() }
        }

        // --- Blackbox ledger: periodic RFC-3161 anchor (opt-in, throttled ~daily, best-effort) so the head
        // is independently timestamped between manual anchors. Sends only a hash to a public TSA. ---
        if (settings.autoAnchorLedger) {
            runCatching {
                val now = System.currentTimeMillis()
                val head = container.auditLedgerStore.headHash()
                // Only anchor when the head advanced since the last anchor (don't re-stamp the same head) —
                // this alone keeps anchoring from spamming the TSA even with no time-based cooldown, since
                // an unchanged head is a genuine no-op regardless of how often the worker ticks.
                if (head != dev.mascwa.pulse.core.telemetry.HashChain.GENESIS_HASH &&
                    head != container.auditLedgerStore.anchoredHead()
                ) {
                    if (container.auditLedgerStore.anchorHead()) {
                        container.settingsRepository.update { it.copy(lastLedgerAnchorMs = now) }
                    }
                }
            }
        }

        // --- Hardware-attestation posture into the ledger: record ONLY when the verdict changes (a posture
        // change — bootloader unlocked, GrapheneOS key mismatch, hardware-backing lost — is a real security
        // event; identical verdicts are deduped so the append-only log isn't spammed). Local-only probe, no
        // network — no time throttle, so a posture change is caught as soon as the next tick runs. ---
        runCatching {
            val now = System.currentTimeMillis()
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

        // --- Periodic security audit (read-only, local-only; only after the user has run it once) ---
        runCatching {
            container.securityAuditStore.load()
            val lastScan = container.securityAuditStore.auditFlow.value.lastScanMs
            if (lastScan > 0) {
                val crit = dev.mascwa.pulse.core.telemetry.SecurityAudit.Severity.CRITICAL
                val prevCritical = container.securityAuditStore.auditFlow.value.findings
                    .filter { it.severity == crit }.map { it.id }.toSet()
                val result = container.securityAuditor.runAudit(container.securityAuditStore.snapshot())
                container.securityAuditStore.saveResult(result)
                val newCriticals = container.securityAuditStore.auditFlow.value.findings
                    .filter { it.severity == crit && it.id !in prevCritical }
                if (newCriticals.isNotEmpty()) {
                    // A RED alert on the board: plain words, one fact, tap goes to Settings via the board.
                    securityNotice = "Security check found ${newCriticals.size} new serious problem" +
                        (if (newCriticals.size == 1) "" else "s") + " — ${newCriticals.first().title.take(60)}"
                    securityCritical = true
                }
            }
        }

        // --- Breaking-news TAKEOVER check (shared with the resident live poller; manages its own
        // notify_state). Also serves as the news cache warm-up the board reads. ---
        if (prefs.breakingInterrupt) {
            runCatching { BreakingNewsPulse.check(container) }
        }

        // Read the rest of the dedup state AFTER the breaking check so we don't clobber its
        // seenTopUrls update when we persist below.
        var state = readState()

        // --- Cache warm-ups for the board's rows (fresh data; BriefEngine reads warm caches). Each is
        // gated on its row toggle so a hidden row costs no network. ---
        if (prefs.showMarketsRow) {
            runCatching { container.marketsRepository.fetchAll(force = true) }
        }
        if (prefs.showWeatherRow) {
            runCatching { resolveWeather(settings) }
            // heavy = false: this pass exists to warm Kp for the brief board. The five large solar
            // products are ~546 KB of a ~596 KB refresh and nothing in the background path reads
            // them, so fetching them every 15 minutes was ~57 MB a day for one number. The console
            // still gets the full set; a light pass carries the cached heavy values forward.
            runCatching { container.spaceWeatherRepository.fetch(force = true, heavy = false) }
        }

        // --- Nearby severe incident → the board's ALERT row (YELLOW), deduped by incident id. ---
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
                if (severe.isNotEmpty()) {
                    // A tsunami evaluation leads even when something else is closer: it is the one
                    // hazard here where the reader may have to move, and minutes matter.
                    val lead = severe.filter { it.tsunami }.minByOrNull { it.distanceMeters }
                        ?: severe.minByOrNull { it.distanceMeters }
                        ?: severe.first()
                    val more = severe.size - 1
                    val where = if (lead.distanceMeters > 0) {
                        "${Formatters.compact(lead.distanceMeters / 1000)} km away"
                    } else {
                        "in your area"
                    }
                    // Say the two things that change what you do — how deep, and whether a tsunami
                    // is being assessed — both of which the feed always sent and nothing ever read.
                    val extra = Seismic.compactFacts(
                        depthKm = lead.depthKm,
                        tsunami = lead.tsunami,
                        pagerAlert = lead.pagerAlert,
                    ).firstOrNull()?.let { " · $it" }.orEmpty()
                    safetyNotice = ("Danger nearby: ${lead.title.take(60)} — $where$extra" +
                        if (more > 0) " (+$more more)" else "") to "safety:${lead.id}"
                    severe.forEach { already += it.id }
                    state = state.copy(safetyAlertedIds = already.toList().takeLast(100))
                }
            }
        }

        writeState(state)

        // --- THE board: compose + post the one LCARS notification from everything gathered above. ---
        runCatching {
            BriefEngine.publish(
                context = applicationContext,
                container = container,
                settings = settings,
                // With the takeover on, BreakingNewsPulse just force-fetched TOP/WORLD — read the warm
                // cache. With it OFF nothing else fetches news, so the board must fetch its own.
                forceNews = !prefs.breakingInterrupt,
                opsNotice = opsNotice,
                safetyNotice = safetyNotice,
                securityNotice = securityNotice,
                securityCritical = securityCritical,
            )
        }

        // Refresh the Nova/TeslaUnread unread-count badge on the app icon (best-effort).
        runCatching {
            dev.mascwa.pulse.shortcuts.UnreadBadge.publish(applicationContext, container.findingStore.unseenCount())
        }
        return Result.success()
    }

    /**
     * Push the widget, so it is not left waiting on the OS's thirty-minute floor.
     *
     * ⚠️ Hoisted ABOVE the notification master switch and quiet hours, and it stays there: a widget
     * is not a notification and should not be silenced by one. Turning notifications off used to
     * freeze it.
     */
    private fun refreshWidgets() {
        runCatching {
            val mgr = android.appwidget.AppWidgetManager.getInstance(applicationContext)
            val component = android.content.ComponentName(
                applicationContext, dev.mascwa.pulse.widget.LockWidgetProvider::class.java,
            )
            val ids = mgr.getAppWidgetIds(component)
            if (ids.isNotEmpty()) {
                applicationContext.sendBroadcast(
                    android.content.Intent(
                        android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE,
                    ).apply {
                        setComponent(component)
                        putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    },
                )
            }
        }
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


    private suspend fun readState(): NotifyState =
        container.diskCache.readAny("notify_state", NotifyState.serializer())?.value ?: NotifyState()

    // The worker owns every notify_state field EXCEPT the ones the resident BreakingNewsPulse poller advances
    // on its own cadence (seenTopUrls + the breaking-interrupt throttle/dedup). Re-read the latest blob and
    // keep those so our (older) snapshot — held across this whole doWork — doesn't clobber them.
    private suspend fun writeState(state: NotifyState) {
        val latest = container.diskCache.readAny("notify_state", NotifyState.serializer())?.value
        val merged = if (latest != null) state.copy(
            seenTopUrls = latest.seenTopUrls,
            breakingInterruptLastMs = latest.breakingInterruptLastMs,
            breakingInterruptSeen = latest.breakingInterruptSeen,
            // BriefEngine owns the one-notification alert dedup key on its own cadence — never clobber it.
            lastUrgentKey = latest.lastUrgentKey,
        ) else state
        container.diskCache.write("notify_state", merged, NotifyState.serializer())
    }


    companion object {
        const val UNIQUE_NAME = "pulse_periodic_refresh"
    }
}
