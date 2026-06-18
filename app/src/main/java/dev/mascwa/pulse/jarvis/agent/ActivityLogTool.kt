package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.usage.FeatureCatalog
import dev.mascwa.pulse.data.usage.UsageRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The assistant's read access to the app's real-time activity log — a content-free, on-device,
 * time-ordered trail of recent events (navigation, the assistant's own tool calls, lifecycle). Lets
 * J.A.R.V.I.S. answer "what have you/we just been doing?" and ground itself in the live session.
 */
class ActivityLogTool(
    private val repo: UsageRepository,
    private val limit: Int = 40,
) : JarvisTool {
    override val name = "activity"
    override val usage =
        "activity <anything> — read the app's recent real-time activity log (newest first, on-device)"

    private val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override suspend fun run(arg: String): String = runCatching {
        val events = repo.recentActivity(limit)
        if (events.isEmpty()) return@runCatching "The activity log is empty so far."
        events.joinToString("\n") { e ->
            val label = if (e.category == "nav") FeatureCatalog.labelFor(e.label) else e.label
            "${time.format(Date(e.epochMs))} · ${e.category} · $label"
        }
    }.getOrElse { "Activity read failed: ${it.message}" }
}
