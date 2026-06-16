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

/**
 * Posts standard, always-reliable notifications with a Cyberpunk accent:
 * neon-yellow tint ([setColor]) and a "// TAG" sub-header. (Custom RemoteViews
 * were dropped because they can silently fail to render in the system UI.)
 */
class Notifier(private val context: Context) {

    init {
        NotificationChannels.ensure(context)
    }

    fun canPost(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

    fun notifyBreaking(id: Int, title: String, body: String, route: String = "news") =
        post(NotificationChannels.BREAKING, id, "BREAKING", title, body, route, NotificationCompat.PRIORITY_HIGH)

    fun notifyMarket(id: Int, title: String, body: String) =
        post(NotificationChannels.MARKETS, id, "MARKET", title, body, "markets", NotificationCompat.PRIORITY_DEFAULT)

    fun notifyWeather(id: Int, title: String, body: String) =
        post(NotificationChannels.WEATHER, id, "WEATHER", title, body, "weather", NotificationCompat.PRIORITY_DEFAULT)

    fun notifySpace(id: Int, title: String, body: String) =
        post(NotificationChannels.SPACE, id, "SKY", title, body, "grid", NotificationCompat.PRIORITY_DEFAULT)

    fun notifySafety(id: Int, title: String, body: String) =
        post(NotificationChannels.SAFETY, id, "SAFETY", title, body, "grid", NotificationCompat.PRIORITY_HIGH)

    fun notifyFlight(id: Int, title: String, body: String) =
        post(NotificationChannels.FLIGHT, id, "TACNET", title, body, "grid", NotificationCompat.PRIORITY_LOW)

    fun notifyDigest(id: Int, title: String, body: String, lines: List<String>) {
        val joined = lines.joinToString("\n").ifBlank { body }
        post(NotificationChannels.DIGEST, id, "DAILY DIGEST", title, joined, "home", NotificationCompat.PRIORITY_LOW)
    }

    private fun post(
        channel: String, id: Int, tag: String, title: String, body: String, route: String, priority: Int,
    ) {
        if (!canPost()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ROUTE, route)
        }
        val pi = PendingIntent.getActivity(
            context, route.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setColor(ContextCompat.getColor(context, R.color.cp_yellow))
            .setSubText("// $tag")
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(title).bigText(body))
            .setPriority(priority)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        safeNotify(id, notification)
    }

    private fun safeNotify(id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between check and post; ignore.
        }
    }
}
