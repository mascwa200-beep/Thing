package dev.mascwa.pulse.core.telemetry

/**
 * A structured **user profile** for J.A.R.V.I.S. — durable, categorized memory of the user's
 * preferences, interests and ongoing projects, kept separate from the flat note store so it can be
 * woven into *every* reply (proactive tailoring) rather than only surfaced on a keyword recall.
 *
 * This is the pure, CI-tested core: classify a statement into a category, detect when a message is a
 * durable self-declaration worth keeping, merge/dedupe entries with reinforcement, and render a compact
 * digest for the system prompt. Storage and wiring live in the app layer.
 *
 * Content note: the profile is the one place the assistant deliberately retains user-stated facts about
 * themselves — that is its purpose. It stays on-device and the user can clear it.
 */
enum class ProfileCategory { PREFERENCE, INTEREST, PROJECT, FACT }

/** One thing J.A.R.V.I.S. durably knows about the user. [weight] grows each time it's reaffirmed. */
data class ProfileEntry(
    val category: ProfileCategory,
    val text: String,
    val createdMs: Long,
    val lastSeenMs: Long,
    val weight: Int,
)

object UserProfile {
    const val MAX_ENTRIES = 120
    const val MAX_TEXT_LEN = 160
    const val DEFAULT_PER_CATEGORY = 5
    const val DEFAULT_MAX_CHARS = 700

    private val PROJECT_CUES = listOf(
        "working on", "building", "my project", "project", "deadline", "shipping", "developing",
        "my app", "my startup", "launching", "writing a", "writing my",
    )
    private val PREFERENCE_CUES = listOf(
        "prefer", "i like", "i love", "i hate", "i dislike", "don't like", "do not like", "favorite",
        "favourite", "always", "usually", "i want", "call me", "i need",
    )
    private val INTEREST_CUES = listOf(
        "interested in", "fan of", "hobby", "i enjoy", "passionate about", "i'm learning",
        "im learning", "learning",
    )

    // Compiled once — these run on the per-message capture path (detect/normalize are called for every
    // stored entry inside merge/forget's O(N) scans). Regex is immutable/thread-safe, so sharing is safe.
    private val WHITESPACE = Regex("\\s+")
    private val FIRST_PERSON = Regex("(?i)\\b(i|i'm|im|i've|ive|my|me)\\b")
    private val CATEGORY_LABELS = listOf(
        ProfileCategory.PREFERENCE to "Preferences",
        ProfileCategory.INTEREST to "Interests",
        ProfileCategory.PROJECT to "Projects",
        ProfileCategory.FACT to "Other",
    )

    /** Best-guess category for a free-text statement. Order: project, then preference, then interest. */
    fun classify(text: String): ProfileCategory {
        val t = text.lowercase()
        return when {
            PROJECT_CUES.any { t.contains(it) } -> ProfileCategory.PROJECT
            PREFERENCE_CUES.any { t.contains(it) } -> ProfileCategory.PREFERENCE
            INTEREST_CUES.any { t.contains(it) } -> ProfileCategory.INTEREST
            else -> ProfileCategory.FACT
        }
    }

    /**
     * Return a category only when [text] is a confident first-person, durable self-declaration worth
     * remembering (so passing remarks and questions are ignored). Used for always-on auto-capture.
     */
    fun detect(text: String): ProfileCategory? {
        val t = text.trim()
        if (t.isEmpty() || t.endsWith("?")) return null
        val firstPerson = FIRST_PERSON.containsMatchIn(t)
        if (!firstPerson) return null
        val cat = classify(t)
        return if (cat == ProfileCategory.FACT) null else cat
    }

    /** Normalized key for dedupe: lowercase, collapsed whitespace, no surrounding punctuation. */
    fun normalize(text: String): String =
        text.lowercase().trim().trim('.', '!', ',', ';', ':', '"', '\'').replace(WHITESPACE, " ")

    /**
     * Fold a statement into the profile: reaffirm (bump weight + recency) if already known, else add it.
     * Caps the store, evicting the weakest (lowest weight, then stalest) entry past [cap].
     */
    fun merge(
        entries: List<ProfileEntry>,
        text: String,
        category: ProfileCategory,
        nowMs: Long,
        cap: Int = MAX_ENTRIES,
    ): List<ProfileEntry> {
        val clean = text.trim().take(MAX_TEXT_LEN)
        if (clean.isBlank()) return entries
        val key = normalize(clean)
        val existing = entries.firstOrNull { normalize(it.text) == key }
        val updated = if (existing != null) {
            entries.map {
                if (it === existing) it.copy(
                    category = category,
                    lastSeenMs = nowMs,
                    weight = it.weight + 1,
                ) else it
            }
        } else {
            entries + ProfileEntry(category, clean, createdMs = nowMs, lastSeenMs = nowMs, weight = 1)
        }
        return if (updated.size > cap) {
            val weakest = updated.minWithOrNull(
                compareBy<ProfileEntry> { it.weight }.thenBy { it.lastSeenMs },
            )
            if (weakest != null) updated.filterNot { it === weakest } else updated
        } else {
            updated
        }
    }

    /** Remove every entry matching [text] (by normalized key). Returns the new list. */
    fun forget(entries: List<ProfileEntry>, text: String): List<ProfileEntry> {
        val key = normalize(text)
        return entries.filterNot { normalize(it.text) == key }
    }

    /**
     * A single tailored highlight line for surfaces like the home "for you" card: the strongest ongoing
     * project, else the strongest interest. Null when neither is known.
     */
    fun highlight(entries: List<ProfileEntry>): String? {
        inCategory(entries, ProfileCategory.PROJECT).firstOrNull()?.let { return "Working on: ${it.text}" }
        inCategory(entries, ProfileCategory.INTEREST).firstOrNull()?.let { return "Following: ${it.text}" }
        return null
    }

    /** Entries in a category, strongest (then most-recent) first. */
    fun inCategory(entries: List<ProfileEntry>, category: ProfileCategory): List<ProfileEntry> =
        entries.filter { it.category == category }
            .sortedWith(compareByDescending<ProfileEntry> { it.weight }.thenByDescending { it.lastSeenMs })

    /**
     * A compact, prioritized digest for the system prompt — one labelled line per non-empty category,
     * bounded by [perCategory] items and an overall [maxChars]. Empty when nothing is known.
     */
    fun digest(
        entries: List<ProfileEntry>,
        perCategory: Int = DEFAULT_PER_CATEGORY,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): String {
        if (entries.isEmpty()) return ""
        val lines = CATEGORY_LABELS.mapNotNull { (cat, label) ->
            val items = inCategory(entries, cat).take(perCategory)
            if (items.isEmpty()) null else "$label: " + items.joinToString("; ") { it.text }
        }
        val body = lines.joinToString("\n")
        return if (body.length <= maxChars) body else body.take(maxChars - 1).trimEnd() + "…"
    }
}
