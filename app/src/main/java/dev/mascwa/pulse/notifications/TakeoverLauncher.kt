package dev.mascwa.pulse.notifications

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.mascwa.pulse.feature.breaking.BreakingNewsActivity

/**
 * The BREAKING NEWS takeover, launch-first (owner's directive: "it has to display the whole screen of the
 * event, no matter what is happening on the phone — function like an actual takeover, not a notification"):
 *
 * 1. When the user has granted "display over other apps" (SYSTEM_ALERT_WINDOW), an app is EXEMPT from
 *    Android's background-activity-launch restrictions — so [BreakingNewsActivity] is started DIRECTLY and
 *    genuinely takes over mid-use, no tap needed. (The same exemption this app's former game overlay used
 *    to deep-link from over other apps — proven on this device.)
 * 2. Without that grant, fall back to the full-screen-intent notification: instant takeover over the lock
 *    screen; a max-priority heads-up while the phone is in active use — the OS ceiling without the grant.
 *
 * Settings → Notifications offers the one-tap grant. A failed direct start (OEM quirk) falls through to
 * the notification path, so a major event is never silently dropped.
 */
object TakeoverLauncher {

    fun launch(context: Context, notifier: Notifier, headline: String, query: String) {
        if (Settings.canDrawOverlays(context)) {
            val intent = Intent(context, BreakingNewsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(BreakingNewsActivity.EXTRA_HEADLINE, headline)
                putExtra(BreakingNewsActivity.EXTRA_QUERY, query)
            }
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        notifier.notifyBreakingInterrupt(headline, query)
    }
}
