package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.core.telemetry.UserProfile
import dev.mascwa.pulse.data.profile.ProfileStore

/**
 * Lets J.A.R.V.I.S. maintain its structured memory of the user — durable preferences, interests and
 * ongoing projects — so it can tailor help over time. The profile digest is already injected into the
 * system prompt every turn; this tool is how new durable facts get *recorded* (and removed).
 *
 * Usage:
 *  - `profile <fact about the user>` — remember it (auto-categorized).
 *  - `profile list` — show what's currently remembered.
 *  - `profile forget <fact>` — drop a remembered fact.
 */
class ProfileTool(
    private val store: ProfileStore,
) : JarvisTool {
    override val name = "profile"
    override val usage =
        "profile <fact> | profile list | profile forget <fact> — remember the user's durable preferences, interests & projects"

    override suspend fun run(arg: String): String = runCatching {
        val a = arg.trim()
        when {
            a.isEmpty() || a.equals("list", ignoreCase = true) -> {
                val digest = store.digest()
                if (digest.isBlank()) "Nothing recorded about the user yet."
                else "What you remember about the user:\n$digest"
            }
            a.startsWith("forget ", ignoreCase = true) -> {
                val fact = a.substring(7).trim()
                if (fact.isBlank()) "Specify what to forget." else { store.forget(fact); "Forgotten: \"$fact\"." }
            }
            else -> {
                val category = UserProfile.classify(a)
                store.add(a, category)
                "Recorded (${category.name.lowercase()}): \"$a\"."
            }
        }
    }.getOrElse { "Profile update failed: ${it.message}" }
}
