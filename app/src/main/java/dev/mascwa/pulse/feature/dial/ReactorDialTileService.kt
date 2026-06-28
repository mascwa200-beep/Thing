package dev.mascwa.pulse.feature.dial

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.navigation.Routes

/**
 * A Quick Settings tile that opens the Reactor Dial from anywhere — a reliable, system-level touch entry
 * (unlike the launcher-dependent wallpaper double-tap). Add it from the Quick Settings edit panel, or via
 * Settings → Appearance → "Add Reactor Dial quick-tile".
 */
class ReactorDialTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, Routes.DIAL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
