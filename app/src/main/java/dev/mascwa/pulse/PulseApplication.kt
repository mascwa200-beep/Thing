package dev.mascwa.pulse

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import dev.mascwa.pulse.core.network.CleartextPolicy
import dev.mascwa.pulse.di.AppContainer
import dev.mascwa.pulse.notifications.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Application entry point. Owns the manual DI [AppContainer] and provides the
 * on-demand WorkManager configuration (the default initializer is disabled in
 * the manifest so we control WorkManager startup).
 */
class PulseApplication : Application(), Configuration.Provider, ComponentCallbacks2, ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    /**
     * The process-lifetime scope. Readable because a service sometimes has to finish a write that
     * outlives itself — a notification action that both persists a setting and calls `stopSelf()`
     * cannot use its own scope, since `onDestroy` cancels that as soon as its teardown completes.
     * It is already handed out to [AppContainer.observeVoicePreference] for a related reason.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Install the app-wide crash guard early so any uncaught throwable is logged to the
        // on-device crash console before the OS terminates the process.
        container.crashReporter.install()
        dev.mascwa.pulse.crash.Breadcrumbs.drop("app", "process started")
        NotificationChannels.ensure(this)
        // What this phone can afford, decided once. The probe is cheap and the answer is a property
        // of the hardware, so there is nothing to keep in sync afterwards.
        // ⚠️ `decorativeAnimation`, and I got this wrong once by writing the name from memory —
        // `animations` does not exist, and that one unresolved reference kept four commits from
        // compiling. Read the declaration.
        //
        // ⚠️ It is also a slightly WIDER reading than the field's own KDoc, which says "purely
        // decorative infinite animations". A route transition is finite and cheap per navigation —
        // but it holds two screens composed while it runs, which on the tier this gates is the part
        // that costs. There is no narrower field and inventing one for a single consumer would be
        // worse; saying so here beats letting the next reader assume the field means only glows.
        //
        // ⚠️ `durableBudget()`, not the live one, and that distinction is the whole reason it
        // exists. This is set ONCE for the process, so folding in a momentary thermal reading means
        // a phone that merely happened to be warm at launch has its animations off all day, long
        // after it cooled — while a phone that goes hot later is not covered either way. Hardware
        // alone is the right input for anything decided once and then kept.
        runCatching {
            dev.mascwa.pulse.ui.LcarsTransitions.animate =
                container.deviceProbe.durableBudget().decorativeAnimation
        }
        // Give back the disk the last self-update borrowed. ⚠️ Here rather than after the install,
        // because `PackageInstaller.commit()` usually kills this process on a successful update, so
        // any line written after it may never run. Launch is the point that is always reached. The
        // companion object form means no `OkHttpClient` is built on the startup path just to delete
        // files, and one call clears both this app's APK and the companion's — they share the
        // directory. On appScope because it touches the filesystem.
        appScope.launch { dev.mascwa.pulse.data.update.UpdateRepository.pruneCache(this@PulseApplication) }
        // And the photographs the camera left behind. Same reasoning, same launch-time answer: both
        // callers of `createCameraImageUri` read the file once and abandon it, and a cancelled
        // capture belongs to no call site at all. See `pruneCameraCaptures` for why an hour.
        appScope.launch { dev.mascwa.pulse.core.util.pruneCameraCaptures(this@PulseApplication) }
        // Seed the APK-bundled reference docs into the knowledge library on first launch.
        appScope.launch { container.knowledgeSeeder.seedIfNeeded() }
        // Start Trusted Network Mode's monitor (reactive: no-op until the user enables it in Settings).
        runCatching { container.trustedNetworkMonitor.begin() }
        // Keep the HTTPS-only egress guard in sync with the security setting + log blocked cleartext.
        CleartextPolicy.onBlocked = { host ->
            runCatching { container.usageRepository.log("network", "blocked cleartext egress to $host (HTTPS-only)") }
        }
        appScope.launch {
            container.settingsRepository.settings
                .map { it.security.httpsOnly }
                .distinctUntilChanged()
                .collect { CleartextPolicy.enabled = it }
        }
        // Keep the spoken voice in step with the setting. Does not create the TTS engine — most
        // launches never speak, and binding one costs about a second.
        container.observeVoicePreference(appScope)
        // Open in whatever alert condition the last published board was in.
        appScope.launch {
            runCatching { dev.mascwa.pulse.notifications.BriefEngine.restoreCondition(container) }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    /** Install the container's bounded Coil loader as the app-wide singleton (all AsyncImage use it). */
    override fun newImageLoader(): ImageLoader = container.imageLoader

    /**
     * React to OS memory pressure so the process is trimmed instead of silently killed.
     *
     * ⚠️ **The in-memory image cache and nothing else — this used to wipe the FEED DISK CACHE too,
     * and that was a defect in the direction this app can least afford.** `diskCache` is the small
     * JSON store every screen falls back to when the network is gone: it is what makes the offline
     * takeover, the "last received" banner and the whole freshness vocabulary mean anything. It is a
     * few megabytes of DISK, and deleting disk cannot relieve a shortage of RAM — so the trade was
     * to spend the expensive resources (network, battery, time, and on a metered connection money)
     * to free the cheap one, in response to a signal about a third resource entirely.
     *
     * ⚠️ And the guard read as though it only fired in an emergency, which on a modern platform it
     * does not. Of the seven levels, `TRIM_MEMORY_RUNNING_*`, `MODERATE` and `COMPLETE` are all
     * deprecated as of API 34 — checked against the real android.jar, not recalled — leaving
     * `UI_HIDDEN` (20) and `BACKGROUND` (40) as the only two that arrive. So `>= BACKGROUND` meant
     * "every time the app is backgrounded", and the app was throwing away its offline copy of the
     * world each time somebody pressed home.
     *
     * We deliberately do NOT unload the on-device LLM here either: it lives in a separate,
     * OS-reclaimable process, and tearing it down mid-conversation is exactly what caused
     * "inference fault: process lost". If the OS reclaims that process, the next message reloads it
     * via ensureReady().
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching { container.imageLoader.memoryCache?.clear() }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { container.imageLoader.memoryCache?.clear() }
    }
}
