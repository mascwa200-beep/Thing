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

    /** A proactive ORACLE foresight push — J.A.R.V.I.S. surfacing the single most important thing right now. */
    fun notifyOracle(insight: dev.mascwa.pulse.core.telemetry.Insight) =
        post(
            NotificationChannels.ORACLE, 2000 + (insight.id.hashCode() and 0xffff),
            "ORACLE", insight.title, insight.detail, insight.actionRoute ?: "jarvis",
            NotificationCompat.PRIORITY_HIGH,
        )

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

    /** Life-sim survival check-in (a decaying need ran low) — opens the game to tend it. */
    fun notifySurvival(id: Int, title: String, body: String, urgent: Boolean) =
        post(
            NotificationChannels.REMINDERS, id, "SURVIVAL", title, body, "tacnet",
            if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT,
        )

    /** Life-sim agenda check-in (a real appointment is imminent) — opens the game's agenda. */
    fun notifyAgenda(id: Int, title: String, body: String) =
        post(NotificationChannels.REMINDERS, id, "AGENDA", title, body, "tacnet", NotificationCompat.PRIORITY_HIGH)

    /**
     * The AGGRESSIVE self-care check-in: a full-screen alert (over the lock screen) that can't be dismissed
     * until answered. Fires a full-screen intent to [CheckinActivity] on the high-importance REMINDERS
     * channel; ongoing so it persists until the activity clears it on answer.
     */
    fun notifyCheckin(habit: dev.mascwa.pulse.core.telemetry.Habit) {
        if (!canPost()) return
        val intent = Intent(context, dev.mascwa.pulse.feature.checkin.CheckinActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(dev.mascwa.pulse.feature.checkin.CheckinActivity.EXTRA_ACTIVITY, habit.activity.name)
        }
        val pi = PendingIntent.getActivity(
            context, dev.mascwa.pulse.feature.checkin.CheckinActivity.NOTIF_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setColor(ContextCompat.getColor(context, R.color.cp_yellow))
            .setSubText("// CHECK-IN")
            .setContentTitle("Check-in required")
            .setContentText(habit.question)
            .setStyle(
                NotificationCompat.BigTextStyle().setBigContentTitle("Check-in required")
                    .bigText("${habit.question}\n\nOpen to answer — it won't clear until you do."),
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
        safeNotify(dev.mascwa.pulse.feature.checkin.CheckinActivity.NOTIF_ID, notification)
    }

    /**
     * The MAXIMUM phone penalty: a full-screen lock-screen gate (over the lock screen) that pins the phone
     * until you tend the critically-neglected [needNames] (NeedKind names). Fires a full-screen intent to
     * [dev.mascwa.pulse.feature.checkin.PenaltyGateActivity] on the high-importance REMINDERS channel; ongoing
     * until the activity clears it on release. Opt-in (kiosk tier) + Device-Owner gated by the caller.
     */
    fun notifyPenaltyGate(needNames: List<String>) {
        if (!canPost() || needNames.isEmpty()) return
        val intent = Intent(context, dev.mascwa.pulse.feature.checkin.PenaltyGateActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(dev.mascwa.pulse.feature.checkin.PenaltyGateActivity.EXTRA_NEEDS, needNames.toTypedArray())
        }
        val pi = PendingIntent.getActivity(
            context, dev.mascwa.pulse.feature.checkin.PenaltyGateActivity.NOTIF_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setColor(ContextCompat.getColor(context, R.color.cp_yellow))
            .setSubText("// PHONE LOCKED")
            .setContentTitle("Phone locked — take care of yourself")
            .setContentText("Tend the need to get back in.")
            .setStyle(
                NotificationCompat.BigTextStyle().setBigContentTitle("Phone locked — take care of yourself")
                    .bigText(
                        "Locked until you tend: ${needNames.joinToString(", ") { it.lowercase() }}.\n" +
                            "Emergency calls still work, and the lock releases on its own after a while.",
                    ),
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
        safeNotify(dev.mascwa.pulse.feature.checkin.PenaltyGateActivity.NOTIF_ID, notification)
    }

    /**
     * A smart self-care check-in: a full-screen takeover (over the lock screen) prompting the one thing that
     * needs doing most now (brush / floss / water / …). Fires a full-screen intent to [CareCheckinActivity] on
     * the high-importance REMINDERS channel; the activity clears it on answer. Answerable (DONE / NOT YET).
     */
    fun notifyCareCheckin(checkin: dev.mascwa.pulse.core.telemetry.CareCheckin) {
        if (!canPost()) return
        val intent = Intent(context, dev.mascwa.pulse.feature.checkin.CareCheckinActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(dev.mascwa.pulse.feature.checkin.CareCheckinActivity.EXTRA_CHECKIN, checkin.name)
        }
        val pi = PendingIntent.getActivity(
            context, dev.mascwa.pulse.feature.checkin.CareCheckinActivity.NOTIF_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setColor(ContextCompat.getColor(context, R.color.cp_yellow))
            .setSubText("// CHECK-IN")
            .setContentTitle(checkin.label)
            .setContentText(checkin.prompt)
            .setStyle(
                NotificationCompat.BigTextStyle().setBigContentTitle(checkin.label)
                    .bigText("${checkin.prompt}\n\nOpen to confirm when it's done, or tap NOT YET."),
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        safeNotify(dev.mascwa.pulse.feature.checkin.CareCheckinActivity.NOTIF_ID, notification)
    }

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

    /** Quiet confirmation that the camera/mic caught you tending a need in real life and the game credited it
     *  automatically — one fixed id per need so it replaces, on the low-priority DIGEST channel. */
    fun notifySensedCare(need: dev.mascwa.pulse.core.telemetry.NeedKind) =
        post(
            NotificationChannels.DIGEST, 7790 + need.ordinal, "SENSED",
            "👁 Caught you taking care of yourself",
            "The camera/mic sensed it — ${need.label} restored.",
            "tacnet", NotificationCompat.PRIORITY_LOW,
        )

    /** A rotating field-survival tip — frequent and QUIET (low-priority DIGEST channel, fixed id so each
     *  new tip silently replaces the last). Taps open straight to the SPECIFIC guide that teaches the tip's
     *  skill ([guideId], e.g. a knot tip → the knots guide); falls back to the guides list when unknown. */
    fun notifyTip(id: Int, title: String, body: String, guideId: String? = null) {
        val route = if (guideId.isNullOrBlank()) "survival" else "survival?guide=$guideId"
        post(NotificationChannels.DIGEST, id, "SURVIVAL TIP", title, body, route, NotificationCompat.PRIORITY_LOW)
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
