package dev.mascwa.pulse.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.mascwa.pulse.device.DeviceProbeReader
import java.util.concurrent.TimeUnit

/** Schedules/refreshes the periodic [RefreshWorker]. */
class NotificationScheduler(
    private val context: Context,
    private val deviceProbe: DeviceProbeReader? = null,
) {

    /**
     * @param intervalMinutes what the user asked for. What is actually scheduled is that stretched
     *   by `DeviceClass.Budget.backgroundScale`, so a phone with little to spare is woken less often
     *   rather than doing the same work badly.
     *
     * ⚠️ **This is the DISCRETIONARY half and nothing else moves.** `RefreshWorker` already trims
     * individual passes by work tier; this stretches how often it is asked at all. The emergency
     * watch's sixty-second poll is deliberately NOT scaled — its own file says why, at length — and
     * neither are the two service self-heals or the app update, which sit above the notification
     * gates for reasons already written down. A cheap phone should be woken less to publish a news
     * board, and not one second later to be told about a tornado.
     *
     * ⚠️ The DURABLE budget, so the number is hardware-derived and therefore stable. A period is
     * persisted by WorkManager and re-read on every schedule, and `UPDATE` reschedules whenever it
     * changes — folding a momentary thermal reading in would have the period flapping between
     * whatever the phone's temperature happened to be at each app launch.
     *
     * ⚠️ A null probe means today's cadence exactly, because an absent measurement must never
     * demote — the same rule the whole of `DeviceClass` is built on.
     */
    fun schedule(intervalMinutes: Int, wifiOnly: Boolean) {
        val scale = runCatching { deviceProbe?.durableBudget()?.backgroundScale }.getOrNull() ?: 1f
        // WorkManager enforces a 15-minute minimum period.
        val asked = intervalMinutes.coerceAtLeast(15)
        val minutes = (asked * scale).toInt()
            // ⚠️ **Never stretched past the longest interval the app itself offers.** Measured
            // against the real picker — 15 / 30 / 60 / 120 / 240 minutes — the multiplier matters at
            // the short end and compounds badly at the long one: a MINIMAL phone set to four hours
            // would otherwise be woken about once a day. Above this the user's own choice is already
            // doing the work the scale exists to do.
            //
            // ⚠️ If that picker ever gains a longer option this constant will be stale, and the
            // direction it goes stale in is the harmless one: a longer choice simply stops being
            // stretched, which is what somebody asking for it wanted anyway.
            .coerceAtMost(LONGEST_OFFERED_MINUTES)
            // Never SHORTER than what was asked. The cap above cannot lower a stored value that is
            // already past it — a restored backup can hold any number the picker never offered.
            .coerceAtLeast(asked)
            .coerceAtLeast(15)
            .toLong()
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

    private companion object {
        /** The longest option the settings picker offers, in minutes. See [schedule]. */
        const val LONGEST_OFFERED_MINUTES = 240
    }
}
