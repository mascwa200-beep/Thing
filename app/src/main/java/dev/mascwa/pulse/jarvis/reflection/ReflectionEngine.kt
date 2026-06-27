package dev.mascwa.pulse.jarvis.reflection

import dev.mascwa.pulse.core.telemetry.Memory
import dev.mascwa.pulse.core.telemetry.MemoryKind
import dev.mascwa.pulse.core.telemetry.MemoryStream
import dev.mascwa.pulse.data.memory.MemoryStreamStore
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.jarvis.inference.EngineState
import dev.mascwa.pulse.jarvis.inference.LocalInferenceEngine
import kotlinx.coroutines.flow.toList

/**
 * The **reflection** layer of the Mnemosyne episodic stack (Generative-Agents style). Periodically, once
 * enough significant experience has accumulated, it asks the model to synthesise a handful of recent
 * OBSERVATIONs into higher-level **insights** — durable patterns/goals/preferences that aren't stated in
 * any single observation — and records them back as REFLECTION memories. Those then surface in recall and
 * in the Memory screen's episodic section, so J.A.R.V.I.S. reasons over conclusions, not just raw events.
 *
 * The decision logic (when to reflect, which seeds) is the pure, CI-tested [MemoryStream]; this class only
 * orchestrates the gating, the LLM synthesis and the write-back. Fully defensive and cloud-gated: it is a
 * no-op unless a working inference backend is Ready (in practice the cloud brain), the feature is enabled,
 * and the throttle window has elapsed — so it never runs the on-device model unprompted or burns the cloud.
 */
class ReflectionEngine(
    private val memoryStream: MemoryStreamStore,
    private val engine: LocalInferenceEngine,
    private val settings: SettingsRepository,
) {

    /** Run one reflection pass if it's due. Returns the insights recorded (possibly empty). Never throws. */
    suspend fun reflectIfDue(now: Long = System.currentTimeMillis()): List<String> = runCatching {
        val s = settings.current()
        if (!s.jarvis.reflectionEnabled) return emptyList()
        // Needs a working backend — the on-device echo engine can't synthesise, so this effectively gates
        // on the cloud brain being configured + Ready.
        if (engine.state.value !is EngineState.Ready) return emptyList()
        if (now - s.lastReflectionMs < MIN_INTERVAL_MS) return emptyList()

        val memories = memoryStream.all()
        val since = if (s.lastReflectionMs > 0L) s.lastReflectionMs else now - LOOKBACK_MS
        if (!MemoryStream.shouldReflect(MemoryStream.recentImportanceSum(memories, since))) return emptyList()

        val seeds = MemoryStream.reflectionSeeds(memories)
        if (seeds.isEmpty()) return emptyList()

        val insights = synthesize(seeds)
        insights.forEach { memoryStream.record(it, MemoryKind.REFLECTION) }
        // Stamp the throttle whenever we ran a real pass (even if the model offered nothing), so a barren
        // period doesn't re-hit the model every cycle.
        settings.update { it.copy(lastReflectionMs = now) }
        insights
    }.getOrDefault(emptyList())

    private suspend fun synthesize(seeds: List<Memory>): List<String> {
        val observations = seeds.joinToString("\n") { "- ${it.text}" }
        val system =
            "You are J.A.R.V.I.S.'s reflective memory. From these recent observations about the user, infer " +
                "1–3 higher-level INSIGHTS — durable patterns, preferences, goals or conclusions that emerge " +
                "ACROSS them but aren't stated in any single line. Each insight: one line, third person " +
                "(\"The user …\"), concrete and non-obvious. Output only the insights, one per line, or nothing " +
                "if none are warranted. No preamble, no numbering."
        val raw = engine.generate(observations, emptyList(), system).toList().joinToString("").trim()
        return raw.lines()
            .map { it.trim().removePrefix("-").removePrefix("•").trim().trim('"') }
            .filter { it.length in 8..240 && it.any(Char::isLetter) && !it.startsWith("//") }
            .distinct()
            .take(MAX_INSIGHTS)
    }

    private companion object {
        const val MIN_INTERVAL_MS = 4L * 60 * 60 * 1000  // reflect at most ~every 4 hours
        const val LOOKBACK_MS = 24L * 60 * 60 * 1000     // first run considers the last day of observations
        const val MAX_INSIGHTS = 3
    }
}
