package dev.mascwa.pulse

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import dev.mascwa.pulse.di.AppContainer
import dev.mascwa.pulse.notifications.NotificationChannels

/**
 * Application entry point. Owns the manual DI [AppContainer] and provides the
 * on-demand WorkManager configuration (the default initializer is disabled in
 * the manifest so we control WorkManager startup).
 */
class PulseApplication : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationChannels.ensure(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()
}
