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

    /** A major "this just in" emergency — its own channel + the most-urgent priority, distinct from breaking. */
    fun notifyEmergency(id: Int, title: String, body: String, route: String = "news") =
        post(NotificationChannels.EMERGENCY, id, "THIS JUST IN", title, body, route, NotificationCompat.PRIORITY_MAX)

    /** A proactive ORACLE foresight push — J.A.R.V.I.S. surfacing the single most important thing right now.
     *  ONE fixed id so a newer top insight replaces the last (latest-only, no tray pile-up). */
    fun notifyOracle(insight: dev.mascwa.pulse.core.telemetry.Insight) =
        post(
            NotificationChannels.ORACLE, NotifId.ORACLE,
            "ORACLE", insight.title, insight.detail, insight.actionRoute ?: "oracle",
            NotificationCompat.PRIORITY_HIGH,
        )

    /**
     * The WORLD PULSE — a quiet, always-latest live feed: one intimate cross-signal read of the world woven
     * with your day, updating IN PLACE (one fixed id, silent MIN-importance channel). Taps open the ORACLE HUD
     * where the full ranked read lives. Composed by [dev.mascwa.pulse.core.telemetry.Oracle.worldPulse].
     */
    fun notifyWorldPulse(pulse: dev.mascwa.pulse.core.telemetry.WorldPulse) {
        if (!canPost()) return
        val body = pulse.lines.joinToString("\n")
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ROUTE, "oracle")
        }
        val pi = PendingIntent.getActivity(
            context, "world_pulse".hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.WORLD_PULSE)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setColor(ContextCompat.getColor(context, R.color.cp_yellow))
            .setSubText("// WORLD PULSE")
            .setContentTitle(pulse.headline)
            .setContentText(pulse.lines.getOrNull(1) ?: pulse.headline)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle("⚡ WORLD PULSE").bigText(body),
            )
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        safeNotify(NotifId.WORLD_PULSE, notification)
    }

    fun notifyMarket(id: Int, title: String, body: String) =
        post(NotificationChannels.MARKETS, id, "MARKET", title, body, "markets", NotificationCompat.PRIORITY_DEFAULT)

    fun notifyWeather(id: Int, title: String, body: String) =
        post(NotificationChannels.WEATHER, id, "WEATHER", title, body, "weather", NotificationCompat.PRIORITY_DEFAULT)

    fun notifySpace(id: Int, title: String, body: String) =
        post(NotificationChannels.SPACE, id, "SKY", title, body, "space_wx", NotificationCompat.PRIORITY_DEFAULT)

    fun notifySafety(id: Int, title: String, body: String) =
        post(NotificationChannels.SAFETY, id, "SAFETY", title, body, "safety", NotificationCompat.PRIORITY_HIGH)

    fun notifyFlight(id: Int, title: String, body: String) =
        post(NotificationChannels.FLIGHT, id, "TACNET", title, body, "radar", NotificationCompat.PRIORITY_LOW)

    fun notifyUpdate(id: Int, title: String, body: String) =
        post(NotificationChannels.DIGEST, id, "UPDATE", title, body, "settings", NotificationCompat.PRIORITY_DEFAULT)

    fun notifyReminder(id: Int, title: String, body: String) =
        post(NotificationChannels.REMINDERS, id, "REMINDER", title, body, "home", NotificationCompat.PRIORITY_HIGH)

    /**
     * The BREAKING NEWS interrupt — a full-screen intent that force-opens the cinematic
     * [dev.mascwa.pulse.feature.breaking.BreakingNewsActivity] on a MAJOR detected event (a death, a
     * disaster). Over the lock screen it takes over instantly (the Hollywood moment); while the phone is in
     * active use the OS shows it as a max-priority heads-up to tap — the ceiling Android allows. CATEGORY_CALL
     * is the category that most reliably triggers the full-screen path. Opt-out gated by the caller.
     */
    fun notifyBreakingInterrupt(headline: String, query: String) {
        if (!canPost()) return
        val intent = Intent(context, dev.mascwa.pulse.feature.breaking.BreakingNewsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(dev.mascwa.pulse.feature.breaking.BreakingNewsActivity.EXTRA_HEADLINE, headline)
            putExtra(dev.mascwa.pulse.feature.breaking.BreakingNewsActivity.EXTRA_QUERY, query)
        }
        val pi = PendingIntent.getActivity(
            context, dev.mascwa.pulse.feature.breaking.BreakingNewsActivity.NOTIF_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.BREAKING_INTERRUPT)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setColor(ContextCompat.getColor(context, R.color.cp_yellow))
            .setSubText("// BREAKING")
            .setContentTitle("🔴 BREAKING NEWS")
            .setContentText(headline)
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle("🔴 BREAKING NEWS").bigText(headline))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        safeNotify(dev.mascwa.pulse.feature.breaking.BreakingNewsActivity.NOTIF_ID, notification)
    }

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

/**
 * The canonical notification ids. Each notification CATEGORY owns ONE fixed id, so a newer item silently
 * replaces the previous one in place — the tray always shows the single latest thing per category, never a
 * pile-up. Kept in one place so the "latest-only" contract is auditable.
 */
object NotifId {
    const val BREAKING = 1001
    const val EMERGENCY = 1002
    const val ORACLE = 2100
    const val WORLD_PULSE = 2200
    const val MARKET = 3100      // one rolled-up "biggest mover (+N more)" — was one-per-instrument
    const val WEATHER = 3001
    const val DIGEST = 4001
    const val SPACE_STORM = 5001
    const val SPACE_NEO = 5002
    const val SPACE_AURORA = 5003
    const val SAFETY = 6100      // one rolled-up "nearest severe incident (+N more)" — was one-per-incident
    const val FLIGHT = 7000      // one overhead aircraft, latest
    const val UPDATE = 7401
    const val FINDING = 7501
}
