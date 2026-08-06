package dev.mascwa.pulse.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

/**
 * The one-notification channel set: the silent board, its alerting twin, and the breaking-news takeover's
 * full-screen-intent fallback. [ensure] also DELETES every channel id the retired multi-channel system
 * created, so the OS notification-settings page shows exactly these three — and new ids were chosen for
 * the board deliberately, so no stale per-channel user override resurrects onto them.
 */
object NotificationChannels {
    /** THE one notification (silent in-place refreshes). */
    const val BRIEF = "channel_brief"

    /** The same one notification re-posted when a NEW urgent item enters — this channel carries the buzz. */
    const val BRIEF_ALERT = "channel_brief_alert"

    /** The full-screen breaking-news takeover's notification fallback (TakeoverLauncher). */
    const val BREAKING_INTERRUPT = "channel_breaking_interrupt"

    /** Channel ids of the retired multi-channel system — removed from the OS on every ensure(). */
    private val RETIRED = listOf(
        "channel_emergency", "channel_breaking", "channel_oracle", "channel_security", "channel_markets",
        "channel_weather", "channel_digest", "channel_space", "channel_safety", "channel_flight",
        "channel_reminders", "channel_world_pulse",
        // The vitals check-in's ad hoc channel (its alert now rides the board's alerting twin).
        "jarvis_vitals_checkin",
    )

    fun ensure(context: Context) {
        val mgr = context.getSystemService<NotificationManager>() ?: return
        val channels = listOf(
            // THE ONE NOTIFICATION — the LCARS situation board. Silent, no badge: it refreshes in place
            // all day; only its BRIEF_ALERT twin below ever buzzes.
            NotificationChannel(
                BRIEF,
                "Situation Board",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "The one LCARS notification: news, markets, weather and your agenda, always current."
                enableVibration(false)
                setShowBadge(false)
            },
            // The alerting twin: same notification id, used only when something NEW is genuinely urgent
            // (a due reminder, a major emergency, a security or safety notice) — buzzes once, then the
            // silent channel takes back over.
            NotificationChannel(
                BRIEF_ALERT,
                "Alert Conditions",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Sound and vibration when something on the board is urgent."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 180, 400, 180, 650)
                enableLights(true)
                lightColor = android.graphics.Color.RED
                setBypassDnd(false)
            },
            // The full-screen BREAKING NEWS takeover fallback — IMPORTANCE_HIGH is the max a channel can
            // declare; the full-screen intent is what actually takes over the screen.
            NotificationChannel(
                BREAKING_INTERRUPT,
                "Emergency Broadcast",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Takes over the screen for a major event."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                enableLights(true)
                lightColor = android.graphics.Color.RED
                setBypassDnd(false)
            },
        )
        mgr.createNotificationChannels(channels)
        RETIRED.forEach { runCatching { mgr.deleteNotificationChannel(it) } }
    }
}
