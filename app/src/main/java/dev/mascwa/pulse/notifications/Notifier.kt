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

    fun notifyUpdate(id: Int, title: String, body: String) =
        post(NotificationChannels.DIGEST, id, "UPDATE", title, body, "settings", NotificationCompat.PRIORITY_DEFAULT)

    fun notifyReminder(id: Int, title: String, body: String) =
        post(NotificationChannels.REMINDERS, id, "REMINDER", title, body, "home", NotificationCompat.PRIORITY_HIGH)

    /** Life-sim survival check-in (a decaying need ran low) — opens the game to tend it. */
    fun notifySurvival(id: Int, title: String, body: String, urgent: Boolean) =
        post(
            NotificationChannels.REMINDERS, id, "SURVIVAL", title, body, "tacnet",
            if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT,
        )

    /** Life-sim agenda check-in (a real appointment is imminent) — opens the game's agenda. */
    fun notifyAgenda(id: Int, title: String, body: String) =
        post(NotificationChannels.REMINDERS, id, "AGENDA", title, body, "tacnet", NotificationCompat.PRIORITY_HIGH)

    /** A rotating field-survival tip — frequent and QUIET (low-priority DIGEST channel, fixed id so each
     *  new tip silently replaces the last); opens the Survival guides. */
    fun notifyTip(id: Int, title: String, body: String) =
        post(NotificationChannels.DIGEST, id, "SURVIVAL TIP", title, body, "survival", NotificationCompat.PRIORITY_LOW)

    /** J.A.R.V.I.S. has curated a finding and is ready to talk about it — opens the console. */
    fun notifyFinding(id: Int, title: String, body: String) =
        post(NotificationChannels.DIGEST, id, "J.A.R.V.I.S.", title, body, "jarvis", NotificationCompat.PRIORITY_DEFAULT)

    /** Trusted Network Mode status (e.g. needs Device-Owner provisioning) — opens Settings. */
    fun notifySecurity(id: Int, title: String, body: String) =
        post(NotificationChannels.DIGEST, id, "SECURITY", title, body, "settings", NotificationCompat.PRIORITY_DEFAULT)

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
