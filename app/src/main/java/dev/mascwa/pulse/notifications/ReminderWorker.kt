package dev.mascwa.pulse.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.mascwa.pulse.PulseApplication

/**
 * Fires a single user-set reminder at its set moment: republishes the one LCARS board with the reminder
 * as its ALERT row/headline on the alerting channel (a user-set reminder always buzzes — it's the one
 * interruption the user explicitly asked for, so it also bypasses quiet hours). Enqueued by
 * [dev.mascwa.pulse.jarvis.agent.ReminderTool] as a delayed one-time WorkManager job, so it survives app
 * restarts and reboots without needing the exact-alarm permission.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val message = inputData.getString(KEY_MESSAGE).orEmpty().ifBlank { "Reminder" }
        runCatching {
            val container = (applicationContext as PulseApplication).container
            val settings = container.settingsRepository.current()
            BriefEngine.publish(applicationContext, container, settings, reminderNow = message)
        }.onFailure {
            // Never drop a user-set reminder: even if the full board can't be gathered, post the one line.
            Notifier(applicationContext).notifyUrgentLine(
                headline = "Reminder — $message",
                detail = message,
                key = "rem:${message.hashCode()}",
            )
        }
        return Result.success()
    }

    companion object {
        const val KEY_MESSAGE = "reminder_message"
        const val KEY_ID = "reminder_id"
        const val TAG = "jarvis_reminder"
    }
}
