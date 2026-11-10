package dev.mascwa.pulse.feature.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.R
import dev.mascwa.pulse.notifications.NotifId

/**
 * The call's notification: Android's incoming-call card while it rings, the ongoing-call card after.
 *
 * ## One id, rebuilt from the registry every time
 *
 * [NotifId.CALL] carries whichever card fits the call the screen is about ([CallRegistry.focus]).
 * [update] is called on every change Telecom reports, so the card is never a step behind the call.
 *
 * - **Ringing** is posted here, with a full-screen intent to [InCallActivity]: Android shows the call
 *   screen over a locked or sleeping phone and a heads-up over one in use.
 * - **In progress** is posted by [OngoingCallService], a foreground service of type `phoneCall`,
 *   because Android only lets a call-style card stand without a full-screen intent when a foreground
 *   service owns it.
 *
 * ⚠️ **Android refuses a call-style card it does not like** — `NotificationManagerService` throws
 * "CallStyle notifications must be for a foreground service … or use a fullScreenIntent" — and a
 * refused card is no card at all. So every post here is wrapped, and a refusal posts a PLAIN ongoing
 * notification with the same buttons instead. A call is never left with nothing in the tray.
 *
 * ⚠️ **ANSWER opens the call screen directly**, as an activity intent, rather than a broadcast that
 * then starts the screen: Android 12 blocks an activity started from a receiver a notification
 * started. It carries its own action, so it is a different PendingIntent from the card's tap — which
 * must open the screen and never answer.
 */
internal object CallNotices {

    const val CHANNEL = "channel_calls"

    fun update(context: Context) {
        val focus = CallRegistry.focus(CallRegistry.calls.value)
        when {
            focus == null -> clear(context)
            focus.phase == CallDisplay.Phase.RINGING -> post(context, ringing(context, focus))
            else -> OngoingCallService.start(context)
        }
    }

    fun clear(context: Context) {
        OngoingCallService.stop(context)
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NotifId.CALL) }
    }

    /** Post [styled]; if Android refuses it, the plain card for the same call. */
    private fun post(context: Context, styled: Pair<Notification, Notification>) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context)
        if (runCatching { mgr.notify(NotifId.CALL, styled.first) }.isFailure) {
            runCatching { mgr.notify(NotifId.CALL, styled.second) }
        }
    }

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "The call in progress, and a call ringing. Silent: Android rings the phone itself."
                // Telecom plays the ringtone; a sound here would play a second one over it.
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    /** What a call is called on its card: the name, else the number, else why there is none. */
    fun title(call: CallRegistry.LiveCall): String =
        call.name ?: call.number.takeIf { it.isNotBlank() } ?: CallLogDigest.withheldLabel(call.presentation)

    private fun person(call: CallRegistry.LiveCall): Person =
        Person.Builder().setName(title(call)).setImportant(true).build()

    /** The call screen, opened by tapping the card. */
    fun screen(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            NotifId.CALL,
            Intent(context, InCallActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun answer(context: Context, id: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            NotifId.CALL,
            Intent(context, InCallActivity::class.java)
                .setAction(InCallActivity.ACTION_ANSWER)
                .putExtra(InCallActivity.EXTRA_CALL, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun action(context: Context, action: String, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            NotifId.CALL,
            Intent(context, CallActionReceiver::class.java).setAction(action).putExtra(CallActionReceiver.EXTRA_CALL, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** The ringing card, call-style first and plain second. */
    fun ringing(context: Context, call: CallRegistry.LiveCall): Pair<Notification, Notification> {
        val decline = action(context, CallActionReceiver.ACTION_DECLINE, call.id)
        val answer = answer(context, call.id)
        val styled = base(context, call)
            .setFullScreenIntent(screen(context), true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person(call), decline, answer))
            .build()
        val plain = base(context, call)
            .setFullScreenIntent(screen(context), true)
            .setContentText(if (call.video) "Incoming video call · answered as audio" else "Incoming call")
            .addAction(0, "Decline", decline)
            .addAction(0, "Answer", answer)
            .build()
        return styled to plain
    }

    /** What stands for a moment when the service is started for a call that has already ended. */
    fun ended(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle("Call ended")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setSilent(true)
            .build()

    /** The in-progress card, used by [OngoingCallService]. */
    fun ongoing(context: Context, call: CallRegistry.LiveCall, now: Long): Pair<Notification, Notification> {
        val hangUp = action(context, CallActionReceiver.ACTION_HANG_UP, call.id)
        fun timed(b: NotificationCompat.Builder): NotificationCompat.Builder =
            if (call.phase == CallDisplay.Phase.ACTIVE && call.connectMs > 0) {
                b.setUsesChronometer(true).setWhen(call.connectMs).setShowWhen(true)
            } else {
                b.setContentText(CallDisplay.label(call.phase, call.connectMs, now).lowercase().replaceFirstChar { it.uppercase() })
            }
        val styled = timed(base(context, call).setSilent(true).setOnlyAlertOnce(true))
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(call), hangUp))
            .build()
        val plain = timed(base(context, call).setSilent(true).setOnlyAlertOnce(true))
            .addAction(0, "Hang up", hangUp).build()
        return styled to plain
    }

    private fun base(context: Context, call: CallRegistry.LiveCall): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle(title(call))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            // ⚠️ Neither `setSilent` nor `setOnlyAlertOnce` here — both go on the ongoing card only
            // (below), where the once-a-second timer update must not re-alert. NotificationCompat
            // implements `setSilent` by stopping the card from alerting at all, so a ringing card
            // marked either way gets no heads-up over a phone in use; worse, `setOnlyAlertOnce` would
            // stop a SECOND incoming call (call-waiting replaces this same id) from re-firing its
            // full-screen intent. The channel is silent, so Telecom's ringtone is the only sound; a
            // re-posted ringing card cannot buzz.
            .setContentIntent(screen(context))
            .setColor(ContextCompat.getColor(context, R.color.nw_accent))
}
