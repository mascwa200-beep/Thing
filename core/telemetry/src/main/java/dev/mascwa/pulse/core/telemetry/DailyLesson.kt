package dev.mascwa.pulse.core.telemetry

/**
 * Deciding what the Computer should teach you today.
 *
 * [Curriculum] answers "I want to learn X". This answers the question nobody asked: with a library of
 * hundreds of guides sitting on the device, what is worth putting in front of you *now*, given what
 * you have said you care about, what is actually on your list, what you have already been shown, and
 * what you are due to be asked again.
 *
 * Two rules govern it.
 *
 * **A due review beats a new lesson, always.** Being shown something once is not learning; the whole
 * point of [Recall] is the second and fifth and thirtieth time. A picker that keeps offering fresh
 * material while yesterday's quietly rots is a reading list wearing a tutor's clothes.
 *
 * **Every pick states why it was picked**, the way [Insight] carries its sources. A lesson that
 * cannot say what it is doing in front of you reads as arbitrary, and arbitrary is what people learn
 * to swipe away.
 *
 * Pure and deterministic: no clock, no locale, no library access. The caller passes a [StudyContext]
 * and gets a [Lesson] or null, which is what lets CI hold the priority order.
 */
object DailyLesson {

    enum class Kind {
        /** Questions are due. Answering them is the study session. */
        REVIEW,

        /** The next unread step of an enrolled path. */
        SYLLABUS,

        /** Grounded in something genuinely on your list. */
        TASK,

        /** Grounded in a stated interest. */
        INTEREST,

        /** Nothing to anchor to, so: something from the library you have not seen. */
        ROTATION,
    }

    /**
     * Today's item.
     *
     * For [Kind.REVIEW] the guide fields are empty and [dueCount] carries the session; for every other
     * kind it is the other way round. A screen reads [headline] and [reason] and needs to know neither.
     */
    data class Lesson(
        val kind: Kind,
        val reason: String,
        val guideId: String = "",
        val guideTitle: String = "",
        val category: String = "",
        val dueCount: Int = 0,
    ) {
        val headline: String
            get() = when (kind) {
                Kind.REVIEW -> if (dueCount == 1) "1 question to answer" else "$dueCount questions to answer"
                else -> guideTitle
            }

        /** One line for the notification board — short, and never a second sentence. */
        val boardLine: String
            get() = when (kind) {
                Kind.REVIEW -> headline
                else -> "Read: $guideTitle"
            }
    }

    /**
     * Everything the picker is allowed to know.
     *
     * @param dayIndex whole local days since the epoch, **computed by the caller**. The core has no
     *   clock and no zone on purpose: a "today" derived from UTC inside a pure module is a day out
     *   for half the planet, which is a bug this project has already shipped once and fixed.
     * @param taught guide ids already offered as a lesson, so nothing new repeats.
     * @param completed step ids finished on the enrolled path.
     */
    data class StudyContext(
        val dayIndex: Int = 0,
        val dueCount: Int = 0,
        val syllabus: Curriculum.Syllabus? = null,
        val completed: Set<String> = emptySet(),
        val pendingTasks: List<String> = emptyList(),
        val interests: List<String> = emptyList(),
        val library: List<GuideSearch.Entry> = emptyList(),
        val taught: Set<String> = emptySet(),
    )

    /** Today's item, or null when there is genuinely nothing to offer. */
    fun pick(ctx: StudyContext): Lesson? {
        if (ctx.dueCount > 0) {
            return Lesson(
                kind = Kind.REVIEW,
                dueCount = ctx.dueCount,
                reason = "due for recall — being asked again is what makes it stick",
            )
        }

        // Hoisted to a local val rather than read through ctx twice: a nullable property re-read
        // inside a lambda is the smart-cast trap this project keeps rediscovering.
        val syllabus = ctx.syllabus
        if (syllabus != null) {
            val step = syllabus.next(ctx.completed, count = 1).firstOrNull()
            if (step != null) {
                return Lesson(
                    kind = Kind.SYLLABUS,
                    guideId = step.guideId,
                    guideTitle = step.title,
                    category = step.category,
                    reason = "step ${step.position} of ${syllabus.steps.size} on your path to ${syllabus.goal}",
                )
            }
        }

        // Something you actually have to do beats something you once said you liked.
        for (task in ctx.pendingTasks.take(MAX_ANCHORS)) {
            grounded(ctx, task)?.let {
                return Lesson(
                    kind = Kind.TASK,
                    guideId = it.id,
                    guideTitle = it.title,
                    category = it.category,
                    reason = "\"${task.trim()}\" is on your list",
                )
            }
        }

        for (interest in ctx.interests.take(MAX_ANCHORS)) {
            grounded(ctx, interest)?.let {
                return Lesson(
                    kind = Kind.INTEREST,
                    guideId = it.id,
                    guideTitle = it.title,
                    category = it.category,
                    reason = "you follow ${interest.trim()}",
                )
            }
        }

        return rotation(ctx)?.let {
            Lesson(
                kind = Kind.ROTATION,
                guideId = it.id,
                guideTitle = it.title,
                category = it.category,
                reason = "something from the library you have not read",
            )
        }
    }

    /**
     * The best guide for [phrase] that is genuinely *about* it, or null.
     *
     * Reuses [LibraryConsult.isTopical] — the strict bar written for grounding an answer — rather
     * than taking the ranker's closest match. The reasoning is the same and the stakes are the same:
     * [GuideSearch.rank] always returns something, so an ungated pick would put a guide about
     * association football in front of you captioned "because *call the dentist* is on your list",
     * which is worse than offering nothing.
     *
     * ⚠️ **The key deliberately includes words appearing in no guide at all, and that is the whole
     * mechanism.** [GuideSearch.distinctiveToken] returns the phrase's rarest word; when your task is
     * "service the boiler" and the library has no boiler guide, the rarest word is "boiler", nothing
     * satisfies it, and the anchor is dropped. Preferring the rarest word the library *does* know
     * looks more accommodating and is much worse: it keys on the leftover verb, and running this over
     * the real 581-guide library produced "*service the boiler before winter* is on your list" above
     * **Severe Weather: Storms, Tornadoes and Hurricanes**, and "*call the dentist*" above
     * **Poisoning and Overdose**. A brand name in a phrase whose subject the library does cover will
     * be dropped by the same rule, and that trade is deliberate: silence is the right failure here.
     */
    private fun grounded(ctx: StudyContext, phrase: String): GuideSearch.Entry? {
        if (phrase.isBlank() || ctx.library.isEmpty()) return null
        val key = GuideSearch.distinctiveToken(ctx.library, phrase, maxEntries = MAX_KEY_FREQUENCY)
            ?: return null
        return GuideSearch.rank(ctx.library, phrase, limit = RANK_DEPTH)
            .map { it.entry }
            .firstOrNull { it.id !in ctx.taught && isAbout(it, key) }
    }

    /**
     * Whether a guide is *about* [key], rather than merely mentioning it.
     *
     * ⚠️ **Stricter than [LibraryConsult.isTopical], deliberately, and the difference was measured.**
     * That bar accepts a match anywhere including summary and section headings, which is right for
     * grounding an answer: the reader has already named the subject, so a guide with a section on it
     * is exactly what they want. A lesson is the opposite situation — nobody asked, and the caption
     * *asserts* the connection — so a passing mention is not enough.
     *
     * Run over the real 581-guide library, allowing heading and summary matches offers
     * **Archaeological Excavation** to someone interested in photography (it has a site-photography
     * section), **Finding and Mapping Oven Hot Spots** to a cyclist (thermal cycling), **History of
     * the Personal Computer** for "wiring a plug", **Testing a Business Idea With Preorders** for
     * "renew the passport", and **Newborn Care Basics** for "car maintenance" — every one captioned
     * as though it were about your task. Requiring the title or category removes all of them.
     *
     * It costs two good picks, and that is the honest price: "water purification" loses *Disinfectants
     * & Water Chemistry* and "sourdough" loses *The Science of Fermentation*, because in both the word
     * is in the body rather than the name. Falling through to something else is a much smaller failure
     * than a confident false reason.
     */
    private fun isAbout(entry: GuideSearch.Entry, key: String): Boolean =
        GuideSearch.fieldMatch(entry.title, key) > 0 || GuideSearch.fieldMatch(entry.category, key) > 0

    /**
     * Something unread, chosen by the day rather than by relevance.
     *
     * ⚠️ Stepping by a prime rather than taking `dayIndex % size` is deliberate: the candidate list is
     * ordered by id, so walking it in order teaches the library alphabetically — a fortnight of
     * agriculture, then a fortnight of archaeology. A coprime stride covers the whole list without
     * repeating and reads as variety. The survival-tip rotation had exactly this problem.
     */
    private fun rotation(ctx: StudyContext): GuideSearch.Entry? {
        val candidates = ctx.library.filter { it.id !in ctx.taught }.sortedBy { it.id }
        if (candidates.isEmpty()) return null
        val at = Math.floorMod(ctx.dayIndex * ROTATION_STRIDE, candidates.size)
        return candidates[at]
    }

    /** How many tasks and interests to try before giving up on an anchored pick. */
    const val MAX_ANCHORS = 4

    /** How deep to look for a topical guide before deciding the phrase has no material. */
    const val RANK_DEPTH = 8

    /**
     * Above this many guides a word is common enough that checking against it proves nothing.
     *
     * Much wider than [GuideSearch.distinctiveToken]'s own default, which is sized for "is a full-text
     * scan worth paying for". A stated interest is frequently one broad word, and in the real library
     * "astronomy" is in 17 guides, "chemistry" 26, "history" 25 and "cooking" 32 — at the tighter
     * default every one of those is discarded as too common to key on and silently yields no lesson.
     * Raising it is safe because [isAbout], not this, is what keeps a weak match out.
     */
    const val MAX_KEY_FREQUENCY = 120

    /** Prime, so it strides the candidate list rather than walking it alphabetically. */
    const val ROTATION_STRIDE = 131
}
