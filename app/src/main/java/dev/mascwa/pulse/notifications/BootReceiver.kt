package dev.mascwa.pulse.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.mascwa.pulse.PulseApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-arms the periodic refresh after a reboot or app update, and — J.A.R.V.I.S.'s "survival instinct" —
 * brings the resident assistant back on its own so it survives a restart instead of staying down until
 * the app is next opened. This is resilience, not defiance: it only revives services the user has
 * explicitly left enabled, and "Stand down" / turning the toggles off still stops them for good.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val app = context.applicationContext as? PulseApplication ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = app.container.settingsRepository.current()
                if (settings.notifications.masterEnabled) {
                    app.container.notificationScheduler.schedule(
                        settings.refreshIntervalMinutes,
                        settings.refreshOnlyOnWifi,
                    )
                }
                // Revive the resident assistant if the user left it on (it gracefully falls back to a
                // non-mic foreground service if the OS blocks starting the mic from boot; the wake word
                // then re-arms next time the app is opened).
                if (settings.jarvis.residentService) {
                    runCatching {
                        dev.mascwa.pulse.jarvis.matrix.ActiveMatrixService.start(context, wakeWord = settings.jarvis.wakeWord)
                    }
                }
                if (settings.jarvis.vitalsTracking) {
                    runCatching { dev.mascwa.pulse.jarvis.vitals.VitalsTrackingService.start(context) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
