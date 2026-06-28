package dev.mascwa.pulse.core.telemetry

/**
 * Derives the presentation-agnostic bits of an "operator dossier" — a spy-style intel file J.A.R.V.I.S.
 * keeps on its operator, assembled from data the app already holds on-device (profile, objectives,
 * device disposition, activity). Pure + CI-tested; the screen does the rendering.
 *
 * Nothing here collects or transmits anything — it only derives a stable callsign and an "intel level"
 * from counts the caller already has.
 */
object OperatorDossier {

    /** A deterministic, stable spy callsign (ADJECTIVE NOUN) derived from a seed, so it feels personal
     *  but never changes across launches. Blank seeds fall back to a fixed default. */
    fun codename(seed: String): String {
        val s = seed.trim().ifBlank { "OPERATOR" }
        val h = s.fold(7) { acc, ch -> (acc * 31 + ch.code) and 0x7fffffff }
        val adj = ADJECTIVES[h % ADJECTIVES.size]
        val noun = NOUNS[(h / ADJECTIVES.size) % NOUNS.size]
        return "$adj $noun"
    }

    /** 0..100 estimate of how much J.A.R.V.I.S. knows about the operator. Profile facts weigh most;
     *  open objectives and recent activity contribute, but the activity term saturates at [ACTIVITY_CAP]
     *  so a chatty log can't dominate. Negative inputs are clamped to zero. */
    fun intelLevel(profileCount: Int, objectiveCount: Int, activityCount: Int): Int {
        val p = profileCount.coerceAtLeast(0)
        val o = objectiveCount.coerceAtLeast(0)
        val a = activityCount.coerceAtLeast(0).coerceAtMost(ACTIVITY_CAP)
        return (p * 7 + o * 4 + a).coerceIn(0, 100)
    }

    /** Plain-language classification of an [intelLevel] (0..100). */
    fun classification(intelLevel: Int): String = when {
        intelLevel >= 75 -> "COMPREHENSIVE"
        intelLevel >= 40 -> "PARTIAL"
        intelLevel >= 10 -> "FRAGMENTARY"
        else -> "MINIMAL"
    }

    private const val ACTIVITY_CAP = 20

    private val ADJECTIVES = listOf(
        "SILENT", "COBALT", "NIGHT", "IRON", "HOLLOW", "AMBER", "VECTOR", "GHOST", "CRIMSON", "ARC",
    )
    private val NOUNS = listOf(
        "WARDEN", "FALCON", "CIPHER", "HALO", "SENTINEL", "ORACLE", "MERIDIAN", "ECHO", "LANTERN", "SPECTRE",
    )
}
