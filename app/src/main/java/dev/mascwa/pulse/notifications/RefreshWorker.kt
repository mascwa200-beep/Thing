package dev.mascwa.pulse.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.collect
import dev.mascwa.pulse.BuildConfig
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

        // ⚠️ ABOVE the notification gates, for the same reason as the two services and the widgets:
        // staying on the newest build is not a notification preference. Until now the ONLY caller of
        // the install path was MainActivity, so a phone that was never opened was never updated —
        // and the check that does sit below these gates only ever posted a note about it.
        val buildWaiting = installNewestBuild(settings)

        // ⚠️ Above the notification gates for the same reason as everything else up here, and AFTER
        // the app update on purpose: both want the same Wi-Fi, and being on the newest build matters
        // more than having one more optional payload. The provisioner takes at most one item per
        // pass, so this cannot become a long job that starves the rest of the worker.
        runCatching {
            container.payloadProvisioner.runPass(
                unmetered = container.connectivityObserver.isUnmetered.value,
                interrogatorOn = settings.sensing.interrogator,
            )
        }

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
        //
        // ⚠️ Reuses what the install pass above already learned rather than asking again. `check()`
        // sends `Cache-Control: no-cache` on purpose — it must never be answered from a stale disk
        // entry — so a second call here would be a second live request every worker tick for an
        // answer already in hand.
        //
        // And the note only appears when a build genuinely is WAITING. Most of the time the pass
        // above has already installed it, in which case saying it is "ready to install in Settings"
        // would send somebody to press a button for work that is already done.
        buildWaiting?.let { info ->
            opsNotice = "A new app build (#${info.versionCode}) is ready — ${info.waitingBecause}"
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
     * Fetch and install the newest green build, on a phone nobody has opened.
     *
     * ## Why this is here at all
     *
     * ⚠️ Until now the only caller of the install path was `MainActivity.maybeAutoUpdate`, so the
     * app updated itself **only if you opened it**. A phone left in a drawer for a fortnight stayed
     * on whatever build it had. This worker did already ask `updateRepository.check()` — but below
     * the notification gates, and only to write a line into the board, so with notifications off it
     * did not even ask.
     *
     * ## What it will not do
     *
     * - **Not on a metered connection.** The APK is around 158 MB and CI publishes on every push;
     *   spending somebody's mobile allowance on that, unasked and unseen, is not a trade to make on
     *   their behalf. [ConnectivityObserver.isUnmetered] answers false when it cannot classify the
     *   connection, which is the safe direction and the reason this reads it rather than guessing.
     * - **Not while the app is on screen.** Installing replaces the running process, so an app
     *   vanishing mid-sentence reads as a crash. `appForeground` is the same signal the assistant's
     *   navigation tool uses to tell a visible console from an invisible one.
     * - **Not twice.** `unconfirmedUpdateCode` is the loop-breaker: it is set when an install is
     *   committed and cleared the first time the app reaches the foreground afterwards, whichever
     *   build that turns out to be. While it is set this stands down. ⚠️ It is deliberately NOT a
     *   claim that a build is bad — nothing here can know that — only that one install is in flight.
     *   `lastAutoUpdateCode` is the second half: it stops the same build being fetched again.
     *
     * The green gate lives in `updateRepository.check()`, which only reports a build whose CI run
     * actually passed — this must never be the thing that decides that.
     */
    private suspend fun installNewestBuild(
        settings: AppSettings,
    ): PendingBuild? {
        // ⚠️ **The loop-breaker has to be clearable without anyone opening the app**, and until this
        // was written it was not — `MainActivity` cleared it on foreground, which was sufficient
        // when a visit was the only thing that could ever install. It no longer is: a phone that is
        // never opened would install exactly once and then be blocked for good, which is precisely
        // the phone this whole pass exists for.
        //
        // The evidence that the install landed is that THIS code is running from it. Comparing the
        // build we are executing against the one that was committed answers it with no foreground
        // and no guessing — and if the install genuinely failed, the running build is still the old
        // one, so it stays blocked, which is the safety property the field is for.
        var pending = settings.unconfirmedUpdateCode
        if (pending != 0 && BuildConfig.VERSION_CODE >= pending) {
            runCatching { container.settingsRepository.update { it.copy(unconfirmedUpdateCode = 0) } }
            pending = 0
        }

        // ⚠️ These are ordered cheapest-first and, more importantly, the network request sits behind
        // all of them: a phone on mobile data must not pay for a check it could never act on.
        if (pending != 0) return null
        val foreground = container.appForeground.value
        val unmetered = container.connectivityObserver.isUnmetered.value

        return runCatching {
            val info = container.updateRepository.check().available ?: return@runCatching null
            if (info.versionCode <= settings.lastAutoUpdateCode) return@runCatching null

            if (foreground) return@runCatching PendingBuild(info.versionCode, "waiting until you put the phone down")
            if (!unmetered) return@runCatching PendingBuild(info.versionCode, "waiting for Wi-Fi")

            val file = container.updateRepository.download(info) { }

            // ⚠️ Written BEFORE the install is committed, and both fields together. The commit
            // replaces this process, so anything recorded afterwards may simply never happen — and
            // an install handed to the system with no loop-breaker written would be re-attempted on
            // the next pass, forever.
            container.settingsRepository.update {
                it.copy(lastAutoUpdateCode = info.versionCode, unconfirmedUpdateCode = info.versionCode)
            }

            // ⚠️ Read again, because downloading 158 MB takes long enough for somebody to have
            // picked the phone up meanwhile. The check at the top is what avoids starting the work;
            // this one is what avoids replacing an app being read.
            if (container.appForeground.value) {
                return@runCatching PendingBuild(info.versionCode, "downloaded — it will install when you put the phone down")
            }
            dev.mascwa.pulse.core.util.installApk(applicationContext, file)
            null
        }.getOrNull()
    }

    /**
     * A newer build that exists and has not been installed, and the honest reason why not.
     *
     * ⚠️ The reason is the point. "A new build is ready to install in Settings" was true when a tap
     * was the only way it could ever happen; now the ordinary case is that the phone installs it
     * itself, so a standing note has to say what it is actually waiting for or it sends somebody to
     * press a button for work already done.
     */
    private data class PendingBuild(val versionCode: Int, val waitingBecause: String)

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
