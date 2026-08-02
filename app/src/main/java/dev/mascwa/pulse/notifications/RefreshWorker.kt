package dev.mascwa.pulse.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.collect
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.core.telemetry.Affliction
import dev.mascwa.pulse.core.telemetry.AlertLevel
import dev.mascwa.pulse.core.telemetry.QuietHours
import dev.mascwa.pulse.core.telemetry.SurvivalAlerts
import dev.mascwa.pulse.core.telemetry.SurvivalNeed
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

        // Self-heal the always-on ambient watch/listen: if the owner left it on but the OS reclaimed the
        // service (it's START_NOT_STICKY), (re)start it here each run. Idempotent — no-op if already up;
        // best-effort (a background FGS start can be refused, then the next app-open re-arms it).
        if (settings.ambientSensingAlways && settings.ambientSensing) {
            runCatching {
                dev.mascwa.pulse.data.perception.AmbientSensingService.start(
                    applicationContext, mic = settings.ambientMic, cam = settings.ambientCamera,
                )
            }
        }

        val prefs = settings.notifications
        if (!prefs.masterEnabled) return Result.success()

        val cal = Calendar.getInstance()
        val today = dayKey(cal)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        if (QuietHours.isQuiet(prefs.quietHoursEnabled, prefs.quietStartHour, prefs.quietEndHour, hour)) {
            return Result.success()
        }

        val notifier = container.notifier

        // --- Self-care check-ins: CareSchedule is now the SOLE clock-driven due-check engine — the older
        //     HabitCheckin fixed-interval scheduling path has been retired from here (merged, not duplicated).
        //     `aggressiveCheckin` is no longer a second, independently-scheduled system; it's a STRICTNESS mode
        //     on this same due check-in (an ongoing, harder-to-dismiss notification that, on a dodge, is
        //     eligible to escalate into the lockout — see CareCheckinActivity/LockoutActivity). Steps aside
        //     when J.A.R.V.I.S. is driving the timing himself (the pass below owns the "when" then), exactly
        //     like the retired aggressive path used to. ---
        val jarvisDrivenCheckins = prefs.jarvisDrivenCheckins && settings.jarvis.cloudActive
        if (prefs.smartCheckins && !jarvisDrivenCheckins) {
            runCatching {
                val due = container.careCheckinStore.schedule()
                if (due != null) {
                    notifier.notifyCareCheckin(due, strict = prefs.aggressiveCheckin)
                    container.careCheckinStore.markAsked(due)
                }
            }
        }

        // --- App update available? (in-app updater; dedup by build number) ---
        if (prefs.updateChecks) {
            runCatching {
                val info = container.updateRepository.check().available
                if (info != null && info.versionCode > settings.lastUpdateNotifiedCode) {
                    notifier.notifyUpdate(
                        id = NotifId.UPDATE,
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
                run {
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
                            id = NotifId.FINDING,
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

        // --- J.A.R.V.I.S.-driven self-care timing (opt-in, cloud-gated, throttled): rather than a fixed
        //     clock, let the assistant decide WHEN to nudge — it reads the owner's directive + current needs +
        //     what the sensors actually saw and asks (via `selfcare ask`) only when it judges the moment right.
        //     Only consult the model when something is genuinely overdue (saves credits). ---
        if (prefs.selfCareCheckins && jarvisDrivenCheckins) {
            runCatching {
                val now = System.currentTimeMillis()
                if (container.habitStore.nextDue() != null) {
                    val directive = settings.jarvis.selfCareDirective
                    val sys = dev.mascwa.pulse.jarvis.JarvisPersona.SYSTEM_PROMPT +
                        if (directive.isNotBlank()) {
                            "\n\nThe owner's self-care directive (interpret it): ${directive.trim()}"
                        } else {
                            ""
                        }
                    val query =
                        "Quietly review the owner's self-care right now. Use `selfcare due` to see what habit is " +
                            "overdue, `selfcare needs` for their need levels, and `selfcare evidence` for what " +
                            "the sensors actually saw recently. Then — ONLY if their directive and the evidence " +
                            "make this a genuinely good moment to nudge them about ONE specific unmet need — " +
                            "fire a single check-in with `selfcare ask <shower|teeth|meal|water>`. If it's a " +
                            "poor time (they clearly just did it, nothing's due, or the directive says leave " +
                            "them be), do nothing. Be sparing — at most one nudge. Reply with a one-line note."
                    container.agentOrchestrator.run(query, sys).collect { /* drive to completion */ }
                    container.settingsRepository.update { it.copy(lastSelfCareCheckMs = now) }
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
                    notifier.notifySecurity(
                        id = 7620,
                        title = "Security audit: ${newCriticals.size} new critical finding(s)",
                        body = newCriticals.first().title,
                    )
                }
            }
        }

        // --- Breaking + emergency news (shared with the resident live poller; manages its own notify_state).
        // The check fetches the top feed once and fires each notification per its own pref, so emergencies
        // come through even if the general breaking feed is off. ---
        if (prefs.breakingNews || prefs.emergencyAlerts || prefs.breakingInterrupt) {
            runCatching { BreakingNewsPulse.check(container) }
        }

        // ORACLE — fuse every signal, refresh the always-latest WORLD PULSE feed, and fire the top proactive
        // foresight push every eligible tick (no per-insight cooldown). Runs when EITHER surface is enabled
        // (each is gated inside).
        if (prefs.oracleEnabled || prefs.worldPulse) {
            runCatching { dev.mascwa.pulse.data.oracle.OracleEngine.run(container, settings) }
        }

        // Read the rest of the dedup state AFTER the breaking check so we don't clobber its
        // seenTopUrls update when we persist the other sections below.
        var state = readState()

        // --- Market / price alerts. No daily dedup: a mover still past the threshold re-alerts on every
        // eligible tick (owner's explicit choice — max frequency over once-a-day quiet). ---
        if (prefs.marketAlerts) {
            runCatching {
                val quotes = container.marketsRepository.fetchAll(force = true).data
                // Latest-only: collapse all fresh movers into ONE in-place notification (biggest + "N more"),
                // instead of one-per-instrument piling up the tray.
                val fresh = quotes.filter { q ->
                    val pct = q.changePercent
                    pct != null && abs(pct) >= prefs.marketMovePercent
                }.sortedByDescending { abs(it.changePercent ?: 0.0) }
                if (fresh.isNotEmpty()) {
                    val lead = fresh.first()
                    val pct = lead.changePercent ?: 0.0
                    val dir = if (pct >= 0) "▲" else "▼"
                    val more = fresh.size - 1
                    notifier.notifyMarket(
                        id = NotifId.MARKET,
                        title = "${lead.label} $dir ${Formatters.signedPercent(pct)}" + if (more > 0) "  (+$more more)" else "",
                        body = "Now ${Formatters.number(lead.price, 2)} ${lead.currency}".trim() +
                            if (more > 0) " · also ${fresh.drop(1).take(3).joinToString(", ") { it.label }}" else "",
                    )
                }
                state = state.copy(marketAlertDay = today)
            }
        }

        // --- Severe weather alerts. No daily dedup — a still-severe forecast re-alerts each tick. ---
        var weather: WeatherData? = null
        if (prefs.weatherAlerts) {
            runCatching {
                weather = resolveWeather(settings)
                weather?.daily?.firstOrNull()?.let { day ->
                    val severe = WeatherCode.isSevere(day.weatherCode)
                    val wet = (day.precipProbabilityMax ?: 0) >= 70
                    if (severe || wet) {
                        notifier.notifyWeather(
                            id = NotifId.WEATHER,
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

        // --- Space weather (geomagnetic storm). No daily dedup — an ongoing storm re-alerts each tick. ---
        if (prefs.spaceAlerts) {
            runCatching {
                val sw = container.spaceWeatherRepository.fetch(force = true).data
                val kp = sw.kp
                if (kp != null && kp >= 5.0) {
                    notifier.notifySpace(
                        id = NotifId.SPACE_STORM,
                        title = "Geomagnetic storm: ${sw.stormLevel}",
                        body = "Planetary Kp ${"%.1f".format(kp)}. Aurora: ${sw.auroraChance}.",
                    )
                    state = state.copy(spaceAlertDay = today)
                }
            }
        }

        // --- Aurora likely (NOAA OVATION at the user's location). No daily dedup. ---
        if (prefs.auroraAlerts) {
            runCatching {
                val loc = container.locationProvider.current()
                if (loc != null) {
                    val sw = container.spaceWeatherRepository.fetch(true, loc.latitude, loc.longitude).data
                    val pct = sw.auroraProbabilityPct
                    if (pct != null && pct >= 25) {
                        notifier.notifySpace(
                            id = NotifId.SPACE_AURORA,
                            title = "Aurora likely — $pct% overhead",
                            body = "NOAA OVATION puts aurora at $pct% over your location" +
                                (sw.kp?.let { " · Kp ${"%.1f".format(it)}" } ?: "") + ".",
                        )
                        state = state.copy(auroraAlertDay = today)
                    }
                }
            }
        }

        // --- Hazardous near-Earth object. No daily dedup. ---
        if (prefs.spaceAlerts) {
            runCatching {
                val orbital = container.orbitalRepository.fetch(null, null, force = true).data
                val hazard = orbital.neos.firstOrNull { it.hazardous }
                if (hazard != null) {
                    notifier.notifySpace(
                        id = NotifId.SPACE_NEO,
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
                    // Latest-only: ONE in-place notification for the NEAREST fresh severe incident (+ "N more"),
                    // instead of up to three stacking in the tray.
                    if (severe.isNotEmpty()) {
                        val lead = severe.minByOrNull { it.distanceMeters } ?: severe.first()
                        val more = severe.size - 1
                        notifier.notifySafety(
                            id = NotifId.SAFETY,
                            title = "Safety: ${lead.title.take(60)}" + if (more > 0) "  (+$more more)" else "",
                            body = (if (lead.distanceMeters > 0)
                                "${Formatters.compact(lead.distanceMeters / 1000)} km away · " else "Your area · ") + lead.source,
                        )
                        severe.forEach { already += it.id }
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
                            id = NotifId.FLIGHT,
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

        // --- Life-sim survival check-ins: nudge when a real-decaying need runs low, escalating through four
        // bands (NOTICE → LOW → URGENT → CRITICAL) each throttled on its own NEED|BAND key (worse = sooner),
        // with the live stat toll + advice in the body, and a one-shot recovery confirmation when a need is
        // brought back up; + remind when a real appointment is imminent (once per event). The needs bleed into
        // reality, so this doubles as a "drink / eat / rest / wash" reminder. Keeps you alive. ---
        if (prefs.survivalAlerts) {
            runCatching {
                val now = System.currentTimeMillis()
                val life = container.specialGameStore.lifeSnapshot()
                val active = state.survivalNeedActive.toMutableSet()
                SurvivalNeed.entries.forEach { need ->
                    val value = need.kind.value(life)
                    val band = SurvivalAlerts.bandFor(value)
                    if (band != null) {
                        val alert = SurvivalAlerts.alertFor(need, value, now) ?: return@forEach
                        // No per-band cooldown — a need still in a bad band re-nags every eligible tick
                        // (owner's explicit choice: max notification frequency).
                        // Richer body: the themed line + the live stat stakes (when it's biting) + advice.
                        val body = buildString {
                            append(alert.body)
                            if (alert.stakes.isNotBlank()) append("\nToll right now: ${alert.stakes}")
                            append("\n${alert.advice}")
                        }
                        notifier.notifySurvival(
                            id = 7700 + need.ordinal,
                            title = alert.title,
                            body = body,
                            urgent = band.isPressing,
                        )
                        // Track LOW-or-worse so a recovery can be confirmed; a mere NOTICE isn't worth one.
                        if (band != AlertLevel.NOTICE) active += need.name
                    } else if (need.name in active) {
                        // Climbed back to healthy after being a real concern → confirm the recovery once.
                        val rec = SurvivalAlerts.recovery(need, now)
                        notifier.notifySurvival(id = 7700 + need.ordinal, title = rec.title, body = rec.body, urgent = false)
                        active -= need.name
                    }
                }
                state = state.copy(survivalNeedActive = active.toList())

                // Lingering afflictions: fire once when a disease takes hold (a neglected need went sick),
                // once when it clears. Deduped via afflictedNotified. Shares the survival-alerts toggle.
                val aff = container.specialGameStore.afflictionSnapshot()
                val activeAfflictions = aff.active.map { it.name }
                val knownAfflictions = state.afflictedNotified
                aff.active.filter { it.name !in knownAfflictions }.forEach { a ->
                    notifier.notifySurvival(7780 + a.ordinal, "⚕ AFFLICTED · ${a.label}", "${a.desc} ${a.cure}", urgent = true)
                }
                Affliction.entries.filter { it.name in knownAfflictions && it.name !in activeAfflictions }.forEach { a ->
                    notifier.notifySurvival(
                        7780 + a.ordinal, "✓ RECOVERED · ${a.label}",
                        "You've shaken off ${a.label.lowercase()}. Back to fighting form.", urgent = false,
                    )
                }
                state = state.copy(afflictedNotified = activeAfflictions)

                // Imminent real appointment → an agenda reminder, once per event while it's imminent.
                val agenda = dev.mascwa.pulse.core.telemetry.CalendarQuests.compose(
                    container.calendarRepository.upcoming(now), now, max = 5,
                )
                val notified = state.agendaNotifiedIds.toMutableSet()
                // Latest-only: ONE in-place reminder for the SOONEST imminent appointment (+ "N more");
                // CalendarQuests.compose returns soonest-first.
                val dueAgenda = agenda.filter { it.imminent && it.id.toString() !in notified }
                if (dueAgenda.isNotEmpty()) {
                    val lead = dueAgenda.first()
                    val more = dueAgenda.size - 1
                    notifier.notifyAgenda(
                        id = NotifId.AGENDA,
                        title = "⏰ Incoming: ${lead.title.take(60)}" + if (more > 0) "  (+$more more)" else "",
                        body = "${lead.countdown} — ${lead.briefing}",
                    )
                    dueAgenda.forEach { notified += it.id.toString() }
                }
                // Keep only ids still in the agenda window so the dedup set can't grow without bound.
                val liveIds = agenda.map { it.id.toString() }.toSet()
                state = state.copy(agendaNotifiedIds = notified.filter { it in liveIds })
            }
        }

        // --- "Don't break your streak": if a meaningful self-care streak is in its grace day (done
        // yesterday, not yet today) nudge to keep it alive. Uses the SAME local-epoch-day the streaks were
        // stamped with, so "yesterday" lines up. No daily dedup — re-nags every eligible tick while at risk. ---
        if (prefs.selfCareCheckins && prefs.streakReminders) {
            runCatching {
                val now = System.currentTimeMillis()
                val todayDay = ((now + java.util.TimeZone.getDefault().getOffset(now)) / 86_400_000L).toInt()
                run {
                    val streaks = container.habitStore.streaks().values
                    val atRisk = dev.mascwa.pulse.core.telemetry.SelfCareStreak.bestAtRisk(streaks, todayDay)
                    if (atRisk > 0) {
                        notifier.notifySurvival(
                            id = 7740,
                            title = "🔥 Keep your $atRisk-day streak alive",
                            body = "Check in on your self-care today so your $atRisk-day streak doesn't break.",
                            urgent = false,
                        )
                        state = state.copy(streakReminderDay = todayDay)
                    }
                }
            }
        }

        // --- Phone penalties: engage/lift the "neglect bites the phone" device locks from the background-
        // decayed needs, so a need that lapses while the app is closed still locks its capability (and one
        // that recovers is released). The controller no-ops unless the opt-in toggle is on AND Pulse is a
        // Device Owner; fully defensive. ---
        runCatching {
            val life = container.specialGameStore.lifeSnapshot()
            // fireGate=true: the background worker is the only path that pops the harsher kiosk lock-screen gate
            // (so it fires when the phone is idle, not while you're actively in Pulse). No re-trigger cooldown.
            container.phonePenaltyController.reconcile(life, fireGate = true)
        }

        // --- Survival tips: push one tip from the 300+ catalog (quiet, low-priority, fixed id so each
        // silently replaces the last). No cooldown — fires every eligible tick. The tip index is DERIVED
        // FROM THE CLOCK on a fine ROTATION_MS granularity (not a persisted cursor, and not a suppression
        // gate — purely which tip is "current"), so the rotation can never get stuck on tip 0 even if state
        // fails to persist, consecutive ticks show a genuinely different tip, and the ×131 scramble (coprime
        // with the ~310-tip catalog) makes it feel random while still covering everything over time. ---
        if (prefs.survivalTips) {
            runCatching {
                val now = System.currentTimeMillis()
                val bucket = now / TIP_ROTATION_MS
                val idx = (bucket * 131).toInt()
                val tips = dev.mascwa.pulse.core.telemetry.SurvivalTips
                // Deep-link the tip to the specific guide that teaches its skill (knot tip → knots guide).
                notifier.notifyTip(NotifId.TIP, "⛑ Survival tip", tips.tip(idx), guideId = tips.guideIdAt(idx))
                state = state.copy(survivalTipIndex = idx, survivalTipLastMs = now)
            }
        }

        // --- Daily digest. digestHour is a "not before this local time" floor, not a repeat cooldown — kept.
        // No daily dedup beyond that, so it refreshes with the latest lines on every eligible tick after
        // digestHour rather than freezing at whatever was true the first time it fired that day. ---
        if (prefs.dailyDigest && hour >= prefs.digestHour) {
            runCatching {
                val lines = buildDigestLines(settings, weather)
                if (lines.isNotEmpty()) {
                    notifier.notifyDigest(
                        id = NotifId.DIGEST,
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
        // Self-care streak: a glanceable nudge (grace-day) or a "going strong" line — only when the check-in
        // system is on and a streak actually exists, so it never clutters the digest for non-players.
        if (settings.notifications.selfCareCheckins) {
            runCatching {
                val streaks = container.habitStore.streaks().values
                val now = System.currentTimeMillis()
                val today = ((now + java.util.TimeZone.getDefault().getOffset(now)) / 86_400_000L).toInt()
                val atRisk = dev.mascwa.pulse.core.telemetry.SelfCareStreak.bestAtRisk(streaks, today)
                val liveBest = streaks.maxOfOrNull {
                    dev.mascwa.pulse.core.telemetry.SelfCareStreak.currentAsOf(it, today)
                } ?: 0
                when {
                    atRisk > 0 -> lines += "🔥 Keep your $atRisk-day self-care streak alive today"
                    liveBest > 0 -> lines += "🔥 $liveBest-day self-care streak going strong"
                }
            }
        }
        return lines
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
        ) else state
        container.diskCache.write("notify_state", merged, NotifyState.serializer())
    }

    private fun dayKey(cal: Calendar): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)

    companion object {
        const val UNIQUE_NAME = "pulse_periodic_refresh"
        // Rotation granularity for which survival tip is "current" — NOT a suppression cooldown (there is
        // none: tips fire every eligible worker tick). A small value just guarantees consecutive ticks show
        // a genuinely different tip instead of repeating the same one for hours.
        private const val TIP_ROTATION_MS = 60L * 1000 // one minute
    }
}
