package dev.mascwa.pulse.core.util

/**
 * Centralised secret scrubbing for anything that leaves the device (debug reports uploaded to the repo,
 * shared logs). Masks known API-key / token shapes AND any exact credential values passed in
 * [extraSecrets]. The exact-value pass is the load-bearing guarantee: a debug bundle can never carry the
 * GitHub token / cloud key / model token even if it appears in a stack trace or logcat line and doesn't
 * happen to match a generic pattern (e.g. an OpenRouter `sk-or-v1-…` key with hyphens).
 */
object SecretScrub {

    private val PATTERNS = listOf(
        Regex("(?i)\\bbearer\\s+[A-Za-z0-9._\\-]{10,}"),
        Regex("\\bsk-[A-Za-z0-9\\-]{12,}"),
        Regex("\\bgh[pousr]_[A-Za-z0-9]{16,}"),
        Regex("\\bgithub_pat_[A-Za-z0-9_]{20,}"),
        Regex("\\bAIza[0-9A-Za-z_\\-]{20,}"),
        Regex("\\bxox[baprs]-[A-Za-z0-9\\-]{10,}"),
        Regex("(?i)\\b(token|api[_-]?key|secret|password|passwd|authorization)\\b\\s*[:=]\\s*\\S+"),
    )

    /** Mask [text]. [extraSecrets] are the live credential values (redacted by exact match, length ≥ 8). */
    fun scrub(text: String, extraSecrets: List<String> = emptyList()): String {
        var out = text
        for (sec in extraSecrets) {
            val s = sec.trim()
            if (s.length >= 8) out = out.replace(s, "[redacted]")
        }
        for (p in PATTERNS) out = p.replace(out, "[redacted]")
        return out
    }
}
