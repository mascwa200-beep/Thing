package dev.mascwa.pulse.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import dev.mascwa.pulse.R

object NotificationChannels {
    const val EMERGENCY = "channel_emergency"
    const val BREAKING = "channel_breaking"
    const val BREAKING_INTERRUPT = "channel_breaking_interrupt"
    const val ORACLE = "channel_oracle"
    const val SECURITY = "channel_security"
    const val MARKETS = "channel_markets"
    const val WEATHER = "channel_weather"
    const val DIGEST = "channel_digest"
    const val SPACE = "channel_space"
    const val SAFETY = "channel_safety"
    const val FLIGHT = "channel_flight"
    const val REMINDERS = "channel_reminders"
    const val WORLD_PULSE = "channel_world_pulse"

    fun ensure(context: Context) {
        val mgr = context.getSystemService<NotificationManager>() ?: return
        val channels = listOf(
            // Its own, most-attention-grabbing channel for major "this just in" emergency events — a
            // distinct urgent vibration + light so it reads differently from the general breaking feed.
            NotificationChannel(
                EMERGENCY,
                context.getString(R.string.channel_emergency_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_emergency_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 180, 400, 180, 650)
                enableLights(true)
                lightColor = android.graphics.Color.RED
                setBypassDnd(false)
            },
            NotificationChannel(
                BREAKING,
                context.getString(R.string.channel_breaking_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_breaking_desc) },
            // The full-screen BREAKING NEWS takeover — its own urgent channel (literal strings to avoid a
            // resource dependency). IMPORTANCE_HIGH is the max a channel can declare; the full-screen intent
            // is what actually takes over the screen. Always RED ALERT — it only fires on major events.
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
            // ORACLE — the Computer's proactive foresight pushes (cross-signal advisories).
            NotificationChannel(
                ORACLE,
                "Computer Advisory",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "The Computer's best next move for you, based on what it knows."
                enableVibration(true)
            },
            // SECURITY — split out of the low-importance DIGEST catch-all: a real security notice deserves
            // its own channel and a proper importance level, not to be buried with the daily roundup.
            NotificationChannel(
                SECURITY,
                context.getString(R.string.channel_security_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_security_desc) },
            NotificationChannel(
                MARKETS,
                context.getString(R.string.channel_markets_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_markets_desc) },
            NotificationChannel(
                WEATHER,
                context.getString(R.string.channel_weather_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_weather_desc) },
            NotificationChannel(
                DIGEST,
                context.getString(R.string.channel_digest_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.channel_digest_desc) },
            NotificationChannel(
                SPACE,
                context.getString(R.string.channel_space_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_space_desc) },
            NotificationChannel(
                SAFETY,
                context.getString(R.string.channel_safety_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_safety_desc) },
            NotificationChannel(
                FLIGHT,
                context.getString(R.string.channel_flight_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.channel_flight_desc) },
            NotificationChannel(
                REMINDERS,
                context.getString(R.string.channel_reminders_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_reminders_desc) },
            // The SITUATION REPORT — a silent, low-key live feed that stays in the tray and updates in place
            // with the latest intimate cross-signal read. MIN importance so it never buzzes or heads-up; it's
            // an ambient dashboard, not an alert. (Literal strings to avoid a resource dependency.)
            NotificationChannel(
                WORLD_PULSE,
                "Situation Report",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "A quiet, always-latest read of the world and your day."
                enableVibration(false)
                setShowBadge(false)
            },
        )
        mgr.createNotificationChannels(channels)
    }
}
