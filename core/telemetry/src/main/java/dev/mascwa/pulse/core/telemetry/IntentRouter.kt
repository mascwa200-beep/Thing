package dev.mascwa.pulse.core.telemetry

/** What the user's console message resolves to. */
sealed interface JarvisIntent {
    /** Report live device telemetry (battery, power, network, time) deterministically. */
    data object Status : JarvisIntent

    /** Drop to a locked-down, low-noise profile — executed by the app's device-control layer. */
    data object Lockdown : JarvisIntent

    /** Anything else → hand the text to the language model / persona core. */
    data class Chat(val text: String) : JarvisIntent
}

/**
 * A tiny, dependency-free intent classifier so the console can answer device questions
 * accurately (from telemetry) and trigger actions, instead of sending everything to a
 * model that can only guess at hardware state.
 */
class IntentRouter {

    fun route(input: String): JarvisIntent {
        val t = input.trim().lowercase()
        return when {
            t.isBlank() -> JarvisIntent.Chat(input)
            LOCKDOWN_HINTS.any { t.contains(it) } -> JarvisIntent.Lockdown
            STATUS_HINTS.any { t.contains(it) } -> JarvisIntent.Status
            else -> JarvisIntent.Chat(input)
        }
    }

    private companion object {
        val STATUS_HINTS = listOf(
            "status", "system report", "diagnostic", "battery", "power level",
            "telemetry", "vitals", "how's the device", "device status",
        )
        val LOCKDOWN_HINTS = listOf("lockdown", "lock down", "go dark", "panic", "secure mode")
    }
}
