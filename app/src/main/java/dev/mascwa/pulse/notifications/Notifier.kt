package dev.mascwa.pulse.notifications

import android.Manifest
import android.app.NotificationManager
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
 * The Star Trek LCARS alert-condition system: RED and YELLOW ALERT are Starfleet's own universally-
 * recognized severity words — no prior knowledge needed to know which one means "urgent." ROUTINE carries
 * no alert framing at all, so "alert" language stays meaningful instead of diluted across every post.
 */
enum class AlertCondition { ROUTINE, YELLOW, RED }

/**
 * The app posts exactly ONE notification — the LCARS situation board ([notifyBrief], fixed id
 * [NotifId.BRIEF], replaced in place forever) — plus the full-screen breaking-news TAKEOVER
 * ([notifyBreakingInterrupt], the fallback path of [TakeoverLauncher] when direct launch isn't granted).
 * Every legacy per-category notification is gone; the mandatory foreground-service notifications
 * (voice matrix / vitals / radio) live with their services, restyled to the same LCARS convention.
 */
class Notifier(private val context: Context) {

    init {
        NotificationChannels.ensure(context)
    }

    fun canPost(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * THE one notification — posts a [dev.mascwa.pulse.core.telemetry.UnifiedBrief] as the single LCARS
     * situation board. [alertNew] true routes the post through the HIGH-importance
     * [NotificationChannels.BRIEF_ALERT] twin so it buzzes exactly once for a genuinely new urgent item;
     * otherwise the silent [NotificationChannels.BRIEF] channel refreshes the board without a sound.
     * Rendering: DecoratedCustomViewStyle keeps the system template frame (the measured, can't-silently-fail
     * part) around LinearLayout-only LCARS RemoteViews; title/text are always set too, so
     * accessibility/Wear and any render fallback still carry the full content.
     */
    fun notifyBrief(brief: dev.mascwa.pulse.core.telemetry.UnifiedBrief, alertNew: Boolean) {
        if (!canPost()) return
        sweepLegacyOnce()
        val condition = when (brief.urgency) {
            dev.mascwa.pulse.core.telemetry.BriefUrgency.RED -> AlertCondition.RED
            dev.mascwa.pulse.core.telemetry.BriefUrgency.YELLOW -> AlertCondition.YELLOW
            dev.mascwa.pulse.core.telemetry.BriefUrgency.ROUTINE -> AlertCondition.ROUTINE
        }
        val alerting = alertNew && condition != AlertCondition.ROUTINE
        val channel = if (alerting) NotificationChannels.BRIEF_ALERT else NotificationChannels.BRIEF
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ROUTE, "home")
        }
        val pi = PendingIntent.getActivity(
            context, "brief".hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val fallbackText = brief.rows.joinToString("\n") { it.text }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setColor(ContextCompat.getColor(context, colorFor(condition)))
            .setSubText(subtextFor(condition, "Situation Board"))
            .setContentTitle(brief.headline)
            .setContentText(brief.rows.getOrNull(1)?.text ?: brief.tempLabel ?: brief.headline)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(LcarsNotificationRenderer.collapsed(context, brief))
            .setCustomBigContentView(LcarsNotificationRenderer.expanded(context, brief))
            .setTicker(fallbackText.take(200))
            .setPriority(if (alerting) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_MIN)
            .setOnlyAlertOnce(!alerting)
            .setContentIntent(pi)
            // The board is the resident situation display — tapping it opens the app WITHOUT dismissing it
            // (a swipe still dismisses; the next refresh brings it back).
            .setAutoCancel(false)
            .build()
        safeNotify(NotifId.BRIEF, notification)
    }

    /**
     * A one-line urgent post through the same single notification id, for callers that can't gather a full
     * signal snapshot (a network callback, a vitals anomaly). The next routine board refresh replaces it
     * with the full picture. Callers keep their own once-latches; this always alerts.
     */
    fun notifyUrgentLine(headline: String, detail: String, key: String, red: Boolean = false) {
        val brief = dev.mascwa.pulse.core.telemetry.UnifiedBrief(
            headline = headline,
            tempLabel = null,
            rows = listOf(
                dev.mascwa.pulse.core.telemetry.BriefRow(
                    dev.mascwa.pulse.core.telemetry.BriefRowKind.ALERT,
                    detail.ifBlank { headline },
                ),
            ),
            urgency = if (red) {
                dev.mascwa.pulse.core.telemetry.BriefUrgency.RED
            } else {
                dev.mascwa.pulse.core.telemetry.BriefUrgency.YELLOW
            },
            urgencyKey = key,
        )
        notifyBrief(brief, alertNew = true)
    }

    fun cancelBrief() {
        runCatching { NotificationManagerCompat.from(context).cancel(NotifId.BRIEF) }
    }

    /**
     * The BREAKING NEWS interrupt — the full-screen-intent FALLBACK of [TakeoverLauncher] (used when
     * "display over other apps" isn't granted, so a direct launch would be suppressed). Over the lock
     * screen it takes over instantly; while the phone is in active use the OS shows it as a max-priority
     * heads-up to tap — the ceiling Android allows without the overlay grant. CATEGORY_CALL is the
     * category that most reliably triggers the full-screen path. Always RED ALERT — major events only.
     */
    fun notifyBreakingInterrupt(headline: String, query: String) {
        if (!canPost()) return
        val intent = Intent(context, dev.mascwa.pulse.feature.breaking.BreakingNewsActivity::class.java).apply {
            // ⚠️ FLAG_ACTIVITY_CLEAR_TASK removed — it was here too, on the fallback path. It wipes the
            // back stack, so tapping this notification and then pressing Back left you nowhere near
            // what you had been reading. Same defect the overlay rewrite fixed on the direct-launch
            // path; fixing only one of the two would have left it reachable by the commoner route,
            // since this is what runs whenever "display over other apps" is not granted.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(dev.mascwa.pulse.feature.breaking.BreakingNewsActivity.EXTRA_HEADLINE, headline)
            putExtra(dev.mascwa.pulse.feature.breaking.BreakingNewsActivity.EXTRA_QUERY, query)
        }
        val pi = PendingIntent.getActivity(
            context, dev.mascwa.pulse.feature.breaking.BreakingNewsActivity.NOTIF_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = "Breaking news"
        val notification = NotificationCompat.Builder(context, NotificationChannels.BREAKING_INTERRUPT)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setColor(ContextCompat.getColor(context, colorFor(AlertCondition.RED)))
            .setSubText(subtextFor(AlertCondition.RED, "Emergency Broadcast"))
            .setContentTitle(title)
            .setContentText(headline)
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(title).bigText(headline))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        safeNotify(dev.mascwa.pulse.feature.breaking.BreakingNewsActivity.NOTIF_ID, notification)
    }

    /** RED/YELLOW ALERT are the universally-recognized Star Trek severity words — no legend needed to read
     *  them. ROUTINE carries no alert framing, just the surface's own plain name. */
    internal fun subtextFor(condition: AlertCondition, label: String): String = when (condition) {
        AlertCondition.RED -> "RED ALERT"
        AlertCondition.YELLOW -> "YELLOW ALERT"
        AlertCondition.ROUTINE -> label.uppercase()
    }

    internal fun colorFor(condition: AlertCondition): Int = when (condition) {
        AlertCondition.RED -> R.color.lcars_condition_red
        AlertCondition.YELLOW -> R.color.lcars_condition_yellow
        AlertCondition.ROUTINE -> R.color.lcars_condition_routine
    }

    /**
     * One-time per process: clear every legacy/stale notification the retired multi-channel system may
     * have left in the tray after the update (including randomly-id'd old reminders), keeping only the
     * board, the takeover, and the four mandatory foreground-service notifications.
     */
    private fun sweepLegacyOnce() {
        if (swept) return
        swept = true
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val keep = setOf(
                NotifId.BRIEF,
                dev.mascwa.pulse.feature.breaking.BreakingNewsActivity.NOTIF_ID,
                7301, // ActiveMatrixService ongoing
                7311, // VitalsTrackingService ongoing
                7321, // RemoteLinkService ongoing — also carries the live pairing code
                4201, // RadioService playback
            )
            nm.activeNotifications.filter { it.id !in keep }.forEach { nm.cancel(it.tag, it.id) }
        }
    }

    private fun safeNotify(id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between check and post; ignore.
        }
    }

    private companion object {
        @Volatile var swept = false
    }
}

/**
 * The canonical notification ids. The whole board lives and refreshes under ONE fixed id; the takeover's
 * transient id lives with its Activity (BreakingNewsActivity.NOTIF_ID = 1003) and self-cancels on open.
 */
object NotifId {
    const val BRIEF = 2300
}
