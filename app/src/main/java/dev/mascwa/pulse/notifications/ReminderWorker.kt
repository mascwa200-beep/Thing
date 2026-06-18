package dev.mascwa.pulse.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.mascwa.pulse.PulseApplication

/**
 * Fires a single user-set reminder: posts a notification with the reminder text via [Notifier]. Enqueued
 * by [dev.mascwa.pulse.jarvis.agent.ReminderTool] as a delayed one-time WorkManager job, so it survives
 * app restarts and reboots without needing the exact-alarm permission.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val message = inputData.getString(KEY_MESSAGE).orEmpty().ifBlank { "Reminder, sir." }
        val id = inputData.getInt(KEY_ID, System.currentTimeMillis().toInt())
        val notifier = runCatching { (applicationContext as PulseApplication).container.notifier }.getOrNull()
            ?: Notifier(applicationContext)
        notifier.notifyReminder(id, "Reminder", message)
        return Result.success()
    }

    companion object {
        const val KEY_MESSAGE = "reminder_message"
        const val KEY_ID = "reminder_id"
        const val TAG = "jarvis_reminder"
    }
}
