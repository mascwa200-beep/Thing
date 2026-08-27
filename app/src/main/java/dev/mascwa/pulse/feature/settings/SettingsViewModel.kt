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
    /** The same checker pointed at the standalone nutrition app's own release. */
    private val companionUpdates: UpdateRepository,
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
    private val python: dev.mascwa.pulse.data.python.PythonRuntime,
) : ViewModel() {

    // Steam-style master-detail position — WHICH category is open (null = the master list).
    // VM-scoped, per the arc's rule for state that must outlive the composition: this used to be a
    // plain remember{} in the screen, so tabbing away and back (saveState/restoreState retains the
    // ViewModelStore but rebuilds the composition) snapped the user from deep inside a category
    // back to the entry category. Seeded ONCE per VM from the route argument, so a restore never
    // re-applies a stale deep-link argument over where the user actually navigated.
    val selectedCategory = kotlinx.coroutines.flow.MutableStateFlow<SettingsCategory?>(null)
    private var categorySeeded = false
    fun seedCategory(cat: SettingsCategory?) {
        if (categorySeeded) return
        categorySeeded = true
        if (cat != null) selectedCategory.value = cat
    }

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

    private val _pythonTest = MutableStateFlow<dev.mascwa.pulse.data.python.PythonRuntime.Report?>(null)
    /** Result of the Python self-test (null = not run / dialog dismissed). */
    val pythonTestResult: StateFlow<dev.mascwa.pulse.data.python.PythonRuntime.Report?> = _pythonTest

    private val _pythonTestRunning = MutableStateFlow(false)
    val pythonTestRunning: StateFlow<Boolean> = _pythonTestRunning

    /**
     * Start the embedded interpreter and report what it can actually do.
     *
     * ⚠️ This is the owner-verify half of the toolchain proof. CI asserts that the interpreter and
     * its standard-library asset are inside the shipped APK — a fact about a zip file — and cannot
     * say whether it starts on a real phone. Neither check substitutes for the other, exactly as
     * with the native library's symbol check and the ledger self-test above.
     */
    fun runPythonTest() {
        if (_pythonTestRunning.value) return
        viewModelScope.launch {
            _pythonTestRunning.value = true
            _pythonTest.value = runCatching { python.selfTest() }.getOrElse {
                dev.mascwa.pulse.data.python.PythonRuntime.Report(
                    running = false, interpreter = null, roundTrip = null, stdlib = null,
                    error = it.message ?: it::class.java.simpleName,
                )
            }
            _pythonTestRunning.value = false
        }
    }

    /** Dismiss the Python self-test dialog. */
    fun dismissPythonTest() {
        _pythonTest.value = null
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
                val h = auditLedger.health()
                // ⚠️ **The unreadable case leads, and it used to be invisible.** `verify()` runs over
                // whatever chain is in memory, and when the stored blob cannot be decoded that is an
                // empty replacement — trivially intact. So this row said "Intact" at precisely the
                // moment the record had become unreachable and nothing new was reaching disk.
                if (h.unreadable) {
                    "STORED RECORD UNREADABLE — nothing is being written" +
                        (if (h.unwritten > 0) "; ${h.unwritten} event(s) held in memory only" else "") +
                        ". Clear the ledger to start a new chain."
                } else buildList {
                    add(if (h.verification.valid) "Intact" else "BROKEN at #${h.verification.brokenAtSeq}")
                    when (h.signatureValid) {
                        true -> add("signed")
                        false -> add("bad signature")
                        null -> {}
                    }
                    h.anchorMs?.let { add("anchored ${DateUtils.getRelativeTimeSpanString(it)}") }
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

    // ------------------------------------------------------------------- the companion nutrition app

    private val _companion = MutableStateFlow<UpdateUi>(UpdateUi.Idle)

    /**
     * The standalone nutrition app's own release, so it can be put on this phone at all.
     *
     * ⚠️ **This is how that app is obtained.** It has no store listing and its releases sit in a
     * private repository, so without this the only route is a desktop, a browser signed in to
     * GitHub and a cable. This app already holds the token that can read those releases, which is
     * the whole reason the download belongs here rather than there.
     *
     * ⚠️ It reuses [UpdateUi] rather than growing a parallel vocabulary, so the same screen code
     * renders both — and the states mean exactly what they mean above, including [UpdateUi.Pending]
     * for a build still going through CI.
     */
    val companionState: StateFlow<UpdateUi> = _companion

    /**
     * Fetch the nutrition app: ask GitHub what the newest build is and, if there is one, start
     * downloading it. One action.
     *
     * ⚠️ **This used to be a bare "check", and that is precisely why somebody hunting for a way to
     * get the app did not find one.** A person looking for a download expects a button that says
     * download; a button that says *check* reads as diagnostics, and the actual Download control
     * only appeared after that first tap succeeded. Three taps, the first two looking like
     * different things. The check still happens — it has to, because the release URL is only known
     * afterwards — it simply is not a separate decision any more.
     *
     * ⚠️ **Never [UpdateUi.UpToDate].** That repository is pointed at with `currentVersionCode = 0`
     * — this app cannot read the version of a package it does not own — so every published build is
     * newer than nothing and the answer is always either "here it is" or "it is not built yet".
     * Saying "up to date" would be a claim about a package this app has not looked at.
     *
     * ⚠️ It starts a download of roughly 180 MB without asking a second time, which is deliberate
     * and is why the button has to name the size. The alternative — a confirmation between the
     * check and the fetch — is the very step that made this unfindable.
     */
    fun getCompanion() {
        if (_companion.value is UpdateUi.Checking || _companion.value is UpdateUi.Downloading) return
        _companion.value = UpdateUi.Checking
        viewModelScope.launch {
            val resolved = runCatching { companionUpdates.check() }.fold(
                onSuccess = { result ->
                    val info = result.available
                    when {
                        info != null -> UpdateUi.Available(info)
                        else -> UpdateUi.Pending(result.latestVersionName)
                    }
                },
                onFailure = { e ->
                    val code = (e as? dev.mascwa.pulse.core.network.HttpException)?.code
                    UpdateUi.Error(
                        when {
                            companionUpdates.token() == null ->
                                "The nutrition app's releases are in the same private repo — add a " +
                                    "GitHub token (repo scope) in Computer Setup."
                            code == 404 ->
                                "No nutrition release yet (404), or the token cannot see this repo."
                            code == 401 || code == 403 ->
                                "GitHub refused the token ($code) — check it has repo scope."
                            else -> "Couldn't reach GitHub${code?.let { " ($it)" } ?: ""}."
                        },
                    )
                },
            )
            _companion.value = resolved
            // The whole point: having found a build, go and get it rather than waiting for a
            // second tap on a control that was not visible until this moment.
            if (resolved is UpdateUi.Available) downloadCompanion()
        }
    }

    /** Fetch the nutrition APK; the screen installs the file this leaves in [companionState]. */
    fun downloadCompanion() {
        val info = (_companion.value as? UpdateUi.Available)?.info
            ?: (_companion.value as? UpdateUi.ReadyToInstall)?.info ?: return
        _companion.value = UpdateUi.Downloading(0)
        viewModelScope.launch {
            val file = runCatching {
                companionUpdates.download(info) { pct -> _companion.value = UpdateUi.Downloading(pct) }
            }.getOrNull()
            _companion.value =
                if (file != null) UpdateUi.ReadyToInstall(info, file)
                else UpdateUi.Error("Download failed — try again.")
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
                // ⚠️ HEALTH stands in the WEATHER seat, and the swap is deliberate. Five slots, and
                // the two rows a reader cannot conjure on demand are the two worth showing: an
                // advisory needs the Oracle to have reasoned its way to one, and a health row needs
                // both a settled calorie target and a day of logging behind it. WEATHER renders on
                // essentially every real board, and the temperature chip above still exercises it.
                dev.mascwa.pulse.core.telemetry.BriefRow(
                    dev.mascwa.pulse.core.telemetry.BriefRowKind.HEALTH,
                    "1,240 kcal left · 84 g protein to go",
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
