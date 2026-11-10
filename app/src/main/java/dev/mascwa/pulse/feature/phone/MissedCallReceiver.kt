package dev.mascwa.pulse.feature.phone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.R
import dev.mascwa.pulse.data.phone.ContactsRepository
import dev.mascwa.pulse.notifications.NotifId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Posts the missed-call notice while LCARS is the phone app, in Android's place.
 *
 * ## Why it ships DISABLED and is enabled only by [reconcile]
 *
 * Telecom stops posting its own missed-call notification the moment the default phone app has a
 * receiver for `SHOW_MISSED_CALLS_NOTIFICATION` — and it sends that broadcast with `READ_PHONE_STATE`
 * required of the receiver. So a receiver that is present but cannot act (permission revoked,
 * notifications off, LCARS no longer the phone app) is the worst state there is: Android has stepped
 * aside and nobody posts anything. A disabled receiver is invisible to Telecom's query, so **whenever
 * [MissedCalls.claim] is not LCARS, Android posts its own** — structurally, by the component being off.
 *
 * ⚠️ **It records nothing and sends nothing** (PhoneRecordsNothingTest holds the phone packages to
 * that): it reads the caller's number from the broadcast, looks the name up on-device, and posts.
 */
class MissedCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SHOW) return
        val count = intent.getIntExtra(EXTRA_COUNT, 0)
        // Telecom sends 0 once the missed calls have been seen — clear whatever is up.
        if (count <= 0) {
            cancel(context)
            return
        }
        val number = intent.getStringExtra(EXTRA_NUMBER)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val name = number?.takeIf { it.isNotBlank() }?.let {
                    runCatching { ContactsRepository(context).nameFor(it) }.getOrNull()
                }
                post(context, count, name, number)
            } finally {
                pending.finish()
            }
        }
    }

    private fun post(context: Context, count: Int, name: String?, number: String?) {
        val text = MissedCalls.text(count, name, number) ?: return
        val mgr = NotificationManagerCompat.from(context)
        ensureChannel(context)
        val b = NotificationCompat.Builder(context, MissedCalls.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setColor(ContextCompat.getColor(context, R.color.nw_accent))
            .setAutoCancel(true)
            .setContentIntent(open(context, PhoneActivity.ACTION_RECENTS, null))
        if (text.callBack && number != null) {
            b.addAction(0, "Call back", open(context, PhoneActivity.ACTION_CALL_BACK, number))
            b.addAction(0, "Message", message(context, number))
        }
        runCatching { mgr.notify(NotifId.MISSED_CALLS, b.build()) }
    }

    /** An activity PendingIntent into the phone app. Distinct per [action] — `filterEquals` ignores the extra. */
    private fun open(context: Context, action: String, number: String?): PendingIntent {
        val intent = Intent(context, PhoneActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (number != null) intent.putExtra(PhoneActivity.EXTRA_NUMBER, number)
        return PendingIntent.getActivity(
            context, NotifId.MISSED_CALLS, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Hand a text to the SMS app — distinct by its `smsto:` data, so two numbers never collide. */
    private fun message(context: Context, number: String): PendingIntent =
        PendingIntent.getActivity(
            context, NotifId.MISSED_CALLS,
            Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        // Telecom's broadcast and its two public extras. @SystemApi on the SDK, but stable strings.
        const val ACTION_SHOW = "android.telecom.action.SHOW_MISSED_CALLS_NOTIFICATION"
        private const val EXTRA_COUNT = "android.telecom.extra.NOTIFICATION_COUNT"
        private const val EXTRA_NUMBER = "android.telecom.extra.NOTIFICATION_PHONE_NUMBER"

        private fun ensureChannel(context: Context) {
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(MissedCalls.CHANNEL) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(MissedCalls.CHANNEL, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Calls you missed, while LCARS is the phone app."
                },
            )
        }

        /** Take down the missed-call notice. Called when the calls are seen (RECENTS opened). */
        fun cancel(context: Context) {
            runCatching { NotificationManagerCompat.from(context).cancel(NotifId.MISSED_CALLS) }
        }

        /**
         * Turn the receiver on exactly when [MissedCalls.claim] says LCARS should post, off otherwise —
         * the one mechanism that keeps "Android posts them whenever LCARS cannot" true. Run on
         * app start, from the switch, and whenever Settings returns.
         *
         * ⚠️ The stand-down is read from [PhoneStore], not passed in, so a call-screen crash-loop
         * stand-down also stops missed-call posting — until the switch's `turnOn` clears the guard.
         */
        fun reconcile(context: Context) {
            val claim = MissedCalls.claim(
                isPhoneApp = isPhoneApp(context),
                readPhoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                    PackageManager.PERMISSION_GRANTED,
                notificationsAllowed = runCatching {
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
                }.getOrDefault(false),
                stoodDown = runCatching { PhoneStore(context).standDownAtMs() != null }.getOrDefault(false),
            )
            enable(context, claim == MissedCalls.Claim.LCARS)
        }

        private fun isPhoneApp(context: Context): Boolean = runCatching {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        }.getOrDefault(false)

        private fun enable(context: Context, on: Boolean) {
            val state = if (on) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            runCatching {
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(context, MissedCallReceiver::class.java),
                    state,
                    PackageManager.DONT_KILL_APP,
                )
            }
            // If LCARS no longer posts, drop any notice it left up.
            if (!on) cancel(context)
        }
    }
}
