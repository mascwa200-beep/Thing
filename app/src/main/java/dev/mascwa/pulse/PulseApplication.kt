package dev.mascwa.pulse

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import dev.mascwa.pulse.di.AppContainer
import dev.mascwa.pulse.notifications.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Owns the manual DI [AppContainer] and provides the
 * on-demand WorkManager configuration (the default initializer is disabled in
 * the manifest so we control WorkManager startup).
 */
class PulseApplication : Application(), Configuration.Provider, ComponentCallbacks2, ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Install the app-wide crash guard early so any uncaught throwable is logged to the
        // on-device crash console before the OS terminates the process.
        container.crashReporter.install()
        NotificationChannels.ensure(this)
        // Seed the APK-bundled reference docs into the knowledge library on first launch.
        appScope.launch { container.knowledgeSeeder.seedIfNeeded() }
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
