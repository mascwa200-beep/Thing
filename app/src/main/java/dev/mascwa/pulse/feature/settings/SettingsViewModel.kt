package dev.mascwa.pulse.feature.settings

import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.data.settings.SettingsBackup
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.selfcode.SelfCoder
import dev.mascwa.pulse.data.update.UpdateInfo
import dev.mascwa.pulse.data.update.UpdateRepository
import dev.mascwa.pulse.notifications.NotificationScheduler
import dev.mascwa.pulse.notifications.Notifier
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val scheduler: NotificationScheduler,
    private val diskCache: DiskCache,
    private val notifier: Notifier,
    private val updates: UpdateRepository,
    private val selfCoder: SelfCoder,
    private val usage: dev.mascwa.pulse.data.usage.UsageRepository,
    private val cerebellum: dev.mascwa.pulse.data.cerebellum.CerebellumStore,
    private val profile: dev.mascwa.pulse.data.profile.ProfileStore,
    private val tasks: dev.mascwa.pulse.data.tasks.TaskStore,
    private val memoryStream: dev.mascwa.pulse.data.memory.MemoryStreamStore,
    private val wifi: dev.mascwa.pulse.security.WifiPolicyController,
    private val auditLedger: dev.mascwa.pulse.data.blackbox.AuditLedgerStore,
    private val ledgerSelfTest: dev.mascwa.pulse.data.blackbox.LedgerSelfTest,
    private val oracleLearning: dev.mascwa.pulse.data.oracle.OracleLearningStore,
    private val study: dev.mascwa.pulse.data.study.StudyStore,
) : ViewModel() {

    private val _selfTest = MutableStateFlow<dev.mascwa.pulse.data.blackbox.LedgerSelfTest.Report?>(null)
    /** Result of the ledger self-test (null = not run / dialog dismissed). */
    val ledgerSelfTestResult: StateFlow<dev.mascwa.pulse.data.blackbox.LedgerSelfTest.Report?> = _selfTest

    private val _selfTestRunning = MutableStateFlow(false)
    val ledgerSelfTestRunning: StateFlow<Boolean> = _selfTestRunning

    /** Run the on-device ledger self-test (chain · secure-element signature · encryption · live TSA). */
    fun runLedgerSelfTest() {
        if (_selfTestRunning.value) return
        viewModelScope.launch {
            _selfTestRunning.value = true
            _selfTest.value = runCatching { ledgerSelfTest.run() }.getOrNull()
            _selfTestRunning.value = false
        }
    }

    /** Dismiss the self-test result dialog. */
    fun dismissLedgerSelfTest() {
        _selfTest.value = null
    }

    private val _selfCode = MutableStateFlow("")
    /** Status line for the self-coding "propose a change" action. */
    val selfCodeStatus: StateFlow<String> = _selfCode

    private val _auditLedger = MutableStateFlow("Tap to verify")
    /** Inline integrity readout for the Settings "Verify audit ledger" control. */
    val auditLedgerStatus: StateFlow<String> = _auditLedger

    /** Re-check the audit ledger's chain integrity + head signature + last anchor, shown inline. */
    fun verifyAuditLedger() {
        _auditLedger.value = "Verifying…"
        viewModelScope.launch {
            _auditLedger.value = runCatching {
                val v = auditLedger.verify()
                val signed = auditLedger.headSignatureValid()
                val anchorMs = auditLedger.anchorTimeMs()
                buildList {
                    add(if (v.valid) "Intact" else "BROKEN at #${v.brokenAtSeq}")
                    when (signed) {
                        true -> add("signed")
                        false -> add("bad signature")
                        null -> {}
                    }
                    if (anchorMs != null) add("anchored ${DateUtils.getRelativeTimeSpanString(anchorMs)}")
                }.joinToString(" · ")
            }.getOrDefault("Couldn't verify")
        }
    }

    /** Record a device-policy change in the tamper-evident ledger (content-free label + detail). */
    fun recordDevicePolicy(action: String, detail: String) {
        runCatching { auditLedger.record(dev.mascwa.pulse.core.telemetry.AuditEventType.SECURITY, "devicepolicy.$action", detail) }
    }

    /** Wipe the audit ledger. */
    fun clearAuditLedger() {
        viewModelScope.launch {
            runCatching { auditLedger.clear() }
            _auditLedger.value = "Cleared"
        }
    }

    /**
     * Clear the persisted list of paired computers, and record the revocation in the audit ledger — an
     * unpairing is exactly the sort of event worth being able to prove happened.
     *
     * This is only the PERSISTED half. A running link holds its peers as an in-memory snapshot, so the
     * caller must also tell the service to drop them
     * ([dev.mascwa.pulse.remote.RemoteLinkService.requestUnpairAll]); otherwise every paired machine keeps
     * command access until the service next restarts.
     */
    fun unpairAllComputers() {
        viewModelScope.launch {
            runCatching {
                repo.update { it.copy(remote = it.remote.copy(pairedKeys = emptyList())) }
                auditLedger.record(
                    dev.mascwa.pulse.core.telemetry.AuditEventType.SECURITY,
                    "remote.unpaired.all",
                    "",
                )
            }
        }
    }

    /** Have J.A.R.V.I.S. draft a change for [goal] and stage it for approval. [path] is optional — leave
     *  it blank and J.A.R.V.I.S. picks the file itself; approve the staged change to open the PR. */
    fun proposeSelfChange(goal: String, path: String) {
        if (goal.isBlank()) { _selfCode.value = "Enter a goal."; return }
        _selfCode.value = "Drafting a change…"
        viewModelScope.launch {
            _selfCode.value = runCatching { selfCoder.propose(goal, path) }
                .getOrElse { SelfCoder.Result(false, it.message ?: "failed") }
                .message
        }
    }

    val settings: StateFlow<AppSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** In-app updater UI state. */
    sealed interface UpdateUi {
        data object Idle : UpdateUi
        data object Checking : UpdateUi
        data class UpToDate(val latest: String?) : UpdateUi
        /** A newer build exists but isn't installable yet (still building / not verified). */
        data class Pending(val latest: String?) : UpdateUi
        data class Available(val info: UpdateInfo) : UpdateUi
        data class Downloading(val pct: Int) : UpdateUi
        data class ReadyToInstall(val info: UpdateInfo, val file: File) : UpdateUi
        data class Error(val message: String) : UpdateUi
    }

    private val _update = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val updateState: StateFlow<UpdateUi> = _update

    /** Currently-installed version name, e.g. "1.0.42-debug". */
    val installedVersion: String get() = updates.currentVersionName

    /** Check the `latest` release for a newer build. Safe to call on screen open. */
    fun checkForUpdate() {
        if (_update.value is UpdateUi.Checking || _update.value is UpdateUi.Downloading) return
        _update.value = UpdateUi.Checking
        viewModelScope.launch {
            _update.value = runCatching { updates.check() }.fold(
                onSuccess = { result ->
                    val info = result.available
                    when {
                        info != null -> UpdateUi.Available(info)
                        result.pending -> UpdateUi.Pending(result.latestVersionName)
                        else -> UpdateUi.UpToDate(result.latestVersionName)
                    }
                },
                onFailure = { e ->
                    val code = (e as? dev.mascwa.pulse.core.network.HttpException)?.code
                    val msg = when {
                        updates.token() == null ->
                            "Your repo is private — add a GitHub token (repo scope) in Computer Setup, or make the repo public."
                        code == 401 ->
                            "GitHub rejected the token (401). Re-paste it with no spaces — a classic token needs the 'repo' scope (or a fine-grained one with Contents: read on this repo)."
                        code == 403 ->
                            "GitHub refused the request (403) — the token is rate-limited or lacks scope."
                        code == 404 ->
                            "Release or repo not found (404) — the token can't see this private repo. A classic token needs the 'repo' scope."
                        else ->
                            "Couldn't reach the update server${code?.let { " ($it)" } ?: ""} — check your connection."
                    }
                    UpdateUi.Error(msg)
                },
            )
        }
    }

    /** Download the available update; on success the screen installs the returned file. */
    fun downloadUpdate() {
        val info = (_update.value as? UpdateUi.Available)?.info
            ?: (_update.value as? UpdateUi.ReadyToInstall)?.info ?: return
        _update.value = UpdateUi.Downloading(0)
        viewModelScope.launch {
            val file = runCatching { updates.download(info) { pct -> _update.value = UpdateUi.Downloading(pct) } }.getOrNull()
            _update.value = if (file != null) UpdateUi.ReadyToInstall(info, file) else UpdateUi.Error("Download failed — try again.")
        }
    }

    private val _cacheSize = MutableStateFlow(0L)
    val cacheSize: StateFlow<Long> = _cacheSize

    init {
        refreshCacheSize()
        // Keep the background worker in sync with notification/refresh prefs.
        viewModelScope.launch {
            repo.settings
                .map { Triple(it.notifications.masterEnabled, it.refreshIntervalMinutes, it.refreshOnlyOnWifi) }
                .distinctUntilChanged()
                .collect { (master, interval, wifi) ->
                    if (master) scheduler.schedule(interval, wifi) else scheduler.cancel()
                }
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    fun resetToDefaults() {
        viewModelScope.launch { repo.replace(AppSettings()) }
    }

    private val _backup = MutableStateFlow("")
    /** Status line for the local backup / restore actions. */
    val backupStatus: StateFlow<String> = _backup

    /** Write a credential-free backup of the current settings to the user-chosen [uri]. */
    fun exportSettings(context: Context, uri: Uri) {
        viewModelScope.launch {
            _backup.value = runCatching {
                val text = SettingsBackup.encode(repo.current(), System.currentTimeMillis())
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("couldn't open the file")
                }
                "Backup saved. API keys & tokens were left out for safety — re-add them after a restore."
            }.getOrElse { "Backup failed: ${it.message}" }
        }
    }

    /** Restore settings from the backup file at [uri], keeping the device's existing credentials. */
    fun importSettings(context: Context, uri: Uri) {
        viewModelScope.launch {
            _backup.value = runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        String(it.readBytes(), Charsets.UTF_8)
                    } ?: error("couldn't open the file")
                }
                repo.replace(SettingsBackup.decode(text, repo.current()))
                "Settings restored. Your current API keys & tokens were kept."
            }.getOrElse { "Restore failed: ${it.message}" }
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch { _cacheSize.value = diskCache.sizeBytes() }
    }

    fun clearCache() {
        viewModelScope.launch {
            diskCache.clear()
            refreshCacheSize()
        }
    }

    /** Wipe the on-device usage history that powers J.A.R.V.I.S.'s tailored recommendations. */
    fun clearUsageData() {
        viewModelScope.launch { usage.clear() }
    }

    /** Forget everything the virtual cerebellum has learned (practiced skills / reflexes). */
    fun resetReflexes() {
        viewModelScope.launch { cerebellum.clear() }
    }

    /** Forget the structured user profile (durable preferences / interests / projects). */
    fun clearProfile() {
        viewModelScope.launch { profile.clear() }
    }

    /** Forget the tracked task board (the user's ongoing & completed tasks/goals). */
    fun clearTasks() {
        viewModelScope.launch { tasks.clear() }
    }

    /** Forget which advisories the user acts on — every Oracle rule back to equal footing. */
    fun clearOracleLearning() {
        viewModelScope.launch { oracleLearning.clear() }
    }

    /** Forget the episodic memory stream (timestamped observations & reflections). */
    fun clearMemoryStream() {
        viewModelScope.launch { memoryStream.clear() }
    }

    /** Forget the study log — the enrolled path, what has been taught, and every review schedule. */
    fun clearStudy() {
        viewModelScope.launch { study.clear() }
    }

    // ----- Trusted Network Mode / security -----

    /** True once Pulse is provisioned as Device Owner → the Wi-Fi toggle will actually take effect. */
    val isDeviceOwner: Boolean get() = wifi.isDeviceOwner()

    /** The currently-associated Wi-Fi SSID (quotes stripped), or null if not on Wi-Fi / unreadable. */
    fun currentNetworkName(): String? {
        val raw = wifi.currentSsid() ?: return null
        val clean = raw.trim().removePrefix("\"").removeSuffix("\"").trim()
        return if (clean.isBlank() || clean.equals("<unknown ssid>", ignoreCase = true)) null else clean
    }

    /** Add an SSID to the home list (de-duped, case-insensitive). */
    fun addHomeSsid(ssid: String) {
        val clean = ssid.trim().removePrefix("\"").removeSuffix("\"").trim()
        if (clean.isBlank()) return
        update { s ->
            if (s.security.homeSsids.any { it.equals(clean, ignoreCase = true) }) s
            else s.copy(security = s.security.copy(homeSsids = s.security.homeSsids + clean))
        }
    }

    fun removeHomeSsid(ssid: String) = update { s ->
        s.copy(security = s.security.copy(homeSsids = s.security.homeSsids.filterNot { it.equals(ssid, ignoreCase = true) }))
    }

    fun sendTestNotification() {
        // Posts a fully-populated sample of THE one LCARS notification on the alerting channel (so it pops
        // as a heads-up) — this is the on-device render check for the board's custom layout: expand it and
        // every row's colour-block label + plain text line should sit cleanly, nothing overlapping.
        val sample = dev.mascwa.pulse.core.telemetry.UnifiedBrief(
            headline = "LCARS notifications are working",
            tempLabel = "72°F",
            rows = listOf(
                dev.mascwa.pulse.core.telemetry.BriefRow(
                    dev.mascwa.pulse.core.telemetry.BriefRowKind.ALERT,
                    "This is a test — Yellow Alert looks like this",
                ),
                dev.mascwa.pulse.core.telemetry.BriefRow(
                    dev.mascwa.pulse.core.telemetry.BriefRowKind.NEWS,
                    "Your top story appears here — Sample Source",
                ),
                dev.mascwa.pulse.core.telemetry.BriefRow(
                    dev.mascwa.pulse.core.telemetry.BriefRowKind.WEATHER,
                    "72°F now · Cloudy · High 78 / Low 61",
                ),
                dev.mascwa.pulse.core.telemetry.BriefRow(
                    dev.mascwa.pulse.core.telemetry.BriefRowKind.AGENDA,
                    "Dentist in 1h 40m · 3 tasks open · 2 reminders set",
                ),
                // ADVISORY in the MARKETS seat, which is exactly what a real six-row board looks
                // like after the composer trims it. The layout has five slots and the renderer takes
                // the first five, so a six-row sample would silently drop this row — the one row
                // this button now exists to show.
                dev.mascwa.pulse.core.telemetry.BriefRow(
                    dev.mascwa.pulse.core.telemetry.BriefRowKind.ADVISORY,
                    "Leave in 10 min — 4.2 km to the dentist and rain from 08:40",
                ),
            ),
            urgency = dev.mascwa.pulse.core.telemetry.BriefUrgency.YELLOW,
            urgencyKey = "test:${System.currentTimeMillis()}",
        )
        notifier.notifyBrief(sample, alertNew = true)
    }
}
