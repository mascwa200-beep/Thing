package dev.mascwa.pulse.jarvis.agent

/**
 * A capability J.A.R.V.I.S. can invoke during the agentic loop. Each tool takes a single string
 * argument (whatever the model wrote after the tool name) and returns a short observation string.
 * Implementations must never throw — return an error string instead; the orchestrator truncates.
 */
interface JarvisTool {
    /** Single lowercase token the model writes after `TOOL` (e.g. "web", "repo"). */
    val name: String

    /** One-line `name <arg> — what it does` shown to the model so it knows the tool exists. */
    val usage: String

    suspend fun run(arg: String): String
}
