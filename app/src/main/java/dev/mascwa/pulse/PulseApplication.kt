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
        runCatching {
            dev.mascwa.pulse.ui.LcarsTransitions.animate = container.deviceProbe.budget().animations
        }
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
     * React to OS memory pressure so the process is trimmed instead of silently killed. We drop the
     * in-memory image cache always, and the disk caches when backgrounded. We deliberately do NOT
     * unload the on-device LLM here: it lives in a separate, OS-reclaimable process, and tearing it
     * down mid-conversation is exactly what caused "inference fault: process lost". If the OS reclaims
     * that process, the next message reloads it via ensureReady().
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching { container.imageLoader.memoryCache?.clear() }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            appScope.launch { runCatching { container.diskCache.clear() } }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { container.imageLoader.memoryCache?.clear() }
        appScope.launch { runCatching { container.diskCache.clear() } }
    }
}
