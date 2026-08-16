package dev.mascwa.pulse.core.telemetry

import kotlin.math.min

/**
 * Turning "I want to learn X" into an ordered path through the bundled library.
 *
 * The library is hundreds of guides across dozens of categories and has always been browsable — a
 * rail of categories, a search box, a reader. What it has never been is **followable**: there was no
 * way to say what you wanted to learn and be handed a route through it, a piece at a time.
 *
 * ⚠️ **This is not a taught sequence, and the copy must never imply that it is.** The corpus carries
 * no dependency graph — nothing anywhere records that one guide should be read before another — so
 * inventing an order and calling it pedagogy would be a confident fiction of exactly the kind the
 * question extractor was written to avoid. What this actually does is two honest things: it puts the
 * closest material first, and it keeps related material together so the path does not lurch between
 * subjects. [ORDERING_NOTE] says so on the screen.
 *
 * Pure and deterministic: the same goal against the same index yields the same path every time, which
 * is what lets a store persist a goal and a set of finished ids rather than a frozen list.
 */
object Curriculum {

    /**
     * One guide on the path.
     *
     * @param position 1-based, so it can be shown as "4 of 12" without arithmetic at the call site.
     * @param why one short line on why this sits here, for the same reason [Oracle.Insight] carries
     *   its sources: a path nobody can interrogate reads as arbitrary.
     */
    data class Step(
        val guideId: String,
        val title: String,
        val category: String,
        val supergroup: String,
        val position: Int,
        val why: String,
    )

    /**
     * A composed path.
     *
     * Derived, never persisted: a store keeps the goal and which ids are finished, and recomposes.
     * That way a library that grows re-plans instead of pointing at guides that have moved.
     */
    data class Syllabus(
        val goal: String,
        val steps: List<Step>,
        val perDay: Int = DEFAULT_PER_DAY,
    ) {
        val isEmpty: Boolean get() = steps.isEmpty()

        /** How many sittings the whole path takes at the chosen pace. */
        val days: Int get() = if (steps.isEmpty()) 0 else (steps.size + perDay - 1) / perDay

        /** The honest caveat, carried with the path so a screen cannot forget to show it. */
        val note: String get() = ORDERING_NOTE

        /** Which sitting [step] falls on, 1-based. */
        fun dayFor(step: Step): Int = (step.position - 1) / perDay + 1

        fun done(completed: Set<String>): Int = steps.count { it.guideId in completed }

        fun remaining(completed: Set<String>): Int = steps.size - done(completed)

        /** 0.0..1.0. An empty path is complete rather than 0/0 — there is nothing left to do. */
        fun progress(completed: Set<String>): Double =
            if (steps.isEmpty()) 1.0 else done(completed).toDouble() / steps.size

        /** The next few unfinished steps, in path order. */
        fun next(completed: Set<String>, count: Int = perDay): List<Step> =
            steps.filter { it.guideId !in completed }.take(count.coerceAtLeast(1))

        fun describeProgress(completed: Set<String>): String {
            if (steps.isEmpty()) return "nothing to study"
            val d = done(completed)
            return when (d) {
                0 -> "not started · ${steps.size} guides"
                steps.size -> "complete · ${steps.size} guides"
                else -> "$d of ${steps.size} done"
            }
        }
    }

    /**
     * Compose a path for [goal] from the library [entries].
     *
     * @param supergroups category → supergroup, **passed in rather than imported**: the taxonomy is
     *   app-side content (it changes when bundled content changes) and this module must not depend on
     *   the app. An unmapped category falls back to [UNGROUPED] rather than being dropped, so a newly
     *   bundled category still appears on a path the day it lands.
     *
     * Relevance comes from [GuideSearch.rank] — the same ranker the assistant, voice and search all
     * use, so a goal and a question about the same subject reach the same guides. Order is then
     * regrouped for cohesion: strongest supergroup first, strongest category within it, strongest
     * guide within that.
     */
    fun compose(
        goal: String,
        entries: List<GuideSearch.Entry>,
        supergroups: Map<String, String> = emptyMap(),
        length: Int = DEFAULT_LENGTH,
        perDay: Int = DEFAULT_PER_DAY,
    ): Syllabus {
        val subject = goal.trim()
        val pace = perDay.coerceIn(1, MAX_PER_DAY)
        val want = length.coerceIn(1, MAX_LENGTH)
        if (subject.isEmpty() || entries.isEmpty()) return Syllabus(subject, emptyList(), pace)

        val hits = GuideSearch.rank(entries, searchPhrase(subject), limit = want)
        if (hits.isEmpty()) return Syllabus(subject, emptyList(), pace)

        val closest = hits.first().entry.id
        val ordered = cohere(hits, supergroups)

        val seenCategory = mutableSetOf<String>()
        val steps = ordered.mapIndexed { index, hit ->
            val category = hit.entry.category
            val opensCategory = seenCategory.add(category)
            Step(
                guideId = hit.entry.id,
                title = hit.entry.title,
                category = category,
                supergroup = supergroups[category] ?: UNGROUPED,
                position = index + 1,
                why = when {
                    hit.entry.id == closest -> "closest match in the library"
                    opensCategory -> "opens the $category material"
                    else -> "continues $category"
                },
            )
        }
        return Syllabus(subject, steps, pace)
    }

    /**
     * Words that mean something in a **question** and nothing in a stated **goal**.
     *
     * "How do I understand a circuit diagram" is asking something; "understand electricity" is naming
     * a subject with a verb of intent bolted on, and matching guides on that verb is how a path about
     * electricity fills up with *Depression: Understanding and Treating It*. These sit here rather
     * than in [GuideSearch.STOPWORDS] because the ranker is shared with the assistant, voice and
     * search, where a question's phrasing is the caller's own and must not be edited underneath them.
     *
     * ⚠️ **The list is this short because the wider ones were tested against the real 581-guide index
     * and made results worse.** Dropping prepositions ("around") lost *Mapping Hazards Around Your
     * Home Address* from a home-repair path while keeping the noise it was meant to remove, and
     * dropping "work"/"works" put *Ocean Acidification* at the top of an economics path. Only the
     * intent-and-framing class survived the evidence.
     */
    val GOAL_NOISE: Set<String> = setOf(
        "learn", "learns", "learning", "learned", "learnt",
        "understand", "understands", "understanding",
        "study", "studying", "master", "mastering", "teach", "teaching", "know", "knowing",
        "basic", "basics", "fundamentals", "introduction", "intro", "beginner", "beginners",
        "course", "courses", "tutorial", "overview", "everything",
    )

    /**
     * The goal reduced to what is actually being asked for, for searching only.
     *
     * The stated goal is kept verbatim on the [Syllabus] — it is what the reader typed and what the
     * screen shows. This is the phrase handed to the ranker. Falls back to the whole goal when
     * stripping would leave nothing, so "the basics" still searches for something.
     */
    fun searchPhrase(goal: String): String {
        val words = goal.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }
        val kept = words.filterNot { it in GOAL_NOISE }
        return if (kept.isEmpty()) goal else kept.joinToString(" ")
    }

    /**
     * Regroup ranked hits so related material sits together, without disturbing which material won.
     *
     * Both levels are ordered by their **best** member rather than by size or by an average: a
     * category holding one excellent guide should lead a category holding six mediocre ones, and an
     * average would bury it. Ties break on name, and hits within a category on score then title, so
     * the same goal always produces the same path.
     */
    private fun cohere(
        hits: List<GuideSearch.Hit>,
        supergroups: Map<String, String>,
    ): List<GuideSearch.Hit> {
        val byCategory = hits.groupBy { it.entry.category }
        val categories = byCategory.map { (name, members) ->
            Cluster(
                name = name,
                supergroup = supergroups[name] ?: UNGROUPED,
                best = members.maxOf { it.score },
                members = members.sortedWith(
                    compareByDescending<GuideSearch.Hit> { it.score }.thenBy { it.entry.title },
                ),
            )
        }
        return categories
            .groupBy { it.supergroup }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<Cluster>>> { group ->
                    group.value.maxOf { it.best }
                }.thenBy { it.key },
            )
            .flatMap { (_, group) ->
                group.sortedWith(compareByDescending<Cluster> { it.best }.thenBy { it.name })
                    .flatMap { it.members }
            }
    }

    private data class Cluster(
        val name: String,
        val supergroup: String,
        val best: Double,
        val members: List<GuideSearch.Hit>,
    )

    /**
     * A few goals worth offering when nobody has typed one, in the order they should be offered.
     *
     * ⚠️ **Every wording here was chosen by composing it against the real bundled library and reading
     * the path, not by writing down what sounded good.** The difference is not cosmetic: "growing
     * food" returns seven food-*safety* guides and no gardening at all, while "growing food and
     * gardening" reaches the agriculture material; "fixing things around the house" leads with *The
     * SI Base Units* where "home repair and maintenance" returns six Home & Repair guides; and "money
     * and how economies work" returns engineering project management where "economics and markets"
     * returns economics. [suggestions] additionally refuses to offer any of them the library cannot
     * support, so a wording that stops working as content changes disappears rather than misleads.
     */
    val SUGGESTED_GOALS: List<String> = listOf(
        "first aid and emergencies",
        "home repair and maintenance",
        "navigation and map reading",
        "cooking safely from scratch",
        "understanding the weather",
        "fire and shelter in the wild",
        "growing food and gardening",
        "economics and markets",
        "how the human body works",
        "stargazing and astronomy",
        "chemistry from the ground up",
        "staying safe outdoors",
    )

    /**
     * The suggested goals the bundled library can genuinely teach, in curated order.
     *
     * Curated order rather than alphabetical or by score: these are an opening offer, and sorting
     * them by anything mechanical buries the ones most people want behind whichever happens to match
     * the most guides.
     */
    fun suggestions(entries: List<GuideSearch.Entry>, limit: Int = 6): List<String> {
        if (entries.isEmpty()) return emptyList()
        return SUGGESTED_GOALS
            .filter { goal -> GuideSearch.rank(entries, searchPhrase(goal), limit = MIN_SUPPORT).size >= MIN_SUPPORT }
            .take(min(limit.coerceAtLeast(1), SUGGESTED_GOALS.size))
    }

    /** Said on the screen, not just here. See the class note. */
    const val ORDERING_NOTE: String =
        "Ordered by how closely each guide matches, with related subjects kept together. " +
            "The library has no prerequisite map, so this is a reading route, not a taught course."

    /** Where a category with no mapping lands. Mirrors the app taxonomy's own fallback. */
    const val UNGROUPED = "Other"

    const val DEFAULT_LENGTH = 12
    const val MAX_LENGTH = 40
    const val DEFAULT_PER_DAY = 1
    const val MAX_PER_DAY = 5

    /** A goal the library answers with fewer guides than this is not worth suggesting. */
    const val MIN_SUPPORT = 3
}
