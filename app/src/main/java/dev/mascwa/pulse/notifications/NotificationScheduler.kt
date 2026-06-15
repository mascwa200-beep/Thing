package dev.mascwa.pulse.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Schedules/refreshes the periodic [RefreshWorker]. */
class NotificationScheduler(private val context: Context) {

    fun schedule(intervalMinutes: Int, wifiOnly: Boolean) {
        // WorkManager enforces a 15-minute minimum period.
        val minutes = intervalMinutes.coerceAtLeast(15).toLong()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(RefreshWorker.UNIQUE_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            RefreshWorker.UNIQUE_NAME,
            // Update keeps the schedule current when the user changes the interval.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(RefreshWorker.UNIQUE_NAME)
    }
}
