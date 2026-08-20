package dev.mascwa.pulse.core.telemetry

/**
 * Which of the app's several ways of answering a question to try, in what order, and what to say
 * about where the answer came from.
 *
 * ⚠️ **This exists because the `web` tool was measurably not a search engine.** It called
 * DuckDuckGo's Instant Answer API, which is an *entity lookup*: over fourteen real queries, all
 * eight natural-language questions returned nothing and all six bare-noun lookups returned an
 * abstract. A clean split — and the tool advertised itself as "search the web", so the model called
 * it with exactly the shape that always fails, and read "No instant answer found" as "the web does
 * not know", which is a different and much more damaging claim.
 *
 * The fix is not a different endpoint. It is recognising that this app has **three** ways to answer,
 * each good at something different, and that the shape of the question says which to reach for:
 *
 *  - the **bundled library** — 651 guides, offline, free, instant, and genuinely the best answer to
 *    "how do I treat a burn". Useless for anything that happened this morning.
 *  - an **encyclopaedia** — keyless and enormous, and the right answer to "what is a caldera".
 *    Weak on questions phrased as questions, and blind to current events.
 *  - the **open web** — the only thing that can answer "what happened in the election", and the only
 *    tier that needs a key the user may not have supplied.
 *
 * ⚠️ **The most valuable rule here is the refusal.** When the question needs the open web and the
 * open web is not configured, this says so by name rather than handing back an encyclopaedia article
 * on the general subject and letting it read as an answer. Naming the gap is what lets the model — or
 * the reader — do something about it. Same discipline as `Readability.Outcome` and
 * `Rebuttal.Provenance`: the verdict is part of the output, not an afterthought.
 *
 * Pure and deterministic, so CI gates it and it runs locally. The network lives in the caller.
 */
object SearchPlan {

    /** A way of answering. Ordered by how local it is, which is also cheapest-first. */
    enum class Tier { LIBRARY, ENCYCLOPAEDIA, WEB }

    /**
     * What kind of question this is.
     *
     * Deliberately four and not more. Each one changes the tier order; a distinction that does not
     * would be a label with nothing behind it.
     */
    enum class Shape {
        /** "how do I treat a burn", "what should I do if the power goes out" — the library's subject. */
        PRACTICAL,

        /** "caldera", "the Treaty of Westphalia" — a thing to look up. An encyclopaedia's subject. */
        ENTITY,

        /** "what happened in the election", "bitcoin price today" — only the live web can answer. */
        CURRENT,

        /** Everything else. Try everything, cheapest first. */
        GENERAL,
    }

    /** Which tiers the caller can actually reach right now. */
    data class Availability(
        val library: Boolean = true,
        val encyclopaedia: Boolean = true,
        /** The open web needs a user-supplied key, so this is usually false. */
        val web: Boolean = false,
    )

    /**
     * What to do about a query.
     *
     * [order] is the tiers to try, best-first, already filtered to what is available. [missing] names
     * a tier that the shape wanted and could not have — that is the line worth printing, and it is
     * separate from [order] being empty, because "I looked and found nothing" and "I could not look"
     * are different answers.
     */
    data class Plan(
        val shape: Shape,
        val order: List<Tier>,
        /** The query reduced to something a keyword engine can use. */
        val term: String,
        /**
         * A tier the shape specifically called for and could not have, or null.
         *
         * ⚠️ Distinct from [unavailable], and the distinction is what keeps this line worth reading.
         * This one is for printing NEXT TO A GOOD ANSWER — "you got an answer, but the tier this
         * question really wanted was missing" — so it fires only when the shape depended on it. A
         * practical question that the library answered was not harmed by the web being unconfigured,
         * and a notice on every answer is a notice nobody reads.
         */
        val missing: Tier?,
        /**
         * Every tier that could not be tried at all.
         *
         * For the case where nothing was found: then an unexplored avenue IS the relevant fact,
         * whatever the shape, and [emptyVerdict] says so.
         */
        val unavailable: List<Tier>,
    ) {
        val canSearch: Boolean get() = order.isNotEmpty()
    }

    // ---- shape -----------------------------------------------------------------------------------

    /**
     * Words that mean the answer is younger than the app.
     *
     * ⚠️ Matched as whole words, and several are two words on purpose. A bare "current" catches
     * "electric current"; "current affairs" and "current price" do not. This is the same substring
     * trap that put a two-stroke engine under "stroke symptoms" and cost a whole slice to fix.
     */
    val CURRENT_MARKERS: Set<String> = setOf(
        "today", "todays", "tonight", "yesterday", "tomorrow",
        "now", "right now", "just now", "currently", "at the moment",
        "latest", "newest", "recent", "recently", "breaking",
        "this week", "this month", "this year", "last week", "last night",
        "news", "headlines", "update", "updates",
        "score", "scores", "who won", "election results", "stock price", "share price",
        "current price", "current affairs", "current events", "exchange rate",
        "weather forecast", "is it open", "opening hours", "in stock",
        "released", "release date", "coming out", "announced",
    )

    /**
     * Openings that mean somebody wants to *do* something rather than know a fact.
     *
     * The library is written as instructions, so this is the signal that it is the right shelf.
     */
    val PRACTICAL_MARKERS: Set<String> = setOf(
        "how do i", "how to", "how can i", "how should i", "how does one",
        "what should i do", "what do i do", "what to do",
        "steps to", "guide to", "way to", "best way",
        "treat", "treating", "fix", "fixing", "repair", "repairing", "build", "building",
        "make", "making", "cook", "cooking", "clean", "cleaning", "install", "installing",
        "survive", "surviving", "escape", "prevent", "preventing", "avoid", "avoiding",
        "first aid", "emergency", "symptoms of", "signs of",
        "tie a", "start a", "put out", "stop the",
    )

    /** Interrogative openings — present means it is phrased as a question, not typed as an entity. */
    private val QUESTION_OPENERS = setOf(
        "who", "what", "whats", "when", "where", "why", "which", "how",
        "is", "are", "was", "were", "does", "do", "did", "can", "could",
        "should", "would", "will", "has", "have", "tell", "explain",
    )

    /**
     * A bare lookup is short. Above this it is a sentence, whatever its opening word.
     *
     * Four rather than three: "the treaty of westphalia" is four words and is plainly an entity, and
     * proper names routinely run to three or four.
     */
    const val ENTITY_MAX_WORDS = 4

    /** What kind of question this is. */
    fun shapeOf(query: String): Shape {
        val q = normalise(query)
        if (q.isBlank()) return Shape.GENERAL

        // Current beats everything, and it beats practical deliberately: "what should I do about the
        // storm warning today" is answerable only by something that knows about today. A bundled
        // guide would answer confidently about storms in general, which is the wrong answer given
        // confidently — the failure this whole core exists to prevent.
        if (CURRENT_MARKERS.any { containsPhrase(q, it) }) return Shape.CURRENT
        if (PRACTICAL_MARKERS.any { containsPhrase(q, it) }) return Shape.PRACTICAL

        val words = q.split(' ').filter { it.isNotBlank() }
        val opensAsQuestion = words.firstOrNull() in QUESTION_OPENERS
        if (!opensAsQuestion && words.size <= ENTITY_MAX_WORDS) return Shape.ENTITY
        return Shape.GENERAL
    }

    // ---- the plan --------------------------------------------------------------------------------

    /**
     * The full tier order for a shape, before availability is applied.
     *
     * ⚠️ CURRENT lists LIBRARY last rather than not at all. It is nearly always the wrong shelf for a
     * question about today, but "nearly always" is not "never" — "what should I do about the storm
     * today" does have a real guide behind it — and a tier that is tried last and rejected costs
     * nothing, where a tier that was never tried cannot be recovered.
     */
    fun preference(shape: Shape): List<Tier> = when (shape) {
        Shape.PRACTICAL -> listOf(Tier.LIBRARY, Tier.ENCYCLOPAEDIA, Tier.WEB)
        Shape.ENTITY -> listOf(Tier.ENCYCLOPAEDIA, Tier.LIBRARY, Tier.WEB)
        Shape.CURRENT -> listOf(Tier.WEB, Tier.ENCYCLOPAEDIA, Tier.LIBRARY)
        Shape.GENERAL -> listOf(Tier.LIBRARY, Tier.ENCYCLOPAEDIA, Tier.WEB)
    }

    /**
     * Which tier's absence is worth telling the user about, for this shape.
     *
     * Only the tier the shape actually *needed*. A practical question that the library answers has
     * not been harmed by the web being unconfigured, and saying so on every answer would train the
     * reader to ignore the line — the same reason the 52-week band on a market row stays quiet
     * through the broad middle.
     */
    private fun requiredTier(shape: Shape): Tier? = when (shape) {
        Shape.CURRENT -> Tier.WEB
        else -> null
    }

    /** What to do about [query], given what is reachable. */
    fun plan(query: String, have: Availability = Availability()): Plan {
        val shape = shapeOf(query)
        val prefer = preference(shape)
        val order = prefer.filter { available(it, have) }
        val need = requiredTier(shape)
        return Plan(
            shape = shape,
            order = order,
            term = searchTerm(query),
            missing = need?.takeIf { !available(it, have) },
            unavailable = prefer.filterNot { available(it, have) },
        )
    }

    private fun available(tier: Tier, have: Availability): Boolean = when (tier) {
        Tier.LIBRARY -> have.library
        Tier.ENCYCLOPAEDIA -> have.encyclopaedia
        Tier.WEB -> have.web
    }

    // ---- the term --------------------------------------------------------------------------------

    /**
     * The query with its question scaffolding taken off, for an engine that matches keywords.
     *
     * ⚠️ **This is half the fix.** "what is the capital of France" fails an entity endpoint and
     * "capital of France" succeeds, and the difference is four words that carry no subject. Reuses
     * [GuideSearch.STOPWORDS] rather than keeping a second list: two definitions of "words that carry
     * no meaning" would drift, and this repo has corrected a duplicated definition four times.
     *
     * ⚠️ Word ORDER is kept, unlike [GuideSearch.tokens], and duplicates with it. A phrase engine
     * reads "capital France" as a worse query than "capital of France"; the ranker does not care. So
     * only leading scaffolding is removed, and interior connectives are left where they are.
     *
     * Falls back to the trimmed original whenever stripping would leave nothing — a search box handed
     * an empty string returns everything, which is the worst possible answer to a real question.
     */
    fun searchTerm(query: String): String {
        val q = normalise(query)
        if (q.isBlank()) return query.trim()
        var words = q.split(' ').filter { it.isNotBlank() }
        // Strip from the FRONT only, and only scaffolding: "what is the", "how do i", "tell me about".
        var cut = 0
        while (cut < words.size && words[cut] in LEADING_SCAFFOLD) cut++
        // ⚠️ Never strip the whole query. If every word looked like scaffolding then the scaffolding
        // IS the query ("what is what"), and an empty string handed to a search engine returns
        // everything — the worst possible answer to a real question.
        //
        // This guard is the ONLY thing upholding that. An `ifBlank` fallback used to sit on the
        // return as a second net, and it was unreachable: `normalise` has already stripped the
        // punctuation `trimEnd` looks for, the blank case returned at the top, and with this guard
        // `words` is never empty — so it could not fire. Two mechanisms where one is dead is the
        // "computed and never used" defect this repo keeps correcting, and it also made the guard
        // untestable, since removing it broke nothing.
        if (cut in 1 until words.size) words = words.drop(cut)
        return words.joinToString(" ").trim()
    }

    /**
     * Words that can be removed from the FRONT of a query without losing the subject.
     *
     * A subset of the stopword list on purpose: "of", "in" and "for" are stopwords and are load-bearing
     * *inside* a phrase, but a query never usefully begins with them either.
     */
    private val LEADING_SCAFFOLD: Set<String> = setOf(
        "who", "what", "whats", "when", "where", "why", "which", "whose",
        "is", "are", "was", "were", "be", "does", "do", "did",
        "can", "could", "should", "would", "will",
        "tell", "me", "about", "explain", "define", "describe", "search", "find", "look", "up",
        "the", "a", "an", "please", "for",
    )

    // ---- results ---------------------------------------------------------------------------------

    /** One answer from one tier. */
    data class Answer(
        val tier: Tier,
        val title: String,
        val snippet: String,
        /** Where to read the whole thing, or null for the bundled library (which has no URL). */
        val url: String? = null,
    )

    /**
     * How an answer should introduce itself.
     *
     * ⚠️ **Not decoration.** A keyless encyclopaedia summary, a bundled offline guide and a live web
     * result carry very different warranties, and a model handed all three as undifferentiated text
     * will present a 2019 encyclopaedia sentence as the current state of the world. Naming the tier
     * is the cheapest possible guard against that, and the reader gets it too.
     */
    fun provenance(tier: Tier): String = when (tier) {
        Tier.LIBRARY -> "From the offline library on this device"
        Tier.ENCYCLOPAEDIA -> "From Wikipedia"
        Tier.WEB -> "From a web search"
    }

    /**
     * What to say when nothing answered.
     *
     * Takes what was actually tried rather than assuming, so the sentence is a report and not a
     * guess. [missing] is the interesting half: "I searched the library and Wikipedia and found
     * nothing" and "this needs a live web search, which is not set up" are different problems with
     * different fixes, and only the second one the reader can do something about.
     */
    fun emptyVerdict(query: String, tried: List<Tier>, unavailable: List<Tier>): String {
        val subject = query.trim().ifBlank { "that" }
        val what = tried.joinToString(" and ") { source(it) }
        val looked = if (tried.isEmpty()) "Nothing is configured to answer that."
        else "Searched $what and found nothing useful for \"$subject\"."
        val gaps = unavailable.joinToString("") { gap(it) }
        return looked + gaps
    }

    private fun gap(tier: Tier): String = when (tier) {
        Tier.WEB -> " A live web search was not available — add a Brave Search key in Settings to " +
            "enable it."
        Tier.ENCYCLOPAEDIA -> " Wikipedia could not be reached."
        Tier.LIBRARY -> " The offline library is unavailable."
    }

    private fun source(tier: Tier): String = when (tier) {
        Tier.LIBRARY -> "the offline library"
        Tier.ENCYCLOPAEDIA -> "Wikipedia"
        Tier.WEB -> "the web"
    }

    /**
     * Several tiers' answers, ordered and capped so no single tier crowds the rest out.
     *
     * ⚠️ Scores are NOT compared across tiers, and that is the whole point of this function existing
     * rather than a `sortedByDescending`. A library relevance score and a web engine's rank are not
     * the same quantity and never will be; interleaving them by number would be arithmetic on units
     * that do not share a scale. So the tier ORDER decides precedence — it already encodes which
     * shelf this question belongs on — and within a tier the caller's own ordering is preserved.
     *
     * Duplicates are dropped by URL, because the same page can legitimately come back from two tiers
     * and reading it twice is worse than reading it once.
     */
    fun merge(answers: List<Answer>, order: List<Tier>, limit: Int = 5, perTier: Int = 3): List<Answer> {
        if (answers.isEmpty() || limit <= 0) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Answer>(limit)
        for (tier in order) {
            var taken = 0
            for (a in answers) {
                if (a.tier != tier) continue
                if (taken >= perTier || out.size >= limit) break
                val key = a.url?.lowercase() ?: "${a.tier}:${a.title.lowercase()}"
                if (!seen.add(key)) continue
                out.add(a)
                taken++
            }
            if (out.size >= limit) break
        }
        return out
    }
}

/**
 * Lowercase, punctuation-flattened, single-spaced — the form every rule above is written against.
 *
 * ⚠️ **The apostrophe is DELETED, not turned into a space, and that is the whole point.** Every word
 * list above is written without apostrophes, so keeping them meant "what's" could never match
 * `whats` and "today's" could never match `todays` — a breaking-news query silently classified as an
 * encyclopaedia lookup. Turning it into a space would be just as wrong the other way, splitting
 * "what's" into "what" and "s".
 *
 * This is the apostrophe trap recorded in this repo, in reverse. There it was an *optional*
 * apostrophe collapsing "it's" onto the real word "its"; here it is a *retained* one keeping a
 * contraction away from the form the rules are written in. Both are settled the same way: pick one
 * spelling, and let a test prove that collapsing to it creates no collision.
 */
private fun normalise(s: String): String =
    s.lowercase().filter { it != '\'' && it != '’' }
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("").split(' ').filter { it.isNotBlank() }.joinToString(" ")

/**
 * Whether [haystack] contains [phrase] on word boundaries.
 *
 * ⚠️ A plain `contains` is the recurring trap in this repo: a bare "current" matches "electric
 * current", "news" matches "newsagent", "score" matches "scoreboard". Both sides are already
 * normalised to single-spaced words, so padding with spaces is an exact word-boundary test and
 * handles multi-word phrases at the same time.
 */
private fun containsPhrase(haystack: String, phrase: String): Boolean =
    " $haystack ".contains(" $phrase ")
