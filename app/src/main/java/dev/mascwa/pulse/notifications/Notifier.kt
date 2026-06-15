package dev.mascwa.pulse.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.R

/** Builds and posts notifications across the four channels. */
class Notifier(private val context: Context) {

    init {
        NotificationChannels.ensure(context)
    }

    fun canPost(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

    fun notifyBreaking(id: Int, title: String, body: String, route: String = "news") =
        post(NotificationChannels.BREAKING, id, title, body, route, NotificationCompat.PRIORITY_HIGH)

    fun notifyMarket(id: Int, title: String, body: String) =
        post(NotificationChannels.MARKETS, id, title, body, "markets", NotificationCompat.PRIORITY_DEFAULT)

    fun notifyWeather(id: Int, title: String, body: String) =
        post(NotificationChannels.WEATHER, id, title, body, "weather", NotificationCompat.PRIORITY_DEFAULT)

    fun notifySpace(id: Int, title: String, body: String) =
        post(NotificationChannels.SPACE, id, title, body, "grid", NotificationCompat.PRIORITY_DEFAULT)

    fun notifyDigest(id: Int, title: String, body: String, lines: List<String>) {
        if (!canPost()) return
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.take(6).forEach { style.addLine(it) }
        val n = baseBuilder(NotificationChannels.DIGEST, title, body, "home")
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        safeNotify(id, n)
    }

    private fun post(channel: String, id: Int, title: String, body: String, route: String, priority: Int) {
        if (!canPost()) return
        val n = baseBuilder(channel, title, body, route)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priority)
            .build()
        safeNotify(id, n)
    }

    private fun baseBuilder(channel: String, title: String, body: String, route: String): NotificationCompat.Builder {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ROUTE, route)
        }
        val pi = PendingIntent.getActivity(
            context, route.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pi)
            .setAutoCancel(true)
    }

    private fun safeNotify(id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between check and post; ignore.
        }
    }
}
