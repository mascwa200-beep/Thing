package dev.mascwa.pulse.core.telemetry

/**
 * One search over everything the device itself holds.
 *
 * The app is a Library Computer Access and Retrieval System carrying hundreds of written guides, the
 * user's notes, their diary, an episodic memory stream, their tasks, their profile and the
 * assistant's own findings — and its only search box sends the query to the internet. Everything
 * above is already on the disk, already loaded, and already unsearchable except by opening the one
 * screen that happens to hold it.
 *
 * Ranking is **not** reimplemented here. [GuideSearch] was tuned against the real 577-guide index
 * and its `Entry` shape — an id, a title, a category, a summary, some headings — describes a note or
 * a task as well as it describes a guide. This adds only what a mixed corpus needs that a
 * single-kind one does not.
 *
 * **Which is diversity.** Guides outnumber notes by two orders of magnitude, so a plain top-ten is
 * ten guides, every time, and the three things the user actually wrote are never seen. [search]
 * therefore ranks across the whole corpus — inverse document frequency is only meaningful over all
 * of it — and then caps how many results any one kind may occupy.
 */
object DeviceSearch {

    /** Where a result came from. The label is user-facing; the route is where tapping it should go. */
    enum class RecordKind(val label: String, val route: String) {
        GUIDE("Guide", "survival"),
        NOTE("Note", "notes"),
        DIARY("Diary", "diary"),
        MEMORY("Memory", "jarvis_memory"),
        TASK("Task", "jarvis_memory"),
        PROFILE("Profile", "jarvis_memory"),
        FINDING("Finding", "jarvis_memory"),
        KNOWLEDGE("Document", "jarvis_memory"),

        /**
         * An app feature itself, so typing "radar" here opens the radar instead of only finding
         * prose about radar. ⚠️ Convention: a FEATURE record's entry **id IS the route to open** —
         * the `route` below is only the fallback for a tap handler that predates the convention.
         */
        FEATURE("Feature", "menu"),

        /**
         * A place inside Settings — a category, or one section within it.
         *
         * ⚠️ **This is a separate kind for a measured reason, not for tidiness.** These records were
         * [FEATURE]s, and a Settings record systematically OUTRANKS the screen it describes. The
         * mechanism is field redundancy, not length: [GuideSearch.fieldScore] sums the best match
         * per field and never divides by length, so what matters is how many fields carry the word.
         * A Settings record's body is a keyword list, which by construction repeats its own title,
         * so it scores a title hit *and* a body hit where a real feature scores only the title hit
         * (its human pitch does not repeat its label). Measured over the real corpus, that put a
         * Settings row above the real screen for sixteen ordinary one-word queries — `sos`, `home`,
         * `markets`, `settings`, `security`, `network` among them — and for `device` it pushed two
         * real screens off the capped slate entirely.
         *
         * Giving them their own kind hands them their own [DEFAULT_PER_KIND] budget, so they cannot
         * take a place from a screen **by construction** rather than by a measurement someone has to
         * keep re-running. It also lets the results list say "Setting", which is what they are.
         *
         * ⚠️ Shares the FEATURE convention that **the entry id IS the route to open**, and here that
         * route carries an argument (`settings?cat=…`), so a tap handler MUST open `r.id` — falling
         * through to [route] would drop the argument and land on Settings' entry category instead of
         * the one the user searched for.
         *
         * ⚠️ **A kind's [label] is scored, so naming one is not a purely cosmetic act.** [of] puts
         * it in the record's `category` field, which [GuideSearch] weights at 4 — so every record of
         * this kind gains a free stem match on the query "settings", and for that one query a
         * Settings row therefore outranks the Settings screen itself. Left alone deliberately:
         * every competing row opens Settings, so nothing is mis-navigated, and renaming a
         * user-facing heading to dodge a scoring artefact would be the worse trade. Worth knowing
         * before adding a kind whose label collides with the name of a real screen.
         */
        SETTING("Setting", "settings"),
    }

    /**
     * One searchable thing, whatever it happens to be.
     *
     * @param atMs when it was written, where that is known — used only to break ties, since between
     *   two equally good matches in your own writing the recent one is nearly always the one meant.
     */
    data class Record(
        val entry: GuideSearch.Entry,
        val kind: RecordKind,
        val atMs: Long = 0L,
    )

    data class Result(val record: Record, val score: Double) {
        val id: String get() = record.entry.id
        val title: String get() = record.entry.title
        val kind: RecordKind get() = record.kind
    }

    /** Default breadth. Enough to choose from, few enough to read without scrolling past the answer. */
    const val DEFAULT_LIMIT = 12

    /**
     * How many results one kind may occupy.
     *
     * Without this the guide corpus wins every slot on the strength of its size alone, which is the
     * difference between a search over the device and a search over the library with extra steps.
     */
    const val DEFAULT_PER_KIND = 4

    /**
     * Rank [records] against [query], best first, with no kind allowed more than [perKind] places.
     *
     * Scoring runs over the **whole** corpus before any capping: term rarity is a property of the
     * collection, and computing it per kind would make a word that is ordinary among guides look
     * distinctive among four notes.
     */
    fun search(
        records: List<Record>,
        query: String,
        limit: Int = DEFAULT_LIMIT,
        perKind: Int = DEFAULT_PER_KIND,
    ): List<Result> {
        val tokens = GuideSearch.tokens(query)
        if (tokens.isEmpty() || records.isEmpty()) return emptyList()

        val entries = records.map { it.entry }
        val weights = tokens.associateWith { GuideSearch.idf(entries, it) }

        val scored = records.asSequence()
            .map { Result(it, GuideSearch.score(it.entry, tokens, weights)) }
            .filter { it.score > 0.0 }
            .sortedWith(compareByDescending<Result> { it.score }.thenByDescending { it.record.atMs })
            .toList()

        val taken = HashMap<RecordKind, Int>()
        val out = ArrayList<Result>(limit)
        for (r in scored) {
            if (out.size >= limit) break
            val n = taken.getOrDefault(r.kind, 0)
            if (n >= perKind) continue
            taken[r.kind] = n + 1
            out += r
        }
        // Fill the remaining places ONLY when there is no diversity left to protect — everything
        // that matched is one kind, so the cap is denying places to nothing. Filling whenever the
        // capped pass left room would hand those places straight back to the largest kind, which is
        // the exact outcome the cap exists to prevent. A short list is the cap working.
        if (out.size < limit && scored.map { it.kind }.distinct().size == 1) {
            for (r in scored) {
                if (out.size >= limit) break
                if (r !in out) out += r
            }
        }
        return out
    }

    /** The same results arranged by kind, in the order each kind first appears. For a sectioned list. */
    fun byKind(results: List<Result>): List<Pair<RecordKind, List<Result>>> =
        results.groupBy { it.kind }.toList()

    /** How many of each kind are searchable, for an honest "searching N things" line. */
    fun corpusSummary(records: List<Record>): List<Pair<RecordKind, Int>> =
        RecordKind.entries.mapNotNull { k ->
            records.count { it.kind == k }.takeIf { it > 0 }?.let { k to it }
        }

    // ---- building records ---------------------------------------------------------------------

    /**
     * A record from a piece of the user's own writing.
     *
     * The body goes in `summary` because that is the field [GuideSearch] searches for prose; a note
     * has no headings and its "category" is what kind of thing it is, which is what the reader wants
     * to see beside it anyway.
     */
    fun of(
        id: String,
        kind: RecordKind,
        title: String,
        body: String = "",
        atMs: Long = 0L,
    ): Record = Record(
        entry = GuideSearch.Entry(
            id = id,
            title = title.ifBlank { body.take(TITLE_FALLBACK_CHARS) },
            category = kind.label,
            summary = body,
        ),
        kind = kind,
        atMs = atMs,
    )

    /** Untitled writing still needs something to show and something to match a title against. */
    const val TITLE_FALLBACK_CHARS = 60
}
