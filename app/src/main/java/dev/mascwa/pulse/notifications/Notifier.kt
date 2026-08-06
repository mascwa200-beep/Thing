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
 * The Star Trek LCARS alert-condition system: RED and YELLOW ALERT are Starfleet's own universally-
 * recognized severity words — no prior knowledge needed to know which one means "urgent." ROUTINE carries
 * no alert framing at all (its subtext is just the channel's plain name), so "alert" language stays
 * meaningful instead of diluted across every notification. See [Notifier.subtextFor]/[Notifier.colorFor].
 */
enum class AlertCondition { ROUTINE, YELLOW, RED }

/**
 * Posts minimal, LCARS-styled notifications: one plain-fact title, at most one plain supporting sentence,
 * and an alert-condition colour + subtext ([AlertCondition]) instead of decorative emoji or a "// TAG"
 * sub-header. (Custom RemoteViews were dropped because they can silently fail to render in the system UI.)
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
     * situation board (fixed id [NotifId.BRIEF], replaced in place forever). [alertNew] true routes the
     * post through the HIGH-importance [NotificationChannels.BRIEF_ALERT] twin so it buzzes exactly once
     * for a genuinely new urgent item; otherwise the silent [NotificationChannels.BRIEF] channel refreshes
     * the board without a sound. Rendering: DecoratedCustomViewStyle keeps the system template frame (the
     * measured, can't-silently-fail part) around LinearLayout-only LCARS RemoteViews; title/text are always
     * set too, so accessibility/Wear and any render fallback still carry the full content.
     */
    fun notifyBrief(brief: dev.mascwa.pulse.core.telemetry.UnifiedBrief, alertNew: Boolean) {
        if (!canPost()) return
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

    fun notifyBreaking(id: Int, title: String, body: String, route: String = "news") =
        post(
            NotificationChannels.BREAKING, id, "News Bulletin", title, body, route,
            NotificationCompat.PRIORITY_HIGH, AlertCondition.YELLOW,
        )

    /** A major "this just in" emergency — its own channel + the most-urgent priority, distinct from breaking. */
    fun notifyEmergency(id: Int, title: String, body: String, route: String = "news") =
        post(
            NotificationChannels.EMERGENCY, id, "Emergency Alert", title, body, route,
            NotificationCompat.PRIORITY_MAX, AlertCondition.RED,
        )

    /** A proactive ORACLE foresight push — the Computer surfacing the single most important thing right now.
     *  ONE fixed id so a newer top insight replaces the last (latest-only, no tray pile-up). */
    fun notifyOracle(insight: dev.mascwa.pulse.core.telemetry.Insight) =
        post(
            NotificationChannels.ORACLE, NotifId.ORACLE,
            "Computer Advisory", insight.title, insight.detail, insight.actionRoute ?: "oracle",
            NotificationCompat.PRIORITY_HIGH, AlertCondition.YELLOW,
        )

    /**
     * The SITUATION REPORT — a quiet, always-latest live feed: one intimate cross-signal read of the world
     * woven with your day, updating IN PLACE (one fixed id, silent MIN-importance channel). Taps open the
     * ORACLE HUD where the full ranked read lives. Composed by [dev.mascwa.pulse.core.telemetry.Oracle.worldPulse].
     * Collapsed to the headline + ONE supporting line — no stacked multi-line dump.
     */
    fun notifyWorldPulse(pulse: dev.mascwa.pulse.core.telemetry.WorldPulse) {
        if (!canPost()) return
        val supporting = pulse.lines.getOrNull(1) ?: pulse.headline
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
            .setColor(ContextCompat.getColor(context, colorFor(AlertCondition.ROUTINE)))
            .setSubText(subtextFor(AlertCondition.ROUTINE, "Situation Report"))
            .setContentTitle(pulse.headline)
            .setContentText(supporting)
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(pulse.headline).bigText(supporting))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        safeNotify(NotifId.WORLD_PULSE, notification)
    }

    fun notifyMarket(id: Int, title: String, body: String) =
        post(NotificationChannels.MARKETS, id, "Market Report", title, body, "markets", NotificationCompat.PRIORITY_DEFAULT)

    fun notifyWeather(id: Int, title: String, body: String) =
        post(NotificationChannels.WEATHER, id, "Weather Report", title, body, "weather", NotificationCompat.PRIORITY_DEFAULT)

    fun notifySpace(id: Int, title: String, body: String) =
        post(NotificationChannels.SPACE, id, "Space Weather", title, body, "space_wx", NotificationCompat.PRIORITY_DEFAULT)

    fun notifySafety(id: Int, title: String, body: String) =
        post(
            NotificationChannels.SAFETY, id, "Safety Alert", title, body, "safety",
            NotificationCompat.PRIORITY_HIGH, AlertCondition.YELLOW,
        )

    fun notifyFlight(id: Int, title: String, body: String) =
        post(NotificationChannels.FLIGHT, id, "Sensor Contact", title, body, "radar", NotificationCompat.PRIORITY_LOW)

    fun notifyUpdate(id: Int, title: String, body: String) =
        post(NotificationChannels.DIGEST, id, "Ship's Log", title, body, "settings", NotificationCompat.PRIORITY_DEFAULT)

    fun notifyReminder(id: Int, title: String, body: String) =
        post(NotificationChannels.REMINDERS, id, "Reminder", title, body, "home", NotificationCompat.PRIORITY_HIGH)

    /**
     * The BREAKING NEWS interrupt — a full-screen intent that force-opens the cinematic
     * [dev.mascwa.pulse.feature.breaking.BreakingNewsActivity] on a MAJOR detected event (a death, a
     * disaster). Over the lock screen it takes over instantly (the Hollywood moment); while the phone is in
     * active use the OS shows it as a max-priority heads-up to tap — the ceiling Android allows. CATEGORY_CALL
     * is the category that most reliably triggers the full-screen path. Opt-out gated by the caller. Always
     * RED ALERT — it only ever fires on a major event.
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

    /** The Computer has curated a finding and is ready to talk about it — opens the console. */
    fun notifyFinding(id: Int, title: String, body: String) =
        post(NotificationChannels.DIGEST, id, "Ship's Log", title, body, "jarvis", NotificationCompat.PRIORITY_DEFAULT)

    /** A device or network security notice — its own HIGH-importance channel, not buried in the daily log. */
    fun notifySecurity(id: Int, title: String, body: String) =
        post(
            NotificationChannels.SECURITY, id, "Security Alert", title, body, "settings",
            NotificationCompat.PRIORITY_HIGH, AlertCondition.YELLOW,
        )

    /** [moreCount] folds any remaining digest items into one short parenthetical — never a stacked list. */
    fun notifyDigest(id: Int, title: String, body: String, moreCount: Int = 0) {
        val text = if (moreCount > 0) "$body (+$moreCount more in your Ship's Log)" else body
        post(NotificationChannels.DIGEST, id, "Ship's Log", title, text, "home", NotificationCompat.PRIORITY_LOW)
    }

    /** [condition] is a property of the notification category, not a caller choice — see the per-function
     *  defaults above. [channelLabel] is the plain name shown in the subtext for [AlertCondition.ROUTINE];
     *  RED/YELLOW override it with the alert-condition word instead, since that's more useful in the moment. */
    private fun post(
        channel: String, id: Int, channelLabel: String, title: String, body: String, route: String,
        priority: Int, condition: AlertCondition = AlertCondition.ROUTINE,
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
            .setColor(ContextCompat.getColor(context, colorFor(condition)))
            .setSubText(subtextFor(condition, channelLabel))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(title).bigText(body))
            .setPriority(priority)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        safeNotify(id, notification)
    }

    /** RED/YELLOW ALERT are the universally-recognized Star Trek severity words — no legend needed to read
     *  them. ROUTINE carries no alert framing, just the channel's own plain name, so "alert" stays meaningful. */
    private fun subtextFor(condition: AlertCondition, channelLabel: String): String = when (condition) {
        AlertCondition.RED -> "RED ALERT"
        AlertCondition.YELLOW -> "YELLOW ALERT"
        AlertCondition.ROUTINE -> channelLabel.uppercase()
    }

    private fun colorFor(condition: AlertCondition): Int = when (condition) {
        AlertCondition.RED -> R.color.lcars_condition_red
        AlertCondition.YELLOW -> R.color.lcars_condition_yellow
        AlertCondition.ROUTINE -> R.color.lcars_condition_routine
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
    /** THE one notification — the whole board lives and refreshes under this single id. */
    const val BRIEF = 2300
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
