package dev.mascwa.pulse.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.mascwa.pulse.PulseApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Re-arms the periodic refresh after a reboot or app update. */
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
            } finally {
                pending.finish()
            }
        }
    }
}
