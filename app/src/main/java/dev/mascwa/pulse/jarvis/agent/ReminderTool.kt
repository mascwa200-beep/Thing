package dev.mascwa.pulse.jarvis.agent

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.mascwa.pulse.data.jarvis.JarvisMemory
import dev.mascwa.pulse.data.jarvis.db.NoteSource
import dev.mascwa.pulse.notifications.ReminderWorker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Sets a one-off reminder ("remind me to X at Y"). Built on WorkManager so it needs no exact-alarm
 * permission and survives app restarts/reboots; it also files the reminder in durable memory so
 * J.A.R.V.I.S. can recall it. Honest limit: WorkManager timing isn't exact-to-the-second (Doze can defer
 * it a few minutes).
 */
class ReminderTool(
    private val context: Context,
    private val memory: JarvisMemory,
) : JarvisTool {
    override val name = "remind"
    override val usage =
        "remind <when> | <message> — e.g. remind in 30 minutes | stretch  ·  remind 3pm | call the dentist"

    override suspend fun run(arg: String): String {
        val p = arg.split("|", limit = 2).map { it.trim() }
        val whenText = p.getOrElse(0) { "" }
        val message = p.getOrElse(1) { "" }.ifBlank { "Reminder" }
        if (whenText.isBlank()) return "When should I remind you? e.g. \"in 20 minutes | …\" or \"7:30 | …\"."
        val delayMs = parseDelayMs(whenText)
            ?: return "I couldn't read that time — try \"in 30 minutes\" or \"3pm\"."
        if (delayMs < 1_000 || delayMs > MAX_DELAY_MS) return "Pick a time from a minute out to about a week."

        val id = (System.currentTimeMillis() and 0xFFFFFF).toInt()
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ReminderWorker.KEY_MESSAGE to message, ReminderWorker.KEY_ID to id))
            .addTag(ReminderWorker.TAG)
            .build()
        runCatching { WorkManager.getInstance(context).enqueue(req) }
            .onFailure { return "I couldn't schedule that reminder." }

        val pattern = if (delayMs > 12L * 3_600_000L) "EEE h:mm a" else "h:mm a"
        val label = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(System.currentTimeMillis() + delayMs))
        runCatching { memory.remember("Reminder at $label: $message", NoteSource.INFERENCE) }
        return "Reminder set for $label — \"$message\"."
    }

    /** Delay (ms) from now for a "when" phrase: relative ("in 30 min", "2 hours", "in 3 days") or a clock
     *  time ("3pm", "7:30", "tomorrow 8am", "tonight" → next occurrence). Null if unparseable. */
    private fun parseDelayMs(text: String): Long? {
        var t = text.lowercase().trim()
        if (t.isBlank()) return null
        // Normalise a few common word forms so the numeric parsing below catches them.
        t = t.replace("half an hour", "30 minutes").replace("half hour", "30 minutes").replace("an hour", "1 hour")
        val tomorrow = t.contains("tomorrow")
        // Relative ("in 30 min", "2 hours", "3 days") — unless an explicit clock time or "tomorrow" is given.
        if (!tomorrow && (t.contains("in ") || !t.contains(":"))) {
            Regex("(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?|[smhd])\\b").find(t)?.let { m ->
                val n = m.groupValues[1].toLongOrNull() ?: return@let
                val unitMs = when {
                    m.groupValues[2].startsWith("d") -> 86_400_000L
                    m.groupValues[2].startsWith("h") -> 3_600_000L
                    m.groupValues[2].startsWith("s") -> 1_000L
                    else -> 60_000L
                }
                return n * unitMs
            }
        }
        // Absolute clock time, or a coarse part-of-day keyword.
        val tm = parseClock(t) ?: when {
            t.contains("tonight") || t.contains("evening") -> 20 to 0
            t.contains("noon") -> 12 to 0
            t.contains("morning") -> 8 to 0
            else -> null
        } ?: return null
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, tm.first)
            set(Calendar.MINUTE, tm.second)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (tomorrow) cal.add(Calendar.DAY_OF_MONTH, 1)
        else if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis - System.currentTimeMillis()
    }

    private fun parseClock(text: String): Pair<Int, Int>? {
        val pm = text.contains("pm")
        val am = text.contains("am")
        val m = Regex("(\\d{1,2})(?:\\s*[:.]\\s*(\\d{1,2}))?").find(text) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        if (pm && hour < 12) hour += 12
        if (am && hour == 12) hour = 0
        if (hour !in 0..23 || min !in 0..59) return null
        return hour to min
    }

    private companion object {
        const val MAX_DELAY_MS = 7L * 24L * 3_600_000L
    }
}
