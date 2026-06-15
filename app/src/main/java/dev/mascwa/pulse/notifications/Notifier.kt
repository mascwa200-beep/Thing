package dev.mascwa.pulse.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.R

/**
 * Builds and posts notifications with a Cyberpunk 2077–style custom layout
 * (neon-yellow accent bar, mono/display fonts, "// TAG" header) on dark.
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

    fun notifyDigest(id: Int, title: String, body: String, lines: List<String>) {
        val joined = lines.joinToString("\n").ifBlank { body }
        post(NotificationChannels.DIGEST, id, "DAILY DIGEST", title, joined, "home", NotificationCompat.PRIORITY_LOW)
    }

    private fun post(
        channel: String, id: Int, tag: String, title: String, body: String, route: String, priority: Int,
    ) {
        if (!canPost()) return
        val n = baseBuilder(channel, tag, title, body, route)
            .setPriority(priority)
            .build()
        safeNotify(id, n)
    }

    private fun remoteView(layout: Int, tag: String, title: String, body: String): RemoteViews =
        RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.notif_tag, "// $tag")
            setTextViewText(R.id.notif_title, title)
            setTextViewText(R.id.notif_body, body)
        }

    private fun baseBuilder(
        channel: String, tag: String, title: String, body: String, route: String,
    ): NotificationCompat.Builder {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ROUTE, route)
        }
        val pi = PendingIntent.getActivity(
            context, route.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val collapsed = remoteView(R.layout.notif_cyberpunk, tag, title, body)
        val expanded = remoteView(R.layout.notif_cyberpunk_big, tag, title, body)
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setColor(ContextCompat.getColor(context, R.color.cp_yellow))
            .setColorized(true)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            // Plain-text fallback for surfaces that ignore custom views.
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
