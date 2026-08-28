package dev.mascwa.nutrition.data

import android.content.Context
import android.os.Build
import dev.mascwa.nutrition.BuildConfig
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.data.update.ApkInstaller
import dev.mascwa.pulse.data.update.UpdateInfo
import dev.mascwa.pulse.data.update.UpdateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * This app keeping itself current.
 *
 * ⚠️ **It updates itself; it is not "an update screen".** The screen exists so the owner can see
 * what happened and force it, but nothing there has to be touched for an update to arrive: a check
 * runs when the app comes to the foreground, a newer build downloads, and it installs when the app
 * is put down. That is the same shape the LCARS application uses and for the same reason — an app
 * you have to remember to update is one that silently stops being updated.
 *
 * ## What it cannot do, said once
 *
 * ⚠️ **The releases are in a private repository, so a GitHub token is required and there is no way
 * around that.** Without one the check gets a 404 and says so. A public mirror would remove the
 * requirement and is not something this file can decide.
 *
 * ⚠️ **The first install of an update will show the system's confirmation.** This app is not a
 * device owner and is not yet the installer of record for itself, so [ApkInstaller]'s top two rungs
 * do not apply. The rung that does apply arms the next one: once this app has committed one session
 * it becomes the installer of record, and later updates can complete with no dialog at all. So the
 * honest description is "one tap the first time, none after", not "silent".
 *
 * ## Unless the companion is here, in which case neither applies
 *
 * ⚠️ **Both paragraphs above describe this app updating ITSELF.** On a phone that also carries the
 * LCARS application, none of it is the route that actually runs: that app is provisioned as a device
 * owner, already holds a token, and reinstalls this one whenever a newer build is published — so the
 * token is not needed and no confirmation is shown, because rung one applies to the installer rather
 * than to the installed. [maintainedByCompanion] is how the screen tells which world it is in, and
 * everything here stays as the fallback for when LCARS is removed.
 */
class NutritionUpdates(
    context: Context,
    http: HttpClient,
    private val settings: HealthSettingsStore,
) {

    private val appContext = context.applicationContext

    private val repo = UpdateRepository(
        appContext,
        http,
        tag = UpdateRepository.NUTRITION_TAG,
        workflow = UpdateRepository.NUTRITION_WORKFLOW,
        currentVersionCode = BuildConfig.VERSION_CODE,
        currentVersionName = BuildConfig.VERSION_NAME,
        token = { settings.currentUpdateToken() },
    )

    /** What the update screen shows. Every state says what actually happened, never just "no". */
    sealed interface State {
        /** Nothing has been asked yet this session. */
        data object Idle : State

        data object Checking : State

        /** The newest published build is the one running. */
        data class Current(val latest: String) : State

        /**
         * A newer build exists but is not offerable yet — still compiling, or its run failed.
         *
         * ⚠️ Distinct from [Current] on purpose. Telling somebody they are up to date when a newer
         * build is halfway through CI is a small lie that makes the next check look broken.
         */
        data class Pending(val latest: String) : State

        data class Available(val info: UpdateInfo) : State

        data class Downloading(val info: UpdateInfo, val percent: Int) : State

        /**
         * Downloaded and waiting to be installed when the app is next put down.
         *
         * ⚠️ Carries the [file] rather than leaving the caller to rebuild its path. The path is
         * this module's business — it is composed from the release tag inside the shared
         * repository — and a screen that reconstructed it would be a second statement of it,
         * silently wrong the day the naming changes.
         */
        data class Ready(val info: UpdateInfo, val file: File) : State

        /**
         * The check or the download failed, with what the failure actually was.
         *
         * ⚠️ A 404 here almost always means "no token, private repository" rather than "no such
         * release", so the message names that possibility rather than making the reader guess.
         */
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val installedVersion: String get() = BuildConfig.VERSION_NAME
    val installedCode: Int get() = BuildConfig.VERSION_CODE

    /** The stored token, so the screen can show whether one is set without holding it. */
    suspend fun hasToken(): Boolean = settings.currentUpdateToken() != null

    /**
     * Whether the LCARS application put this app here, and therefore keeps it current.
     *
     * ⚠️ **The installer of record, not merely "is LCARS installed".** Presence would be an
     * inference; this is a fact about how this copy arrived. LCARS is provisioned as a device owner
     * on the owner's phone, holds the GitHub token already, and now runs a pass that reinstalls the
     * companion whenever a newer build is published — so when this returns true, both things this
     * card would otherwise ask for are already handled and asking for them again is noise.
     *
     * ⚠️ **It reports what is true, and never disables anything.** The token field stays: LCARS can
     * be uninstalled, and this app's own updater is then the only route left. What changes is the
     * copy, which stops insisting on a token that is not needed today.
     *
     * ⚠️ Needs the `<queries>` entry in this module's manifest. Without it package visibility
     * filters the installing package to null and this silently answers false on a phone where it is
     * true — the same trap the Health Connect check has, one line above it in the same block.
     *
     * ⚠️ `suspend` with the dispatcher chosen HERE rather than at the call site, mirroring
     * [hasToken]. `getInstallSourceInfo` is a binder call into the package manager, and a caller
     * that forgot to move it would put one on the main thread — a decision that belongs beside the
     * work rather than repeated by every reader of it.
     */
    suspend fun maintainedByCompanion(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val pm = appContext.packageManager
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(appContext.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(appContext.packageName)
            }
            installer == UpdateRepository.LCARS_PACKAGE
        }.getOrDefault(false)
    }

    /** Save a pasted token; a blank one clears it. */
    suspend fun saveToken(value: String) = settings.setUpdateToken(value)

    /**
     * Ask GitHub what the newest build is.
     *
     * ⚠️ Returns the [UpdateInfo] when there is one so a caller that wants to go straight on to
     * downloading does not have to read it back out of the state and race with the screen.
     */
    suspend fun check(): UpdateInfo? {
        _state.value = State.Checking
        val result = runCatching { repo.check() }
        val check = result.getOrElse { err ->
            _state.value = State.Failed(explain(err))
            return null
        }
        // ⚠️ **Hoisted to a local, and it has to be.** `UpdateCheck.available` is a `val` declared
        // in `:core:update`, and Kotlin refuses to smart-cast a public property from another module
        // — so `check.available != null -> check.available.also { ... }` leaves the receiver
        // nullable and `State.Available`, which takes a non-null, will not accept it. A local val
        // smart-casts normally. This repository has now paid for that rule four times.
        val available = check.available
        val name = check.latestVersionName ?: "unknown"
        return when {
            available != null -> available.also { _state.value = State.Available(it) }
            check.pending -> { _state.value = State.Pending(name); null }
            else -> { _state.value = State.Current(name); null }
        }
    }

    /** Fetch the APK, reporting progress. Returns the file, or null when it could not be had. */
    suspend fun download(info: UpdateInfo): File? {
        _state.value = State.Downloading(info, 0)
        return runCatching {
            repo.download(info) { pct -> _state.value = State.Downloading(info, pct) }
        }.onSuccess { file ->
            _state.value = State.Ready(info, file)
        }.onFailure { err ->
            _state.value = State.Failed(explain(err))
        }.getOrNull()
    }

    /**
     * Install a downloaded build.
     *
     * ⚠️ **The pending marker is written BEFORE the commit, and that ordering is load-bearing.**
     * Android tears this process down while its own package is replaced, so anything written after
     * `commit` may simply never run. Recording it first means a successful install always leaves the
     * marker set, and the next launch — which is the newer build — clears it by comparison.
     */
    suspend fun install(): Boolean {
        val ready = _state.value as? State.Ready ?: return false
        settings.setPendingInstall(ready.info.versionCode)
        return ApkInstaller.install(appContext, ready.file)
    }

    /** Whether there is a downloaded build sitting ready to be installed. */
    val hasDownload: Boolean get() = _state.value is State.Ready

    /**
     * Whether an automatic update may run right now.
     *
     * ⚠️ **Clearing the marker is done by comparison with the running build, not by "we got here".**
     * The evidence that an install landed is that this code is executing FROM it. A build that
     * failed to install leaves the marker above the running version, so the guard stays set and the
     * app stops trying — which is the whole point of it.
     */
    suspend fun clearPendingIfLanded(): Boolean {
        val pending = settings.pendingInstall()
        if (pending == 0) return true
        if (BuildConfig.VERSION_CODE >= pending) {
            settings.setPendingInstall(0)
            return true
        }
        return false
    }

    private fun explain(err: Throwable): String {
        val message = err.message.orEmpty()
        return when {
            "404" in message ->
                "GitHub answered 404. The releases are in a private repository, so this needs a " +
                    "token with repo scope — or the token it has cannot see that repository."
            "401" in message || "403" in message ->
                "GitHub refused the token. Check it has not expired and carries repo scope."
            message.isBlank() -> "Could not reach GitHub (${err.javaClass.simpleName})."
            else -> message
        }
    }
}
