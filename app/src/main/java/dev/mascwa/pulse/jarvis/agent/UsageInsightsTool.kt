package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.core.telemetry.FeatureMeta
import dev.mascwa.pulse.core.telemetry.UsageInsights
import dev.mascwa.pulse.data.usage.FeatureCatalog
import dev.mascwa.pulse.data.usage.UsageRepository
import java.util.Calendar

/**
 * Gives the assistant read access to on-device, aggregated app-usage stats and the tailored
 * recommendations derived from them — so it can personalize replies ("you check Markets every
 * morning…") without any new data path. The data is counts + timing only (no content, no PII) and
 * stays on-device unless the user has opted into a cloud brain, in which case it rides the existing
 * cloud path like any other tool observation.
 */
class UsageInsightsTool(
    private val repo: UsageRepository,
    private val catalog: List<FeatureMeta> = FeatureCatalog.entries,
    private val nowHour: () -> Int = { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) },
) : JarvisTool {
    override val name = "usage"
    override val usage =
        "usage <anything> — read the user's on-device feature-usage stats & tailored recommendations"

    override suspend fun run(arg: String): String = runCatching {
        val snapshot = repo.snapshot()
        if (snapshot.totalEvents == 0) {
            return@runCatching "No usage recorded yet — the user hasn't navigated enough for a pattern."
        }
        val top = UsageInsights.topFeatures(snapshot, limit = 6)
            .joinToString("\n") { "- ${FeatureCatalog.labelFor(it.key)}: ${it.count} visits" }
        val recs = UsageInsights.recommend(snapshot, nowHour(), catalog)
        val recLines = if (recs.isEmpty()) "(none)"
        else recs.joinToString("\n") { "• ${it.headline} — ${it.detail}" }
        buildString {
            append("Most-used features (${snapshot.totalEvents} total visits):\n")
            append(top)
            append("\n\nTailored recommendations:\n")
            append(recLines)
        }
    }.getOrElse { "Usage read failed: ${it.message}" }
}
