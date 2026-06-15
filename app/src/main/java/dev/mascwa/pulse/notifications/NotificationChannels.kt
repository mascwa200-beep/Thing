package dev.mascwa.pulse.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import dev.mascwa.pulse.R

object NotificationChannels {
    const val BREAKING = "channel_breaking"
    const val MARKETS = "channel_markets"
    const val WEATHER = "channel_weather"
    const val DIGEST = "channel_digest"
    const val SPACE = "channel_space"
    const val SAFETY = "channel_safety"

    fun ensure(context: Context) {
        val mgr = context.getSystemService<NotificationManager>() ?: return
        val channels = listOf(
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
        )
        mgr.createNotificationChannels(channels)
    }
}
