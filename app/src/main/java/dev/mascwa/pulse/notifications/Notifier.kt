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
            // ⚠️ Derived, never re-typed — see [NotifId]. The hand-written version of this set was
            // missing the Sensorium (7401) and the emergency watch (7402), so this sweep cancelled the
            // ongoing notification of two running foreground services on the first board post of every
            // process, taking the scanner's Stop button with it.
            nm.activeNotifications
                .filter { it.id !in NotifId.PERSISTENT }
                .forEach { nm.cancel(it.tag, it.id) }
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
 * The canonical notification ids — **every one of them, in one place**.
 *
 * The whole board lives and refreshes under [BRIEF]; the takeover's transient id self-cancels on open;
 * the rest are the mandatory ongoing notifications of the foreground services.
 *
 * ⚠️ **This exists because scattering them broke twice at once.** Each service used to declare its own
 * `private const val NOTIF_ID`, so nothing could see them together, and two things followed:
 *
 * 1. **A collision.** `SensoriumService` and `BreakingOverlayService` were both 7401. They are
 *    foreground services that run at the same time — ambient sensing is adaptive-24/7 and a breaking
 *    story can arrive at any moment — and an id with no tag is the whole identity of a notification.
 *    So the overlay's notification REPLACED the scanner's (taking its status line and its Stop button
 *    with it), the scanner's three-minutely refresh overwrote the overlay's back again, and whichever
 *    stopped first removed the other's notification, leaving a foreground service holding the camera
 *    and microphone with nothing on screen to say so or to stop it.
 * 2. **A keep-list that could not be kept.** [Notifier.sweepLegacyOnce] cancels every notification
 *    outside a hand-typed set of magic numbers. It was written before the Sensorium and the emergency
 *    watch existed and was never extended, so the first board post of every process cancelled both of
 *    their ongoing notifications. Same shape as the mirror-map gap already recorded in this repo: two
 *    independent statements of one fact, only one of which gets updated.
 *
 * The sweep's keep set is now [PERSISTENT], derived from these constants, and `NotifIdTest` fails the
 * build if any two ids collide or if a foreground id is missing from it. Adding a service means adding
 * one constant here; forgetting to is a red build, not a silent bug on someone's phone.
 */
object NotifId {
    /** THE one notification — the situation board, replaced in place forever. */
    const val BRIEF = 2300

    /** The breaking-news takeover's full-screen-intent fallback; self-cancels when opened. */
    const val TAKEOVER = 1003

    // ---- foreground-service ongoing notifications (mandatory while their service runs) ----
    const val FGS_ACTIVE_MATRIX = 7301
    const val FGS_VITALS = 7311
    const val FGS_REMOTE_LINK = 7321
    const val FGS_SENSORIUM = 7401
    const val FGS_EMERGENCY_WATCH = 7402
    const val FGS_BREAKING_OVERLAY = 7403
    const val FGS_INTERROGATOR = 7411
    const val FGS_RADIO = 4201

    /** Every id a sweep must never touch: the board, the takeover, and each service's ongoing. */
    val PERSISTENT: Set<Int> = setOf(
        BRIEF, TAKEOVER,
        FGS_ACTIVE_MATRIX, FGS_VITALS, FGS_REMOTE_LINK,
        FGS_SENSORIUM, FGS_EMERGENCY_WATCH, FGS_BREAKING_OVERLAY, FGS_RADIO,
        FGS_INTERROGATOR,
    )

    /**
     * ⚠️ **These double as PendingIntent request codes, and that is not decoration.**
     *
     * A PendingIntent's identity is its request code plus `Intent.filterEquals`, which compares
     * action, categories, component, data, identifier, package and type — and **not the extras**
     * (read out of the platform bytecode). So every `PendingIntent.getActivity(ctx, 0, Intent(ctx,
     * MainActivity::class.java), …)` in the app is one and the same PendingIntent, and
     * `FLAG_UPDATE_CURRENT` makes the last one built win the extras outright.
     *
     * That is harmless while they all mean "just open the app", which is why most of them still use
     * 0. The moment one carries something — a route, an id, anything the tap depends on — sharing a
     * request code silently deletes it: `RadioService` shipped exactly that, and its "open the radio"
     * tap landed on Home because the Sensorium rebuilds its extras-free copy every three minutes.
     *
     * **So: any PendingIntent whose extras matter must use its owner's id from here as the request
     * code.** They are unique by test, which is the property being borrowed.
     */
    val FOREGROUND: Set<Int> = setOf(
        FGS_ACTIVE_MATRIX, FGS_VITALS, FGS_REMOTE_LINK,
        FGS_SENSORIUM, FGS_EMERGENCY_WATCH, FGS_BREAKING_OVERLAY, FGS_RADIO,
        FGS_INTERROGATOR,
    )
}
