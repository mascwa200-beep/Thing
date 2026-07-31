package dev.mascwa.pulse.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import dev.mascwa.pulse.R

object NotificationChannels {
    const val EMERGENCY = "channel_emergency"
    const val BREAKING = "channel_breaking"
    const val MARKETS = "channel_markets"
    const val WEATHER = "channel_weather"
    const val DIGEST = "channel_digest"
    const val SPACE = "channel_space"
    const val SAFETY = "channel_safety"
    const val FLIGHT = "channel_flight"
    const val REMINDERS = "channel_reminders"

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
        )
        mgr.createNotificationChannels(channels)
    }
}
