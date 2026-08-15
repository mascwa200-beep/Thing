package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.core.telemetry.EventSeverity
import dev.mascwa.pulse.data.sensing.SensoriumEngine
import dev.mascwa.pulse.data.sensing.SensoriumStore

/**
 * Lets the Computer interrogate the Sensorium on demand — the fused live reading, what's unusual
 * against the learned normal, and the recent sensed-event log. Read-only over label-derived state;
 * the persona already carries the one-line ambient read every turn, this tool is for going deeper
 * ("what did you hear while I was out?", "is anything odd around me?").
 */
class EnvironmentTool(
    private val engine: SensoriumEngine,
    private val store: SensoriumStore,
) : JarvisTool {
    override val name = "environment"
    override val usage =
        "environment [events] — the live Sensorium read (surroundings, anomalies vs your learned normal); pass 'events' for the recent sensed-event log"

    override suspend fun run(arg: String): String = runCatching {
        if (arg.trim().equals("events", ignoreCase = true)) {
            val events = store.eventsFlow.value.take(20)
            if (events.isEmpty()) return@runCatching "Nothing sensed recently."
            events.joinToString("\n") { e ->
                val mins = (System.currentTimeMillis() - e.atMs) / 60_000L
                val ago = if (mins < 60) "${mins}m ago" else "${mins / 60}h ago"
                val tag = if (e.severity == EventSeverity.ALERT.name) "[ALERT] " else ""
                "$ago — $tag${e.title}: ${e.detail}"
            }
        } else {
            buildString {
                append("Surroundings: ").append(engine.reading.value.describe())
                val anomalies = engine.anomalies.value
                if (anomalies.isNotEmpty()) {
                    append("\nUnusual right now: ")
                    append(anomalies.joinToString("; ") { "${it.metric} ${it.text}" })
                }
                engine.normalLine.value?.let { append("\nLearned normal: ").append(it) }
                append("\nSenses: ")
                append(if (engine.micArmed.value) "ears armed" else "ears on standby")
                append(", ")
                append(if (engine.camArmed.value) "eyes armed" else "eyes on standby")
                append(" · level ").append(engine.level.value.name)
            }
        }
    }.getOrElse { "Sensorium read failed: ${it.message}" }
}
